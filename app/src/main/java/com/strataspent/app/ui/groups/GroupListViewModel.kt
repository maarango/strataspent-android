package com.strataspent.app.ui.groups

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.strataspent.app.data.AuthRepository
import com.strataspent.app.data.GroupRepository
import com.strataspent.app.data.model.Group
import com.strataspent.app.data.model.UserProfile
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn

@OptIn(ExperimentalCoroutinesApi::class)
class GroupListViewModel(
    private val authRepo: AuthRepository,
    private val groupRepo: GroupRepository,
) : ViewModel() {

    val user: StateFlow<UserProfile?> = authRepo.currentUser
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _loadError = MutableStateFlow<String?>(null)
    val loadError: StateFlow<String?> = _loadError.asStateFlow()

    val groups: StateFlow<List<Group>> = authRepo.currentUser
        .flatMapLatest { u ->
            if (u == null) flowOf(emptyList()) else groupRepo.groupsFor(u.uid, u.email)
        }
        .onEach { _loadError.value = null }
        .catch { t ->
            Log.w(TAG, "groups flow error", t)
            _loadError.value = t.message ?: "Couldn't load groups"
            emit(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun signOut() = authRepo.signOut()

    private companion object { const val TAG = "GroupListVM" }
}
