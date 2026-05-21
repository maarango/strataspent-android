package com.strataspent.app.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Simple SharedPreferences-backed setting for the speech-recognition
 * language. `null` means "use the device's default locale" — letting
 * Android pick the appropriate speech model.
 *
 * Stored as an IETF BCP-47 tag (e.g. "es-ES", "en-US") so it plugs
 * directly into RecognizerIntent.EXTRA_LANGUAGE.
 */
class LanguagePreference(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _voiceLanguageTag = MutableStateFlow(prefs.getString(KEY_VOICE_LANG, null))
    val voiceLanguageTag: StateFlow<String?> = _voiceLanguageTag.asStateFlow()

    fun setVoiceLanguageTag(tag: String?) {
        prefs.edit {
            if (tag.isNullOrBlank()) remove(KEY_VOICE_LANG) else putString(KEY_VOICE_LANG, tag)
        }
        _voiceLanguageTag.value = tag?.takeIf { it.isNotBlank() }
    }

    private val _currencyCode = MutableStateFlow(prefs.getString(KEY_CURRENCY, null))
    /** ISO-4217 currency code (USD, EUR, MXN, …). `null` = use device default. */
    val currencyCode: StateFlow<String?> = _currencyCode.asStateFlow()

    fun setCurrencyCode(code: String?) {
        prefs.edit {
            if (code.isNullOrBlank()) remove(KEY_CURRENCY) else putString(KEY_CURRENCY, code)
        }
        _currencyCode.value = code?.takeIf { it.isNotBlank() }
    }

    companion object {
        private const val PREFS_NAME = "strata_prefs"
        private const val KEY_VOICE_LANG = "voice_language_tag"
        private const val KEY_CURRENCY = "currency_code"

        /** (tag, human-readable label). `null` tag means "Use device language". */
        val OPTIONS: List<Pair<String?, String>> = listOf(
            null to "Use device language",
            "en-US" to "English (US)",
            "es-ES" to "Spanish (Spain)",
            "es-419" to "Spanish (Latin America)",
            "es-MX" to "Spanish (Mexico)",
            "pt-BR" to "Portuguese (Brazil)",
            "fr-FR" to "French",
            "de-DE" to "German",
        )

        /** Curated short list of ISO-4217 currencies most likely to be used. */
        val CURRENCY_OPTIONS: List<Pair<String?, String>> = listOf(
            null to "Use device default",
            "USD" to "USD — US Dollar",
            "EUR" to "EUR — Euro",
            "GBP" to "GBP — Pound Sterling",
            "MXN" to "MXN — Mexican Peso",
            "COP" to "COP — Colombian Peso",
            "ARS" to "ARS — Argentine Peso",
            "BRL" to "BRL — Brazilian Real",
            "CAD" to "CAD — Canadian Dollar",
            "AUD" to "AUD — Australian Dollar",
            "JPY" to "JPY — Japanese Yen",
            "CNY" to "CNY — Chinese Yuan",
            "INR" to "INR — Indian Rupee",
            "CHF" to "CHF — Swiss Franc",
            "SEK" to "SEK — Swedish Krona",
        )
    }
}
