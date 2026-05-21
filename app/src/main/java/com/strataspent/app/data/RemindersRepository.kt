package com.strataspent.app.data

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.strataspent.app.data.model.Reminder
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

/**
 * CRUD for the `groups/{groupId}/reminders` subcollection used by the web
 * app for due-date reminders (rent, utility bills, etc.).
 *
 * The Firestore rules enforce single-field updates for the partial-edit cases
 * (mark complete; complete + reschedule), so check_completed and snoozeDueDate
 * are split into dedicated methods rather than one fat update.
 */
class RemindersRepository(private val firestore: FirebaseFirestore) {

    private fun ref(groupId: String) =
        firestore.collection("groups").document(groupId).collection("reminders")

    fun reminders(groupId: String): Flow<List<Reminder>> = callbackFlow {
        val reg = ref(groupId)
            .orderBy("dueDate", Query.Direction.ASCENDING)
            .addSnapshotListener { snap, err ->
                if (err != null) { close(err); return@addSnapshotListener }
                trySend(snap?.documents?.mapNotNull { it.toReminder() } ?: emptyList())
            }
        awaitClose { reg.remove() }
    }

    fun reminder(groupId: String, id: String): Flow<Reminder?> = callbackFlow {
        val reg = ref(groupId).document(id).addSnapshotListener { snap, err ->
            if (err != null) { close(err); return@addSnapshotListener }
            trySend(snap?.toReminder())
        }
        awaitClose { reg.remove() }
    }

    suspend fun addReminder(
        groupId: String,
        title: String,
        dueDate: String,
        creatorUid: String,
        creatorName: String,
        amount: Double? = null,
        frequency: String? = null,
        category: String? = null,
        paymentType: String? = null,
    ) {
        val data = buildMap<String, Any?> {
            put("title", title)
            put("dueDate", dueDate)
            put("creatorUid", creatorUid)
            put("creatorName", creatorName)
            put("isCompleted", false)
            put("createdAt", FieldValue.serverTimestamp())
            amount?.let { put("amount", it) }
            frequency?.let { put("frequency", it) }
            category?.let { put("category", it) }
            paymentType?.let { put("paymentType", it) }
        }
        val task = ref(groupId).add(data)
        withTimeoutOrNull(3.seconds) { task.await() }
    }

    suspend fun updateReminder(
        groupId: String,
        reminderId: String,
        title: String,
        dueDate: String,
        creatorUid: String,
        creatorName: String,
        isCompleted: Boolean,
        amount: Double? = null,
        frequency: String? = null,
        category: String? = null,
        paymentType: String? = null,
    ) {
        val data = buildMap<String, Any?> {
            put("title", title)
            put("dueDate", dueDate)
            put("creatorUid", creatorUid)
            put("creatorName", creatorName)
            put("isCompleted", isCompleted)
            put("createdAt", FieldValue.serverTimestamp())
            amount?.let { put("amount", it) }
            frequency?.let { put("frequency", it) }
            category?.let { put("category", it) }
            paymentType?.let { put("paymentType", it) }
        }
        val task = ref(groupId).document(reminderId).update(data)
        withTimeoutOrNull(3.seconds) { task.await() }
    }

    /** Single-field update — the rules allow this for any group member. */
    suspend fun setCompleted(groupId: String, reminderId: String, completed: Boolean) {
        val task = ref(groupId).document(reminderId).update("isCompleted", completed)
        withTimeoutOrNull(3.seconds) { task.await() }
    }
}

internal fun com.google.firebase.firestore.DocumentSnapshot.toReminder(): Reminder? {
    if (!exists()) return null
    return Reminder(
        id = id,
        title = getString("title").orEmpty(),
        amount = getDouble("amount"),
        dueDate = getString("dueDate").orEmpty(),
        creatorUid = getString("creatorUid").orEmpty(),
        creatorName = getString("creatorName").orEmpty(),
        isCompleted = getBoolean("isCompleted") ?: false,
        frequency = getString("frequency"),
        syncToExpenditure = getBoolean("syncToExpenditure"),
        category = getString("category"),
        paymentType = getString("paymentType"),
        createdAt = getTimestamp("createdAt"),
    )
}
