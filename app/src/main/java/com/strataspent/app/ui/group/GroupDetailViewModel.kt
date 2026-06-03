package com.strataspent.app.ui.group

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.strataspent.app.data.AuthRepository
import com.strataspent.app.data.ExpenseRepository
import com.strataspent.app.data.GroupRepository
import com.strataspent.app.data.UserDirectoryRepository
import com.strataspent.app.data.computeBalances
import com.strataspent.app.data.isVisibleTo
import com.strataspent.app.data.model.Expenditure
import com.strataspent.app.data.model.Group
import com.strataspent.app.data.model.MemberBalance
import com.strataspent.app.data.model.UserProfile
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class GroupDetailUi(
    val group: Group? = null,
    val expenditures: List<Expenditure> = emptyList(),
    val balances: List<MemberBalance> = emptyList(),
    val error: String? = null,
)

class GroupDetailViewModel(
    val groupId: String,
    authRepo: AuthRepository,
    groupRepo: GroupRepository,
    expenseRepo: ExpenseRepository,
    private val userDirectory: UserDirectoryRepository,
) : ViewModel() {

    val me: StateFlow<UserProfile?> = authRepo.currentUser
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val ui: StateFlow<GroupDetailUi> = combine(
        groupRepo.group(groupId)
            .onEach { g -> g?.let { hydrateDirectory(it.members) } },
        expenseRepo.expenditures(groupId)
            .onEach { exps -> hydrateDirectory(exps.map { it.contributorUid }) },
        authRepo.currentUser,
        userDirectory.directory,
    ) { group, expenditures, viewer, dir ->
        if (group == null) return@combine GroupDetailUi()
        // Hide other members' private expenditures from this viewer. Private
        // entries split solely with their owner, so they net to zero in the
        // balance fold — dropping them here keeps the list and balances aligned.
        val visible = expenditures.filter { it.isVisibleTo(viewer, dir) }
        GroupDetailUi(
            group = group,
            expenditures = visible,
            balances = computeBalances(group, visible, viewer, dir),
        )
    }.catch { t ->
        Log.w(TAG, "group detail flow error", t)
        emit(GroupDetailUi(error = t.message ?: "Couldn't load group"))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GroupDetailUi())

    private fun hydrateDirectory(identifiers: List<String>) {
        viewModelScope.launch { userDirectory.prefetch(identifiers) }
    }

    private companion object { const val TAG = "GroupDetailVM" }
}
