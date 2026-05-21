package com.strataspent.app.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.strataspent.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    vm: SettingsViewModel,
    onBack: () -> Unit,
) {
    val user by vm.user.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val snackbarHost = remember { SnackbarHostState() }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        coroutineScope.launch {
            runCatching {
                val text = vm.formatDataDump()
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(text.toByteArray(Charsets.UTF_8))
                    }
                }
            }.onSuccess { snackbarHost.showSnackbar("Data exported.") }
                .onFailure { snackbarHost.showSnackbar("Export failed: ${it.message}") }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHost) },
    ) { inner ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Account header
            user?.let {
                Card(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.padding(16.dp).fillMaxWidth(),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(Icons.Filled.Person, contentDescription = null)
                        Column(Modifier.weight(1f)) {
                            Text(
                                it.displayName.ifBlank { "(no name)" },
                                style = MaterialTheme.typography.titleMedium,
                            )
                            if (it.email.isNotBlank()) {
                                Text(
                                    it.email,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            SectionTitle("About")
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(Icons.Filled.Info, contentDescription = null)
                        Text("What is StrataSpent?", style = MaterialTheme.typography.titleSmall)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Track shared family expenses across multiple devices. " +
                            "Groups, balances, splits, receipt OCR powered by Gemini, " +
                            "reminders with push notifications, and per-user financial flow.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(12.dp))
                    LabelValue("Version", "${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})")
                    LabelValue("Package", BuildConfig.APPLICATION_ID)
                    LabelValue("Creator", "Built with Claude Code")
                }
            }

            SectionTitle("Display")
            val currency by vm.currencyCode.collectAsStateWithLifecycle()
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(Icons.Filled.AttachMoney, contentDescription = null)
                        Text("Currency", style = MaterialTheme.typography.titleSmall)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Used everywhere we display money — balances, totals, " +
                            "expense rows, analytics, and the bill splitter. The " +
                            "underlying numbers are unchanged; this only changes formatting.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    GenericDropdown(
                        label = "Currency",
                        selected = currency,
                        options = vm.currencyOptions,
                        onSelect = vm::setCurrencyCode,
                    )
                }
            }

            SectionTitle("Voice input")
            val voiceTag by vm.voiceLanguageTag.collectAsStateWithLifecycle()
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(Icons.Filled.Language, contentDescription = null)
                        Text(
                            "Speech recognition language",
                            style = MaterialTheme.typography.titleSmall,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Used by the Voice chip on the Add expenditure screen. " +
                            "Defaults to the device's language; pick a specific one " +
                            "if the system picks the wrong model for you.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    LanguageDropdown(
                        selected = voiceTag,
                        options = vm.languageOptions,
                        onSelect = vm::setVoiceLanguageTag,
                    )

                    Spacer(Modifier.height(16.dp))
                    Text(
                        "Working offline",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Voice needs EITHER internet OR an on-device speech pack " +
                            "for the locale being used. Two ways to get offline working:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Option A — keep the in-app dropdown above set to a specific " +
                            "language (e.g. Spanish (Spain)) and download the matching " +
                            "pack via the system settings button below. The pack name " +
                            "in Android must match the variant you picked here EXACTLY " +
                            "(es-ES vs es-MX are different downloads).",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Option B (often easier) — leave the in-app dropdown on " +
                            "\"Use device language\" and switch your phone's whole " +
                            "system language to Spanish under Android Settings → " +
                            "System → Languages. Android will then automatically use " +
                            "whichever Spanish speech pack you've installed, no " +
                            "variant-matching headaches.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = {
                            val intent = android.content.Intent(
                                android.provider.Settings.ACTION_VOICE_INPUT_SETTINGS,
                            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            runCatching { context.startActivity(intent) }.onFailure {
                                coroutineScope.launch {
                                    snackbarHost.showSnackbar(
                                        "Couldn't open Voice Input settings on this device."
                                    )
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Open system Voice Input settings (Option A)") }
                    Spacer(Modifier.height(4.dp))
                    OutlinedButton(
                        onClick = {
                            val intent = android.content.Intent(
                                android.provider.Settings.ACTION_LOCALE_SETTINGS,
                            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            runCatching { context.startActivity(intent) }.onFailure {
                                coroutineScope.launch {
                                    snackbarHost.showSnackbar(
                                        "Couldn't open Language settings on this device."
                                    )
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Open system Language settings (Option B)") }
                }
            }

            SectionTitle("Maintenance")
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(Icons.Filled.SystemUpdate, contentDescription = null)
                        Text("Updates", style = MaterialTheme.typography.titleSmall)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "You're on the latest build pushed from your dev machine. " +
                            "Production update channel isn't wired up yet — install new " +
                            "debug builds via `gradlew installDebug`.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            coroutineScope.launch {
                                snackbarHost.showSnackbar("You're on version ${BuildConfig.VERSION_NAME}.")
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Check for updates") }
                }
            }

            SectionTitle("Export")
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(Icons.Filled.Download, contentDescription = null)
                        Text("Download data as .txt", style = MaterialTheme.typography.titleSmall)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Generates a plain-text dump of every group you belong to: " +
                            "members, balances, all expenditures, and reminders. " +
                            "You choose where to save the file.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            val ts = System.currentTimeMillis()
                            exportLauncher.launch("strataspent_$ts.txt")
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Download .txt") }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp),
    )
}

@Composable
private fun LabelValue(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanguageDropdown(
    selected: String?,
    options: List<Pair<String?, String>>,
    onSelect: (String?) -> Unit,
) = GenericDropdown(label = "Language", selected = selected, options = options, onSelect = onSelect)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GenericDropdown(
    label: String,
    selected: String?,
    options: List<Pair<String?, String>>,
    onSelect: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val currentLabel = options.firstOrNull { it.first == selected }?.second
        ?: options.first().second
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = currentLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { (tag, optLabel) ->
                DropdownMenuItem(
                    text = { Text(optLabel) },
                    onClick = {
                        onSelect(tag)
                        expanded = false
                    },
                )
            }
        }
    }
}
