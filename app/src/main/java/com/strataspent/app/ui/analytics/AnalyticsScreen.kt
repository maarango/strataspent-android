package com.strataspent.app.ui.analytics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.strataspent.app.ui.LocalCurrencyCode
import com.strataspent.app.ui.formatMoney as appFormatMoney

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalyticsScreen(
    vm: AnalyticsViewModel,
    onBack: () -> Unit,
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val aiState by vm.aiState.collectAsStateWithLifecycle()
    val categoryBreakdown by vm.categoryBreakdown.collectAsStateWithLifecycle()
    val currencyCode = LocalCurrencyCode.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Analytics") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { inner ->
        var mode by remember { mutableStateOf(AnalyticsMode.GROUP) }
        val scope = if (mode == AnalyticsMode.GROUP) ui.group else ui.you
        val categoryRows = if (mode == AnalyticsMode.GROUP) categoryBreakdown.group
            else categoryBreakdown.you

        if (ui.group.expenditureCount == 0 && ui.you.expenditureCount == 0) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(inner)
                    .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Add some expenditures to see analytics.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }

        var showIncomeDialog by remember { mutableStateOf(false) }
        if (showIncomeDialog) {
            EditIncomeDialog(
                current = ui.personal.income,
                onDismiss = { showIncomeDialog = false },
                onSave = { amount ->
                    vm.updateMyIncome(amount)
                    showIncomeDialog = false
                },
            )
        }

        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(inner),
        ) {
            item { ModeSelector(mode = mode, onChange = { mode = it }) }
            item { SummaryCard(scope = scope, mode = mode) }
            item {
                AiAnalysisCard(
                    state = aiState,
                    mode = mode,
                    onRange = vm::setAnalysisRange,
                    onGenerate = { vm.generateAiAnalysis(mode, currencyCode) },
                )
            }
            item {
                PersonalFlowCard(
                    p = ui.personal,
                    onEditIncome = { showIncomeDialog = true },
                )
            }
            item {
                CategoryCard(
                    rows = categoryRows,
                    period = categoryBreakdown.period,
                    onPeriod = vm::setCategoryPeriod,
                )
            }
            item { CashflowCard(scope.byDay) }
            item {
                Text(
                    "Top categories",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            items(categoryRows) { row ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(row.category)
                    Text(formatMoney(row.total), fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

enum class AnalyticsMode { GROUP, PERSONAL }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModeSelector(mode: AnalyticsMode, onChange: (AnalyticsMode) -> Unit) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        val options = listOf(AnalyticsMode.GROUP to "Group", AnalyticsMode.PERSONAL to "You")
        options.forEachIndexed { idx, (value, label) ->
            SegmentedButton(
                selected = mode == value,
                onClick = { onChange(value) },
                shape = SegmentedButtonDefaults.itemShape(index = idx, count = options.size),
            ) { Text(label) }
        }
    }
}

@Composable
private fun PersonalFlowCard(p: PersonalFlow, onEditIncome: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Your financial flow", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = onEditIncome) { Text("Edit income") }
            }
            Spacer(Modifier.height(8.dp))
            FlowRow(label = "Income", value = p.income, positive = true)
            FlowRow(label = "Spending (public)", value = -p.publicSpending, positive = false)
            FlowRow(label = "Spending (private)", value = -p.privateSpending, positive = false)
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Net", style = MaterialTheme.typography.titleMedium)
                Text(
                    formatMoney(p.net),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (p.net >= 0) MaterialTheme.colorScheme.tertiary
                    else MaterialTheme.colorScheme.error,
                )
            }

            // Payment-type breakdown of your own spending. Hidden when there
            // are no tagged payments at all (debit/credit fields are optional).
            if (p.debitSpending > 0 || p.creditSpending > 0 || p.unspecifiedPaymentSpending > 0) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "By payment type",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                if (p.debitSpending > 0) FlowRow("Debit", -p.debitSpending, positive = false)
                if (p.creditSpending > 0) FlowRow("Credit", -p.creditSpending, positive = false)
                if (p.unspecifiedPaymentSpending > 0) {
                    FlowRow("Unspecified", -p.unspecifiedPaymentSpending, positive = false)
                }
            }
        }
    }
}

@Composable
private fun FlowRow(label: String, value: Double, positive: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            formatMoney(value),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = if (positive) MaterialTheme.colorScheme.tertiary
            else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AiAnalysisCard(
    state: AiAnalysisUi,
    mode: AnalyticsMode,
    onRange: (AnalysisRange) -> Unit,
    onGenerate: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("AI financial analysis", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "An AI read of ${if (mode == AnalyticsMode.GROUP) "the group's" else "your"} " +
                    "spending over the chosen period — patterns, trends, and tips. " +
                    "Uses cloud AI; needs internet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            RangeDropdown(selected = state.range, enabled = !state.loading, onSelect = onRange)
            Spacer(Modifier.height(12.dp))

            Button(
                onClick = onGenerate,
                enabled = !state.loading,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.loading) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.size(8.dp))
                    Text("Analyzing…")
                } else {
                    Text(if (state.analysis == null) "Generate analysis" else "Regenerate")
                }
            }

            if (state.error != null) {
                Spacer(Modifier.height(12.dp))
                Text(state.error, color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium)
            }
            if (state.analysis != null && !state.loading) {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))
                Text(state.analysis, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RangeDropdown(
    selected: AnalysisRange,
    enabled: Boolean,
    onSelect: (AnalysisRange) -> Unit,
    label: String = "Analysis period",
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = it },
    ) {
        OutlinedTextField(
            value = selected.label,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            AnalysisRange.entries.forEach { range ->
                DropdownMenuItem(
                    text = { Text(range.label) },
                    onClick = {
                        onSelect(range)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun SummaryCard(scope: AnalyticsScope, mode: AnalyticsMode) {
    val label = if (mode == AnalyticsMode.GROUP) "Total spent (group, public)"
    else "Total spent (you)"
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(formatMoney(scope.grandTotal), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            Text(
                "${scope.expenditureCount} expenditures",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryCard(
    rows: List<CategoryBar>,
    period: AnalysisRange,
    onPeriod: (AnalysisRange) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("By category", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            RangeDropdown(
                selected = period,
                enabled = true,
                onSelect = onPeriod,
                label = "Period",
            )
            Spacer(Modifier.height(12.dp))
            if (rows.isEmpty()) {
                Text(
                    "No spending in this period.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                BarChart(data = rows.map { it.category to it.total })
            }
        }
    }
}

@Composable
private fun CashflowCard(rows: List<CashflowPoint>) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Cash flow over time", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            if (rows.size < 2) {
                Text(
                    "Need at least two days of data for a timeline.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LineChart(data = rows.map { it.date to it.total })
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun formatMoney(value: Double): String =
    appFormatMoney(value, LocalCurrencyCode.current)

@Composable
private fun EditIncomeDialog(
    current: Double,
    onDismiss: () -> Unit,
    onSave: (Double) -> Unit,
) {
    var input by remember(current) {
        mutableStateOf(
            if (current > 0) {
                val asLong = current.toLong()
                if (current == asLong.toDouble()) asLong.toString() else "%.2f".format(current)
            } else ""
        )
    }
    val parsed = input.toDoubleOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Your income") },
        text = {
            Column {
                Text(
                    "Set your recurring/standing income (e.g. monthly salary). " +
                        "It feeds the \"Net\" line on your financial flow.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = input,
                    onValueChange = { v -> input = v.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("Amount") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "For one-off bonuses or extra income, add an expenditure with " +
                        "category \"Global Income\".",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { parsed?.let(onSave) },
                enabled = parsed != null && parsed >= 0.0,
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
