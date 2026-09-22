package com.lumina.studio.core.merge

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * M17 translation alignment math (pure JVM, no android.*).
 *
 * Method: zero-mean normalized cross-correlation (NCC) on luma thumbnails
 * (longest edge [MergeLimits.ALIGN_THUMB_EDGE]), exhaustive integer search
 * over ±range. NCC is brightness/contrast invariant, so bracketed exposures
 * still correlate — this is what makes one aligner serve both HDR (same
 * viewpoint) and panorama (overlapping viewpoint). Translation-only is a
 * deliberate limit: rotation beyond ~±2° (or hand-held parallax) lowers NCC
 * and surfaces [MergeError.AlignmentFailed]/[MergeError.InsufficientOverlap]
 * instead of a corrupt merge.
 *
 * Convention: aligning mov to ref with [Shift](dx, dy) means
 * aligned(x, y) = mov(x - dx, y - dy) with edge-clamp sampling
 * ([applyShiftRgb]). [ncc] scores exactly that overlap (no clamp: only the
 * shared interior counts).
 *
 * Determinism: single-threaded loops, Double accumulators, fixed
 * tie-break (higher score wins; ties within 1e-9 prefer smaller
 * |dx|+|dy|, then smaller |dx|, then smaller dx, then smaller dy).
 */
object AlignMath {
    const val INVALID_SCORE = -2f

    data class Shift(val dx: Int, val dy: Int, val score: Float)

    data class LumaThumb(val data: FloatArray, val width: Int, val height: Int)

    fun lumaOf(r: Float, g: Float, b: Float): Float =
        0.2126f * r + 0.7152f * g + 0.0722f * b

    fun rgbToLuma(rgb: FloatArray, width: Int, height: Int): FloatArray {
        val out = FloatArray(width * height)
        var s = 0
        var d = 0
        while (d < out.size) {
            out[d] = lumaOf(rgb[s], rgb[s + 1], rgb[s + 2])
            s += 3
            d++
        }
        return out
    }

    fun medianOf(values: FloatArray): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.clone()
        sorted.sort()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid]
        else (sorted[mid - 1] / 2f + sorted[mid] / 2f)
    }

    /** Box-average downsample of a luma field to [longestEdge]. */
    fun toThumb(luma: FloatArray, width: Int, height: Int, longestEdge: Int): LumaThumb {
        if (width <= 0 || height <= 0 || luma.size < width * height) {
            return LumaThumb(FloatArray(0), 0, 0)
        }
        val longest = maxOf(width, height)
        if (longest <= longestEdge || longestEdge <= 0) {
            return LumaThumb(luma.copyOf(width * height), width, height)
        }
        val scale = longestEdge.toFloat() / longest.toFloat()
        val nw = maxOf(1, (width * scale + 0.5f).toInt())
        val nh = maxOf(1, (height * scale + 0.5f).toInt())
        val out = FloatArray(nw * nh)
        for (y in 0 until nh) {
            val y0 = (y * height) / nh
            val y1 = (((y + 1) * height + nh - 1) / nh).coerceIn(y0 + 1, height)
            for (x in 0 until nw) {
                val x0 = (x * width) / nw
                val x1 = (((x + 1) * width + nw - 1) / nw).coerceIn(x0 + 1, width)
                var sum = 0.0
                var n = 0
                for (sy in y0 until y1) {
                    var idx = sy * width + x0
                    for (sx in x0 until x1) {
                        sum += luma[idx].toDouble()
                        idx++
                        n++
                    }
                }
                out[y * nw + x] = if (n > 0) (sum / n).toFloat() else 0f
            }
        }
        return LumaThumb(out, nw, nh)
    }

    fun shiftRangeFor(fraction: Float, dim: Int): Int =
        maxOf(0, (dim * fraction + 0.5f).toInt())

    /**
     * NCC of ref vs mov shifted by (dx, dy), over the shared interior only.
     * Returns [INVALID_SCORE] when the overlap is too small to be
     * meaningful. Flat-vs-flat-equal scores 1 (identical blanks do overlap);
     * flat-vs-varying scores 0 (no information to align on).
     */
    fun ncc(
        ref: FloatArray,
        mov: FloatArray,
        width: Int,
        height: Int,
        dx: Int,
        dy: Int
    ): Float {
        if (width <= 0 || height <= 0) return INVALID_SCORE
        if (ref.size < width * height || mov.size < width * height) return INVALID_SCORE
        val x0 = maxOf(0, dx)
        val x1 = minOf(width, width + dx)
        val y0 = maxOf(0, dy)
        val y1 = minOf(height, height + dy)
        val ow = x1 - x0
        val oh = y1 - y0
        if (ow < 4 || oh < 4 || ow * oh < MergeLimits.MIN_VALID_OVERLAP_PX) return INVALID_SCORE
        var sumR = 0.0
        var sumM = 0.0
        var n = 0
        for (y in y0 until y1) {
            var ri = y * width + x0
            var mi = (y - dy) * width + (x0 - dx)
            for (x in x0 until x1) {
                sumR += ref[ri].toDouble()
                sumM += mov[mi].toDouble()
                ri++
                mi++
                n++
            }
        }
        if (n <= 0) return INVALID_SCORE
        val meanR = sumR / n
        val meanM = sumM / n
        var cov = 0.0
        var varR = 0.0
        var varM = 0.0
        for (y in y0 until y1) {
            var ri = y * width + x0
            var mi = (y - dy) * width + (x0 - dx)
            for (x in x0 until x1) {
                val dr = ref[ri].toDouble() - meanR
                val dm = mov[mi].toDouble() - meanM
                cov += dr * dm
                varR += dr * dr
                varM += dm * dm
                ri++
                mi++
            }
        }
        if (varR <= 1e-12 && varM <= 1e-12) {
            return if (abs(meanR - meanM) < 1e-6) 1f else 0f
        }
        if (varR <= 1e-12 || varM <= 1e-12) return 0f
        return (cov / sqrt(varR * varM)).toFloat().coerceIn(-1f, 1f)
    }

    /**
     * Exhaustive integer-shift search. [checkCancel] runs per dy row (throw
     * to abort — CancellationException propagates untouched). Returns the
     * best shift even when weak; callers threshold via [checkConfidence].
     *
     * SIGN CONVENTION: the returned shift is the ALIGNING shift — applying
     * it to `mov` via [applyShiftRgb] brings `mov` onto `ref`. It is the
     * negation of the motion that created `mov` from `ref`.
     */
    fun estimateShift(
        ref: FloatArray,
        mov: FloatArray,
        width: Int,
        height: Int,
        maxShiftX: Int,
        maxShiftY: Int,
        checkCancel: () -> Unit = {}
    ): Shift {
        var best = Shift(0, 0, INVALID_SCORE)
        val mx = maxShiftX.coerceAtLeast(0)
        val my = maxShiftY.coerceAtLeast(0)
        for (dy in -my..my) {
            checkCancel()
            for (dx in -mx..mx) {
                val s = ncc(ref, mov, width, height, dx, dy)
                if (s < -1.5f) continue
                if (s > best.score + 1e-9f) {
                    best = Shift(dx, dy, s)
                } else if (abs(s - best.score) <= 1e-9f && best.score > -1.5f) {
                    val cur = abs(dx) + abs(dy)
                    val refM = abs(best.dx) + abs(best.dy)
                    val better = cur < refM ||
                        (cur == refM && (abs(dx) < abs(best.dx) ||
                            (abs(dx) == abs(best.dx) && (dx < best.dx ||
                                (dx == best.dx && dy < best.dy)))))
                    if (better) best = Shift(dx, dy, s)
                }
            }
        }
        return best
    }

    /** Confidence gate: null when [score] qualifies, else AlignmentFailed. */
    fun checkConfidence(score: Float, min: Float = MergeLimits.MIN_ALIGN_NCC): MergeError? {
        if (score >= min) return null
        return MergeError.AlignmentFailed(
            String.format(
                java.util.Locale.US,
                "low alignment confidence (NCC %.2f, need >= %.2f). ",
                score,
                min
            ) +
                "The photos may not show the same scene, or hand shake/rotation " +
                "exceeds the translation-only model (reliable up to ~±2°)."
        )
    }

    /** Integer translate with edge-clamp sampling (matches [ncc]). */
    fun applyShiftRgb(rgb: FloatArray, width: Int, height: Int, dx: Int, dy: Int): FloatArray {
        val out = FloatArray(rgb.size)
        if (width <= 0 || height <= 0 || rgb.size < width * height * 3) return out
        for (y in 0 until height) {
            val sy = (y - dy).coerceIn(0, height - 1)
            for (x in 0 until width) {
                val sx = (x - dx).coerceIn(0, width - 1)
                val s = (sy * width + sx) * 3
                val d = (y * width + x) * 3
                out[d] = rgb[s]
                out[d + 1] = rgb[s + 1]
                out[d + 2] = rgb[s + 2]
            }
        }
        return out
    }
}
