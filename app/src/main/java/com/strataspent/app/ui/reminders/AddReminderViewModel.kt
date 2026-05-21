package com.strataspent.app.ui.reminders

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.strataspent.app.data.AuthRepository
import com.strataspent.app.data.RemindersRepository
import com.strataspent.app.data.todayIso
import com.strataspent.app.data.model.ReminderFrequency
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AddReminderUi(
    val title: String = "",
    val dueDate: String = todayIso(),
    val amount: String = "",
    val frequency: String = ReminderFrequency.NONE,
    val isCompleted: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val saved: Boolean = false,
) {
    val canSubmit: Boolean
        get() = !loading && !saved && title.isNotBlank() && dueDate.isNotBlank()
}

class AddReminderViewModel(
    val groupId: String,
    val reminderId: String?,
    private val authRepo: AuthRepository,
    private val remindersRepo: RemindersRepository,
) : ViewModel() {

    val isEditing: Boolean = reminderId != null

    private val _state = MutableStateFlow(AddReminderUi())
    val state: StateFlow<AddReminderUi> = _state.asStateFlow()

    init {
        if (reminderId != null) {
            viewModelScope.launch {
                remindersRepo.reminder(groupId, reminderId).first()?.let { r ->
                    _state.update {
                        it.copy(
                            title = r.title,
                            dueDate = r.dueDate,
                            amount = r.amount?.let { v -> "%.2f".format(v).trimEnd('0').trimEnd('.') } ?: "",
                            frequency = r.frequency ?: ReminderFrequency.NONE,
                            isCompleted = r.isCompleted,
                        )
                    }
                }
            }
        }
    }

    fun onTitle(v: String) = _state.update { it.copy(title = v) }
    fun onDueDate(v: String) = _state.update { it.copy(dueDate = v) }
    fun onAmount(v: String) = _state.update {
        it.copy(amount = v.filter { c -> c.isDigit() || c == '.' })
    }
    fun onFrequency(v: String) = _state.update { it.copy(frequency = v) }

    fun submit() {
        val s = _state.value
        if (!s.canSubmit) return
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            runCatching {
                val me = authRepo.currentUser.first() ?: error("Not signed in")
                val creatorName = me.displayName.ifBlank { me.email.ifBlank { me.uid.take(8) } }
                if (reminderId == null) {
                    remindersRepo.addReminder(
                        groupId = groupId,
                        title = s.title.trim(),
                        dueDate = s.dueDate,
                        creatorUid = me.uid,
                        creatorName = creatorName,
                        amount = s.amount.toDoubleOrNull(),
                        frequency = s.frequency.takeIf { it != ReminderFrequency.NONE },
                    )
                } else {
                    remindersRepo.updateReminder(
                        groupId = groupId,
                        reminderId = reminderId,
                        title = s.title.trim(),
                        dueDate = s.dueDate,
                        creatorUid = me.uid,
                        creatorName = creatorName,
                        isCompleted = s.isCompleted,
                        amount = s.amount.toDoubleOrNull(),
                        frequency = s.frequency.takeIf { it != ReminderFrequency.NONE },
                    )
                }
            }.onSuccess { _state.update { it.copy(loading = false, saved = true) } }
                .onFailure { t -> _state.update { it.copy(loading = false, error = t.message ?: "Save failed") } }
        }
    }
}
