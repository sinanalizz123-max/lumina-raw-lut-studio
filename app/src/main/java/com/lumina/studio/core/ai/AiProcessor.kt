package com.lumina.studio.core.ai

/**
 * M12 AI-selection backend contract (§§24-25).
 *
 * Inputs are preview-size ARGB row-major [IntArray]s (android-free on purpose
 * so pure-JVM tests pin the contract); Android callers convert via
 * `Bitmap.getPixels` (see [AiBitmaps]). Outputs are alpha fields 0..1 in the
 * SAME width/height as the input, or null when the backend cannot produce a
 * field (null = unavailable — the caller must show the honest fallback path,
 * see [AiSelectOutcome], never a silent empty mask).
 *
 * Only [HeuristicAiProcessor] ships in M12 (see [AiResearch]); the interface
 * keeps a future vetted backend (e.g. LiteRT) a drop-in swap.
 */
interface AiProcessor {
    val name: String
    val status: String
    val diagnostics: String

    fun subjectMask(argb: IntArray, width: Int, height: Int): FloatArray?

    fun skyMask(argb: IntArray, width: Int, height: Int): FloatArray?
}
