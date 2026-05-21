package com.strataspent.app.data.model

import com.google.firebase.Timestamp

/**
 * Domain models that mirror the StrataSpent web app's Firestore schema so
 * both clients read and write the same documents.
 *
 * ─── Firestore layout (matches github.com/maarango/StrataSpent) ────────────
 *
 *   users/{uid}
 *     displayName : String         (optional)
 *     email       : String         (optional)
 *     photoURL    : String         (optional — note the capital URL)
 *     lastLogin   : serverTimestamp
 *     income      : Number         (optional, >= 0)
 *
 *   groups/{groupId}
 *     name        : String                      (required)
 *     members     : Array<String>               (mix of uids AND emails — rules accept either)
 *     createdBy   : String                      (uid)
 *     createdAt   : serverTimestamp
 *     isStarred   : Boolean                     (required on create, default false)
 *     customCategories     : Array<String>      (optional)
 *     categoryAssignments  : Map<category, uid> (optional, legacy)
 *     categoryShares       : Map<category, Array<{memberUid, percentage 0..1}>>  (optional)
 *
 *   groups/{groupId}/expenditures/{expId}
 *     category        : String       (one of [Categories.ALL])
 *     amount          : Number       (>= 0, dollars float — NOT cents)
 *     date            : String       ("YYYY-MM-DD")
 *     contributorName : String
 *     contributorUid  : String       (== auth.uid on write — rule-enforced)
 *     note            : String       (required, may be "")
 *     splits          : Map<uidOrEmail, Number>  (absolute dollars; empty = use group default)
 *     visibility      : "public" | "private"     (optional)
 *     paymentType     : "debit" | "credit"       (optional)
 *     recipientUid    : String                   (Settle Up only)
 *     createdAt / updatedAt : serverTimestamp    (optional)
 *
 * ─── Rules quirks to respect from the Kotlin side ──────────────────────────
 *   • Group `update` only allows ONE field-family per call — see addMembersToGroup.
 *   • Every expenditure write requires contributorUid == auth.uid.
 *   • Only `createdBy` can delete the group or remove members.
 *   • Membership is checked as `auth.uid in members OR auth.token.email in members`,
 *     so writing both identifiers when a user joins keeps them visible after they sign
 *     in for the first time on a new device.
 */

/** Allowed category names. The Firestore rules whitelist exactly these strings. */
object Categories {
    const val HOUSING = "Housing"
    const val FOOD = "Food"
    const val TRANSPORT = "Transport"
    const val ELECTRICITY = "Electricity"
    const val INTERNET = "Internet"
    const val PHONE = "Phone"
    const val GAS = "Gas"
    const val ENTERTAINMENT = "Entertainment"
    const val HEALTH = "Health"
    const val SAVINGS = "Savings"
    const val GLOBAL_INCOME = "Global Income"
    const val SETTLE_UP = "Settle Up"
    const val CREDIT_CARD_PAYMENT = "Credit Card Payment"
    const val OTHER = "Other"

    val ALL: List<String> = listOf(
        HOUSING, FOOD, TRANSPORT, ELECTRICITY, INTERNET, PHONE, GAS,
        ENTERTAINMENT, HEALTH, SAVINGS, GLOBAL_INCOME, SETTLE_UP,
        CREDIT_CARD_PAYMENT, OTHER,
    )
}

data class UserProfile(
    val uid: String = "",
    val displayName: String = "",
    val email: String = "",
    val photoURL: String? = null,
    /** Recurring/standing income (e.g. monthly salary). Optional. */
    val income: Double? = null,
)

data class Group(
    val id: String = "",
    val name: String = "",
    /** Mix of uids AND emails; rules' isMember check accepts either. */
    val members: List<String> = emptyList(),
    val createdBy: String = "",
    val createdAt: Timestamp? = null,
    val isStarred: Boolean = false,
)

object Visibility {
    const val PUBLIC = "public"
    const val PRIVATE = "private"
}

object PaymentType {
    const val DEBIT = "debit"
    const val CREDIT = "credit"
}

/** Allowed reminder frequencies — the rules whitelist exactly these strings. */
object ReminderFrequency {
    const val NONE = "none"
    const val DAILY = "daily"
    const val WEEKLY = "weekly"
    const val MONTHLY = "monthly"
    const val YEARLY = "yearly"

    val ALL: List<String> = listOf(NONE, DAILY, WEEKLY, MONTHLY, YEARLY)
}

data class Reminder(
    val id: String = "",
    val title: String = "",
    val amount: Double? = null,
    /** ISO date string "YYYY-MM-DD". */
    val dueDate: String = "",
    val creatorUid: String = "",
    val creatorName: String = "",
    val isCompleted: Boolean = false,
    val frequency: String? = null,
    val syncToExpenditure: Boolean? = null,
    val category: String? = null,
    val paymentType: String? = null,
    val createdAt: Timestamp? = null,
)

data class Expenditure(
    val id: String = "",
    val category: String = Categories.OTHER,
    val amount: Double = 0.0,
    /** ISO date string "YYYY-MM-DD". */
    val date: String = "",
    val contributorName: String = "",
    val contributorUid: String = "",
    val note: String = "",
    /** Per-identifier absolute amounts in dollars. Empty = use group default. */
    val splits: Map<String, Double> = emptyMap(),
    val visibility: String? = null,
    val paymentType: String? = null,
    val recipientUid: String? = null,
)

/** Per-member net balance: positive = group owes them, negative = they owe. */
data class MemberBalance(
    val identifier: String,    // uid or email
    val displayLabel: String,  // short, human-readable label for the row
    val net: Double,
)

/** Friendly label for a uid-or-email identifier when no display name is known.
 *  Real names come from the contributorName field on expenditure documents. */
fun displayLabelFor(identifier: String): String =
    if (identifier.contains('@')) identifier
    else if (identifier.length > 8) identifier.take(8) + "…"
    else identifier

/**
 * Returns the group's members deduplicated for the given viewer's perspective.
 *
 * The web app stores both the creator's `uid` AND `email` in `members` (so the
 * group keeps working if Firebase Auth ever rotates one of them). That makes
 * the viewer appear twice in any naive member iteration. When both forms of
 * the viewer's identity are present, prefer the email and hide the uid entry —
 * any other members pass through unchanged (we don't have their uid↔email
 * mapping without fetching user docs).
 */
fun Group.canonicalMembers(viewer: UserProfile?): List<String> {
    if (viewer == null) return members
    val hasBoth = viewer.uid in members && viewer.email in members
    return if (hasBoth) members.filter { it != viewer.uid } else members
}
