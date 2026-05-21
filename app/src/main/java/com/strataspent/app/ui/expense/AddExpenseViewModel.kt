package com.strataspent.app.ui.expense

import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.content.Context
import com.strataspent.app.data.AuthRepository
import com.strataspent.app.data.ExpenseRepository
import com.strataspent.app.data.GroupRepository
import com.strataspent.app.data.LanguagePreference
import com.strataspent.app.data.OcrProcessingWorker
import com.strataspent.app.data.OcrRepository
import com.strataspent.app.data.PendingOcrRepository
import com.strataspent.app.data.UserDirectoryRepository
import com.strataspent.app.data.canonicalDedupedMembers
import com.strataspent.app.data.todayIso
import com.strataspent.app.data.model.Categories
import com.strataspent.app.data.model.Group
import com.strataspent.app.data.model.UserProfile
import com.strataspent.app.data.model.Visibility
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AddExpenseUi(
    val category: String = Categories.OTHER,
    val amount: String = "",
    val date: String = todayIso(),
    val note: String = "",
    /** Identifiers (uid or email) to split this expense across. */
    val splitAmong: Set<String> = emptySet(),
    /** When true, the expense is hidden from other members (visibility=private)
     *  and split solely with the contributor so the group balance is unaffected. */
    val isPrivate: Boolean = false,
    /** Optional `paymentType` field: null, "debit", or "credit". */
    val paymentType: String? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val saved: Boolean = false,
    val ocrLoading: Boolean = false,
    val ocrMessage: String? = null,
) {
    fun canSubmit(group: Group?): Boolean {
        if (loading || saved || group == null) return false
        val a = amount.toDoubleOrNull() ?: return false
        return a > 0 && date.isNotBlank() && category.isNotBlank() &&
                splitAmong.isNotEmpty()
    }
}

/**
 * Handles both *add* and *edit* of a single expenditure. When [expenseId]
 * is non-null the view-model loads the existing doc and treats `submit` as
 * an update; otherwise it creates a new one.
 *
 * Note: the web schema's Firestore rules require `contributorUid == auth.uid`
 * on every write, so we always set the contributor to the current user — there
 * is no "paid by someone else" picker. To record an inter-member payback, use
 * the Settle Up category (future work).
 */
class AddExpenseViewModel(
    val groupId: String,
    val expenseId: String?,
    authRepo: AuthRepository,
    groupRepo: GroupRepository,
    private val expenseRepo: ExpenseRepository,
    private val userDirectory: UserDirectoryRepository,
    private val ocrRepo: OcrRepository,
    languagePref: LanguagePreference,
    private val pendingOcr: PendingOcrRepository,
) : ViewModel() {

    val voiceLanguageTag: StateFlow<String?> = languagePref.voiceLanguageTag

    val isEditing: Boolean = expenseId != null

    val me: StateFlow<UserProfile?> = authRepo.currentUser
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val group: StateFlow<Group?> = groupRepo.group(groupId)
        .onEach { g -> if (g != null) viewModelScope.launch { userDirectory.prefetch(g.members) } }
        .catch { t ->
            Log.w("AddExpenseVM", "group flow error", t)
            emit(null)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val directory: StateFlow<Map<String, UserProfile>> = userDirectory.directory

    /** Deduplicated member identifiers — used for the split checkboxes so
     *  each person appears once (viewer's pair AND any other known pair). */
    val canonicalMembers: StateFlow<List<String>> = combine(group, me, directory) { g, viewer, dir ->
        if (g == null) emptyList() else canonicalDedupedMembers(g.members, viewer, dir)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _state = MutableStateFlow(AddExpenseUi())
    val state: StateFlow<AddExpenseUi> = _state.asStateFlow()

    init {
        if (expenseId != null) loadExisting(expenseId)
    }

    private fun loadExisting(id: String) {
        viewModelScope.launch {
            expenseRepo.expenditure(groupId, id).first()?.let { e ->
                _state.update {
                    it.copy(
                        category = e.category,
                        amount = formatAmountForEdit(e.amount),
                        date = e.date,
                        note = e.note,
                        splitAmong = e.splits.keys,
                        isPrivate = e.visibility == Visibility.PRIVATE,
                        paymentType = e.paymentType,
                    )
                }
            }
        }
    }

    fun onCategory(v: String) = _state.update { it.copy(category = v) }
    fun onAmount(v: String) = _state.update {
        it.copy(amount = v.filter { c -> c.isDigit() || c == '.' })
    }
    fun onDate(v: String) = _state.update { it.copy(date = v) }
    fun onNote(v: String) = _state.update { it.copy(note = v) }
    fun togglePayee(identifier: String) = _state.update {
        val next = if (identifier in it.splitAmong) it.splitAmong - identifier
        else it.splitAmong + identifier
        it.copy(splitAmong = next)
    }

    fun setPaymentType(type: String?) = _state.update { it.copy(paymentType = type) }

    /**
     * Flip private mode. When turned ON we lock the split to *just the viewer*
     * so the expense doesn't move group balances: contributor = me, splits = me,
     * net change = 0 for everyone else. Restoring public mode reverts the split
     * to the full member list.
     */
    fun setPrivate(value: Boolean, viewerIdentifier: String?, allMembers: List<String>) {
        _state.update { s ->
            val newSplit = when {
                value && viewerIdentifier != null -> setOf(viewerIdentifier)
                !value -> allMembers.toSet()
                else -> s.splitAmong
            }
            s.copy(isPrivate = value, splitAmong = newSplit)
        }
    }

    /** Seed defaults only when adding; for edits we trust the loaded doc. */
    fun seedDefaultsIfEmpty(canonicalMemberIds: List<String>) {
        if (isEditing || canonicalMemberIds.isEmpty()) return
        _state.update { s ->
            if (s.splitAmong.isEmpty()) s.copy(splitAmong = canonicalMemberIds.toSet()) else s
        }
    }

    fun runOcr(image: Bitmap, appContext: Context) {
        _state.update { it.copy(ocrLoading = true, ocrMessage = null) }
        viewModelScope.launch {
            val result = ocrRepo.extractReceipt(image)
            if (result is OcrRepository.OcrResult.Failure) {
                // Likely a network/Gemini failure — queue for background retry.
                pendingOcr.enqueueImage(groupId, image)
                OcrProcessingWorker.schedule(appContext)
                _state.update {
                    it.copy(
                        ocrLoading = false,
                        ocrMessage = "Offline or Gemini unreachable — receipt queued. " +
                            "It'll auto-create a private expense when you're online.",
                    )
                }
            } else {
                applyAiResult(result, source = "Receipt scanned")
            }
        }
    }

    /** Parse a voice transcript into structured fields via Gemini. */
    fun runVoiceInput(transcript: String, appContext: Context) {
        if (transcript.isBlank()) return
        _state.update { it.copy(ocrLoading = true, ocrMessage = "Heard: \"$transcript\"") }
        viewModelScope.launch {
            val result = ocrRepo.transcribeToExpense(transcript, todayIso())
            if (result is OcrRepository.OcrResult.Failure) {
                pendingOcr.enqueueVoice(groupId, transcript)
                OcrProcessingWorker.schedule(appContext)
                _state.update {
                    it.copy(
                        ocrLoading = false,
                        ocrMessage = "Offline or Gemini unreachable — voice note queued. " +
                            "It'll auto-create a private expense when you're online.",
                    )
                }
            } else {
                applyAiResult(result, source = "Heard: \"$transcript\"")
            }
        }
    }

    private fun applyAiResult(result: OcrRepository.OcrResult, source: String) {
        _state.update { current ->
            when (result) {
                is OcrRepository.OcrResult.NotConfigured -> current.copy(
                    ocrLoading = false,
                    ocrMessage = "Set geminiApiKey in local.properties to enable AI input.",
                )
                is OcrRepository.OcrResult.Failure -> current.copy(
                    ocrLoading = false,
                    ocrMessage = "Parse failed: ${result.message}",
                )
                is OcrRepository.OcrResult.Success -> current.copy(
                    ocrLoading = false,
                    ocrMessage = "$source — review the fields before saving.",
                    category = result.category ?: current.category,
                    amount = result.amount?.let { formatAmountForEdit(it) } ?: current.amount,
                    date = result.date ?: current.date,
                    note = result.note ?: current.note,
                )
            }
        }
    }

    fun clearOcrMessage() = _state.update { it.copy(ocrMessage = null) }

    /** Surface a recognizer-level failure (no internet + no on-device pack,
     *  user-cancelled, no resolving activity, etc.). */
    fun reportVoiceFailure(message: String) {
        _state.update { it.copy(ocrLoading = false, ocrMessage = message) }
    }

    fun submit() {
        val s = _state.value
        val g = group.value
        if (!s.canSubmit(g)) return
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            runCatching {
                val u = me.first() ?: error("Not signed in")
                val contributorName = u.displayName.ifBlank { u.email.ifBlank { u.uid.take(8) } }
                val visibility = if (s.isPrivate) Visibility.PRIVATE else Visibility.PUBLIC
                if (expenseId == null) {
                    expenseRepo.addExpenditure(
                        groupId = groupId,
                        category = s.category,
                        amount = s.amount.toDouble(),
                        date = s.date,
                        contributorUid = u.uid,
                        contributorName = contributorName,
                        note = s.note,
                        splitAmong = s.splitAmong.toList(),
                        visibility = visibility,
                        paymentType = s.paymentType,
                    )
                } else {
                    expenseRepo.updateExpenditure(
                        groupId = groupId,
                        expenseId = expenseId,
                        category = s.category,
                        amount = s.amount.toDouble(),
                        date = s.date,
                        contributorUid = u.uid,
                        contributorName = contributorName,
                        note = s.note,
                        splitAmong = s.splitAmong.toList(),
                        visibility = visibility,
                        paymentType = s.paymentType,
                    )
                }
            }.onSuccess {
                _state.update { it.copy(loading = false, saved = true) }
            }.onFailure { t ->
                _state.update { it.copy(loading = false, error = t.message ?: "Save failed") }
            }
        }
    }
}

/** "12.0" → "12", "12.34" → "12.34". */
private fun formatAmountForEdit(amount: Double): String {
    val asLong = amount.toLong()
    return if (amount == asLong.toDouble()) asLong.toString() else amount.toString()
}
