package com.strataspent.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.strataspent.app.ui.LocalCurrencyCode
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.strataspent.app.ServiceLocator
import com.strataspent.app.ui.StrataViewModelFactory
import com.strataspent.app.ui.auth.AuthScreen
import com.strataspent.app.ui.auth.AuthViewModel
import com.strataspent.app.ui.expense.AddExpenseScreen
import com.strataspent.app.ui.expense.AddExpenseViewModel
import com.strataspent.app.ui.group.EditGroupScreen
import com.strataspent.app.ui.group.EditGroupViewModel
import com.strataspent.app.ui.group.GroupDetailScreen
import com.strataspent.app.ui.group.GroupDetailViewModel
import com.strataspent.app.ui.groups.AddMembersScreen
import com.strataspent.app.ui.groups.AddMembersViewModel
import com.strataspent.app.ui.groups.CreateGroupScreen
import com.strataspent.app.ui.groups.CreateGroupViewModel
import com.strataspent.app.ui.groups.GroupListScreen
import com.strataspent.app.ui.groups.GroupListViewModel
import com.strataspent.app.ui.analytics.AnalyticsScreen
import com.strataspent.app.ui.analytics.AnalyticsViewModel
import com.strataspent.app.ui.settings.SettingsScreen
import com.strataspent.app.ui.settings.SettingsViewModel
import com.strataspent.app.ui.split.SplitBillScreen
import com.strataspent.app.ui.split.SplitBillViewModel
import com.strataspent.app.ui.reminders.AddReminderScreen
import com.strataspent.app.ui.reminders.AddReminderViewModel
import com.strataspent.app.ui.reminders.RemindersListScreen
import com.strataspent.app.ui.reminders.RemindersListViewModel

object Routes {
    const val AUTH = "auth"
    const val GROUPS = "groups"
    const val CREATE_GROUP = "groups/create"
    const val GROUP_DETAIL = "groups/{groupId}"
    const val ADD_EXPENSE = "groups/{groupId}/expenses/add"
    const val EDIT_EXPENSE = "groups/{groupId}/expenses/{expenseId}/edit"
    const val ADD_MEMBERS = "groups/{groupId}/members/add"
    const val REMINDERS = "groups/{groupId}/reminders"
    const val ADD_REMINDER = "groups/{groupId}/reminders/add"
    const val EDIT_REMINDER = "groups/{groupId}/reminders/{reminderId}/edit"
    const val ANALYTICS = "groups/{groupId}/analytics"
    const val SETTINGS = "settings"
    const val SPLIT_BILL = "split-bill"
    const val EDIT_GROUP = "groups/{groupId}/edit"

    fun groupDetail(id: String) = "groups/$id"
    fun editGroup(id: String) = "groups/$id/edit"
    fun addExpense(id: String) = "groups/$id/expenses/add"
    fun editExpense(groupId: String, expenseId: String) =
        "groups/$groupId/expenses/$expenseId/edit"
    fun addMembers(groupId: String) = "groups/$groupId/members/add"
    fun reminders(groupId: String) = "groups/$groupId/reminders"
    fun addReminder(groupId: String) = "groups/$groupId/reminders/add"
    fun editReminder(groupId: String, reminderId: String) =
        "groups/$groupId/reminders/$reminderId/edit"
    fun analytics(groupId: String) = "groups/$groupId/analytics"
}

@Composable
fun StrataNavGraph(locator: ServiceLocator) {
    val navController = rememberNavController()
    val factory = StrataViewModelFactory(locator)
    val authVm: AuthViewModel = viewModel(factory = factory)

    val user by locator.authRepo.currentUser
        .collectAsStateWithLifecycle(initialValue = null)

    LaunchedEffect(user) {
        val target = if (user == null) Routes.AUTH else Routes.GROUPS
        if (navController.currentDestination?.route != target) {
            navController.navigate(target) {
                popUpTo(0) { inclusive = true }
                launchSingleTop = true
            }
        }
    }

    val currencyCode by locator.languagePref.currencyCode.collectAsStateWithLifecycle()
    CompositionLocalProvider(LocalCurrencyCode provides currencyCode) {
    NavHost(navController, startDestination = Routes.AUTH) {
        composable(Routes.AUTH) {
            AuthScreen(authVm)
        }

        composable(Routes.GROUPS) {
            val vm: GroupListViewModel = viewModel(factory = factory)
            GroupListScreen(
                vm = vm,
                onOpenGroup = { navController.navigate(Routes.groupDetail(it)) },
                onCreateGroup = { navController.navigate(Routes.CREATE_GROUP) },
                onSettings = { navController.navigate(Routes.SETTINGS) },
                onSplitBill = { navController.navigate(Routes.SPLIT_BILL) },
            )
        }

        composable(Routes.SETTINGS) {
            val vm: SettingsViewModel = viewModel(factory = factory)
            SettingsScreen(vm = vm, onBack = { navController.popBackStack() })
        }

        composable(Routes.SPLIT_BILL) {
            val vm: SplitBillViewModel = viewModel(factory = factory)
            SplitBillScreen(vm = vm, onBack = { navController.popBackStack() })
        }

        composable(Routes.CREATE_GROUP) {
            val vm: CreateGroupViewModel = viewModel(factory = factory)
            CreateGroupScreen(
                vm = vm,
                onBack = { navController.popBackStack() },
                onCreated = { id ->
                    navController.navigate(Routes.groupDetail(id)) {
                        popUpTo(Routes.GROUPS) { inclusive = false }
                    }
                },
            )
        }

        composable(
            route = Routes.GROUP_DETAIL,
            arguments = listOf(navArgument("groupId") { type = NavType.StringType }),
        ) { entry ->
            val gid = entry.arguments?.getString("groupId").orEmpty()
            val scoped = StrataViewModelFactory(locator, gid)
            val vm: GroupDetailViewModel = viewModel(factory = scoped, key = "detail-$gid")
            GroupDetailScreen(
                vm = vm,
                onBack = { navController.popBackStack() },
                onAddExpense = { navController.navigate(Routes.addExpense(gid)) },
                onEditExpense = { eid -> navController.navigate(Routes.editExpense(gid, eid)) },
                onAddMembers = { navController.navigate(Routes.addMembers(gid)) },
                onReminders = { navController.navigate(Routes.reminders(gid)) },
                onAnalytics = { navController.navigate(Routes.analytics(gid)) },
                onEditGroup = { navController.navigate(Routes.editGroup(gid)) },
            )
        }

        composable(
            route = Routes.EDIT_GROUP,
            arguments = listOf(navArgument("groupId") { type = NavType.StringType }),
        ) { entry ->
            val gid = entry.arguments?.getString("groupId").orEmpty()
            val scoped = StrataViewModelFactory(locator, gid)
            val vm: EditGroupViewModel = viewModel(factory = scoped, key = "edit-group-$gid")
            EditGroupScreen(vm = vm, onBack = { navController.popBackStack() })
        }

        composable(
            route = Routes.ADD_EXPENSE,
            arguments = listOf(navArgument("groupId") { type = NavType.StringType }),
        ) { entry ->
            val gid = entry.arguments?.getString("groupId").orEmpty()
            val scoped = StrataViewModelFactory(locator, gid)
            val vm: AddExpenseViewModel = viewModel(factory = scoped, key = "add-expense-$gid")
            AddExpenseScreen(
                vm = vm,
                onBack = { navController.popBackStack() },
                onSaved = { navController.popBackStack() },
            )
        }

        composable(
            route = Routes.EDIT_EXPENSE,
            arguments = listOf(
                navArgument("groupId") { type = NavType.StringType },
                navArgument("expenseId") { type = NavType.StringType },
            ),
        ) { entry ->
            val gid = entry.arguments?.getString("groupId").orEmpty()
            val eid = entry.arguments?.getString("expenseId").orEmpty()
            val scoped = StrataViewModelFactory(locator, gid, eid)
            val vm: AddExpenseViewModel =
                viewModel(factory = scoped, key = "edit-expense-$gid-$eid")
            AddExpenseScreen(
                vm = vm,
                onBack = { navController.popBackStack() },
                onSaved = { navController.popBackStack() },
            )
        }

        composable(
            route = Routes.ADD_MEMBERS,
            arguments = listOf(navArgument("groupId") { type = NavType.StringType }),
        ) { entry ->
            val gid = entry.arguments?.getString("groupId").orEmpty()
            val scoped = StrataViewModelFactory(locator, gid)
            val vm: AddMembersViewModel = viewModel(factory = scoped, key = "add-members-$gid")
            AddMembersScreen(
                vm = vm,
                onBack = { navController.popBackStack() },
                onFinished = { navController.popBackStack() },
            )
        }

        composable(
            route = Routes.REMINDERS,
            arguments = listOf(navArgument("groupId") { type = NavType.StringType }),
        ) { entry ->
            val gid = entry.arguments?.getString("groupId").orEmpty()
            val scoped = StrataViewModelFactory(locator, gid)
            val vm: RemindersListViewModel = viewModel(factory = scoped, key = "reminders-$gid")
            RemindersListScreen(
                vm = vm,
                onBack = { navController.popBackStack() },
                onAdd = { navController.navigate(Routes.addReminder(gid)) },
                onEdit = { rid -> navController.navigate(Routes.editReminder(gid, rid)) },
            )
        }

        composable(
            route = Routes.ADD_REMINDER,
            arguments = listOf(navArgument("groupId") { type = NavType.StringType }),
        ) { entry ->
            val gid = entry.arguments?.getString("groupId").orEmpty()
            val scoped = StrataViewModelFactory(locator, gid)
            val vm: AddReminderViewModel = viewModel(factory = scoped, key = "add-reminder-$gid")
            AddReminderScreen(
                vm = vm,
                onBack = { navController.popBackStack() },
                onSaved = { navController.popBackStack() },
            )
        }

        composable(
            route = Routes.EDIT_REMINDER,
            arguments = listOf(
                navArgument("groupId") { type = NavType.StringType },
                navArgument("reminderId") { type = NavType.StringType },
            ),
        ) { entry ->
            val gid = entry.arguments?.getString("groupId").orEmpty()
            val rid = entry.arguments?.getString("reminderId").orEmpty()
            val scoped = StrataViewModelFactory(locator, gid, reminderId = rid)
            val vm: AddReminderViewModel =
                viewModel(factory = scoped, key = "edit-reminder-$gid-$rid")
            AddReminderScreen(
                vm = vm,
                onBack = { navController.popBackStack() },
                onSaved = { navController.popBackStack() },
            )
        }

        composable(
            route = Routes.ANALYTICS,
            arguments = listOf(navArgument("groupId") { type = NavType.StringType }),
        ) { entry ->
            val gid = entry.arguments?.getString("groupId").orEmpty()
            val scoped = StrataViewModelFactory(locator, gid)
            val vm: AnalyticsViewModel = viewModel(factory = scoped, key = "analytics-$gid")
            AnalyticsScreen(vm = vm, onBack = { navController.popBackStack() })
        }
    }
    } // CompositionLocalProvider
}
