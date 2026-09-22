package com.lumina.studio.core.edit

import kotlin.math.sqrt

/**
 * M8 retouch + lens-blur + dust math (§26-28, pure JVM, no android.*).
 *
 * PreviewRenderer owns the Bitmap loops; everything here is a pure function so
 * JVM tests pin it. All approximations are documented at the call site.
 * Honesty (§79): nothing here is AI — heal is an annulus-median blend, erase
 * is onion-peel diffusion, lens blur is a radial heuristic (no depth sensor),
 * dust scan is a luminance-outlier heuristic labelled "candidates".
 */
object RetouchMath {
    fun luma(r: Float, g: Float, b: Float): Float =
        (0.2126f * r + 0.7152f * g + 0.0722f * b).coerceIn(0f, 1f)

    fun median(values: FloatArray): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.clone()
        sorted.sort()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid]
        else (sorted[mid - 1] + sorted[mid]) / 2f
    }

    /**
     * Heal blend (§26, honest approx): replace the target color with the
     * surrounding-annulus median color, then add back the target's own
     * luminance detail (target minus its luma). Preserves target luma exactly
     * on flat input (out == median) while keeping texture elsewhere. Best on
     * smooth areas; busy texture smears (documented in UI copy).
     */
    fun healPixel(tR: Float, tG: Float, tB: Float, mR: Float, mG: Float, mB: Float): FloatArray {
        val out = FloatArray(3)
        healPixelInto(tR, tG, tB, mR, mG, mB, out)
        return out
    }

    /**
     * M16 allocation-free variant (§34): writes the healed pixel into [out]
     * (size >= 3), bit-identical to [healPixel]. Hot retouch loops must call
     * this with a thread-local scratch buffer instead of allocating per pixel.
     */
    fun healPixelInto(
        tR: Float, tG: Float, tB: Float, mR: Float, mG: Float, mB: Float, out: FloatArray
    ) {
        val mLum = luma(mR, mG, mB)
        out[0] = (tR + (mR - mLum)).coerceIn(0f, 1f)
        out[1] = (tG + (mG - mLum)).coerceIn(0f, 1f)
        out[2] = (tB + (mB - mLum)).coerceIn(0f, 1f)
    }

    /**
     * Feathered disc weight: 1 inside the feather inner edge, smoothstep
     * falloff to 0 at [radiusPx]. Mirrors the mask-stroke falloff convention.
     */
    fun featherAlpha(distPx: Float, radiusPx: Float, feather: Float): Float {
        val r = radiusPx.coerceAtLeast(1e-6f)
        val f = feather.coerceIn(0f, 1f)
        if (f <= 0.001f) return if (distPx <= r) 1f else 0f
        val inner = r * (1f - f)
        if (distPx <= inner) return 1f
        if (distPx >= r) return 0f
        return 1f - GradeMath.smoothstep(inner, r, distPx)
    }

    fun mix(dst: Float, src: Float, alpha: Float): Float =
        (dst + (src - dst) * alpha.coerceIn(0f, 1f)).coerceIn(0f, 1f)

    /**
     * One onion-peel pass (§26 ERASE, beta): every masked pixel with at least
     * one unmasked 4-neighbor becomes the per-channel average of its unmasked
     * 4-neighbors. Returns true when at least one pixel filled. Operates on
     * 0..1 float channels so tests run without Bitmaps; the renderer converts.
     */
    fun onionPeelPass(
        r: FloatArray, g: FloatArray, b: FloatArray,
        mask: BooleanArray, w: Int, h: Int
    ): Boolean {
        if (w <= 0 || h <= 0) return false
        if (r.size != w * h || g.size != w * h || b.size != w * h || mask.size != w * h) return false
        val fillR = FloatArray(w * h)
        val fillG = FloatArray(w * h)
        val fillB = FloatArray(w * h)
        val fillIt = BooleanArray(w * h)
        var filled = false
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                if (!mask[i]) continue
                var sr = 0f
                var sg = 0f
                var sb = 0f
                var n = 0
                if (x > 0 && !mask[i - 1]) {
                    sr += r[i - 1]; sg += g[i - 1]; sb += b[i - 1]; n++
                }
                if (x < w - 1 && !mask[i + 1]) {
                    sr += r[i + 1]; sg += g[i + 1]; sb += b[i + 1]; n++
                }
                if (y > 0 && !mask[i - w]) {
                    sr += r[i - w]; sg += g[i - w]; sb += b[i - w]; n++
                }
                if (y < h - 1 && !mask[i + w]) {
                    sr += r[i + w]; sg += g[i + w]; sb += b[i + w]; n++
                }
                if (n > 0) {
                    fillR[i] = sr / n
                    fillG[i] = sg / n
                    fillB[i] = sb / n
                    fillIt[i] = true
                    filled = true
                }
            }
        }
        if (!filled) return false
        for (i in 0 until w * h) {
            if (fillIt[i]) {
                r[i] = fillR[i]
                g[i] = fillG[i]
                b[i] = fillB[i]
                mask[i] = false
            }
        }
        return true
    }

    /**
     * Bounded onion-peel driver: repeats [onionPeelPass] until the mask is
     * empty, a pass fills nothing, or [maxPasses] is reached. Returns passes
     * used. Callers fill any remainder with the annulus median (documented
     * fallback so large regions always complete).
     */
    fun onionPeelInpaint(
        r: FloatArray, g: FloatArray, b: FloatArray,
        mask: BooleanArray, w: Int, h: Int, maxPasses: Int
    ): Int {
        var passes = 0
        val cap = maxPasses.coerceIn(1, 256)
        while (passes < cap) {
            if (!onionPeelPass(r, g, b, mask, w, h)) break
            passes++
        }
        return passes
    }

    /**
     * Masked energy: mean absolute deviation of masked pixels from the mean
     * of unmasked pixels (per channel, averaged). Convergence tests assert
     * this shrinks across bounded passes on a synthetic step.
     */
    fun maskedEnergy(
        r: FloatArray, g: FloatArray, b: FloatArray,
        mask: BooleanArray, w: Int, h: Int
    ): Float {
        if (w <= 0 || h <= 0) return 0f
        if (r.size != w * h || mask.size != w * h) return 0f
        var sr = 0f
        var sg = 0f
        var sb = 0f
        var n = 0
        for (i in 0 until w * h) {
            if (!mask[i]) {
                sr += r[i]; sg += g[i]; sb += b[i]; n++
            }
        }
        if (n == 0) return 0f
        val mr = sr / n
        val mg = sg / n
        val mb = sb / n
        var energy = 0f
        var m = 0
        for (i in 0 until w * h) {
            if (mask[i]) {
                energy += kotlin.math.abs(r[i] - mr) +
                    kotlin.math.abs(g[i] - mg) +
                    kotlin.math.abs(b[i] - mb)
                m++
            }
        }
        if (m == 0) return 0f
        return energy / (m * 3f)
    }
}

/**
 * M8 lens-blur math (§27, pure JVM). Depth is a radial-gradient-from-focus
 * heuristic with transition control — NOT AI, no depth sensor (UI copy must
 * say so). Render blends 3 box-blur levels by depth band (cheap bokeh approx).
 */
object LensBlurMath {
    /**
     * Depth 0 at the focus point, 1 far away. Distance is aspect-corrected so
     * the protected focus area is an ellipse matching image proportions.
     * [focusRadius] 0..1 is the fully-sharp radius; [transition] 0..1 scales
     * the smooth edge width (0.05 + transition * 0.6 in aspect-corrected units
     * where ~1 spans the frame height).
     */
    fun depthAt(
        nx: Float, ny: Float,
        focusX: Float, focusY: Float,
        focusRadius: Float, transition: Float, aspect: Float
    ): Float {
        val a = aspect.let { if (it.isFinite() && it > 0f) it else 1f }
        val dx = (nx - focusX.coerceIn(0f, 1f)) * a
        val dy = ny - focusY.coerceIn(0f, 1f)
        val dist = sqrt(dx * dx + dy * dy)
        val r0 = focusRadius.coerceIn(0f, 1f)
        val edge = (0.05f + transition.coerceIn(0f, 1f) * 0.6f).coerceAtLeast(1e-3f)
        return GradeMath.smoothstep(r0, r0 + edge, dist).coerceIn(0f, 1f)
    }

    /**
     * Smooth near/mid/far weights for a depth value: near dominates below
     * 0.25, far above 0.75, mid bridges. Sums to ~1 (mid absorbs the slack).
     */
    fun bandWeights(depth: Float): FloatArray {
        val out = FloatArray(3)
        bandWeightsInto(depth, out)
        return out
    }

    /**
     * M16 allocation-free variant (§34): writes near/mid/far into [out]
     * (size >= 3), bit-identical to [bandWeights]. The lens-blur pixel loop
     * must call this with a thread-local scratch buffer.
     */
    fun bandWeightsInto(depth: Float, out: FloatArray) {
        val d = depth.coerceIn(0f, 1f)
        val near = 1f - GradeMath.smoothstep(0.25f, 0.45f, d)
        val far = GradeMath.smoothstep(0.55f, 0.75f, d)
        val mid = (1f - near - far).coerceIn(0f, 1f)
        out[0] = near.coerceIn(0f, 1f)
        out[1] = mid
        out[2] = far.coerceIn(0f, 1f)
    }

    /**
     * Downscale factor for blur [level] (1 = mild, 2 = strong) at
     * [amount] 0..100. 1 = sharp. Strong blurs more aggressively.
     */
    fun downscaleFactor(amount: Float, level: Int): Float {
        val a = (amount.coerceIn(0f, 100f) / 100f)
        return when (level) {
            1 -> (1f - a * 0.4f).coerceIn(0.05f, 1f)
            else -> (1f - a * 0.85f).coerceIn(0.05f, 1f)
        }
    }
}

/**
 * M8 dust-spot candidate (§28, pure JVM). Flags dark-dot outliers on smooth
 * bright areas: center pixel darker than its 5x5 outer-ring median by
 * [threshold], the ring bright (median above [brightFloor]) and smooth
 * (variance below [smoothVar]). The ring excludes the inner 3x3 so the dot
 * itself never trips the smoothness gate. Grid NMS + count cap keep the
 * overlay inspectable. Honest label is "dust candidates" — the user confirms
 * before any heal is created.
 */
data class DustCandidate(
    val id: String,
    val cx: Float,
    val cy: Float,
    val radius: Float,
    val confirmed: Boolean = false
)

object DustMath {
    const val MAX_CANDIDATES = 200
    const val BRIGHT_FLOOR = 0.30f
    const val SMOOTH_VAR = 0.004f

    fun thresholdFor(sensitivity: Float): Float {
        val s = sensitivity.coerceIn(0f, 100f) / 100f
        return 0.14f - s * 0.11f
    }

    fun detectCandidates(
        luma: FloatArray, w: Int, h: Int, sensitivity: Float
    ): List<DustCandidate> {
        if (w < 7 || h < 7) return emptyList()
        if (luma.size != w * h) return emptyList()
        val threshold = thresholdFor(sensitivity)
        val minDim = minOf(w, h).toFloat()
        val cell = 8
        val gridW = (w + cell - 1) / cell
        val gridH = (h + cell - 1) / cell
        val claimed = BooleanArray(gridW * gridH)
        val out = ArrayList<DustCandidate>(32)
        // M16 (§34): the 16-sample outer ring buffer is hoisted out of the
        // per-center loop (was `FloatArray(16)` per candidate center ≈ 65k
        // allocs on a 256px analysis frame). Single reuse buffer, same values.
        val ring = FloatArray(16)
        var n = 0
        // Stride 1: every pixel is evaluated as a candidate center (a
        // stride-2 scan would blind the detector to half the parities).
        // ~1M float ops at 256px — negligible next to the bitmap work.
        for (y in 3 until h - 3) {
            for (x in 3 until w - 3) {
                val i = y * w + x
                // Outer ring only (max(|dx|,|dy|) == 2): 16 samples that
                // exclude the candidate dot itself. [ring] is the hoisted
                // M16 reuse buffer (always fully rewritten below).
                var k = 0
                var sum = 0f
                for (dy in -2..2) {
                    for (dx in -2..2) {
                        if (maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy)) != 2) continue
                        val v = luma[(y + dy) * w + (x + dx)]
                        ring[k++] = v
                        sum += v
                    }
                }
                val mean = sum / 16f
                var variance = 0f
                for (v in ring) {
                    val d = v - mean
                    variance += d * d
                }
                variance /= 16f
                val median = RetouchMath.median(ring)
                val center = luma[i]
                if (median > BRIGHT_FLOOR && variance < SMOOTH_VAR &&
                    median - center > threshold
                ) {
                    val gx = x / cell
                    val gy = y / cell
                    if (!claimed[gy * gridW + gx]) {
                        claimed[gy * gridW + gx] = true
                        out.add(
                            DustCandidate(
                                id = "dust-$n",
                                cx = (x.toFloat() / w.toFloat()).coerceIn(0f, 1f),
                                cy = (y.toFloat() / h.toFloat()).coerceIn(0f, 1f),
                                radius = (4f / minDim).coerceIn(0.002f, 0.05f)
                            )
                        )
                        n++
                        if (out.size >= MAX_CANDIDATES) return out
                    }
                }
            }
        }
        return out
    }
}
