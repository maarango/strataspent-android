package com.strataspent.app.data

import android.content.Context
import android.provider.Telephony
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Read-only access to the phone's SMS inbox (the default messaging app's
 * store, via the [Telephony.Sms.Inbox] content provider) so the user can pick
 * a bank / card notification and turn it into an expense. Requires the
 * READ_SMS runtime permission; callers request it before calling [recent].
 *
 * Nothing is uploaded or stored from here — only the single message the user
 * taps is passed on to the AI extractor.
 */
object SmsInbox {

    private const val TAG = "SmsInbox"

    data class Message(
        val id: Long,
        val sender: String,
        val body: String,
        val receivedAtMillis: Long,
    )

    /**
     * Words that show up in transaction notifications across the languages the
     * app supports. Matched case-insensitively together with a number, so a
     * plain chat message ("see you at 5") doesn't qualify.
     */
    private val TRANSACTION_KEYWORDS = listOf(
        // English
        "purchase", "spent", "debit", "debited", "credit", "charged", "payment",
        "paid", "transaction", "withdraw", "transfer", "card", "acct", "account",
        // Spanish
        "compra", "pago", "cargo", "débito", "debito", "crédito", "credito",
        "transacción", "transaccion", "retiro", "transferencia", "tarjeta", "cuenta",
        // Portuguese
        "pagamento", "cartão", "cartao", "saque", "conta",
        // French
        "achat", "paiement", "carte", "retrait", "virement",
    )

    private val AMOUNT_REGEX = Regex("""\d+(?:[.,]\d+)?""")

    /** Heuristic: does this text look like a money-movement notification? */
    fun looksLikeTransaction(body: String): Boolean {
        val lower = body.lowercase()
        return AMOUNT_REGEX.containsMatchIn(lower) &&
            TRANSACTION_KEYWORDS.any { it in lower }
    }

    /** Most recent inbox messages, newest first. Empty on any provider error. */
    suspend fun recent(context: Context, limit: Int = 200): List<Message> =
        withContext(Dispatchers.IO) {
            val projection = arrayOf(
                Telephony.Sms._ID,
                Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY,
                Telephony.Sms.DATE,
            )
            runCatching {
                context.contentResolver.query(
                    Telephony.Sms.Inbox.CONTENT_URI,
                    projection,
                    null,
                    null,
                    "${Telephony.Sms.DATE} DESC",
                )?.use { c ->
                    val idCol = c.getColumnIndexOrThrow(Telephony.Sms._ID)
                    val addrCol = c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                    val bodyCol = c.getColumnIndexOrThrow(Telephony.Sms.BODY)
                    val dateCol = c.getColumnIndexOrThrow(Telephony.Sms.DATE)
                    buildList {
                        while (c.moveToNext() && size < limit) {
                            val body = c.getString(bodyCol).orEmpty()
                            if (body.isBlank()) continue
                            add(
                                Message(
                                    id = c.getLong(idCol),
                                    sender = c.getString(addrCol).orEmpty(),
                                    body = body,
                                    receivedAtMillis = c.getLong(dateCol),
                                )
                            )
                        }
                    }
                }.orEmpty()
            }.getOrElse { t ->
                Log.w(TAG, "Reading SMS inbox failed", t)
                emptyList()
            }
        }
}
