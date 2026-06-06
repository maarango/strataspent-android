package com.strataspent.app.ui.analytics

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.strataspent.app.data.AiAnalyticsRepository
import com.strataspent.app.data.AuthRepository
import com.strataspent.app.data.ExpenseRepository
import com.strataspent.app.data.UserDirectoryRepository
import com.strataspent.app.data.isOwnedBy
import com.strataspent.app.data.model.Categories
import com.strataspent.app.data.model.Expenditure
import com.strataspent.app.data.model.PaymentType
import com.strataspent.app.data.model.UserProfile
import com.strataspent.app.data.model.Visibility
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

data class CategoryBar(val category: String, val total: Double)
data class CashflowPoint(val date: String, val total: Double)

/** Per-viewer financial flow. Mirrors the web app's private "Financial Flow"
 *  panel: income minus your own spending (public + private). */
data class PersonalFlow(
    val income: Double = 0.0,
    val publicSpending: Double = 0.0,
    val privateSpending: Double = 0.0,
    val debitSpending: Double = 0.0,
    val creditSpending: Double = 0.0,
    val unspecifiedPaymentSpending: Double = 0.0,
) {
    val totalSpending: Double get() = publicSpending + privateSpending
    val net: Double get() = income - totalSpending
}

/** One scope of analytics aggregations — used twice, once per "Group" /
 *  "You" tab on the screen. */
data class AnalyticsScope(
    val expenditureCount: Int = 0,
    val grandTotal: Double = 0.0,
    val byCategory: List<CategoryBar> = emptyList(),
    val byDay: List<CashflowPoint> = emptyList(),
)

data class AnalyticsUi(
    val group: AnalyticsScope = AnalyticsScope(),
    val you: AnalyticsScope = AnalyticsScope(),
    val personal: PersonalFlow = PersonalFlow(),
)

/** How far back the AI analysis looks. [months] == null means all history. */
enum class AnalysisRange(val label: String, val months: Int?) {
    ONE_MONTH("Last month", 1),
    THREE_MONTHS("Last 3 months", 3),
    SIX_MONTHS("Last 6 months", 6),
    ONE_YEAR("Last year", 12),
    ALL("All history", null);

    /** Inclusive lower-bound "yyyy-MM-dd", or null for all history. */
    fun cutoffIso(): String? {
        val m = months ?: return null
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { add(Calendar.MONTH, -m) }
        return "%04d-%02d-%02d".format(
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH),
        )
    }
}

/** Period-filtered category totals for the "By category" card, per scope. */
data class CategoryBreakdown(
    val period: AnalysisRange = AnalysisRange.ALL,
    val group: List<CategoryBar> = emptyList(),
    val you: List<CategoryBar> = emptyList(),
)

/** UI state for the AI financial-analysis card. */
data class AiAnalysisUi(
    val range: AnalysisRange = AnalysisRange.THREE_MONTHS,
    val loading: Boolean = false,
    val analysis: String? = null,
    val error: String? = null,
)

class AnalyticsViewModel(
    val groupId: String,
    private val authRepo: AuthRepository,
    private val expenseRepo: ExpenseRepository,
    private val userDirectory: UserDirectoryRepository,
    private val aiAnalyticsRepo: AiAnalyticsRepository,
) : ViewModel() {

    val ui: StateFlow<AnalyticsUi> = combine(
        expenseRepo.expenditures(groupId)
            .onEach { exps ->
                viewModelScope.launch {
                    // Prefetch every contributor + the viewer themselves so we
                    // can read users/{viewerUid}.income.
                    val viewerUid = authRepo.currentUser.firstOrNull()?.uid
                    val ids = (exps.map { it.contributorUid } + listOfNotNull(viewerUid)).distinct()
                    userDirectory.prefetch(ids)
                }
            },
        authRepo.currentUser,
        userDirectory.directory,
    ) { exps, viewer, dir ->
        val analytics = exps.toAnalytics(viewer, dir)
        // Fold in the viewer's standing income from users/{uid}.income.
        val standingIncome = viewer?.let { dir[it.uid]?.income ?: 0.0 } ?: 0.0
        analytics.copy(
            personal = analytics.personal.copy(
                income = analytics.personal.income + standingIncome,
            )
        )
    }.catch { t ->
        Log.w("AnalyticsVM", "analytics flow error", t)
        emit(AnalyticsUi())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AnalyticsUi())

    // ---- period-filtered "By category" breakdown ---------------------------

    private val _categoryPeriod = MutableStateFlow(AnalysisRange.ALL)

    /** The category breakdown for the period the user picked in the "By
     *  category" card, for both scopes (the screen picks group vs you). */
    val categoryBreakdown: StateFlow<CategoryBreakdown> = combine(
        expenseRepo.expenditures(groupId),
        authRepo.currentUser,
        userDirectory.directory,
        _categoryPeriod,
    ) { exps, viewer, dir, period ->
        val cutoff = period.cutoffIso()
        val inRange = if (cutoff == null) exps
            else exps.filter { it.date.isNotBlank() && it.date >= cutoff }
        CategoryBreakdown(
            period = period,
            group = inRange
                .filter { it.category != Categories.GLOBAL_INCOME && it.visibility != Visibility.PRIVATE }
                .categoryBars(),
            you = inRange
                .filter { it.isOwnedBy(viewer, dir) && it.category != Categories.GLOBAL_INCOME }
                .categoryBars(),
        )
    }.catch { t ->
        Log.w("AnalyticsVM", "category breakdown flow error", t)
        emit(CategoryBreakdown())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CategoryBreakdown())

    fun setCategoryPeriod(range: AnalysisRange) { _categoryPeriod.value = range }

    fun updateMyIncome(amount: Double) {
        viewModelScope.launch {
            val uid = authRepo.currentUser.firstOrNull()?.uid ?: return@launch
            runCatching { userDirectory.setIncome(uid, amount) }
                .onFailure { Log.w("AnalyticsVM", "income update failed", it) }
        }
    }

    // ---- AI financial analysis ---------------------------------------------

    private val _aiState = MutableStateFlow(AiAnalysisUi())
    val aiState: StateFlow<AiAnalysisUi> = _aiState.asStateFlow()

    fun setAnalysisRange(range: AnalysisRange) {
        _aiState.value = _aiState.value.copy(range = range)
    }

    /**
     * Generate a natural-language analysis of the selected scope ([mode]) over
     * the chosen [AiAnalysisUi.range]. Filters the raw expenditures by date so
     * the model sees exactly the window the user picked.
     */
    fun generateAiAnalysis(mode: AnalyticsMode, currencyCode: String?) {
        val range = _aiState.value.range
        _aiState.value = _aiState.value.copy(loading = true, error = null)
        viewModelScope.launch {
            runCatching {
                val exps = expenseRepo.expenditures(groupId).first()
                val viewer = authRepo.currentUser.first()
                val dir = userDirectory.directory.value
                val cutoff = range.cutoffIso()
                val inRange = if (cutoff == null) exps
                    else exps.filter { it.date.isNotBlank() && it.date >= cutoff }
                aiAnalyticsRepo.analyze(buildSummary(inRange, viewer, dir, mode, range, currencyCode))
            }.onSuccess { result ->
                _aiState.value = when (result) {
                    is AiAnalyticsRepository.Result.NotConfigured -> _aiState.value.copy(
                        loading = false, analysis = null,
                        error = "AI isn't configured. Set geminiApiKey in local.properties.",
                    )
                    is AiAnalyticsRepository.Result.Failure -> _aiState.value.copy(
                        loading = false, analysis = null, error = result.message,
                    )
                    is AiAnalyticsRepository.Result.Success -> _aiState.value.copy(
                        loading = false, analysis = result.analysis, error = null,
                    )
                }
            }.onFailure { t ->
                Log.w("AnalyticsVM", "ai analysis error", t)
                _aiState.value = _aiState.value.copy(loading = false, error = t.message ?: "Analysis failed")
            }
        }
    }

    private fun buildSummary(
        exps: List<Expenditure>,
        viewer: UserProfile?,
        dir: Map<String, UserProfile>,
        mode: AnalyticsMode,
        range: AnalysisRange,
        currencyCode: String?,
    ): AiAnalyticsRepository.FinancialSummary {
        val isGroup = mode == AnalyticsMode.GROUP
        // Match the same scoping the charts use (see toAnalytics()).
        val scoped = if (isGroup) {
            exps.filter { it.category != Categories.GLOBAL_INCOME && it.visibility != Visibility.PRIVATE }
        } else {
            exps.filter { it.isOwnedBy(viewer, dir) && it.category != Categories.GLOBAL_INCOME }
        }
        val byCategory = scoped.groupBy { it.category }
            .mapValues { (_, l) -> l.sumOf { it.amount } }
            .entries.sortedByDescending { it.value }.map { it.key to it.value }
        val byMonth = scoped.filter { it.date.length >= 7 }
            .groupBy { it.date.substring(0, 7) }
            .mapValues { (_, l) -> l.sumOf { it.amount } }
            .entries.sortedBy { it.key }.map { it.key to it.value }

        val mine = if (!isGroup) exps.filter { it.isOwnedBy(viewer, dir) } else emptyList()
        return AiAnalyticsRepository.FinancialSummary(
            scopeLabel = if (isGroup) "the group's shared spending" else "your personal spending",
            rangeLabel = if (range.months == null) "all available history"
                else "the ${range.label.lowercase(Locale.US)}",
            currencyCode = currencyCode,
            expenditureCount = scoped.size,
            grandTotal = scoped.sumOf { it.amount },
            byCategory = byCategory,
            byMonth = byMonth,
            monthlyRecurringIncome = if (isGroup) null else (viewer?.let { dir[it.uid]?.income } ?: 0.0),
            incomeInRange = if (isGroup) null
                else mine.filter { it.category == Categories.GLOBAL_INCOME }.sumOf { it.amount },
            debitSpending = if (isGroup) null else scoped.filter { it.paymentType == PaymentType.DEBIT }.sumOf { it.amount },
            creditSpending = if (isGroup) null else scoped.filter { it.paymentType == PaymentType.CREDIT }.sumOf { it.amount },
            // "You" already includes private entries in grandTotal; split them
            // out so the model can analyze public and private spending distinctly.
            publicSpending = if (isGroup) null else scoped.filter { it.visibility != Visibility.PRIVATE }.sumOf { it.amount },
            privateSpending = if (isGroup) null else scoped.filter { it.visibility == Visibility.PRIVATE }.sumOf { it.amount },
        )
    }
}

private fun List<Expenditure>.toAnalytics(
    viewer: UserProfile?,
    directory: Map<String, UserProfile>,
): AnalyticsUi {
    if (isEmpty()) return AnalyticsUi()

    // Group spending = public expenditures only. Income is per-user cash
    // flow (excluded), and private entries are user-only by definition
    // (visibility=private), so neither belongs in the group's shared totals.
    val groupSpending = filter {
        it.category != Categories.GLOBAL_INCOME && it.visibility != Visibility.PRIVATE
    }
    val mine = filter { it.isOwnedBy(viewer, directory) }
    val mineSpending = mine.filter { it.category != Categories.GLOBAL_INCOME }

    val personal = PersonalFlow(
        income = mine.filter { it.category == Categories.GLOBAL_INCOME }.sumOf { it.amount },
        publicSpending = mineSpending.filter { it.visibility != Visibility.PRIVATE }.sumOf { it.amount },
        privateSpending = mineSpending.filter { it.visibility == Visibility.PRIVATE }.sumOf { it.amount },
        debitSpending = mineSpending.filter { it.paymentType == PaymentType.DEBIT }.sumOf { it.amount },
        creditSpending = mineSpending.filter { it.paymentType == PaymentType.CREDIT }.sumOf { it.amount },
        unspecifiedPaymentSpending = mineSpending.filter { it.paymentType.isNullOrBlank() }.sumOf { it.amount },
    )

    return AnalyticsUi(
        group = groupSpending.toScope(),
        you = mineSpending.toScope(),
        personal = personal,
    )
}

/** category → total, biggest first. Shared by the all-time scope and the
 *  period-filtered "By category" card. */
private fun List<Expenditure>.categoryBars(): List<CategoryBar> =
    groupBy { it.category }
        .mapValues { (_, list) -> list.sumOf { it.amount } }
        .entries
        .sortedByDescending { it.value }
        .map { CategoryBar(it.key, it.value) }

private fun List<Expenditure>.toScope(): AnalyticsScope {
    if (isEmpty()) return AnalyticsScope()
    val byCategory = groupBy { it.category }
        .mapValues { (_, list) -> list.sumOf { it.amount } }
        .entries
        .sortedByDescending { it.value }
        .map { CategoryBar(it.key, it.value) }
    val byDay = groupBy { it.date.ifBlank { "0000-00-00" } }
        .mapValues { (_, list) -> list.sumOf { it.amount } }
        .entries
        .sortedBy { it.key }
        .map { CashflowPoint(it.key, it.value) }
    return AnalyticsScope(
        expenditureCount = size,
        grandTotal = sumOf { it.amount },
        byCategory = byCategory,
        byDay = byDay,
    )
}
