package com.strataspent.app.data

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.UserProfileChangeRequest
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.strataspent.app.data.model.UserProfile
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

/**
 * Wraps Firebase Auth plus the `users/{uid}` mirror doc with the field names
 * the web app uses (note: `photoURL` with capital URL, and `lastLogin`).
 *
 * The user doc is upserted (merge) on every auth-state change so accounts
 * that signed up before this code existed get their mirror doc backfilled
 * the first time they sign in.
 */
class AuthRepository(
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
) {

    val currentUser: Flow<UserProfile?> = callbackFlow {
        val listener = FirebaseAuth.AuthStateListener { fb ->
            val u = fb.currentUser
            trySend(u?.let(::profileFrom))
            if (u != null) upsertUserDocFireAndForget(u)
        }
        auth.addAuthStateListener(listener)
        awaitClose { auth.removeAuthStateListener(listener) }
    }

    suspend fun signIn(email: String, password: String) {
        auth.signInWithEmailAndPassword(email.trim(), password).await()
        // Mirror doc upsert is handled by the auth-state listener above.
    }

    suspend fun signUp(displayName: String, email: String, password: String) {
        val result = auth.createUserWithEmailAndPassword(email.trim(), password).await()
        val user = result.user ?: error("Firebase did not return a user after sign-up")
        user.updateProfile(
            UserProfileChangeRequest.Builder().setDisplayName(displayName).build()
        ).await()
        upsertUserDoc(user.uid, displayName, user.email, user.photoUrl?.toString())
    }

    /**
     * Complete a Google Sign-In handshake. The screen layer obtains the Google
     * ID token via Credential Manager + Sign in with Google and hands it here;
     * we exchange it for a Firebase credential and sign in. The user-doc
     * upsert is then handled by the auth-state listener.
     */
    suspend fun signInWithGoogleIdToken(idToken: String) {
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        auth.signInWithCredential(credential).await()
    }

    fun signOut() = auth.signOut()

    // ---- Sync convenience accessors used by WorkManager workers and
    // ---- other non-Flow contexts. Cheap — FirebaseAuth caches in memory.
    fun currentUid(): String? = auth.currentUser?.uid
    fun currentEmail(): String? = auth.currentUser?.email
    fun currentDisplayName(): String? = auth.currentUser?.displayName

    private fun profileFrom(u: FirebaseUser) = UserProfile(
        uid = u.uid,
        displayName = u.displayName.orEmpty(),
        email = u.email.orEmpty(),
        photoURL = u.photoUrl?.toString(),
    )

    private suspend fun upsertUserDoc(
        uid: String, displayName: String?, email: String?, photoURL: String?,
    ) {
        firestore.collection("users").document(uid)
            .set(userDocPayload(displayName, email, photoURL), SetOptions.merge())
            .await()
    }

    /** Listener callbacks can't suspend; we fire the upsert and don't await it. */
    private fun upsertUserDocFireAndForget(u: FirebaseUser) {
        firestore.collection("users").document(u.uid)
            .set(
                userDocPayload(u.displayName, u.email, u.photoUrl?.toString()),
                SetOptions.merge(),
            )

        // Push this device's FCM token into the user doc so the Cloud Function
        // backend can target reminder notifications at it. Stored as an array
        // so a user can have several devices registered.
        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { token ->
                if (token.isNullOrBlank()) return@addOnSuccessListener
                firestore.collection("users").document(u.uid)
                    .set(
                        mapOf("fcmTokens" to FieldValue.arrayUnion(token)),
                        SetOptions.merge(),
                    )
            }
            .addOnFailureListener { t -> Log.w("AuthRepository", "FCM token fetch failed", t) }
    }

    /** Called from [com.strataspent.app.push.StrataPushService.onNewToken]
     *  when FCM rotates the device token. */
    fun storeFcmToken(token: String) {
        val uid = auth.currentUser?.uid ?: return
        if (token.isBlank()) return
        firestore.collection("users").document(uid)
            .set(
                mapOf("fcmTokens" to FieldValue.arrayUnion(token)),
                SetOptions.merge(),
            )
    }

    private fun userDocPayload(
        displayName: String?, email: String?, photoURL: String?,
    ): Map<String, Any> = buildMap {
        displayName?.takeIf { it.isNotBlank() }?.let { put("displayName", it) }
        email?.takeIf { it.isNotBlank() }?.let { put("email", it) }
        photoURL?.let { put("photoURL", it) }
        put("lastLogin", FieldValue.serverTimestamp())
    }
}
