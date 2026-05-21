package com.strataspent.app.ui.groups

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.strataspent.app.data.GroupRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AddMembersUi(
    val pendingEmail: String = "",
    val stagedEmails: List<String> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val done: Boolean = false,
) {
    val canSubmit: Boolean get() = !loading && !done && stagedEmails.isNotEmpty()
}

class AddMembersViewModel(
    val groupId: String,
    private val groupRepo: GroupRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(AddMembersUi())
    val state: StateFlow<AddMembersUi> = _state.asStateFlow()

    fun onPendingEmail(v: String) = _state.update { it.copy(pendingEmail = v) }

    fun stageEmail() = _state.update {
        val e = it.pendingEmail.trim()
        if (e.isEmpty() || e in it.stagedEmails) it.copy(pendingEmail = "")
        else it.copy(stagedEmails = it.stagedEmails + e, pendingEmail = "")
    }

    fun unstageEmail(email: String) = _state.update {
        it.copy(stagedEmails = it.stagedEmails - email)
    }

    fun submit() {
        val s = _state.value
        if (!s.canSubmit) return
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            runCatching {
                groupRepo.addMembersToGroup(groupId, s.stagedEmails)
            }.onSuccess {
                _state.update { it.copy(loading = false, done = true) }
            }.onFailure { t ->
                _state.update { it.copy(loading = false, error = t.message ?: "Add failed") }
            }
        }
    }
}
