package com.strataspent.app.data

import android.util.Log
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Generates a natural-language financial analysis of a user's (or a group's)
 * spending over a chosen time window, via Google AI (Gemini). Unlike
 * [OcrRepository] this asks for prose (not JSON): the model plays financial
 * advisor over a compact, pre-aggregated [FinancialSummary] the caller builds.
 *
 * The API key is read from `BuildConfig.GEMINI_API_KEY`; when blank every call
 * returns [Result.NotConfigured] so the UI can prompt the user.
 */
class AiAnalyticsRepository(private val apiKey: String) {

    sealed interface Result {
        data object NotConfigured : Result
        data class Failure(val message: String) : Result
        data class Success(val analysis: String) : Result
    }

    /** Compact, already-aggregated inputs for one analysis run. Keeping this
     *  small (totals, not raw rows) keeps the prompt cheap and on-point. */
    data class FinancialSummary(
        /** e.g. "the group's shared spending" or "your personal spending". */
        val scopeLabel: String,
        /** e.g. "the last 3 months" or "all available history". */
        val rangeLabel: String,
        val currencyCode: String?,
        val expenditureCount: Int,
        val grandTotal: Double,
        /** category → total, biggest first. */
        val byCategory: List<Pair<String, Double>>,
        /** "yyyy-MM" → total, chronological (the trend). */
        val byMonth: List<Pair<String, Double>>,
        /** Personal scope only: standing monthly income (recurring), or null. */
        val monthlyRecurringIncome: Double? = null,
        /** Personal scope only: one-off income logged in range, or null. */
        val incomeInRange: Double? = null,
        /** Personal scope only: debit / credit tagged spending, or null. */
        val debitSpending: Double? = null,
        val creditSpending: Double? = null,
    )

    suspend fun analyze(summary: FinancialSummary): Result = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext Result.NotConfigured
        val model = GenerativeModel(modelName = "gemini-2.5-flash", apiKey = apiKey)
        runCatching {
            val response = model.generateContent(content { text(buildPrompt(summary)) })
            val text = response.text?.trim().orEmpty()
            if (text.isBlank()) Result.Failure("The model returned an empty analysis. Please try again.")
            else Result.Success(text)
        }.getOrElse { t ->
            Log.w(TAG, "AI analytics failed", t)
            Result.Failure(t.message ?: "Analysis failed")
        }
    }

    private fun buildPrompt(s: FinancialSummary): String {
        val cur = s.currencyCode?.let { " (amounts are in $it)" } ?: ""
        val cats = s.byCategory.joinToString("\n") { "  - ${it.first}: ${money(it.second)}" }
        val months = s.byMonth.joinToString("\n") { "  - ${it.first}: ${money(it.second)}" }
        val personal = buildString {
            s.monthlyRecurringIncome?.let { appendLine("Monthly recurring income: ${money(it)}") }
            s.incomeInRange?.takeIf { it > 0 }?.let { appendLine("One-off income logged in range: ${money(it)}") }
            s.debitSpending?.takeIf { it > 0 }?.let { appendLine("Debit-tagged spending: ${money(it)}") }
            s.creditSpending?.takeIf { it > 0 }?.let { appendLine("Credit-tagged spending: ${money(it)}") }
        }.trim()

        return buildString {
            appendLine(
                "You are a pragmatic personal-finance advisor. Analyze ${s.scopeLabel} over " +
                    "${s.rangeLabel}$cur and give the user a clear, useful read on their money."
            )
            appendLine()
            appendLine("DATA")
            appendLine("Expenditures: ${s.expenditureCount}")
            appendLine("Total spent: ${money(s.grandTotal)}")
            if (personal.isNotBlank()) appendLine(personal)
            appendLine("Spending by category (largest first):")
            appendLine(if (cats.isBlank()) "  - (none)" else cats)
            appendLine("Spending by month (trend):")
            appendLine(if (months.isBlank()) "  - (none)" else months)
            appendLine()
            appendLine("INSTRUCTIONS")
            appendLine("- Write for the user directly, in their everyday language; respond in the same language the category names suggest, otherwise English.")
            appendLine("- Be concise: ~150-220 words total.")
            appendLine("- Cover: where the money goes (top categories), the month-over-month trend (rising/falling/steady), and anything notable or anomalous.")
            appendLine("- End with 2-4 concrete, actionable recommendations as a bulleted list starting with \"- \".")
            appendLine("- Use ONLY the numbers above; never invent transactions or figures. Quote amounts with the currency.")
            appendLine("- Plain text only (short paragraphs and \"- \" bullets). No markdown headers, tables, or code blocks.")
        }
    }

    private fun money(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else "%.2f".format(v)

    private companion object { const val TAG = "AiAnalyticsRepository" }
}
