package com.strataspent.app.data

import android.util.Log
import com.strataspent.app.data.model.Categories
import org.json.JSONObject

/**
 * Shared prompts + JSON parsing for turning a receipt image, a voice
 * transcript, or typed text into a structured expense. Both the cloud path
 * ([OcrRepository], Gemini) and the on-device path ([GemmaExpenseExtractor],
 * MediaPipe/Gemma) feed model output through here so the prompt wording,
 * category mapping, and tolerant JSON recovery stay a single source of truth.
 *
 * The output shape is the existing [OcrRepository.OcrResult.Success]; callers
 * decide how to surface a `null` (un-parseable) result as a Failure.
 */
object ExpenseExtraction {

    private const val TAG = "ExpenseExtraction"

    /** Prompt for the receipt-image (multimodal) path. */
    val IMAGE_PROMPT: String = """
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

    /**
     * Prompt for the natural-language path (voice transcript or typed text).
     * Multilingual: maps spoken categories, spelled-out numbers, and relative
     * dates in the user's own language.
     */
    fun voicePrompt(todayIso: String, transcript: String): String =
        VOICE_PROMPT_TEMPLATE
            .replace("{TODAY}", todayIso)
            .replace("{TRANSCRIPT}", transcript.replace("\"", "'"))

    /**
     * Parse a model's raw text into a structured [OcrRepository.OcrResult.Success],
     * or `null` if no usable JSON object can be recovered. Tolerates markdown
     * fences and stray prose, and repairs JSON that a small on-device model
     * truncated by a few closing characters.
     */
    fun parse(raw: String): OcrRepository.OcrResult.Success? {
        if (raw.isBlank()) return null
        val jsonText = extractJsonObject(raw) ?: repairJsonObject(raw) ?: return null
        val json = runCatching { JSONObject(jsonText) }.getOrElse {
            // The balanced slice still didn't parse — try the repair pass once.
            val repaired = repairJsonObject(raw) ?: return null
            runCatching { JSONObject(repaired) }.getOrElse {
                Log.w(TAG, "Expense JSON parse failed for: $jsonText", it)
                return null
            }
        }
        return OcrRepository.OcrResult.Success(
            category = json.optString("category").ifBlank { null }
                ?.let { c -> Categories.ALL.firstOrNull { it.equals(c, true) } ?: Categories.OTHER },
            amount = json.optDouble("amount").takeIf { !it.isNaN() && it > 0 },
            date = json.optString("date").ifBlank { null },
            note = json.optString("note").ifBlank { null },
        )
    }

    /**
     * Pulls the first balanced `{...}` object out of an arbitrary string.
     * Tolerates markdown fences and stray prose that models sometimes add even
     * when asked for JSON-only output. Returns null when no balanced object is
     * present (e.g. the closing brace was truncated — see [repairJsonObject]).
     */
    fun extractJsonObject(raw: String): String? {
        val start = raw.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until raw.length) {
            val c = raw[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return raw.substring(start, i + 1).trim()
                }
            }
        }
        return null
    }

    /**
     * Best-effort recovery of a JSON object that a small model truncated mid-way:
     * tracks the open `{`/`[` stack and auto-closes any still-open string and
     * containers. Ported from `_tryDecodeObject` in the training app's
     * `gemma_plan_service.dart`. Returns a parseable candidate string, or null.
     */
    fun repairJsonObject(raw: String): String? {
        val start = raw.indexOf('{')
        if (start < 0) return null
        val stack = ArrayDeque<Char>() // expected closers, innermost last
        var inString = false
        var escaped = false
        var end = -1
        for (i in start until raw.length) {
            val c = raw[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> stack.addLast('}')
                '[' -> stack.addLast(']')
                '}', ']' -> {
                    if (stack.isNotEmpty()) stack.removeLast()
                    if (stack.isEmpty()) { end = i + 1; break }
                }
            }
        }
        return when {
            end >= 0 -> raw.substring(start, end)
            stack.isNotEmpty() -> buildString {
                append(raw.substring(start))
                if (inString) append('"')
                while (stack.isNotEmpty()) append(stack.removeLast())
            }
            else -> null
        }
    }

    private val VOICE_PROMPT_TEMPLATE = """
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
}
