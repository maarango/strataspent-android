package com.strataspent.app.ui

import androidx.compose.runtime.staticCompositionLocalOf
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

/**
 * Currency code in effect for the running session. `null` means "use the
 * device's default locale's currency". Supplied near the root of the
 * navigation graph from the user's preference.
 */
val LocalCurrencyCode = staticCompositionLocalOf<String?> { null }

/**
 * Format a monetary value using the supplied ISO-4217 [currencyCode]. Falls
 * back to the device's default locale currency when [currencyCode] is null
 * or unknown.
 */
fun formatMoney(value: Double, currencyCode: String? = null): String {
    val fmt = NumberFormat.getCurrencyInstance(Locale.getDefault())
    if (!currencyCode.isNullOrBlank()) {
        runCatching { fmt.currency = Currency.getInstance(currencyCode) }
    }
    return fmt.format(value)
}
