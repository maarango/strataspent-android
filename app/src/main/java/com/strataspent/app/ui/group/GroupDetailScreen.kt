package com.strataspent.app.ui.group

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.Card
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.strataspent.app.R
import com.strataspent.app.data.model.Expenditure
import com.strataspent.app.data.model.MemberBalance
import com.strataspent.app.data.todayIso
import com.strataspent.app.ui.LocalCurrencyCode
import com.strataspent.app.ui.formatMoney as appFormatMoney
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupDetailScreen(
    vm: GroupDetailViewModel,
    onBack: () -> Unit,
    onAddExpense: () -> Unit,
    onEditExpense: (expenseId: String) -> Unit,
    onAddMembers: () -> Unit,
    onReminders: () -> Unit,
    onAnalytics: () -> Unit,
    onEditGroup: () -> Unit,
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val selectedDate by vm.selectedDate.collectAsStateWithLifecycle()
    val group = ui.group

    // Inline calendar state, seeded from the selected day. User taps flow back
    // into the view-model via the LaunchedEffect below (one-way: picker → VM).
    val dateState = rememberDatePickerState(
        initialSelectedDateMillis = isoToUtcMillis(selectedDate)
    )
    LaunchedEffect(dateState.selectedDateMillis) {
        dateState.selectedDateMillis?.let { millis ->
            val iso = utcMillisToIso(millis)
            if (iso != selectedDate) vm.selectDate(iso)
        }
    }

    // Only the selected day's expenses appear in the list (balances stay all-time).
    val dayExpenses = remember(ui.expenditures, selectedDate) {
        ui.expenditures.filter { it.date == selectedDate }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(group?.name ?: "") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    IconButton(onClick = onAnalytics) {
                        Icon(Icons.Filled.BarChart, contentDescription = "Analytics")
                    }
                    IconButton(onClick = onReminders) {
                        Icon(
                            Icons.Filled.NotificationsActive,
                            contentDescription = "Reminders",
                        )
                    }
                    IconButton(onClick = onAddMembers) {
                        Icon(
                            Icons.Filled.PersonAdd,
                            contentDescription = stringResource(R.string.members_add_content_desc),
                        )
                    }
                    IconButton(onClick = onEditGroup) {
                        Icon(Icons.Filled.Edit, contentDescription = "Edit group")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddExpense) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.group_add_expense))
            }
        },
    ) { inner ->
        if (group == null) return@Scaffold
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(inner),
        ) {
            item {
                Text(
                    stringResource(R.string.group_balances),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            items(ui.balances, key = { it.identifier }) { b -> BalanceRow(b) }

            item { Spacer(Modifier.height(12.dp)) }
            item { HorizontalDivider() }
            item { Spacer(Modifier.height(4.dp)) }

            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.group_expenses),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        if (selectedDate == todayIso()) "Today · ${prettyDate(selectedDate)}"
                        else prettyDate(selectedDate),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (dayExpenses.isEmpty()) {
                item {
                    Text(
                        "No expenses on this day.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(dayExpenses, key = { it.id }) { e ->
                    ExpenseRow(e, onClick = { onEditExpense(e.id) })
                }
            }

            // Calendar: pick a day to view/edit its expenses.
            item { Spacer(Modifier.height(12.dp)) }
            item { HorizontalDivider() }
            item {
                Card(Modifier.fillMaxWidth()) {
                    DatePicker(
                        state = dateState,
                        title = null,
                        headline = null,
                        showModeToggle = false,
                        colors = DatePickerDefaults.colors(
                            containerColor = MaterialTheme.colorScheme.surface,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun BalanceRow(b: MemberBalance) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(b.displayLabel, style = MaterialTheme.typography.bodyLarge)
        Text(
            formatMoney(b.net),
            fontWeight = FontWeight.Medium,
            color = when {
                b.net > 0.005 -> MaterialTheme.colorScheme.tertiary
                b.net < -0.005 -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurface
            },
        )
    }
}

@Composable
private fun ExpenseRow(e: Expenditure, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            // Row 1: category · date · paid by · split | amount
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val uniqueSplitParties = e.splits.keys.map { it.lowercase() }.distinct().size
                val splitLabel = if (uniqueSplitParties == 0) "no split"
                else if (uniqueSplitParties == 1) "1 way"
                else "$uniqueSplitParties ways"
                val payer = e.contributorName.ifBlank { "someone" }
                Text(
                    "${e.category} · ${e.date} · $payer · $splitLabel",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(end = 12.dp),
                )
                Text(
                    formatMoney(e.amount),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            // Row 2: note (single line; full text visible on tap → edit screen).
            if (e.note.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    e.note,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** The web schema stores amounts as plain numbers without currency info.
 *  We read the user's currency preference from LocalCurrencyCode and fall
 *  back to the device's default locale currency. */
@androidx.compose.runtime.Composable
private fun formatMoney(value: Double): String =
    appFormatMoney(value, LocalCurrencyCode.current)

// --- date <-> calendar conversion -----------------------------------------
// Expenditure dates are "yyyy-MM-dd" strings; the Material DatePicker speaks
// UTC-midnight millis. We parse/format with a fixed UTC zone on both sides so
// the day label round-trips identically regardless of the device timezone.

private fun utcFormat(pattern: String): SimpleDateFormat =
    SimpleDateFormat(pattern, Locale.getDefault()).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

private fun isoToUtcMillis(iso: String): Long? =
    runCatching { utcFormat("yyyy-MM-dd").parse(iso)?.time }.getOrNull()

private fun utcMillisToIso(millis: Long): String =
    utcFormat("yyyy-MM-dd").format(Date(millis))

/** "Mon, Jun 5" for the section header. */
private fun prettyDate(iso: String): String =
    runCatching { utcFormat("yyyy-MM-dd").parse(iso) }.getOrNull()
        ?.let { utcFormat("EEE, MMM d").format(it) } ?: iso
