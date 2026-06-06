package com.strataspent.app.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persistence for the optional on-device Gemma feature. Parallels the training
 * app's `GemmaStorage` (`lib/data/gemma_storage.dart`): a per-user opt-in flag
 * and consent timestamp, the selected model preset key, and an optional
 * user-supplied Hugging Face download token.
 *
 * The "is the model downloaded" state is NOT stored here — it's derived from the
 * on-disk weights via [GemmaModelManager.isInstalled].
 */
class GemmaPreferences(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, false))
    /** Opt-in master switch for the offline AI fallback. */
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun setEnabled(value: Boolean) {
        prefs.edit { putBoolean(KEY_ENABLED, value) }
        _enabled.value = value
    }

    private val _modelKey = MutableStateFlow(prefs.getString(KEY_MODEL, null) ?: GemmaModelConfig.DEFAULT.key)
    /** Selected preset key (see [GemmaModelConfig.key]). */
    val modelKey: StateFlow<String> = _modelKey.asStateFlow()

    fun setModelKey(key: String) {
        prefs.edit { putString(KEY_MODEL, key) }
        _modelKey.value = key
    }

    /** Resolve the currently-selected config. */
    fun currentConfig(): GemmaModelConfig = GemmaModelConfig.byKey(_modelKey.value)

    private val _userHfToken = MutableStateFlow(prefs.getString(KEY_HF_TOKEN, null))
    /** User-pasted Hugging Face token; falls back to the build-time token. */
    val userHfToken: StateFlow<String?> = _userHfToken.asStateFlow()

    fun setUserHfToken(token: String?) {
        val clean = token?.trim()?.takeIf { it.isNotEmpty() }
        prefs.edit {
            if (clean == null) remove(KEY_HF_TOKEN) else putString(KEY_HF_TOKEN, clean)
        }
        _userHfToken.value = clean
    }

    private val _consentAtMs = MutableStateFlow(prefs.getLong(KEY_CONSENT_AT, 0L).takeIf { it > 0 })
    /** When the user accepted the Gemma license / download notice, or null. */
    val consentAtMs: StateFlow<Long?> = _consentAtMs.asStateFlow()

    fun hasConsented(): Boolean = _consentAtMs.value != null

    fun markConsented(nowMs: Long) {
        prefs.edit { putLong(KEY_CONSENT_AT, nowMs) }
        _consentAtMs.value = nowMs
    }

    companion object {
        private const val PREFS_NAME = "strata_prefs"
        private const val KEY_ENABLED = "gemma_enabled_v1"
        private const val KEY_MODEL = "gemma_model_key"
        private const val KEY_HF_TOKEN = "gemma_hf_token"
        private const val KEY_CONSENT_AT = "gemma_consent_at_ms"
    }
}
