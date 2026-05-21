package com.strataspent.app.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.strataspent.app.MainActivity
import com.strataspent.app.R

/**
 * Receives FCM messages forwarded by Firebase. Surfaces them as a notification
 * that, when tapped, deep-links back into the web shell (MainActivity).
 */
class StrataPushService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        // Push the rotated token into users/{uid}.fcmTokens via the
        // already-initialized service locator. If no user is signed in, we
        // skip and rely on the auth listener to push the next token.
        runCatching {
            val app = applicationContext as com.strataspent.app.StrataSpentApplication
            app.locator.authRepo.storeFcmToken(token)
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val title = message.notification?.title ?: message.data["title"] ?: getString(R.string.app_name)
        val body = message.notification?.body ?: message.data["body"] ?: return

        ensureChannel()

        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            message.data["deepLink"]?.let { putExtra("deepLink", it) }
        }
        val pending = PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.splash_wallet)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(message.messageId?.hashCode() ?: System.currentTimeMillis().toInt(), notification)
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Reminders",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "Group expenditure reminders and receipt updates" }

            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private companion object {
        const val CHANNEL_ID = "strataspent_reminders"
    }
}
