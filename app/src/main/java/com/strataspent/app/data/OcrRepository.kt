package com.strataspent.app.data

import android.graphics.Bitmap
import android.util.Log
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import com.google.ai.client.generativeai.type.generationConfig
import com.strataspent.app.data.model.Categories
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
        data class Failure(val message: String) : OcrResult
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
                    text(IMAGE_PROMPT)
                }
            )
            parse(response.text.orEmpty())
        }.getOrElse { t ->
            Log.w(TAG, "Gemini OCR failed", t)
            OcrResult.Failure(t.message ?: "OCR failed")
        }
    }

    /** Parse a natural-language expense description (typically a voice
     *  transcript) into the same structured form as receipt OCR. */
    suspend fun transcribeToExpense(transcript: String, todayIso: String): OcrResult =
        withContext(Dispatchers.IO) {
            if (apiKey.isBlank()) return@withContext OcrResult.NotConfigured
            if (transcript.isBlank()) return@withContext OcrResult.Failure("Empty transcript")
            val model = newModel()
            val prompt = VOICE_PROMPT_TEMPLATE
                .replace("{TODAY}", todayIso)
                .replace("{TRANSCRIPT}", transcript.replace("\"", "'"))
            runCatching {
                val response = model.generateContent(content { text(prompt) })
                parse(response.text.orEmpty())
            }.getOrElse { t ->
                Log.w(TAG, "Voice → expense parse failed", t)
                OcrResult.Failure(t.message ?: "Voice parse failed")
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
        val jsonText = extractJsonObject(raw)
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

    private fun parse(raw: String): OcrResult {
        if (raw.isBlank()) return OcrResult.Failure("Empty Gemini response")
        val jsonText = extractJsonObject(raw)
            ?: return OcrResult.Failure(
                "No JSON object in Gemini reply: ${raw.take(160).replace('\n', ' ')}"
            )
        return try {
            val json = JSONObject(jsonText)
            OcrResult.Success(
                category = json.optString("category").ifBlank { null }
                    ?.let { c -> Categories.ALL.firstOrNull { it.equals(c, true) } ?: Categories.OTHER },
                amount = json.optDouble("amount").takeIf { !it.isNaN() && it > 0 },
                date = json.optString("date").ifBlank { null },
                note = json.optString("note").ifBlank { null },
            )
        } catch (t: Throwable) {
            Log.w(TAG, "OCR JSON parse failed for: $jsonText", t)
            OcrResult.Failure("Couldn't read Gemini response as JSON")
        }
    }

    /**
     * Pulls the first balanced `{...}` object out of an arbitrary string.
     * Tolerates markdown fences and stray prose that Gemini sometimes adds
     * even when asked for JSON-only output.
     */
    private fun extractJsonObject(raw: String): String? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        return if (start in 0 until end) raw.substring(start, end + 1).trim() else null
    }

    private companion object {
        const val TAG = "OcrRepository"

        val IMAGE_PROMPT = """
            Analyze this receipt image. Return ONLY a JSON object (no prose, no markdown)
            with these fields:

            {
              "category": one of ${Categories.ALL.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }},
              "amount": total amount in dollars as a number (no currency symbol),
              "date": purchase date as "YYYY-MM-DD",
              "note": short description (vendor name or 3–6 word summary)
            }

            If a field can't be determined, use null.
        """.trimIndent()

        val VOICE_PROMPT_TEMPLATE = """
            The user described an expense out loud in their own language
            (English, Spanish, Portuguese, French — anything). Parse it into a
            structured expense. Today is {TODAY}.

            Return ONLY a JSON object (no prose, no markdown):

            {
              "category": one of ${Categories.ALL.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }}
                  Translate from the user's language. Examples:
                    - "comida"/"alimentos"/"groceries"/"supermercado"/"mercado" → "Food"
                    - "renta"/"alquiler"/"casa"/"hipoteca" → "Housing"
                    - "gasolina"/"nafta"/"combustible" → "Transport" (use "Gas" if the user means heating gas)
                    - "luz"/"electricidad" → "Electricity"
                    - "internet"/"wifi" → "Internet"
                    - "teléfono"/"celular"/"phone" → "Phone"
                    - "salud"/"médico"/"farmacia" → "Health"
                    - "entretenimiento"/"diversión"/"cine"/"restaurante" → "Entertainment"
                    - "ahorro"/"savings" → "Savings"
                    - "ingreso"/"salario"/"sueldo"/"income" → "Global Income"
                    - "pago tarjeta"/"credit card payment" → "Credit Card Payment"
                    - anything else → "Other",
              "amount": the numerical amount as a plain number, no symbol or units.
                  Convert spelled-out numbers in any language to digits.
                  Examples: "twenty"→20, "veinte"→20, "fifty"→50, "cincuenta"→50,
                  "two hundred"→200, "doscientos"→200,
                  "veinticinco mil"→25000, "twenty-five thousand"→25000,
                  "treinta y cinco"→35, "1.5 million"→1500000.
                  If the user says a currency keep the number only (drop "pesos"/"dollars"/"euros"/"reales"),
              "date": "YYYY-MM-DD" relative to today.
                  Interpret natural-language dates in any language:
                    "ayer"/"yesterday" → today minus 1 day,
                    "hoy"/"today" → today,
                    "anteayer"/"day before yesterday" → today minus 2,
                    "el lunes pasado"/"last Monday" → most recent Monday,
                    "el 15 de marzo" → 2026-03-15 if year unspecified use current year.
                  Default to today if no date stated,
              "note": short description in the user's original language (3–6 words; vendor, item, or context)
            }

            Use null for any field you genuinely cannot determine — but always
            try hard for "amount", since spelled-out numbers are very common.

            User said: "{TRANSCRIPT}"
        """.trimIndent()

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
