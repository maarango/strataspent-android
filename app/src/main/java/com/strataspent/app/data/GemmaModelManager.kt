package com.strataspent.app.data

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/**
 * Download / import / delete lifecycle for the on-device Gemma weights — the
 * native counterpart of `flutter_gemma`'s
 * `installModel(...).fromNetwork(...).withProgress(...)`.
 *
 * Two acquisition paths:
 *   - [download] — streams the gated bundle from Hugging Face with a Bearer
 *     token and reports progress. The token is only sent to `huggingface.co`,
 *     never to the redirected CDN host.
 *   - [importFromUri] — copies a bundle the user already has (picked via SAF)
 *     into the app sandbox. Needs no token; the fastest way to reuse a model
 *     without a second multi-GB download.
 *
 * Weights live under `filesDir/models/<fileName>`. "Installed" is derived from
 * that file existing with non-zero length.
 */
class GemmaModelManager(context: Context) {

    private val appContext = context.applicationContext
    private val modelsDir = File(appContext.filesDir, "models").apply { mkdirs() }

    fun modelFile(config: GemmaModelConfig): File = File(modelsDir, config.fileName)

    fun isInstalled(config: GemmaModelConfig): Boolean =
        modelFile(config).let { it.exists() && it.length() > 0 }

    /** Result of an acquisition attempt. */
    sealed interface Outcome {
        data object Success : Outcome
        /** Failed; [retryable] true when a later retry could plausibly succeed. */
        data class Failure(val message: String, val retryable: Boolean) : Outcome
    }

    /**
     * Download [config]'s weights into the sandbox, reporting [onProgress] as a
     * 0f..1f fraction (or -1f when the server doesn't send a content length).
     * Writes to `<fileName>.part` and atomically renames on success so a partial
     * file is never mistaken for an installed model. Cancellable.
     */
    suspend fun download(
        config: GemmaModelConfig,
        token: String?,
        onProgress: (Float) -> Unit,
    ): Outcome = withContext(Dispatchers.IO) {
        val target = modelFile(config)
        val part = File(modelsDir, "${config.fileName}.part")
        runCatching { part.delete() }

        try {
            val conn = openFollowingRedirects(URL(config.downloadUrl), token)
            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_UNAUTHORIZED || code == HttpURLConnection.HTTP_FORBIDDEN) {
                conn.disconnect()
                return@withContext Outcome.Failure(
                    "Download refused (HTTP $code). Check that your Hugging Face token is valid " +
                        "and that you've accepted the Gemma license for this model.",
                    retryable = false,
                )
            }
            if (code != HttpURLConnection.HTTP_OK) {
                conn.disconnect()
                return@withContext Outcome.Failure("Download failed (HTTP $code).", retryable = true)
            }

            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                part.outputStream().use { output ->
                    val buf = ByteArray(1 shl 16)
                    var read: Int
                    var downloaded = 0L
                    var lastReported = -1
                    while (input.read(buf).also { read = it } >= 0) {
                        coroutineContext.ensureActive() // cancellation between chunks
                        output.write(buf, 0, read)
                        downloaded += read
                        if (total > 0) {
                            val pct = ((downloaded * 100) / total).toInt()
                            if (pct != lastReported) {
                                lastReported = pct
                                onProgress(pct / 100f)
                            }
                        } else {
                            onProgress(-1f)
                        }
                    }
                    output.flush()
                }
            }
            conn.disconnect()

            if (total > 0 && part.length() != total) {
                part.delete()
                return@withContext Outcome.Failure(
                    "Download incomplete (${part.length()} of $total bytes). Try again.",
                    retryable = true,
                )
            }
            if (!part.renameTo(target)) {
                part.copyTo(target, overwrite = true); part.delete()
            }
            onProgress(1f)
            Outcome.Success
        } catch (t: Throwable) {
            runCatching { part.delete() }
            if (t is kotlinx.coroutines.CancellationException) throw t
            Log.w(TAG, "Gemma download failed", t)
            Outcome.Failure(t.message ?: "Download failed", retryable = t is IOException)
        }
    }

    /**
     * Copy a user-picked bundle (SAF `content://` Uri) into the sandbox as
     * [config]'s expected filename. [totalBytes] (from the picker, or <=0 if
     * unknown) drives progress.
     */
    suspend fun importFromUri(
        config: GemmaModelConfig,
        uri: Uri,
        totalBytes: Long,
        onProgress: (Float) -> Unit,
    ): Outcome = withContext(Dispatchers.IO) {
        val target = modelFile(config)
        val part = File(modelsDir, "${config.fileName}.part")
        runCatching { part.delete() }
        try {
            val input = appContext.contentResolver.openInputStream(uri)
                ?: return@withContext Outcome.Failure("Couldn't open the selected file.", retryable = false)
            input.use { ins ->
                part.outputStream().use { output ->
                    val buf = ByteArray(1 shl 16)
                    var read: Int
                    var copied = 0L
                    var lastReported = -1
                    while (ins.read(buf).also { read = it } >= 0) {
                        coroutineContext.ensureActive()
                        output.write(buf, 0, read)
                        copied += read
                        if (totalBytes > 0) {
                            val pct = ((copied * 100) / totalBytes).toInt()
                            if (pct != lastReported) {
                                lastReported = pct
                                onProgress(pct / 100f)
                            }
                        } else {
                            onProgress(-1f)
                        }
                    }
                    output.flush()
                }
            }
            if (part.length() == 0L) {
                part.delete()
                return@withContext Outcome.Failure("The selected file was empty.", retryable = false)
            }
            if (!part.renameTo(target)) {
                part.copyTo(target, overwrite = true); part.delete()
            }
            onProgress(1f)
            Outcome.Success
        } catch (t: Throwable) {
            runCatching { part.delete() }
            if (t is kotlinx.coroutines.CancellationException) throw t
            Log.w(TAG, "Gemma import failed", t)
            Outcome.Failure(t.message ?: "Import failed", retryable = false)
        }
    }

    fun delete(config: GemmaModelConfig) {
        runCatching { modelFile(config).delete() }
        runCatching { File(modelsDir, "${config.fileName}.part").delete() }
    }

    /**
     * Open [url], manually following up to 5 redirects. The Bearer [token] is
     * attached only on the original `huggingface.co` request, not when the
     * redirect points at a CDN host (sending it there can trigger a 400 and
     * needlessly leaks the credential).
     */
    private fun openFollowingRedirects(url: URL, token: String?): HttpURLConnection {
        var current = url
        var redirects = 0
        while (true) {
            val conn = (current.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 30_000
                readTimeout = 60_000
                requestMethod = "GET"
                if (!token.isNullOrBlank() && current.host.endsWith("huggingface.co")) {
                    setRequestProperty("Authorization", "Bearer $token")
                }
            }
            val code = conn.responseCode
            if (code in 300..399 && redirects < 5) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (location.isNullOrBlank()) return conn
                current = URL(current, location)
                redirects++
                continue
            }
            return conn
        }
    }

    private companion object {
        const val TAG = "GemmaModelManager"
    }
}
