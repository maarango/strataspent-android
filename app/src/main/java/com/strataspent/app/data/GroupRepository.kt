package com.strataspent.app.data

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.strataspent.app.data.model.Group
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

data class CreateGroupResult(val groupId: String)

class GroupRepository(private val firestore: FirebaseFirestore) {

    private val groups = firestore.collection("groups")

    /**
     * Live list of groups the user belongs to. The `members` array contains
     * a mix of uids AND emails, so we match either via `array-contains-any`
     * with both of the current user's identifiers.
     */
    fun groupsFor(uid: String, email: String): Flow<List<Group>> = callbackFlow {
        val identifiers = listOfNotNull(
            uid.takeIf { it.isNotBlank() },
            email.takeIf { it.isNotBlank() },
        )
        if (identifiers.isEmpty()) {
            trySend(emptyList())
            awaitClose { }
            return@callbackFlow
        }
        val reg = groups
            .whereArrayContainsAny("members", identifiers)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    close(err); return@addSnapshotListener
                }
                trySend(snap?.documents?.mapNotNull { it.toGroup() } ?: emptyList())
            }
        awaitClose { reg.remove() }
    }

    fun group(groupId: String): Flow<Group?> = callbackFlow {
        val reg = groups.document(groupId).addSnapshotListener { snap, err ->
            if (err != null) {
                close(err); return@addSnapshotListener
            }
            trySend(snap?.toGroup())
        }
        awaitClose { reg.remove() }
    }

    /**
     * Create a new group. Pushes both the owner's uid AND email into the
     * members array (the web app does the same, and rules accept either for
     * the `isMember` check, so this keeps the row visible whether the user
     * later signs in fresh or has an existing session).
     *
     * Invited emails are added as-is — no resolution step is needed because
     * the rules accept email strings directly. When the invited person signs
     * in, the rules' `auth.token.email in members` check matches.
     */
    suspend fun createGroup(
        name: String,
        ownerUid: String,
        ownerEmail: String,
        invitedEmails: List<String>,
    ): CreateGroupResult {
        val members = (listOf(ownerUid, ownerEmail) + invitedEmails)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

        val doc = groups.document()
        doc.set(
            mapOf(
                "name" to name,
                "members" to members,
                "createdBy" to ownerUid,
                "createdAt" to FieldValue.serverTimestamp(),
                "isStarred" to false,
            )
        ).await()
        return CreateGroupResult(doc.id)
    }

    /**
     * Add identifiers (uids or emails) to an existing group's `members`
     * array. The web app's Firestore rules require single-field updates on
     * the group doc (`affectedKeys().hasOnly(['members'])`), so this writes
     * ONLY that field via `arrayUnion`.
     */
    suspend fun addMembersToGroup(groupId: String, identifiers: List<String>) {
        val cleaned = identifiers.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (cleaned.isEmpty()) return
        groups.document(groupId)
            .update("members", FieldValue.arrayUnion(*cleaned.toTypedArray()))
            .await()
    }

    /** Single-field update of the group's name — any member can do it. */
    suspend fun renameGroup(groupId: String, newName: String) {
        val trimmed = newName.trim()
        require(trimmed.isNotBlank()) { "Group name must not be blank." }
        groups.document(groupId).update("name", trimmed).await()
    }

    /**
     * Remove an identifier (uid or email) from the group's `members` array.
     * Rules only allow the group's `createdBy` to shrink the list, so callers
     * should gate this in UI; Firestore will reject otherwise with
     * PERMISSION_DENIED.
     */
    suspend fun removeMemberFromGroup(groupId: String, identifier: String) {
        val cleaned = identifier.trim()
        if (cleaned.isEmpty()) return
        groups.document(groupId)
            .update("members", FieldValue.arrayRemove(cleaned))
            .await()
    }
}

internal fun com.google.firebase.firestore.DocumentSnapshot.toGroup(): Group? {
    if (!exists()) return null
    return Group(
        id = id,
        name = getString("name").orEmpty(),
        members = (get("members") as? List<*>)?.filterIsInstance<String>().orEmpty(),
        createdBy = getString("createdBy").orEmpty(),
        createdAt = getTimestamp("createdAt"),
        isStarred = getBoolean("isStarred") ?: false,
    )
}
