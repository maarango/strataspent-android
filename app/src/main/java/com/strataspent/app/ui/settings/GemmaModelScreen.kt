package com.strataspent.app.ui.settings

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GemmaModelScreen(
    vm: GemmaModelViewModel,
    onBack: () -> Unit,
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var showConsent by remember { mutableStateOf(false) }

    androidx.compose.runtime.LaunchedEffect(ui.message) {
        ui.message?.let { snackbar.showSnackbar(it); vm.clearMessage() }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) vm.importFrom(uri, querySize(context, uri))
    }

    if (showConsent) {
        val cfg = vm.config()
        AlertDialog(
            onDismissRequest = { showConsent = false },
            title = { Text("On-device AI model") },
            text = {
                Text(
                    "This downloads Google's ${cfg.label} model (~${cfg.approxSizeGb} GB) and " +
                        "runs it entirely on your phone — your receipts and notes never leave " +
                        "the device, and no internet is needed once it's downloaded.\n\n" +
                        "The download is large: use Wi-Fi and keep the app open. The weights are " +
                        "provided under Google's Gemma license; by continuing you agree to its terms."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showConsent = false
                    vm.markConsented(System.currentTimeMillis())
                    vm.download()
                }) { Text("I agree") }
            },
            dismissButton = {
                TextButton(onClick = { showConsent = false }) { Text("Cancel") }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Offline AI (Gemma)") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { inner ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Status banner
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        if (ui.installed) "✓ ${vm.config().label} is installed"
                        else "${vm.config().label} not downloaded yet",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "When you're offline, scanning a receipt or dictating an expense will " +
                            "use this model on your device instead of queuing for the cloud. " +
                            "Cloud Gemini is still used whenever you're online.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Enable toggle
            Card(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.padding(16.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Use on-device AI when offline", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Requires the model below to be downloaded.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = ui.enabled, onCheckedChange = vm::setEnabled)
                }
            }

            // Model picker
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Model", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    vm.presets.forEach { preset ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = ui.modelKey == preset.key,
                                    enabled = !ui.busy,
                                    onClick = { vm.selectModel(preset.key) },
                                )
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = ui.modelKey == preset.key,
                                onClick = { vm.selectModel(preset.key) },
                                enabled = !ui.busy,
                            )
                            Spacer(Modifier.height(0.dp))
                            Column(Modifier.padding(start = 8.dp)) {
                                Text(preset.label, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    "~${preset.approxSizeGb} GB · text + image + voice",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            // Hugging Face token (only when the build doesn't embed one)
            if (!ui.hasEmbeddedToken) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Hugging Face token", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "The Gemma weights are gated. Accept the model's license on " +
                                "huggingface.co, create a read token, and paste it here. " +
                                "Not needed if you import a model file you already have.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = ui.tokenInput,
                            onValueChange = vm::onTokenInput,
                            label = { Text("hf_…") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            enabled = !ui.busy,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            // Download / progress
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    if (ui.busy) {
                        val p = ui.progress
                        if (p != null && p >= 0f) {
                            LinearProgressIndicator(
                                progress = { p },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(4.dp))
                            Text("${(p * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                        } else {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Spacer(Modifier.height(4.dp))
                            Text("Working…", style = MaterialTheme.typography.bodySmall)
                        }
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(onClick = vm::cancel, modifier = Modifier.fillMaxWidth()) {
                            Text("Cancel")
                        }
                    } else {
                        Button(
                            onClick = { if (ui.consented) vm.download() else showConsent = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(if (ui.installed) "Re-download" else "Download model") }
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { importLauncher.launch(arrayOf("*/*")) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Import model file…") }
                        if (ui.installed) {
                            Spacer(Modifier.height(8.dp))
                            HorizontalDivider()
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = vm::deleteModel,
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("Delete downloaded model") }
                        }
                    }
                }
            }
        }
    }
}

/** Best-effort file size for a SAF document (drives the import progress bar). */
private fun querySize(context: android.content.Context, uri: Uri): Long {
    return runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.SIZE)
                if (idx >= 0 && c.moveToFirst()) c.getLong(idx) else -1L
            } ?: -1L
    }.getOrDefault(-1L)
}
