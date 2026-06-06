package com.strataspent.app.data

import android.graphics.Bitmap
import android.util.Log
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import com.google.ai.client.generativeai.type.generationConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Calls Google AI (Gemini) to extract structured fields from a receipt image.
 *
 * The repository takes a [Bitmap], wraps it into a multimodal request with a
 * JSON-only prompt, and parses the response into [OcrResult.Success]. Bad
 * JSON or empty responses degrade to [OcrResult.Failure] rather than throw.
 *
 * The API key is read from `BuildConfig.GEMINI_API_KEY`. When blank
 * (the default until the developer sets `geminiApiKey=...` in
 * local.properties or the `GEMINI_API_KEY` env var) every call returns
 * [OcrResult.NotConfigured] so the UI can prompt the user accordingly
 * instead of crashing.
 */
class OcrRepository(private val apiKey: String) {

    sealed interface OcrResult {
        data object NotConfigured : OcrResult
        /**
         * @param retryable true when the failure is likely transient (network
         *   reachability, timeout) so a queued retry could succeed. false when
         *   we reached Gemini but couldn't use the response (empty/non-JSON
         *   body, content blocked) — retrying with the same input will fail
         *   the same way, so the UI should surface the real error and the
         *   background worker should drop the job instead of looping.
         */
        data class Failure(val message: String, val retryable: Boolean) : OcrResult
        data class Success(
            val category: String?,
            val amount: Double?,
            val date: String?,
            val note: String?,
        ) : OcrResult
    }

    /** Multi-item extraction for the bill-splitter. Excludes tax/tip/total. */
    sealed interface LineItemsResult {
        data object NotConfigured : LineItemsResult
        data class Failure(val message: String) : LineItemsResult
        data class Success(val items: List<RawItem>) : LineItemsResult
        data class RawItem(val description: String, val amount: Double)
    }

    suspend fun extractReceipt(image: Bitmap): OcrResult = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext OcrResult.NotConfigured
        val model = newModel()
        runCatching {
            val response = model.generateContent(
                content {
                    image(image)
                    text(ExpenseExtraction.IMAGE_PROMPT)
                }
            )
            wrapParse(response.text.orEmpty())
        }.getOrElse { t ->
            Log.w(TAG, "Gemini OCR failed", t)
            OcrResult.Failure(t.message ?: "OCR failed", retryable = isLikelyTransient(t))
        }
    }

    /** Parse a natural-language expense description (typically a voice
     *  transcript) into the same structured form as receipt OCR. */
    suspend fun transcribeToExpense(transcript: String, todayIso: String): OcrResult =
        withContext(Dispatchers.IO) {
            if (apiKey.isBlank()) return@withContext OcrResult.NotConfigured
            if (transcript.isBlank()) return@withContext OcrResult.Failure(
                "Empty transcript", retryable = false,
            )
            val model = newModel()
            val prompt = ExpenseExtraction.voicePrompt(todayIso, transcript)
            runCatching {
                val response = model.generateContent(content { text(prompt) })
                wrapParse(response.text.orEmpty())
            }.getOrElse { t ->
                Log.w(TAG, "Voice → expense parse failed", t)
                OcrResult.Failure(t.message ?: "Voice parse failed", retryable = isLikelyTransient(t))
            }
        }

    private fun newModel(): GenerativeModel = GenerativeModel(
        modelName = "gemini-2.5-flash",
        apiKey = apiKey,
        generationConfig = generationConfig { responseMimeType = "application/json" },
    )

    /** Pull every line item from a receipt image — used by the bill splitter
     *  to populate items the user can then assign to temporary participants. */
    suspend fun extractLineItems(image: Bitmap): LineItemsResult = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext LineItemsResult.NotConfigured
        val model = newModel()
        runCatching {
            val response = model.generateContent(
                content {
                    image(image)
                    text(LINE_ITEMS_PROMPT)
                }
            )
            parseLineItems(response.text.orEmpty())
        }.getOrElse { t ->
            Log.w(TAG, "Line-item OCR failed", t)
            LineItemsResult.Failure(t.message ?: "OCR failed")
        }
    }

    private fun parseLineItems(raw: String): LineItemsResult {
        if (raw.isBlank()) return LineItemsResult.Failure("Empty Gemini response")
        val jsonText = ExpenseExtraction.extractJsonObject(raw)
            ?: return LineItemsResult.Failure("No JSON object in reply: ${raw.take(160)}")
        return try {
            val json = JSONObject(jsonText)
            val arr = json.optJSONArray("items")
                ?: return LineItemsResult.Failure("Missing items[] in response")
            val items = (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val desc = o.optString("description").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val amt = o.optDouble("amount").takeIf { !it.isNaN() && it > 0 } ?: return@mapNotNull null
                LineItemsResult.RawItem(desc, amt)
            }
            LineItemsResult.Success(items)
        } catch (t: Throwable) {
            Log.w(TAG, "Line-items JSON parse failed: $jsonText", t)
            LineItemsResult.Failure("Couldn't read response as JSON")
        }
    }

    /**
     * Wrap the shared parser into an [OcrResult]. We got past the HTTP call to
     * reach here, so a parse miss is a response-shape problem, not a network
     * one — retrying won't help, hence `retryable = false`.
     */
    private fun wrapParse(raw: String): OcrResult =
        ExpenseExtraction.parse(raw)
            ?: OcrResult.Failure("Couldn't read Gemini response as JSON", retryable = false)

    /**
     * Best-effort classifier: does this exception look like something that
     * could succeed if we try again later? Two signals count as "yes":
     *   1. Anything in the cause chain that looks like a connectivity problem
     *      (IOException, *Timeout*, *UnknownHost*, *Connect*, *Network*).
     *   2. A Gemini ServerException whose JSON body carries a retry-able
     *      HTTP code (429, 5xx) or gRPC status (UNAVAILABLE, INTERNAL,
     *      DEADLINE_EXCEEDED, RESOURCE_EXHAUSTED). The SDK puts the upstream
     *      body straight into the exception message, so a substring check
     *      against `t.message` works.
     *
     * Anything else — blocked client, parse failure, deserialization mismatch
     * on a 4xx response — is treated as permanent so the worker doesn't loop
     * forever and the UI shows the real error.
     */
    private fun isLikelyTransient(t: Throwable): Boolean {
        var cur: Throwable? = t
        while (cur != null) {
            if (cur is java.io.IOException) return true
            val name = cur::class.java.simpleName
            if (name.contains("Timeout", ignoreCase = true) ||
                name.contains("UnknownHost", ignoreCase = true) ||
                name.contains("Connect", ignoreCase = true) ||
                name.contains("Network", ignoreCase = true)
            ) return true
            val msg = cur.message.orEmpty()
            if (TRANSIENT_STATUS_MARKERS.any { it in msg }) return true
            if (TRANSIENT_HTTP_CODE.containsMatchIn(msg)) return true
            cur = cur.cause
        }
        return false
    }

    private companion object {
        const val TAG = "OcrRepository"

        /** gRPC status strings Google returns for transient upstream errors. */
        val TRANSIENT_STATUS_MARKERS = listOf(
            "\"UNAVAILABLE\"",
            "\"INTERNAL\"",
            "\"DEADLINE_EXCEEDED\"",
            "\"RESOURCE_EXHAUSTED\"",
        )

        /** HTTP codes worth retrying (rate limit + server-side failures). */
        val TRANSIENT_HTTP_CODE = Regex("""["]code["]\s*:\s*(429|500|502|503|504)\b""")

        val LINE_ITEMS_PROMPT = """
            Analyze this receipt image. Extract every line item (food, drink,
            service, product) with its individual price.

            EXCLUDE: tax, tip, gratuity, service charge, subtotals, and the
            grand total. Only individual items belong in `items`.

            Return ONLY a JSON object (no prose, no markdown):
            {
              "items": [
                { "description": "Short item name", "amount": 3.50 }
              ]
            }

            If the receipt can't be parsed, return: { "items": [] }
        """.trimIndent()
    }
}
