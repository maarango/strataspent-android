package com.strataspent.app.ui.reminders

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.strataspent.app.data.AuthRepository
import com.strataspent.app.data.ExpenseRepository
import com.strataspent.app.data.RemindersRepository
import com.strataspent.app.data.todayIso
import com.strataspent.app.data.model.Categories
import com.strataspent.app.data.model.Reminder
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class RemindersListViewModel(
    val groupId: String,
    private val authRepo: AuthRepository,
    private val remindersRepo: RemindersRepository,
    private val expenseRepo: ExpenseRepository,
) : ViewModel() {

    val reminders: StateFlow<List<Reminder>> = remindersRepo.reminders(groupId)
        .catch { t ->
            Log.w("RemindersListVM", "reminders flow error", t)
            emit(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun toggleCompleted(reminder: Reminder) {
        viewModelScope.launch {
            runCatching {
                remindersRepo.setCompleted(groupId, reminder.id, !reminder.isCompleted)
            }
        }
    }

    /**
     * "Pay" action for an overdue reminder. Creates a private expenditure
     * keyed to the current user (so the group balance is unaffected) using
     * the reminder's amount and category, then marks the reminder complete.
     */
    fun payReminder(reminder: Reminder) {
        val amount = reminder.amount ?: return
        viewModelScope.launch {
            runCatching {
                val me = authRepo.currentUser.first() ?: error("Not signed in")
                val contributorName = me.displayName.ifBlank { me.email.ifBlank { me.uid.take(8) } }
                val viewerIdentifier = me.email.ifBlank { me.uid }
                expenseRepo.addExpenditure(
                    groupId = groupId,
                    category = reminder.category ?: Categories.OTHER,
                    amount = amount,
                    date = todayIso(),
                    contributorUid = me.uid,
                    contributorName = contributorName,
                    note = "Paid: ${reminder.title}",
                    splitAmong = listOf(viewerIdentifier),
                    visibility = com.strataspent.app.data.model.Visibility.PRIVATE,
                    paymentType = reminder.paymentType,
                )
                remindersRepo.setCompleted(groupId, reminder.id, true)
            }.onFailure { Log.w("RemindersListVM", "Pay failed", it) }
        }
    }
}
