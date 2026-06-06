package com.strataspent.app.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.strataspent.app.data.GemmaExpenseExtractor
import com.strataspent.app.data.GemmaModelConfig
import com.strataspent.app.data.GemmaModelManager
import com.strataspent.app.data.GemmaPreferences
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Consent + download/import manager for the optional on-device Gemma model.
 * Native counterpart of the training app's `GemmaModelScreen` state
 * (`lib/screens/gemma_model_screen.dart`). Lets the user enable the feature,
 * pick a preset, accept the Gemma license + large-download notice, supply a
 * Hugging Face token (when the build embeds none), download the weights with a
 * progress bar, import an existing bundle, or delete the weights.
 */
class GemmaModelViewModel(
    private val prefs: GemmaPreferences,
    private val manager: GemmaModelManager,
    private val extractor: GemmaExpenseExtractor,
    private val embeddedToken: String,
) : ViewModel() {

    data class UiState(
        val enabled: Boolean = false,
        val modelKey: String = GemmaModelConfig.DEFAULT.key,
        val installed: Boolean = false,
        val consented: Boolean = false,
        val hasEmbeddedToken: Boolean = false,
        /** The token the user is editing (separate from persisted value). */
        val tokenInput: String = "",
        /** null = idle, -1f = indeterminate, 0f..1f = downloading/importing. */
        val progress: Float? = null,
        val busy: Boolean = false,
        val message: String? = null,
    )

    val presets: List<GemmaModelConfig> = GemmaModelConfig.PRESETS

    private val _ui = MutableStateFlow(snapshot())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private var job: Job? = null

    private fun snapshot(preserve: UiState? = null): UiState = UiState(
        enabled = prefs.enabled.value,
        modelKey = prefs.modelKey.value,
        installed = manager.isInstalled(prefs.currentConfig()),
        consented = prefs.hasConsented(),
        hasEmbeddedToken = embeddedToken.isNotBlank(),
        tokenInput = preserve?.tokenInput ?: (prefs.userHfToken.value ?: ""),
        progress = preserve?.progress,
        busy = preserve?.busy ?: false,
        message = preserve?.message,
    )

    private fun refresh() = _ui.update { snapshot(preserve = it) }

    fun config(): GemmaModelConfig = prefs.currentConfig()

    fun setEnabled(value: Boolean) {
        prefs.setEnabled(value)
        refresh()
    }

    fun selectModel(key: String) {
        if (_ui.value.busy || key == prefs.modelKey.value) return
        prefs.setModelKey(key)
        extractor.close() // model changed — drop the cached context
        refresh()
    }

    fun onTokenInput(value: String) = _ui.update { it.copy(tokenInput = value) }

    fun markConsented(nowMs: Long) {
        prefs.markConsented(nowMs)
        refresh()
    }

    fun clearMessage() = _ui.update { it.copy(message = null) }

    /** Effective token: the user-entered one, else the build-time one. */
    private fun effectiveToken(): String? =
        _ui.value.tokenInput.trim().ifBlank { embeddedToken }.takeIf { it.isNotBlank() }

    fun download() {
        if (_ui.value.busy) return
        val token = effectiveToken()
        if (token == null) {
            _ui.update { it.copy(message = "Add a Hugging Face token first — the Gemma weights are gated.") }
            return
        }
        // Persist the user-entered token for later runs.
        _ui.value.tokenInput.trim().takeIf { it.isNotEmpty() }?.let { prefs.setUserHfToken(it) }

        val config = prefs.currentConfig()
        _ui.update { it.copy(busy = true, progress = 0f, message = null) }
        job = viewModelScope.launch {
            val outcome = manager.download(config, token) { f ->
                _ui.update { it.copy(progress = f) }
            }
            applyOutcome(outcome, successMsg = "Downloaded ${config.label}. Offline AI is ready.")
        }
    }

    fun importFrom(uri: Uri, totalBytes: Long) {
        if (_ui.value.busy) return
        val config = prefs.currentConfig()
        _ui.update { it.copy(busy = true, progress = if (totalBytes > 0) 0f else -1f, message = null) }
        job = viewModelScope.launch {
            val outcome = manager.importFromUri(config, uri, totalBytes) { f ->
                _ui.update { it.copy(progress = f) }
            }
            applyOutcome(outcome, successMsg = "Imported ${config.label}. Offline AI is ready.")
        }
    }

    private fun applyOutcome(outcome: GemmaModelManager.Outcome, successMsg: String) {
        val msg = when (outcome) {
            is GemmaModelManager.Outcome.Success -> successMsg
            is GemmaModelManager.Outcome.Failure -> outcome.message
        }
        _ui.update { snapshot(preserve = it).copy(busy = false, progress = null, message = msg) }
    }

    fun cancel() {
        job?.cancel()
        job = null
        _ui.update { snapshot(preserve = it).copy(busy = false, progress = null, message = "Canceled.") }
    }

    fun deleteModel() {
        if (_ui.value.busy) return
        val config = prefs.currentConfig()
        extractor.close()
        manager.delete(config)
        _ui.update { snapshot(preserve = it).copy(message = "Deleted ${config.label}.") }
    }
}
