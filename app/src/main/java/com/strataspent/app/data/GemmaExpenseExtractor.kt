@file:Suppress("DEPRECATION") // tasks-genai 0.10.x marks LlmInference/Session as
// deprecated ahead of a future LiteRT-LM API; the current API is still the
// supported on-device path and works as-is.

package com.strataspent.app.data

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.genai.llminference.GraphOptions
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Runs the offline expense extraction on-device with a local Gemma 3n model via
 * Google AI Edge / MediaPipe `tasks-genai`. The native counterpart of the
 * training app's `GemmaPlanService` (`lib/services/gemma_plan_service.dart`).
 *
 * It returns the **same [OcrRepository.OcrResult]** the cloud path does, so
 * `AddExpenseViewModel` can use either interchangeably. Prompts and JSON parsing
 * come from the shared [ExpenseExtraction]; native `tasks-genai` has no
 * constrained function-calling, so structured output is the same JSON-prompt +
 * tolerant-parse approach the cloud OCR already uses (plus truncated-JSON
 * repair for small models).
 *
 * The (hundreds-of-MB) inference context is loaded lazily on first use and
 * cached; call [close] when leaving the AI flow to free it. Inference is
 * serialized with a [Mutex] — the runtime is not safe for concurrent calls.
 */
class GemmaExpenseExtractor(
    context: Context,
    private val prefs: GemmaPreferences,
    private val manager: GemmaModelManager,
) {
    private val appContext = context.applicationContext
    private val mutex = Mutex()

    @Volatile private var llm: LlmInference? = null
    @Volatile private var loadedPath: String? = null

    /** True when the feature is enabled and the selected model is downloaded. */
    fun isReady(): Boolean =
        prefs.enabled.value && manager.isInstalled(prefs.currentConfig())

    /** Receipt image → structured expense (multimodal). */
    suspend fun extractReceipt(image: Bitmap): OcrRepository.OcrResult =
        run(ExpenseExtraction.IMAGE_PROMPT, image)

    /** Voice transcript / typed text → structured expense (text only). */
    suspend fun transcribeToExpense(transcript: String, todayIso: String): OcrRepository.OcrResult {
        if (transcript.isBlank()) {
            return OcrRepository.OcrResult.Failure("Empty transcript", retryable = false)
        }
        return run(ExpenseExtraction.voicePrompt(todayIso, transcript), image = null)
    }

    private suspend fun run(prompt: String, image: Bitmap?): OcrRepository.OcrResult =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val config = prefs.currentConfig()
                val file = manager.modelFile(config)
                if (!file.exists() || file.length() == 0L) {
                    return@withLock OcrRepository.OcrResult.Failure(
                        "On-device model not downloaded. Get it under Settings → Offline AI.",
                        retryable = false,
                    )
                }
                val model = try {
                    ensureLoaded(config, file)
                } catch (t: Throwable) {
                    Log.w(TAG, "Gemma model load failed", t)
                    return@withLock OcrRepository.OcrResult.Failure(
                        "Couldn't load the on-device model: ${t.message}", retryable = false,
                    )
                }
                val raw = try {
                    generate(model, config, prompt, image)
                } catch (t: Throwable) {
                    Log.w(TAG, "Gemma inference failed", t)
                    return@withLock OcrRepository.OcrResult.Failure(
                        "On-device inference failed: ${t.message}", retryable = false,
                    )
                }
                ExpenseExtraction.parse(raw) ?: OcrRepository.OcrResult.Failure(
                    "The on-device model didn't return usable data. Try again.",
                    retryable = false,
                )
            }
        }

    private fun ensureLoaded(config: GemmaModelConfig, file: java.io.File): LlmInference {
        llm?.let { if (loadedPath == file.absolutePath) return it }
        // Switching models — free the previous context first.
        runCatching { llm?.close() }
        llm = null
        loadedPath = null

        val preferred = if (config.preferCpu) LlmInference.Backend.CPU else LlmInference.Backend.GPU
        val model = try {
            createInference(config, file, preferred)
        } catch (t: Throwable) {
            if (preferred == LlmInference.Backend.CPU) throw t
            // GPU path failed (delegate unavailable / OOM) — fall back to CPU.
            Log.w(TAG, "GPU backend failed, retrying on CPU", t)
            createInference(config, file, LlmInference.Backend.CPU)
        }
        llm = model
        loadedPath = file.absolutePath
        return model
    }

    private fun createInference(
        config: GemmaModelConfig,
        file: java.io.File,
        backend: LlmInference.Backend,
    ): LlmInference {
        val options = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(file.absolutePath)
            .setMaxTokens(config.maxTokens)
            .setPreferredBackend(backend)
            .apply { if (config.supportsVision) setMaxNumImages(1) }
            .build()
        return LlmInference.createFromOptions(appContext, options)
    }

    private fun generate(
        model: LlmInference,
        config: GemmaModelConfig,
        prompt: String,
        image: Bitmap?,
    ): String {
        val sessionOptions = LlmInferenceSession.LlmInferenceSessionOptions.builder()
            .setTopK(40)
            .setTemperature(0.4f)
            .apply {
                if (config.supportsVision) {
                    setGraphOptions(
                        GraphOptions.builder().setEnableVisionModality(true).build()
                    )
                }
            }
            .build()
        val session = LlmInferenceSession.createFromOptions(model, sessionOptions)
        return try {
            session.addQueryChunk(prompt)
            if (image != null) session.addImage(BitmapImageBuilder(scaleForVision(image)).build())
            session.generateResponse()
        } finally {
            runCatching { session.close() }
        }
    }

    /**
     * Cap the receipt image's longest side so the bitmap handed to the vision
     * encoder is sane (camera photos are ~12 MP). The model normalizes to its
     * own input resolution internally, so this is mainly a memory/stability win
     * on the largeHeap native path — it doesn't degrade legibility for OCR.
     */
    private fun scaleForVision(src: Bitmap): Bitmap {
        val max = 1024
        val w = src.width
        val h = src.height
        if (w <= max && h <= max) return src
        val ratio = minOf(max.toFloat() / w, max.toFloat() / h)
        return Bitmap.createScaledBitmap(src, (w * ratio).toInt(), (h * ratio).toInt(), true)
    }

    /** Free the in-memory inference context. Safe to call when idle. */
    fun close() {
        runCatching { llm?.close() }
        llm = null
        loadedPath = null
    }

    private companion object {
        const val TAG = "GemmaExpenseExtractor"
    }
}
