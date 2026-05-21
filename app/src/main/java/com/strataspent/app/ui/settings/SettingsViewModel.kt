package com.strataspent.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.strataspent.app.data.AuthRepository
import com.strataspent.app.data.ExpenseRepository
import com.strataspent.app.data.GroupRepository
import com.strataspent.app.data.LanguagePreference
import com.strataspent.app.data.RemindersRepository
import com.strataspent.app.data.UserDirectoryRepository
import com.strataspent.app.data.computeBalances
import com.strataspent.app.data.model.UserProfile
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SettingsViewModel(
    authRepo: AuthRepository,
    private val groupRepo: GroupRepository,
    private val expenseRepo: ExpenseRepository,
    private val remindersRepo: RemindersRepository,
    private val userDirectory: UserDirectoryRepository,
    private val languagePref: LanguagePreference,
) : ViewModel() {

    val user: StateFlow<UserProfile?> = authRepo.currentUser
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val voiceLanguageTag: StateFlow<String?> = languagePref.voiceLanguageTag
    val currencyCode: StateFlow<String?> = languagePref.currencyCode

    val languageOptions: List<Pair<String?, String>> = LanguagePreference.OPTIONS
    val currencyOptions: List<Pair<String?, String>> = LanguagePreference.CURRENCY_OPTIONS

    fun setVoiceLanguageTag(tag: String?) = languagePref.setVoiceLanguageTag(tag)
    fun setCurrencyCode(code: String?) = languagePref.setCurrencyCode(code)

    /**
     * Collect every group the viewer is in, plus its expenditures and
     * reminders, and emit a human-readable plain-text export. Used by the
     * settings screen to write to a user-chosen file via SAF.
     */
    suspend fun formatDataDump(): String {
        val viewer = user.first() ?: return "No user signed in."
        userDirectory.prefetch(listOf(viewer.uid))
        val groups = groupRepo.groupsFor(viewer.uid, viewer.email).first()

        val sb = StringBuilder()
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
        sb.appendLine("StrataSpent Data Export")
        sb.appendLine("Generated: $stamp")
        sb.appendLine("User: ${viewer.displayName.ifBlank { "(no name)" }} <${viewer.email}>")
        sb.appendLine("==================================================")
        sb.appendLine()

        for (g in groups) {
            val exps = expenseRepo.expenditures(g.id).first()
            val balances = computeBalances(g, exps, viewer, userDirectory.directory.value)

            sb.appendLine("GROUP: ${g.name}")
            sb.appendLine("  ID: ${g.id}")
            sb.appendLine("  Members: ${g.members.joinToString(", ")}")
            sb.appendLine("  Created: ${g.createdAt?.toDate()?.let { SimpleDateFormat("yyyy-MM-dd", Locale.US).format(it) } ?: "?"}")
            sb.appendLine()
            sb.appendLine("  Balances:")
            balances.forEach { b ->
                sb.appendLine("    ${b.displayLabel}: ${"%+.2f".format(b.net)}")
            }
            sb.appendLine()
            sb.appendLine("  Expenditures (${exps.size}):")
            exps.forEach { e ->
                val viz = e.visibility?.let { " [$it]" }.orEmpty()
                val note = e.note.takeIf { it.isNotBlank() }?.let { " — $it" }.orEmpty()
                sb.appendLine(
                    "    ${e.date}  ${e.category.padEnd(20)}  " +
                        "${"%10.2f".format(e.amount)}  by ${e.contributorName}$viz$note"
                )
            }
            val reminders = remindersRepo.reminders(g.id).first()
            if (reminders.isNotEmpty()) {
                sb.appendLine()
                sb.appendLine("  Reminders (${reminders.size}):")
                reminders.forEach { r ->
                    val done = if (r.isCompleted) "✓" else "·"
                    val amt = r.amount?.let { " ${"%.2f".format(it)}" }.orEmpty()
                    sb.appendLine("    $done  ${r.dueDate}  ${r.title}$amt  by ${r.creatorName}")
                }
            }
            sb.appendLine()
            sb.appendLine("--------------------------------------------------")
            sb.appendLine()
        }
        return sb.toString()
    }
}
