package com.strataspent.app.ui.expense

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.strataspent.app.R
import com.strataspent.app.data.resolveDisplayName
import com.strataspent.app.data.model.Categories
import com.strataspent.app.data.model.PaymentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddExpenseScreen(
    vm: AddExpenseViewModel,
    onBack: () -> Unit,
    onSaved: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val group by vm.group.collectAsStateWithLifecycle()
    val canonicalMembers by vm.canonicalMembers.collectAsStateWithLifecycle()
    val directory by vm.directory.collectAsStateWithLifecycle()

    var showScanSheet by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val appContext = context.applicationContext
    if (showScanSheet) {
        ReceiptSourceSheet(
            onDismiss = { showScanSheet = false },
            onUriReady = { uri ->
                if (uri != null) {
                    coroutineScope.launch {
                        val bmp = withContext(Dispatchers.IO) { decodeImageUri(context, uri) }
                        bmp?.let { vm.runOcr(it, appContext) }
                    }
                }
            },
        )
    }

    val voiceLanguageTag by vm.voiceLanguageTag.collectAsStateWithLifecycle()
    val voiceLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val matches = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val transcript = matches?.firstOrNull()
            if (transcript.isNullOrBlank()) {
                vm.reportVoiceFailure(
                    "Couldn't catch what you said. Try again, or type the expense manually."
                )
            } else {
                vm.runVoiceInput(transcript, appContext)
            }
        } else {
            // RESULT_CANCELED — most commonly: no internet AND no offline
            // language pack installed for the chosen locale. Either way the
            // recognizer bailed before producing a transcript.
            vm.reportVoiceFailure(
                "Speech recognition didn't run. Check your internet, or install the " +
                    "offline pack for your selected language under " +
                    "Settings → System → Languages → Speech recognition."
            )
        }
    }

    LaunchedEffect(canonicalMembers) {
        vm.seedDefaultsIfEmpty(canonicalMembers)
    }
    LaunchedEffect(state.saved) {
        if (state.saved) onSaved()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (vm.isEditing) R.string.expense_edit_title
                            else R.string.expense_title
                        )
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { inner ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(
                    onClick = { showScanSheet = true },
                    label = { Text(if (state.ocrLoading) "Working…" else "Scan receipt") },
                    leadingIcon = {
                        Icon(Icons.Filled.DocumentScanner, contentDescription = null)
                    },
                    enabled = !state.ocrLoading,
                )
                AssistChip(
                    onClick = {
                        val tag = voiceLanguageTag
                            ?: java.util.Locale.getDefault().toLanguageTag()
                        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                            putExtra(
                                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                            )
                            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                            // Use the device's default locale so Spanish/Portuguese/
                            // etc. devices get their language model instead of English.
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, tag)
                            putExtra(
                                RecognizerIntent.EXTRA_PROMPT,
                                "Describe the expense",
                            )
                        }
                        runCatching { voiceLauncher.launch(intent) }
                    },
                    label = { Text("Voice") },
                    leadingIcon = { Icon(Icons.Filled.Mic, contentDescription = null) },
                    enabled = !state.ocrLoading,
                )
            }
            if (state.ocrMessage != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    state.ocrMessage!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(12.dp))

            CategoryDropdown(state.category, vm::onCategory)

            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = state.amount,
                onValueChange = vm::onAmount,
                label = { Text(stringResource(R.string.expense_amount)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = state.date,
                onValueChange = vm::onDate,
                label = { Text(stringResource(R.string.expense_date)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = state.note,
                onValueChange = vm::onNote,
                label = { Text(stringResource(R.string.expense_note)) },
                singleLine = false,
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(20.dp))

            // Payment type — optional debit/credit tag stored on the doc.
            Text("Payment type", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = state.paymentType == PaymentType.DEBIT,
                    onClick = {
                        vm.setPaymentType(
                            if (state.paymentType == PaymentType.DEBIT) null else PaymentType.DEBIT
                        )
                    },
                    label = { Text("Debit") },
                )
                FilterChip(
                    selected = state.paymentType == PaymentType.CREDIT,
                    onClick = {
                        vm.setPaymentType(
                            if (state.paymentType == PaymentType.CREDIT) null else PaymentType.CREDIT
                        )
                    },
                    label = { Text("Credit") },
                )
            }

            Spacer(Modifier.height(20.dp))

            // Private switch. When ON the split locks to the viewer alone so
            // the group balance stays unchanged.
            val me by vm.me.collectAsStateWithLifecycle()
            val viewerIdentifier = me?.let { u ->
                if (u.email in canonicalMembers) u.email else u.uid
            }
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                    Text("Private", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Hidden from other members; won't affect group balance.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = state.isPrivate,
                    onCheckedChange = { vm.setPrivate(it, viewerIdentifier, canonicalMembers) },
                )
            }

            Spacer(Modifier.height(20.dp))

            Text(
                stringResource(R.string.expense_split_between),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(4.dp))
            canonicalMembers.forEach { identifier ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = identifier in state.splitAmong,
                        onCheckedChange = { vm.togglePayee(identifier) },
                        enabled = !state.isPrivate,
                    )
                    Text(
                        resolveDisplayName(identifier, directory),
                        color = if (state.isPrivate) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            if (state.error != null) {
                Spacer(Modifier.height(8.dp))
                Text(state.error!!, color = MaterialTheme.colorScheme.error)
            }

            Spacer(Modifier.height(20.dp))

            Button(
                onClick = vm::submit,
                enabled = state.canSubmit(group),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.loading) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(18.dp),
                    )
                } else {
                    Text(
                        stringResource(
                            if (vm.isEditing) R.string.expense_save_changes
                            else R.string.expense_save
                        )
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryDropdown(
    selected: String,
    onSelected: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = selected,
            onValueChange = { /* read-only */ },
            readOnly = true,
            label = { Text(stringResource(R.string.expense_category)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            Categories.ALL.forEach { cat ->
                DropdownMenuItem(
                    text = { Text(cat) },
                    onClick = {
                        onSelected(cat)
                        expanded = false
                    },
                )
            }
        }
    }
}
