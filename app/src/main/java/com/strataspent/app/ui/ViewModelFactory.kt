package com.strataspent.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import com.strataspent.app.ServiceLocator
import com.strataspent.app.ui.analytics.AnalyticsViewModel
import com.strataspent.app.ui.auth.AuthViewModel
import com.strataspent.app.ui.expense.AddExpenseViewModel
import com.strataspent.app.ui.group.EditGroupViewModel
import com.strataspent.app.ui.group.GroupDetailViewModel
import com.strataspent.app.ui.groups.AddMembersViewModel
import com.strataspent.app.ui.groups.CreateGroupViewModel
import com.strataspent.app.ui.groups.GroupListViewModel
import com.strataspent.app.ui.reminders.AddReminderViewModel
import com.strataspent.app.ui.reminders.RemindersListViewModel
import com.strataspent.app.ui.settings.SettingsViewModel
import com.strataspent.app.ui.split.SplitBillViewModel

/**
 * Minimal manual-DI factory. Replace with Hilt by deleting this file and the
 * [ServiceLocator] together.
 *
 * Scoping parameters ([groupId], [expenseId], [reminderId]) flow in via the
 * factory constructor — call sites that need them build their own factory
 * instance (see StrataNavGraph).
 */
class StrataViewModelFactory(
    private val locator: ServiceLocator,
    private val groupId: String? = null,
    private val expenseId: String? = null,
    private val reminderId: String? = null,
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
        return when (modelClass) {
            AuthViewModel::class.java -> AuthViewModel(locator.authRepo)
            GroupListViewModel::class.java -> GroupListViewModel(locator.authRepo, locator.groupRepo)
            CreateGroupViewModel::class.java -> CreateGroupViewModel(locator.authRepo, locator.groupRepo)
            GroupDetailViewModel::class.java -> GroupDetailViewModel(
                requireNotNull(groupId) { "groupId required for GroupDetailViewModel" },
                locator.authRepo, locator.groupRepo, locator.expenseRepo,
                locator.userDirectory,
            )
            EditGroupViewModel::class.java -> EditGroupViewModel(
                groupId = requireNotNull(groupId) { "groupId required for EditGroupViewModel" },
                authRepo = locator.authRepo,
                groupRepo = locator.groupRepo,
                userDirectory = locator.userDirectory,
            )
            AddExpenseViewModel::class.java -> AddExpenseViewModel(
                groupId = requireNotNull(groupId) { "groupId required for AddExpenseViewModel" },
                expenseId = expenseId,
                authRepo = locator.authRepo,
                groupRepo = locator.groupRepo,
                expenseRepo = locator.expenseRepo,
                userDirectory = locator.userDirectory,
                ocrRepo = locator.ocrRepo,
                languagePref = locator.languagePref,
                pendingOcr = locator.pendingOcr,
            )
            AddMembersViewModel::class.java -> AddMembersViewModel(
                groupId = requireNotNull(groupId) { "groupId required for AddMembersViewModel" },
                groupRepo = locator.groupRepo,
            )
            RemindersListViewModel::class.java -> RemindersListViewModel(
                groupId = requireNotNull(groupId) { "groupId required for RemindersListViewModel" },
                authRepo = locator.authRepo,
                remindersRepo = locator.remindersRepo,
                expenseRepo = locator.expenseRepo,
            )
            AddReminderViewModel::class.java -> AddReminderViewModel(
                groupId = requireNotNull(groupId) { "groupId required for AddReminderViewModel" },
                reminderId = reminderId,
                authRepo = locator.authRepo,
                remindersRepo = locator.remindersRepo,
            )
            AnalyticsViewModel::class.java -> AnalyticsViewModel(
                groupId = requireNotNull(groupId) { "groupId required for AnalyticsViewModel" },
                authRepo = locator.authRepo,
                expenseRepo = locator.expenseRepo,
                userDirectory = locator.userDirectory,
            )
            SettingsViewModel::class.java -> SettingsViewModel(
                authRepo = locator.authRepo,
                groupRepo = locator.groupRepo,
                expenseRepo = locator.expenseRepo,
                remindersRepo = locator.remindersRepo,
                userDirectory = locator.userDirectory,
                languagePref = locator.languagePref,
            )
            SplitBillViewModel::class.java -> SplitBillViewModel(
                ocrRepo = locator.ocrRepo,
            )
            else -> error("Unknown ViewModel: ${modelClass.name}")
        } as T
    }
}
