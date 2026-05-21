package com.strataspent.app.data

import android.content.Context
import android.graphics.Bitmap
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Persistent queue of pending OCR + voice-transcription jobs. Items are
 * enqueued when the user scans a receipt or dictates an expense while the
 * device is offline (or Gemini fails for any reason). A WorkManager job
 * processes the queue when network returns and auto-creates a private
 * expenditure with whatever Gemini produced.
 *
 * Storage layout:
 *  - filesDir/pending-ocr/<id>.jpg  — image payloads (one file per job)
 *  - SharedPreferences "pending_ocr_queue" — JSON manifest of all jobs
 */
class PendingOcrRepository(context: Context) {

    enum class Type { IMAGE, VOICE }

    data class Job(
        val id: String,
        val type: Type,
        val groupId: String,
        /** For IMAGE: absolute file path. For VOICE: the transcript text. */
        val payload: String,
        val createdAt: Long,
    )

    private val appContext = context.applicationContext
    private val dir = File(appContext.filesDir, "pending-ocr").apply { mkdirs() }
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _queue = MutableStateFlow(loadFromDisk())
    val queue: StateFlow<List<Job>> = _queue.asStateFlow()

    fun snapshot(): List<Job> = _queue.value

    /** Persist an image payload + queue entry. Returns the new job id. */
    fun enqueueImage(groupId: String, bitmap: Bitmap): Job {
        val id = UUID.randomUUID().toString()
        val file = File(dir, "$id.jpg")
        FileOutputStream(file).use { os ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, os)
        }
        val job = Job(id, Type.IMAGE, groupId, file.absolutePath, System.currentTimeMillis())
        _queue.update { it + job }
        save()
        return job
    }

    fun enqueueVoice(groupId: String, transcript: String): Job {
        val id = UUID.randomUUID().toString()
        val job = Job(id, Type.VOICE, groupId, transcript, System.currentTimeMillis())
        _queue.update { it + job }
        save()
        return job
    }

    fun remove(id: String) {
        val job = _queue.value.firstOrNull { it.id == id } ?: return
        if (job.type == Type.IMAGE) {
            runCatching { File(job.payload).delete() }
        }
        _queue.update { current -> current.filterNot { it.id == id } }
        save()
    }

    private inline fun MutableStateFlow<List<Job>>.update(transform: (List<Job>) -> List<Job>) {
        value = transform(value)
    }

    private fun save() {
        val arr = JSONArray()
        for (j in _queue.value) {
            arr.put(
                JSONObject()
                    .put("id", j.id)
                    .put("type", j.type.name)
                    .put("groupId", j.groupId)
                    .put("payload", j.payload)
                    .put("createdAt", j.createdAt)
            )
        }
        prefs.edit { putString(KEY_QUEUE, arr.toString()) }
    }

    private fun loadFromDisk(): List<Job> {
        val raw = prefs.getString(KEY_QUEUE, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val type = runCatching { Type.valueOf(o.optString("type")) }.getOrNull()
                    ?: return@mapNotNull null
                Job(
                    id = o.optString("id"),
                    type = type,
                    groupId = o.optString("groupId"),
                    payload = o.optString("payload"),
                    createdAt = o.optLong("createdAt"),
                )
            }
        }.getOrDefault(emptyList())
    }

    private companion object {
        const val PREFS = "pending_ocr_queue"
        const val KEY_QUEUE = "queue_v1"
    }
}
