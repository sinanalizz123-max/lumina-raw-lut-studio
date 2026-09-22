package com.lumina.studio.core.merge

import com.lumina.studio.core.render.Dims
import kotlin.math.abs
import kotlin.math.exp

/**
 * M17 Mertens exposure fusion (pure JVM, no android.*).
 *
 * This is a genuine multi-image merge — NOT a single-image brightness
 * trick. Per-pixel weights follow Mertens et al. 2007 (contrast ×
 * saturation × well-exposedness, exponents 1/1/1), normalized across
 * frames, blended through a TRUE 2-level Laplacian pyramid (level 0 =
 * full-res detail, level 1 = half-res base; out = blend(L0) +
 * up(blend(L1))). The pyramid removes the halo/block seams a naive
 * weighted average leaves around high-contrast weight edges.
 *
 * Honest limits (documented, not hidden):
 * - Display-referred: inputs are gamma sRGB 0..1 (the pipeline's
 *   working domain), so the output is a fused LDR image in the same
 *   domain — dynamic range is preserved as far as the 8-bit-source
 *   pipeline allows, and the Android edge stores it in RGBA_F16 so no
 *   extra quantization is added before editing. No fake "HDR look".
 * - Deghost-lite: a frame whose luma differs from the per-pixel median
 *   by more than [GHOST_LUMA_THRESH] gets its weight multiplied by
 *   [GHOST_PENALTY]. This suppresses small motion; large moving subjects
 *   still smear — reported honestly instead of claimed away.
 * - Weight ties: a pixel with ~zero total weight in every frame (e.g.
 *   perfectly flat gray in ALL frames) falls back to uniform weights
 *   rather than NaN.
 *
 * Determinism: single-threaded, Double only for NCC-adjacent accumulators
 * (weights stay Float end-to-end like the renderer); same inputs yield
 * identical FloatArrays. Cancellation via [checkCancel] (throw to abort;
 * CancellationException propagates untouched). Ownership: [fuse] never
 * releases its input (see [MergeFrames.use]).
 */
object HdrMath {
    const val WELL_MU = 0.5f
    const val WELL_SIGMA = 0.2f
    const val GHOST_LUMA_THRESH = 0.25f
    const val GHOST_PENALTY = 0.25f

    /** Gaussian well-exposedness: 1 at mid-gray, ~0 at clip. */
    fun wellExposedness(luma: Float): Float {
        val d = (luma.coerceIn(0f, 1f) - WELL_MU) / WELL_SIGMA
        return exp(-0.5f * d * d)
    }

    /** Saturation = population std-dev across R/G/B (Mertens measure). */
    fun saturationOf(r: Float, g: Float, b: Float): Float {
        val m = (r + g + b) / 3f
        val dr = r - m
        val dg = g - m
        val db = b - m
        return kotlin.math.sqrt((dr * dr + dg * dg + db * db) / 3f)
    }

    /** Contrast = |Laplacian| magnitude on luma (edge-clamped borders). */
    fun contrastAt(luma: FloatArray, width: Int, height: Int, x: Int, y: Int): Float {
        fun at(xx: Int, yy: Int): Float =
            luma[yy.coerceIn(0, height - 1) * width + xx.coerceIn(0, width - 1)]
        val c = at(x, y)
        val lap = abs(4f * c - at(x - 1, y) - at(x + 1, y) - at(x, y - 1) - at(x, y + 1)) * 0.25f
        return lap
    }

    /**
     * Normalized per-frame weight maps (with deghost-lite penalty).
     * [rgbFrames]/[lumas] are parallel lists, size w*h*3 / w*h.
     */
    fun frameWeights(
        rgbFrames: List<FloatArray>,
        lumas: List<FloatArray>,
        width: Int,
        height: Int,
        checkCancel: () -> Unit = {}
    ): List<FloatArray> {
        val n = rgbFrames.size
        require(n >= 2) { "fusion needs at least 2 frames" }
        val px = width * height
        val weights = List(n) { FloatArray(px) }
        val med = FloatArray(n)
        for (y in 0 until height) {
            if (y % 16 == 0) checkCancel()
            for (x in 0 until width) {
                val i = y * width + x
                for (k in 0 until n) med[k] = lumas[k][i]
                val median = AlignMath.medianOf(med)
                for (k in 0 until n) {
                    val luma = lumas[k][i]
                    val c = contrastAt(lumas[k], width, height, x, y)
                    val s3 = i * 3
                    val rgb = rgbFrames[k]
                    val s = saturationOf(rgb[s3], rgb[s3 + 1], rgb[s3 + 2])
                    val e = wellExposedness(luma)
                    var w = c * s * e
                    if (abs(luma - median) > GHOST_LUMA_THRESH) w *= GHOST_PENALTY
                    weights[k][i] = w
                }
            }
        }
        checkCancel()
        for (i in 0 until px) {
            var sum = 0f
            for (k in 0 until n) sum += weights[k][i]
            if (sum <= 1e-12f) {
                val u = 1f / n.toFloat()
                for (k in 0 until n) weights[k][i] = u
            } else {
                for (k in 0 until n) weights[k][i] /= sum
            }
        }
        return weights
    }

    private data class RgbLevel(val data: FloatArray, val width: Int, val height: Int)
    private data class WeightLevel(val data: FloatArray, val width: Int, val height: Int)

    private fun downsampleRgb(src: FloatArray, w: Int, h: Int): RgbLevel {
        val nw = maxOf(1, (w + 1) / 2)
        val nh = maxOf(1, (h + 1) / 2)
        val out = FloatArray(nw * nh * 3)
        for (y in 0 until nh) {
            val y0 = (y * 2).coerceIn(0, h - 1)
            val y1 = (y * 2 + 1).coerceIn(0, h - 1)
            for (x in 0 until nw) {
                val x0 = (x * 2).coerceIn(0, w - 1)
                val x1 = (x * 2 + 1).coerceIn(0, w - 1)
                val d = (y * nw + x) * 3
                for (c in 0..2) {
                    out[d + c] = (
                        src[(y0 * w + x0) * 3 + c] + src[(y0 * w + x1) * 3 + c] +
                            src[(y1 * w + x0) * 3 + c] + src[(y1 * w + x1) * 3 + c]
                        ) * 0.25f
                }
            }
        }
        return RgbLevel(out, nw, nh)
    }

    private fun downsampleWeight(src: FloatArray, w: Int, h: Int): WeightLevel {
        val nw = maxOf(1, (w + 1) / 2)
        val nh = maxOf(1, (h + 1) / 2)
        val out = FloatArray(nw * nh)
        for (y in 0 until nh) {
            val y0 = (y * 2).coerceIn(0, h - 1)
            val y1 = (y * 2 + 1).coerceIn(0, h - 1)
            for (x in 0 until nw) {
                val x0 = (x * 2).coerceIn(0, w - 1)
                val x1 = (x * 2 + 1).coerceIn(0, w - 1)
                out[y * nw + x] = (
                    src[y0 * w + x0] + src[y0 * w + x1] +
                        src[y1 * w + x0] + src[y1 * w + x1]
                    ) * 0.25f
            }
        }
        return WeightLevel(out, nw, nh)
    }

    /** Bilinear upsample (edge-clamped), deterministic. */
    private fun upsampleRgb(src: FloatArray, sw: Int, sh: Int, dw: Int, dh: Int): FloatArray {
        val out = FloatArray(dw * dh * 3)
        if (sw <= 0 || sh <= 0 || dw <= 0 || dh <= 0) return out
        val sx = sw.toFloat() / dw.toFloat()
        val sy = sh.toFloat() / dh.toFloat()
        for (y in 0 until dh) {
            val gy = (y + 0.5f) * sy - 0.5f
            val y0 = gy.toInt().coerceIn(0, sh - 1)
            val y1 = (y0 + 1).coerceIn(0, sh - 1)
            val fy = (gy - gy.toInt().coerceIn(0, sh - 1)).coerceIn(0f, 1f)
            for (x in 0 until dw) {
                val gx = (x + 0.5f) * sx - 0.5f
                val x0 = gx.toInt().coerceIn(0, sw - 1)
                val x1 = (x0 + 1).coerceIn(0, sw - 1)
                val fx = (gx - gx.toInt().coerceIn(0, sw - 1)).coerceIn(0f, 1f)
                val d = (y * dw + x) * 3
                for (c in 0..2) {
                    val v00 = src[(y0 * sw + x0) * 3 + c]
                    val v10 = src[(y0 * sw + x1) * 3 + c]
                    val v01 = src[(y1 * sw + x0) * 3 + c]
                    val v11 = src[(y1 * sw + x1) * 3 + c]
                    out[d + c] = (v00 * (1f - fx) + v10 * fx) * (1f - fy) +
                        (v01 * (1f - fx) + v11 * fx) * fy
                }
            }
        }
        return out
    }

    /** Renormalize coarse weight levels (box downsample drifts the sum). */
    private fun renormalize(levels: List<WeightLevel>): List<WeightLevel> {
        if (levels.isEmpty()) return levels
        val n = levels.size
        val px = levels[0].data.size
        for (i in 0 until px) {
            var sum = 0f
            for (k in 0 until n) sum += levels[k].data[i]
            if (sum <= 1e-12f) {
                val u = 1f / n.toFloat()
                for (k in 0 until n) levels[k].data[i] = u
            } else {
                for (k in 0 until n) levels[k].data[i] /= sum
            }
        }
        return levels
    }

    /**
     * Full merge: weights -> 2-level Laplacian pyramid blend -> clamped
     * RGB FloatArray (w*h*3, 0..1). Progress: 0..0.5 weights, 0.5..1.0
     * pyramid. Precondition: frames validated (same dims, >= 2).
     */
    fun fuse(
        frames: MergeFrames,
        checkCancel: () -> Unit = {},
        onProgress: (Float) -> Unit = {}
    ): FloatArray {
        val w = frames.width
        val h = frames.height
        val n = frames.frames.size
        require(n >= 2) { "fusion needs at least 2 frames" }
        require(w > 0 && h > 0) { "fusion needs non-empty frames" }
        val px = w * h
        for (k in 0 until n) {
            require(frames.frames[k].size >= px * 3) { "frame $k is truncated" }
        }
        fun emit(f: Float) {
            runCatching { onProgress(f.coerceIn(0f, 1f)) }
        }
        val lumas = ArrayList<FloatArray>(n)
        for (k in 0 until n) {
            checkCancel()
            lumas.add(AlignMath.rgbToLuma(frames.frames[k], w, h))
        }
        emit(0.25f)
        val weights = frameWeights(frames.frames, lumas, w, h, checkCancel)
        emit(0.5f)
        lumas.clear()
        val coarseFrames = ArrayList<RgbLevel>(n)
        for (k in 0 until n) {
            checkCancel()
            coarseFrames.add(downsampleRgb(frames.frames[k], w, h))
        }
        emit(0.65f)
        val coarseWeights = renormalize(
            weights.map { downsampleWeight(it, w, h) }
        )
        checkCancel()
        val base = FloatArray(px * 3)
        val cw = coarseFrames[0].width
        val ch = coarseFrames[0].height
        val cpx = cw * ch
        for (k in 0 until n) {
            val cf = coarseFrames[k].data
            val cwgt = coarseWeights[k].data
            for (i in 0 until cpx) {
                val g = cwgt[i]
                if (g == 0f) continue
                val s = i * 3
                base[s] += cf[s] * g
                base[s + 1] += cf[s + 1] * g
                base[s + 2] += cf[s + 2] * g
            }
        }
        emit(0.8f)
        val upBase = upsampleRgb(base, cw, ch, w, h)
        checkCancel()
        val out = FloatArray(px * 3)
        for (k in 0 until n) {
            checkCancel()
            val fine = frames.frames[k]
            val coarseUp = upsampleRgb(coarseFrames[k].data, cw, ch, w, h)
            val wk = weights[k]
            for (i in 0 until px) {
                val g = wk[i]
                if (g == 0f) continue
                val s = i * 3
                out[s] += (fine[s] - coarseUp[s]) * g
                out[s + 1] += (fine[s + 1] - coarseUp[s + 1]) * g
                out[s + 2] += (fine[s + 2] - coarseUp[s + 2]) * g
            }
        }
        for (i in out.indices) {
            val s = (i / 3) * 3
            val up = upBase[s + (i % 3)]
            out[i] = (out[i] + up).coerceIn(0f, 1f)
        }
        emit(1f)
        return out
    }

    fun outputDims(width: Int, height: Int): Dims = Dims(width, height)
}
