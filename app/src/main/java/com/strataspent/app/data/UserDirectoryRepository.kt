package com.strataspent.app.data

import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.strataspent.app.data.model.UserProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.tasks.await
import java.util.concurrent.ConcurrentHashMap

/**
 * Caches `users/{uid}` lookups so we can render real display names instead of
 * uid prefixes wherever members appear (balances, splits, expense rows).
 *
 * Process-lifetime cache only — there's no persistent layer. Lookups happen
 * lazily; missing entries trigger a one-shot Firestore fetch and the result
 * is stored back into the flow so all observers re-render.
 *
 * Email→uid index is also maintained so dedupe ("hide the uid row when its
 * matching email is also present") works for non-viewer members too.
 */
class UserDirectoryRepository(private val firestore: FirebaseFirestore) {

    private val byUid = MutableStateFlow<Map<String, UserProfile>>(emptyMap())
    private val inFlight = ConcurrentHashMap<String, Unit>()

    /** All users we've successfully fetched, keyed by uid. */
    val directory: StateFlow<Map<String, UserProfile>> = byUid.asStateFlow()

    /**
     * Fire-and-forget hydration. Pass any identifiers that look like Firebase
     * uids — emails are ignored. Idempotent: in-flight or already-cached uids
     * are skipped.
     */
    suspend fun prefetch(identifiers: Iterable<String>) {
        val cached = byUid.value.keys
        val uidsToFetch = identifiers
            .asSequence()
            .filter { !it.contains('@') }
            .filter { it.isNotBlank() }
            .filter { it !in cached }
            .filter { inFlight.putIfAbsent(it, Unit) == null }
            .toList()

        for (uid in uidsToFetch) {
            runCatching {
                val snap = firestore.collection("users").document(uid).get().await()
                if (snap.exists()) {
                    val profile = UserProfile(
                        uid = uid,
                        displayName = snap.getString("displayName").orEmpty(),
                        email = snap.getString("email").orEmpty(),
                        photoURL = snap.getString("photoURL"),
                        income = snap.getDouble("income"),
                    )
                    Log.d(TAG, "prefetched users/$uid → email=${profile.email} name=${profile.displayName} income=${profile.income}")
                    byUid.update { it + (uid to profile) }
                } else {
                    Log.w(TAG, "prefetch users/$uid → DOC NOT FOUND")
                }
            }.onFailure { Log.w(TAG, "users/$uid fetch failed", it) }
                .also { inFlight.remove(uid) }
        }
    }

    /**
     * Write a new standing income value to `users/{uid}.income` and update
     * the in-memory cache immediately so dependent UI reflects the change
     * without waiting for a refetch.
     */
    suspend fun setIncome(uid: String, amount: Double) {
        firestore.collection("users").document(uid)
            .set(mapOf("income" to amount), SetOptions.merge())
            .await()
        byUid.update { current ->
            val existing = current[uid] ?: UserProfile(uid = uid)
            current + (uid to existing.copy(income = amount))
        }
    }

    private companion object { const val TAG = "UserDirectory" }
}

/**
 * Returns a deduped list of member identifiers for display, using both the
 * viewer's own (uid, email) and any other members we've already cached.
 *
 * For each uid in `members` we look up the cached profile; if their email is
 * also in `members`, we hide the uid row and keep the email. Unknown uids
 * pass through unchanged (they'll show as "abc12345…" until the cache fills).
 */
fun canonicalDedupedMembers(
    members: List<String>,
    viewer: UserProfile?,
    directory: Map<String, UserProfile>,
): List<String> {
    if (members.isEmpty()) return members

    // Build a "uids to hide" set: a uid is hidden if its associated email is
    // also present in `members`.
    val membersSet = members.toHashSet()
    val uidsToHide = HashSet<String>()

    // Viewer's own pair.
    if (viewer != null && viewer.uid in membersSet && viewer.email in membersSet) {
        uidsToHide += viewer.uid
    }

    // Other members we know about via the directory.
    for (id in members) {
        if (id.contains('@')) continue
        val profile = directory[id] ?: continue
        if (profile.email.isNotBlank() && profile.email in membersSet) {
            uidsToHide += id
        }
    }

    return members.filter { it !in uidsToHide }
}

/**
 * Map an identifier (uid or email) to the best human-readable name we have:
 * cached displayName > email > short uid prefix.
 */
fun resolveDisplayName(
    identifier: String,
    directory: Map<String, UserProfile>,
): String {
    if (identifier.contains('@')) {
        // It's an email — see if any cached profile has this email and a name.
        val named = directory.values.firstOrNull {
            it.email.equals(identifier, ignoreCase = true) && it.displayName.isNotBlank()
        }
        return named?.displayName ?: identifier
    }
    val profile = directory[identifier]
    return profile?.displayName?.takeIf { it.isNotBlank() }
        ?: profile?.email?.takeIf { it.isNotBlank() }
        ?: (identifier.take(8) + "…")
}
