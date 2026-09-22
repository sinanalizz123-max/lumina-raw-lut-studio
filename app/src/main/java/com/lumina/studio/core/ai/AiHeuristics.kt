package com.lumina.studio.core.ai

import com.lumina.studio.core.edit.GradeMath
import kotlin.math.exp

/**
 * M12 heuristic selection math (pure JVM, no android.*).
 *
 * Honest scope: these are saliency/color approximations, NOT detection or
 * segmentation. [computeSubjectMask] guesses "central, colorful foreground";
 * [computeSkyMask] selects by blueness + a bright-luma band with an
 * upper-frame bias. PreviewRenderer owns the Bitmap loops for every other
 * mask tool; the heuristic passes run here per-pixel on IntArrays so JVM
 * tests pin them, and the field is resampled to render size at draw time
 * (see [AiMaskResample]).
 */
object AiHeuristics {
    /** Longest side of the analysis bitmap (see AiBitmaps). */
    const val ANALYSIS_MAX_DIM = 256

    fun lumaOf(r: Float, g: Float, b: Float): Float = GradeMath.lumaOf(r, g, b)

    /**
     * Sky blueness 0..1: how much blue dominates red/green. Pure blue = 1,
     * white/grey/green/red = 0.
     */
    fun skyBlueness(r: Float, g: Float, b: Float): Float =
        (b - maxOf(r, g)).coerceIn(0f, 1f)

    /**
     * Sky weight 0..1: blueness x bright-luma band x upper-frame bias.
     * [nx]/[ny] are normalized 0..1 coordinates (ny = 0 is the top row).
     */
    fun skyWeight(r: Float, g: Float, b: Float, nx: Float, ny: Float): Float {
        val blue = skyBlueness(r, g, b)
        if (blue <= 0f) return 0f
        val lumaBand = GradeMath.smoothstep(0.18f, 0.45f, lumaOf(r, g, b))
        if (lumaBand <= 0f) return 0f
        val upperBias = 1f - 0.7f * GradeMath.smoothstep(0.35f, 0.9f, ny.coerceIn(0f, 1f))
        return (blue * lumaBand * upperBias).coerceIn(0f, 1f)
    }

    /**
     * Subject weight 0..1: center gaussian (sigma ~0.55 of the half-frame)
     * times a chroma term, so flat grey edges score low and colorful central
     * pixels score high. Documented guess — not detection.
     */
    fun subjectWeight(r: Float, g: Float, b: Float, nx: Float, ny: Float): Float {
        val dx = nx.coerceIn(0f, 1f) - 0.5f
        val dy = ny.coerceIn(0f, 1f) - 0.5f
        val dist2 = (dx * dx + dy * dy) / (0.55f * 0.55f)
        val center = exp(-dist2).toFloat().coerceIn(0f, 1f)
        if (center <= 0.001f) return 0f
        val chroma = (maxOf(r, g, b) - minOf(r, g, b)).coerceIn(0f, 1f)
        return (center * (0.3f + 0.7f * (chroma * 1.6f).coerceIn(0f, 1f))).coerceIn(0f, 1f)
    }

    /** Analysis dims for a source frame: longest side capped at [maxDim]. */
    fun analysisDims(srcW: Int, srcH: Int, maxDim: Int = ANALYSIS_MAX_DIM): IntArray {
        if (srcW <= 0 || srcH <= 0) return intArrayOf(0, 0)
        val longest = maxOf(srcW, srcH)
        if (longest <= maxDim) return intArrayOf(srcW, srcH)
        val scale = maxDim.toFloat() / longest.toFloat()
        return intArrayOf(
            (srcW * scale + 0.5f).toInt().coerceIn(1, srcW),
            (srcH * scale + 0.5f).toInt().coerceIn(1, srcH)
        )
    }

    fun computeSkyMask(argb: IntArray, w: Int, h: Int): FloatArray? {
        if (w <= 0 || h <= 0 || argb.size != w * h) return null
        val out = FloatArray(w * h)
        for (y in 0 until h) {
            val ny = if (h > 1) y.toFloat() / (h - 1).toFloat() else 0.5f
            for (x in 0 until w) {
                val nx = if (w > 1) x.toFloat() / (w - 1).toFloat() else 0.5f
                val p = argb[y * w + x]
                out[y * w + x] = skyWeight(
                    ((p shr 16) and 0xFF) / 255f,
                    ((p shr 8) and 0xFF) / 255f,
                    (p and 0xFF) / 255f,
                    nx, ny
                )
            }
        }
        return out
    }

    fun computeSubjectMask(argb: IntArray, w: Int, height: Int): FloatArray? {
        if (w <= 0 || height <= 0 || argb.size != w * height) return null
        val out = FloatArray(w * height)
        for (y in 0 until height) {
            val ny = if (height > 1) y.toFloat() / (height - 1).toFloat() else 0.5f
            for (x in 0 until w) {
                val nx = if (w > 1) x.toFloat() / (w - 1).toFloat() else 0.5f
                val p = argb[y * w + x]
                out[y * w + x] = subjectWeight(
                    ((p shr 16) and 0xFF) / 255f,
                    ((p shr 8) and 0xFF) / 255f,
                    (p and 0xFF) / 255f,
                    nx, ny
                )
            }
        }
        return out
    }
}
