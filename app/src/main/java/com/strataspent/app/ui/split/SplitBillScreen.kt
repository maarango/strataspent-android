package com.strataspent.app.ui.split

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.strataspent.app.ui.LocalCurrencyCode
import com.strataspent.app.ui.expense.ReceiptSourceSheet
import com.strataspent.app.ui.expense.decodeImageUri
import com.strataspent.app.ui.formatMoney as appFormatMoney
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SplitBillScreen(
    vm: SplitBillViewModel,
    onBack: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val totals by vm.perPersonTotals.collectAsStateWithLifecycle()
    val grand by vm.grandTotal.collectAsStateWithLifecycle()
    val unassigned by vm.unassignedTotal.collectAsStateWithLifecycle()

    var showScanSheet by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    if (showScanSheet) {
        ReceiptSourceSheet(
            onDismiss = { showScanSheet = false },
            onUriReady = { uri ->
                if (uri != null) {
                    coroutineScope.launch {
                        val bmp = withContext(Dispatchers.IO) { decodeImageUri(context, uri) }
                        bmp?.let(vm::scanReceipt)
                    }
                }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Split a bill")
                        Text(
                            "One-time split · not saved to the cloud",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { inner ->
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(inner),
        ) {
            item { ParticipantsCard(state, vm) }
            item { ItemsActionRow(scanning = state.ocrLoading, onScan = { showScanSheet = true }) }
            if (state.message != null) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.padding(12.dp).fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                state.message!!,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = vm::clearMessage) { Text("OK") }
                        }
                    }
                }
            }
            item { AddItemCard(state, vm) }
            if (state.items.isNotEmpty()) {
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Items", style = MaterialTheme.typography.titleMedium)
                        TextButton(
                            onClick = vm::assignAllToEveryone,
                            enabled = state.names.isNotEmpty(),
                        ) { Text("Assign all to everyone") }
                    }
                }
                items(state.items, key = { it.id }) { item ->
                    ItemRow(
                        item = item,
                        names = state.names,
                        onToggle = { name -> vm.toggleAssignment(item.id, name) },
                        onDelete = { vm.removeItem(item.id) },
                    )
                }
            }
            item { TotalsCard(totals, grand, unassigned) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ParticipantsCard(state: SplitBillUi, vm: SplitBillViewModel) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Who's splitting?", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = state.newName,
                    onValueChange = vm::onNewName,
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = vm::addName) { Text("Add") }
            }
            if (state.names.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    state.names.forEach { name ->
                        AssistChip(
                            onClick = { vm.removeName(name) },
                            label = { Text(name) },
                            trailingIcon = {
                                Icon(
                                    Icons.Filled.Close, contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ItemsActionRow(scanning: Boolean, onScan: () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AssistChip(
            onClick = onScan,
            enabled = !scanning,
            label = { Text(if (scanning) "Scanning…" else "Scan receipt (line items)") },
            leadingIcon = { Icon(Icons.Filled.DocumentScanner, contentDescription = null) },
        )
    }
}

@Composable
private fun AddItemCard(state: SplitBillUi, vm: SplitBillViewModel) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Add item", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = state.newItemDesc,
                onValueChange = vm::onNewItemDesc,
                label = { Text("Description") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = state.newItemAmount,
                    onValueChange = vm::onNewItemAmount,
                    label = { Text("Amount") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = vm::addItem,
                    enabled = state.newItemDesc.isNotBlank() &&
                        (state.newItemAmount.toDoubleOrNull() ?: 0.0) > 0,
                ) { Text("Add") }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ItemRow(
    item: BillItem,
    names: List<String>,
    onToggle: (String) -> Unit,
    onDelete: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(item.description, style = MaterialTheme.typography.titleMedium)
                    val perShare = if (item.assignedTo.isEmpty()) item.amount
                    else item.amount / item.assignedTo.size
                    val ratioLabel = when {
                        item.assignedTo.isEmpty() -> "not assigned"
                        item.assignedTo.size == 1 -> "1 person"
                        else -> "${item.assignedTo.size} people · ${formatMoney(perShare)} each"
                    }
                    Text(
                        ratioLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(formatMoney(item.amount), style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "Remove item")
                }
            }
            if (names.isEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Add participants above to assign this item.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    names.forEach { name ->
                        FilterChip(
                            selected = name in item.assignedTo,
                            onClick = { onToggle(name) },
                            label = { Text(name) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TotalsCard(
    totals: Map<String, Double>,
    grand: Double,
    unassigned: Double,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Totals", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            if (totals.isEmpty()) {
                Text(
                    "Add participants to see per-person totals.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                totals.forEach { (name, total) ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(name)
                        Text(formatMoney(total), fontWeight = FontWeight.Medium)
                    }
                }
            }
            if (unassigned > 0.005) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Unassigned: ${formatMoney(unassigned)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Bill total", style = MaterialTheme.typography.titleMedium)
                Text(formatMoney(grand), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun formatMoney(v: Double): String =
    appFormatMoney(v, LocalCurrencyCode.current)
