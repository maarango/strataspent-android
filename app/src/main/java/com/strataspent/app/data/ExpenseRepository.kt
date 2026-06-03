package com.strataspent.app.data

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import android.util.Log
import com.strataspent.app.data.model.Categories
import com.strataspent.app.data.model.Expenditure
import com.strataspent.app.data.model.Group
import com.strataspent.app.data.model.MemberBalance
import com.strataspent.app.data.model.UserProfile
import com.strataspent.app.data.model.Visibility
import com.strataspent.app.data.model.canonicalMembers
import com.strataspent.app.data.model.displayLabelFor
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToLong
import kotlin.time.Duration.Companion.seconds

/**
 * Read/write expenditures inside a group's `expenditures` subcollection
 * (note: the web app uses "expenditures", not "expenses"; we match that
 * here so both clients share data).
 */
class ExpenseRepository(
    private val firestore: FirebaseFirestore,
    private val auth: FirebaseAuth,
) {

    private fun expRef(groupId: String) =
        firestore.collection("groups").document(groupId).collection("expenditures")

    fun expenditures(groupId: String): Flow<List<Expenditure>> = callbackFlow {
        // Security rules are NOT filters: with the visibility rule deployed, a
        // single unconstrained query over a collection that holds other
        // members' private docs is rejected outright. So we run two queries
        // that each provably match only readable docs, and merge them:
        //   • everyone's PUBLIC expenditures   (visibility == "public")
        //   • all of MY OWN expenditures       (contributorUid == uid) — any visibility
        // No orderBy here: an equality filter + orderBy on another field would
        // force a composite index. We sort client-side after merging instead,
        // by `date` DESC (required, lexical YYYY-MM-DD) — same order as before.
        //
        // Caveat: legacy docs written without a `visibility` field are treated
        // as public by the rules but won't match `visibility == "public"`, so
        // other members won't see them in the list (the owner still does, via
        // the contributorUid query). New writes always set the field.
        val uid = auth.currentUser?.uid
        val col = expRef(groupId)
        val lock = Any()
        var publicDocs: List<Expenditure> = emptyList()
        var myDocs: List<Expenditure> = emptyList()

        fun emitMerged() {
            val merged = synchronized(lock) {
                (publicDocs + myDocs)
                    .associateBy { it.id }            // dedupe my own public docs
                    .values
                    .sortedWith(
                        compareByDescending<Expenditure> { it.date }.thenByDescending { it.id }
                    )
            }
            trySend(merged)
        }

        val regPublic = col.whereEqualTo("visibility", Visibility.PUBLIC)
            .addSnapshotListener { snap, err ->
                if (err != null) { close(err); return@addSnapshotListener }
                synchronized(lock) {
                    publicDocs = snap?.documents?.mapNotNull { it.toExpenditure() } ?: emptyList()
                }
                emitMerged()
            }

        val regMine = uid?.let {
            col.whereEqualTo("contributorUid", it)
                .addSnapshotListener { snap, err ->
                    if (err != null) { close(err); return@addSnapshotListener }
                    synchronized(lock) {
                        myDocs = snap?.documents?.mapNotNull { d -> d.toExpenditure() } ?: emptyList()
                    }
                    emitMerged()
                }
        }

        awaitClose { regPublic.remove(); regMine?.remove() }
    }

    fun expenditure(groupId: String, id: String): Flow<Expenditure?> = callbackFlow {
        val reg = expRef(groupId).document(id).addSnapshotListener { snap, err ->
            if (err != null) {
                close(err); return@addSnapshotListener
            }
            trySend(snap?.toExpenditure())
        }
        awaitClose { reg.remove() }
    }

    suspend fun addExpenditure(
        groupId: String,
        category: String,
        amount: Double,
        date: String,
        contributorUid: String,
        contributorName: String,
        note: String,
        splitAmong: List<String>,
        visibility: String? = null,
        paymentType: String? = null,
    ) {
        val splits = equalSplits(amount, splitAmong)
        val data = buildMap<String, Any?> {
            put("category", category)
            put("amount", amount)
            put("date", date)
            put("contributorName", contributorName)
            put("contributorUid", contributorUid)
            put("note", note)
            put("splits", splits)
            put("createdAt", FieldValue.serverTimestamp())
            put("updatedAt", FieldValue.serverTimestamp())
            visibility?.let { put("visibility", it) }
            paymentType?.let { put("paymentType", it) }
        }
        val task = expRef(groupId).add(data)
        withTimeoutOrNull(3.seconds) { task.await() }
    }

    suspend fun updateExpenditure(
        groupId: String,
        expenseId: String,
        category: String,
        amount: Double,
        date: String,
        contributorUid: String,
        contributorName: String,
        note: String,
        splitAmong: List<String>,
        visibility: String? = null,
        paymentType: String? = null,
    ) {
        val splits = equalSplits(amount, splitAmong)
        val data = buildMap<String, Any?> {
            put("category", category)
            put("amount", amount)
            put("date", date)
            put("contributorName", contributorName)
            put("contributorUid", contributorUid)
            put("note", note)
            put("splits", splits)
            put("updatedAt", FieldValue.serverTimestamp())
            visibility?.let { put("visibility", it) }
            paymentType?.let { put("paymentType", it) }
        }
        val task = expRef(groupId).document(expenseId).update(data)
        withTimeoutOrNull(3.seconds) { task.await() }
    }

    /** Delete an expenditure. The Firestore rule only permits this when the
     *  caller is the contributor (`contributorUid == auth.uid`), so the UI
     *  must gate the action to the owner. */
    suspend fun deleteExpenditure(groupId: String, expenseId: String) {
        val task = expRef(groupId).document(expenseId).delete()
        withTimeoutOrNull(3.seconds) { task.await() }
    }

    /** Equal split with penny-accurate distribution. The web app stores
     *  amounts as floats; we round to 2 decimal places here so per-share
     *  values reconcile exactly to the total amount. */
    private fun equalSplits(amount: Double, among: List<String>): Map<String, Double> {
        require(among.isNotEmpty()) { "Need at least one person to split with." }
        require(amount > 0) { "Amount must be positive." }
        val totalCents = (amount * 100).roundToLong()
        val perCents = totalCents / among.size
        val remainder = totalCents - perCents * among.size
        return among.mapIndexed { idx, key ->
            val cents = perCents + if (idx < remainder) 1 else 0
            key to cents / 100.0
        }.toMap()
    }
}

internal fun com.google.firebase.firestore.DocumentSnapshot.toExpenditure(): Expenditure? {
    if (!exists()) return null
    @Suppress("UNCHECKED_CAST")
    val splits = (get("splits") as? Map<String, Number>).orEmpty()
        .mapValues { it.value.toDouble() }
    return Expenditure(
        id = id,
        category = getString("category") ?: Categories.OTHER,
        amount = getDouble("amount") ?: 0.0,
        date = getString("date").orEmpty(),
        contributorName = getString("contributorName").orEmpty(),
        contributorUid = getString("contributorUid").orEmpty(),
        note = getString("note").orEmpty(),
        splits = splits,
        visibility = getString("visibility"),
        paymentType = getString("paymentType"),
        recipientUid = getString("recipientUid"),
    )
}

/**
 * Reduce a group's expenditures to per-member net balance.
 *   positive → group owes this member
 *   negative → this member owes the group
 *
 * Keys are uid OR email — same identifiers the web app uses in `members`
 * and in `splits`. When we know that a uid and an email refer to the same
 * person (via [viewer] for the current user, or via [directory] for other
 * members), the uid total is folded into the email total and only the
 * email row is returned.
 */
fun computeBalances(
    group: Group,
    expenditures: List<Expenditure>,
    viewer: UserProfile? = null,
    directory: Map<String, UserProfile> = emptyMap(),
): List<MemberBalance> {
    // "Global Income" entries are per-user income events, not shared group
    // activity — they should never move group balances.
    val groupExpenses = expenditures.filter { it.category != Categories.GLOBAL_INCOME }
    Log.d(
        "Balances",
        "INPUT members=${group.members} viewer=${viewer?.uid}/${viewer?.email} " +
            "dirSize=${directory.size} expCount=${groupExpenses.size} (incomeSkipped=${expenditures.size - groupExpenses.size})"
    )
    val totals = HashMap<String, Double>().apply { group.members.forEach { put(it, 0.0) } }
    for (e in groupExpenses) {
        Log.d(
            "Balances",
            "  exp ${e.id} cat=${e.category} amt=${e.amount} contributor=${e.contributorUid} " +
                "splits=${e.splits} visibility=${e.visibility}"
        )
        totals.merge(e.contributorUid, e.amount) { a, b -> a + b }
        for ((id, share) in e.splits) {
            totals.merge(id, -share) { a, b -> a + b }
        }
    }
    Log.d("Balances", "  RAW totals=$totals")

    val membersSet = group.members.toHashSet()

    // The viewer's own user doc may not be cached yet (it's only fetched
    // when prefetch runs, and that races with the first balance compute).
    // We *always* know the viewer's uid↔email pair from Firebase Auth, so
    // synthesise a directory entry for them.
    val effectiveDir = if (viewer != null && viewer.email.isNotBlank()) {
        directory + (viewer.uid to UserProfile(uid = viewer.uid, email = viewer.email))
    } else directory

    // Single fold: for every uid in totals (viewer's own included), if the
    // directory tells us the matching email is in `members`, move the
    // contribution onto that email row. This covers:
    //  - the viewer when only their email (not uid) is in members
    //  - other members who logged contributions before their uid was added
    val foldableUids = totals.keys
        .filter { it.isNotBlank() && !it.contains('@') }
        .toList()
    for (uid in foldableUids) {
        val email = effectiveDir[uid]?.email?.takeIf { it.isNotBlank() } ?: continue
        if (email !in membersSet) continue
        val uidTotal = totals[uid] ?: continue
        totals[email] = (totals[email] ?: 0.0) + uidTotal
        totals[uid] = 0.0
    }

    val canonical = canonicalDedupedMembers(group.members, viewer, directory)
    Log.d("Balances", "  POST-fold totals=$totals canonical=$canonical")
    return canonical.map { id ->
        MemberBalance(
            identifier = id,
            displayLabel = resolveDisplayName(id, directory),
            net = totals[id] ?: 0.0,
        )
    }
}

/**
 * True when [viewer] logged this expenditure. Matches by uid, or by email
 * through the [directory] so the same person across providers (Google vs
 * email/password, with different Firebase uids) still owns their own entry.
 */
fun Expenditure.isOwnedBy(
    viewer: UserProfile?,
    directory: Map<String, UserProfile> = emptyMap(),
): Boolean {
    if (viewer == null) return false
    if (contributorUid == viewer.uid) return true
    val contributorEmail = directory[contributorUid]?.email ?: return false
    return viewer.email.isNotBlank() && contributorEmail.equals(viewer.email, ignoreCase = true)
}

/**
 * A `visibility == "private"` expenditure is visible only to the member who
 * logged it; everyone else must not see it. Public entries are visible to all.
 */
fun Expenditure.isVisibleTo(
    viewer: UserProfile?,
    directory: Map<String, UserProfile> = emptyMap(),
): Boolean = visibility != Visibility.PRIVATE || isOwnedBy(viewer, directory)

/** "YYYY-MM-DD" in UTC — matches the web app's `new Date().toISOString().slice(0,10)`. */
fun todayIso(): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
