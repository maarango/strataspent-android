package com.strataspent.app.ui.groups

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.strataspent.app.data.AuthRepository
import com.strataspent.app.data.GroupRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CreateGroupUiState(
    val name: String = "",
    val pendingEmail: String = "",
    val invitedEmails: List<String> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val createdGroupId: String? = null,
) {
    val canSubmit: Boolean get() = !loading && name.isNotBlank()
}

class CreateGroupViewModel(
    private val authRepo: AuthRepository,
    private val groupRepo: GroupRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(CreateGroupUiState())
    val state: StateFlow<CreateGroupUiState> = _state.asStateFlow()

    fun onName(v: String) = _state.update { it.copy(name = v) }
    fun onPendingEmail(v: String) = _state.update { it.copy(pendingEmail = v) }

    fun addInvitedEmail() = _state.update {
        val e = it.pendingEmail.trim()
        if (e.isEmpty() || e in it.invitedEmails) it.copy(pendingEmail = "")
        else it.copy(invitedEmails = it.invitedEmails + e, pendingEmail = "")
    }

    fun removeInvitedEmail(email: String) = _state.update {
        it.copy(invitedEmails = it.invitedEmails - email)
    }

    fun submit() {
        val s = _state.value
        if (!s.canSubmit) return
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            runCatching {
                val me = authRepo.currentUser.first() ?: error("Not signed in")
                groupRepo.createGroup(
                    name = s.name.trim(),
                    ownerUid = me.uid,
                    ownerEmail = me.email,
                    invitedEmails = s.invitedEmails,
                )
            }.onSuccess { result ->
                _state.update { it.copy(loading = false, createdGroupId = result.groupId) }
            }.onFailure { t ->
                _state.update { it.copy(loading = false, error = t.message ?: "Create failed") }
            }
        }
    }
}
