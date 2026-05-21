package com.strataspent.app.ui.group

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.strataspent.app.data.AuthRepository
import com.strataspent.app.data.GroupRepository
import com.strataspent.app.data.UserDirectoryRepository
import com.strataspent.app.data.model.Group
import com.strataspent.app.data.model.UserProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class EditGroupUi(
    val nameInput: String = "",
    val saving: Boolean = false,
    val message: String? = null,
)

class EditGroupViewModel(
    val groupId: String,
    authRepo: AuthRepository,
    private val groupRepo: GroupRepository,
    private val userDirectory: UserDirectoryRepository,
) : ViewModel() {

    val me: StateFlow<UserProfile?> = authRepo.currentUser
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val group: StateFlow<Group?> = groupRepo.group(groupId)
        .onEach { g -> g?.let { viewModelScope.launch { userDirectory.prefetch(it.members) } } }
        .catch { t ->
            Log.w(TAG, "group flow error", t)
            emit(null)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val directory: StateFlow<Map<String, UserProfile>> = userDirectory.directory

    /** True when the viewer is the group's `createdBy`. Drives the visibility
     *  of the Remove button on each member row. */
    val isCreator: StateFlow<Boolean> = combine(group, me) { g, viewer ->
        g != null && viewer != null && g.createdBy == viewer.uid
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _state = MutableStateFlow(EditGroupUi())
    val state: StateFlow<EditGroupUi> = _state.asStateFlow()

    /** Seed the rename input once the group loads. */
    fun seedNameIfEmpty(currentName: String) {
        _state.update {
            if (it.nameInput.isBlank()) it.copy(nameInput = currentName) else it
        }
    }

    fun onName(v: String) = _state.update { it.copy(nameInput = v) }

    fun renameGroup() {
        val target = _state.value.nameInput.trim()
        val current = group.value?.name.orEmpty()
        if (target.isBlank() || target == current) return
        _state.update { it.copy(saving = true, message = null) }
        viewModelScope.launch {
            runCatching { groupRepo.renameGroup(groupId, target) }
                .onSuccess { _state.update { it.copy(saving = false, message = "Renamed.") } }
                .onFailure { t ->
                    _state.update {
                        it.copy(saving = false, message = "Rename failed: ${t.message}")
                    }
                }
        }
    }

    fun removeMember(identifier: String) {
        viewModelScope.launch {
            runCatching { groupRepo.removeMemberFromGroup(groupId, identifier) }
                .onFailure { t ->
                    _state.update {
                        it.copy(message = "Couldn't remove: ${t.message}")
                    }
                }
        }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    private companion object { const val TAG = "EditGroupVM" }
}
