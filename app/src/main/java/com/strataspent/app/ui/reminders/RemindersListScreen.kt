package com.strataspent.app.ui.reminders

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.strataspent.app.data.model.Reminder
import com.strataspent.app.data.todayIso

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemindersListScreen(
    vm: RemindersListViewModel,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (reminderId: String) -> Unit,
) {
    val reminders by vm.reminders.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Reminders") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAdd) {
                Icon(Icons.Filled.Add, contentDescription = "Add reminder")
            }
        },
    ) { inner ->
        if (reminders.isEmpty()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(inner)
                    .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "No reminders yet. Tap + to create one.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(inner),
            ) {
                items(reminders, key = { it.id }) { r ->
                    ReminderRow(
                        r = r,
                        onToggle = { vm.toggleCompleted(r) },
                        onClick = { onEdit(r.id) },
                        onPay = { vm.payReminder(r) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ReminderRow(
    r: Reminder,
    onToggle: () -> Unit,
    onClick: () -> Unit,
    onPay: () -> Unit,
) {
    var showPayConfirm by remember { mutableStateOf(false) }

    if (showPayConfirm) {
        AlertDialog(
            onDismissRequest = { showPayConfirm = false },
            title = { Text("Mark as paid?") },
            text = {
                Text(
                    "Logs a private expenditure of \$${"%.2f".format(r.amount ?: 0.0)} " +
                        "in \"${r.category ?: "Other"}\" and marks this reminder complete. " +
                        "Group balances are unaffected."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showPayConfirm = false
                    onPay()
                }) { Text("Pay") }
            },
            dismissButton = {
                TextButton(onClick = { showPayConfirm = false }) { Text("Cancel") }
            },
        )
    }

    val isOverdue = !r.isCompleted && r.dueDate.isNotBlank() && r.dueDate <= todayIso()

    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            androidx.compose.foundation.layout.Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = r.isCompleted, onCheckedChange = { onToggle() })
                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                    Text(
                        r.title,
                        style = MaterialTheme.typography.titleMedium,
                        textDecoration = if (r.isCompleted) TextDecoration.LineThrough else null,
                    )
                    val sub = buildString {
                        append(r.dueDate)
                        r.amount?.let { append(" · $${"%.2f".format(it)}") }
                        r.frequency?.takeIf { it.isNotBlank() && it != "none" }?.let { append(" · $it") }
                    }
                    Text(
                        sub,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isOverdue) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (isOverdue && r.amount != null) {
                    Button(onClick = { showPayConfirm = true }) { Text("Pay") }
                }
            }
        }
    }
}
