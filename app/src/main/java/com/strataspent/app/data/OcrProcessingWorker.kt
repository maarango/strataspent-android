package com.strataspent.app.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.strataspent.app.MainActivity
import com.strataspent.app.R
import com.strataspent.app.StrataSpentApplication
import com.strataspent.app.data.model.Visibility
import java.io.File

/**
 * Processes every queued OCR / voice job by calling Gemini and writing the
 * result as a PRIVATE expenditure into the original group. Posts a
 * notification per successful job so the user knows to review it.
 *
 * Scheduled via [schedule]. The CONNECTED network constraint ensures we only
 * run when the device has network — the worker waits otherwise.
 */
class OcrProcessingWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as StrataSpentApplication
        val locator = app.locator
        val pending = locator.pendingOcr
        val jobs = pending.snapshot()
        if (jobs.isEmpty()) return Result.success()

        val uid = locator.authRepo.currentUid() ?: return Result.retry()
        val displayName = locator.authRepo.currentDisplayName() ?: uid.take(8)
        val viewerIdentifier = locator.authRepo.currentEmail()?.takeIf { it.isNotBlank() } ?: uid

        var anySuccess = false
        var anyTransient = false

        for (job in jobs) {
            try {
                val result: OcrRepository.OcrResult = when (job.type) {
                    PendingOcrRepository.Type.IMAGE -> {
                        val bitmap = runCatching {
                            BitmapFactory.decodeFile(job.payload)
                        }.getOrNull()
                        if (bitmap == null) {
                            // File is gone — drop the entry, can't retry.
                            pending.remove(job.id)
                            continue
                        }
                        locator.ocrRepo.extractReceipt(bitmap)
                    }
                    PendingOcrRepository.Type.VOICE ->
                        locator.ocrRepo.transcribeToExpense(job.payload, todayIso())
                }

                when (result) {
                    is OcrRepository.OcrResult.NotConfigured -> {
                        // API key missing — drop, no point retrying.
                        pending.remove(job.id)
                    }
                    is OcrRepository.OcrResult.Failure -> {
                        if (result.retryable) {
                            anyTransient = true
                            Log.w(TAG, "Job ${job.id} failed (will retry): ${result.message}")
                        } else {
                            // Permanent failure — Gemini responded but the body
                            // wasn't usable. Looping won't help; drop the job.
                            Log.w(TAG, "Job ${job.id} unparseable, dropping: ${result.message}")
                            pending.remove(job.id)
                        }
                    }
                    is OcrRepository.OcrResult.Success -> {
                        val amount = result.amount ?: 0.0
                        if (amount <= 0) {
                            // Gemini couldn't read the receipt; drop.
                            pending.remove(job.id)
                            continue
                        }
                        locator.expenseRepo.addExpenditure(
                            groupId = job.groupId,
                            category = result.category ?: "Other",
                            amount = amount,
                            date = result.date ?: todayIso(),
                            contributorUid = uid,
                            contributorName = displayName,
                            note = "[Offline] ${result.note ?: ""}".trim(),
                            splitAmong = listOf(viewerIdentifier),
                            visibility = Visibility.PRIVATE,
                        )
                        pending.remove(job.id)
                        anySuccess = true
                        postProcessedNotification(
                            applicationContext, job, amount, result.category ?: "Other"
                        )
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Job ${job.id} crashed (will retry)", t)
                anyTransient = true
            }
        }

        return when {
            anyTransient -> Result.retry()
            anySuccess || pending.snapshot().isEmpty() -> Result.success()
            else -> Result.success()
        }
    }

    private fun postProcessedNotification(
        context: Context,
        job: PendingOcrRepository.Job,
        amount: Double,
        category: String,
    ) {
        ensureChannel(context)
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("openGroupId", job.groupId)
        }
        val pi = PendingIntent.getActivity(
            context, job.id.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val note = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.splash_wallet)
            .setContentTitle("Receipt processed: ${"%.2f".format(amount)} $category")
            .setContentText("Tap to open the group and review.")
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(job.id.hashCode(), note)
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "Receipt processing", NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "Notifies when a queued offline receipt is processed." }
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(ch)
        }
    }

    companion object {
        const val UNIQUE_NAME = "ocr-processing"
        const val CHANNEL_ID = "strata_ocr_processing"
        const val TAG = "OcrWorker"

        /** Schedule a one-time run that fires as soon as network is available. */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = OneTimeWorkRequestBuilder<OcrProcessingWorker>()
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
