package com.strataspent.app.ui.analytics

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.strataspent.app.data.AuthRepository
import com.strataspent.app.data.ExpenseRepository
import com.strataspent.app.data.UserDirectoryRepository
import com.strataspent.app.data.model.Categories
import com.strataspent.app.data.model.Expenditure
import com.strataspent.app.data.model.PaymentType
import com.strataspent.app.data.model.UserProfile
import com.strataspent.app.data.model.Visibility
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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

class AnalyticsViewModel(
    val groupId: String,
    private val authRepo: AuthRepository,
    expenseRepo: ExpenseRepository,
    private val userDirectory: UserDirectoryRepository,
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

    fun updateMyIncome(amount: Double) {
        viewModelScope.launch {
            val uid = authRepo.currentUser.firstOrNull()?.uid ?: return@launch
            runCatching { userDirectory.setIncome(uid, amount) }
                .onFailure { Log.w("AnalyticsVM", "income update failed", it) }
        }
    }
}

/**
 * "Mine" matches by uid OR by an email lookup through the directory — so if
 * the same person has different Firebase Auth uids across providers
 * (Google vs email/password), their contributions still attribute correctly
 * to the viewer.
 */
private fun isMine(e: Expenditure, viewer: UserProfile?, dir: Map<String, UserProfile>): Boolean {
    if (viewer == null) return false
    if (e.contributorUid == viewer.uid) return true
    val contributorEmail = dir[e.contributorUid]?.email ?: return false
    return contributorEmail.equals(viewer.email, ignoreCase = true) && viewer.email.isNotBlank()
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
    val mine = filter { isMine(it, viewer, directory) }
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
