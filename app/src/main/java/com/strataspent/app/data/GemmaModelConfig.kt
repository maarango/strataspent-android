package com.strataspent.app.data

/**
 * A downloadable on-device model: which weights file to fetch and how to run
 * it. Mirrors `GemmaModelConfig` from the training app's
 * `lib/services/gemma_plan_service.dart`, adapted to the native MediaPipe
 * `tasks-genai` runtime (which loads both `.task` and `.litertlm` bundles).
 *
 * The default presets are **vision-capable Gemma 3n** bundles so the receipt
 * (image) path works fully offline; they also handle text and audio input.
 *
 * NOTE: the exact [downloadUrl] / [fileName] should be verified against the
 * current Hugging Face repo at build time — Google occasionally renames the
 * preview bundles. They are centralized here precisely so a single edit fixes
 * every call site, and the Settings "Import model file…" path sidesteps the URL
 * entirely by letting the user supply a bundle they already have.
 */
data class GemmaModelConfig(
    /** Stable id persisted in [GemmaPreferences] and resolved by [byKey]. */
    val key: String,
    /** Human-readable name for the picker ("Gemma 3n E2B"). */
    val label: String,
    /** Filename as it lands on disk; must equal the basename of [downloadUrl]. */
    val fileName: String,
    /** HTTPS URL to the gated bundle (Hugging Face). Needs a token to download. */
    val downloadUrl: String,
    /** Approximate download size in GB, for the consent / Wi-Fi warning. */
    val approxSizeGb: Double,
    /** Context window for the loaded model. Larger = more RAM. */
    val maxTokens: Int = 2048,
    /** Whether the bundle accepts image input (receipt OCR path). */
    val supportsVision: Boolean = true,
    /** CPU is the broad-compatibility default; GPU only on verified devices. */
    val preferCpu: Boolean = true,
) {
    companion object {
        /** Safe default for most phones — Gemma 3n E2B (int4), ~3 GB. */
        val GEMMA_3N_E2B = GemmaModelConfig(
            key = "gemma3nE2B",
            label = "Gemma 3n E2B",
            fileName = "gemma-3n-E2B-it-int4.task",
            downloadUrl =
                "https://huggingface.co/google/gemma-3n-E2B-it-litert-preview/resolve/main/gemma-3n-E2B-it-int4.task",
            approxSizeGb = 3.1,
            maxTokens = 2048,
            supportsVision = true,
            preferCpu = true,
        )

        /** Higher quality for 6 GB+ phones — Gemma 3n E4B (int4), ~4.4 GB. */
        val GEMMA_3N_E4B = GemmaModelConfig(
            key = "gemma3nE4B",
            label = "Gemma 3n E4B",
            fileName = "gemma-3n-E4B-it-int4.task",
            downloadUrl =
                "https://huggingface.co/google/gemma-3n-E4B-it-litert-preview/resolve/main/gemma-3n-E4B-it-int4.task",
            approxSizeGb = 4.4,
            maxTokens = 2048,
            supportsVision = true,
            preferCpu = true,
        )

        val PRESETS: List<GemmaModelConfig> = listOf(GEMMA_3N_E2B, GEMMA_3N_E4B)
        val DEFAULT: GemmaModelConfig = GEMMA_3N_E2B

        fun byKey(key: String?): GemmaModelConfig =
            PRESETS.firstOrNull { it.key == key } ?: DEFAULT

        /** Pick a preset from available device RAM (MB). */
        fun recommended(ramMb: Long?): GemmaModelConfig =
            if (ramMb != null && ramMb >= 6L * 1024) GEMMA_3N_E4B else GEMMA_3N_E2B
    }
}
