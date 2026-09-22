package com.lumina.studio.core.edit

import kotlin.math.log2

/**
 * M6 shared HSL helpers (pure JVM, no android.*).
 *
 * PreviewRenderer carries its own RGB<->HSL copies bound to the Bitmap path;
 * these duplicates exist so unit tests and [GradeMath]/[PointColorMath] never
 * load android.graphics classes. Same formulae, gamma-domain sRGB by design
 * (selective-color UX is defined on display-referred values; see the M5
 * gamma audit in PreviewRenderer).
 */
object GradeHsl {
    fun rgbToHsl(r: Float, g: Float, b: Float): FloatArray {
        val out = FloatArray(3)
        rgbToHslInto(r, g, b, out)
        return out
    }

    /**
     * M16 allocation-free variant (§34): writes H/S/L into [out] (size >= 3),
     * bit-identical to [rgbToHsl]. Hot pixel loops must call this with a
     * thread-local scratch buffer instead of allocating per pixel.
     */
    fun rgbToHslInto(r: Float, g: Float, b: Float, out: FloatArray) {
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val l = (max + min) / 2f
        if (max == min) {
            out[0] = 0f
            out[1] = 0f
            out[2] = l.coerceIn(0f, 1f)
            return
        }
        val d = max - min
        val s = if (l > 0.5f) d / (2f - max - min) else d / (max + min)
        var h = when (max) {
            r -> (g - b) / d + (if (g < b) 6f else 0f)
            g -> (b - r) / d + 2f
            else -> (r - g) / d + 4f
        }
        h *= 60f
        if (h < 0f) h += 360f
        out[0] = h
        out[1] = s.coerceIn(0f, 1f)
        out[2] = l.coerceIn(0f, 1f)
    }

    fun hslToRgb(hDeg: Float, s: Float, l: Float): FloatArray {
        val out = FloatArray(3)
        hslToRgbInto(hDeg, s, l, out)
        return out
    }

    /**
     * M16 allocation-free variant (§34): writes R/G/B into [out] (size >= 3),
     * bit-identical to [hslToRgb]. Hot pixel loops must call this with a
     * thread-local scratch buffer instead of allocating per pixel.
     */
    fun hslToRgbInto(hDeg: Float, s: Float, l: Float, out: FloatArray) {
        hslToRgbInto(hDeg, s, l, out, 0)
    }

    /**
     * M16 offset variant for packed scratch buffers (§34). Writes R/G/B at
     * [offset]..[offset+2], bit-identical to [hslToRgb].
     */
    fun hslToRgbInto(hDeg: Float, s: Float, l: Float, out: FloatArray, offset: Int) {
        val h = (((hDeg % 360f) + 360f) % 360f) / 360f
        val sat = s.coerceIn(0f, 1f)
        val light = l.coerceIn(0f, 1f)
        if (sat == 0f) {
            out[offset] = light
            out[offset + 1] = light
            out[offset + 2] = light
            return
        }
        val q = if (light < 0.5f) light * (1f + sat) else light + sat - light * sat
        val p = 2f * light - q
        out[offset] = hueToRgb(p, q, h + 1f / 3f)
        out[offset + 1] = hueToRgb(p, q, h)
        out[offset + 2] = hueToRgb(p, q, h - 1f / 3f)
    }

    private fun hueToRgb(p: Float, q: Float, t: Float): Float {
        var tt = t
        if (tt < 0f) tt += 1f
        if (tt > 1f) tt -= 1f
        return when {
            tt < 1f / 6f -> p + (q - p) * 6f * tt
            tt < 1f / 2f -> q
            tt < 2f / 3f -> p + (q - p) * (2f / 3f - tt) * 6f
            else -> p
        }.coerceIn(0f, 1f)
    }

    fun argbToHueDeg(argb: Int): Float {
        val r = ((argb shr 16) and 0xFF) / 255f
        val g = ((argb shr 8) and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        return rgbToHsl(r, g, b)[0]
    }
}

/**
 * M6 color grading math (§19, pure JVM).
 *
 * Per-pixel lift model: luma-zone weights (smooth shadow/mid/high split via
 * smoothstep on Rec.709 luma, [balance]-shifted) x per-zone color
 * (hue/sat -> rgb tint at L=0.5, minus neutral grey so sat=0 is a no-op) x
 * [GradeParams.blending] overall strength. The global wheel adds uniformly;
 * zone wheels add weighted by [zoneWeights]. Lum terms are small additive
 * offsets (+/-0.25 at extremes) so wheels can't clip the frame by themselves.
 * Everything is gamma-domain sRGB lift, matching the display-referred choice
 * of the existing exposure/contrast stages. PreviewRenderer calls
 * [applyGrade] per pixel; tests pin weights/tints here.
 */
object GradeMath {
    fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        if (e0 >= e1) return if (x < e0) 0f else 1f
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    fun lumaOf(r: Float, g: Float, b: Float): Float =
        0.2126f * r + 0.7152f * g + 0.0722f * b

    /**
     * [shadow, mid, high] weights summing to 1. Positive [balance] shifts both
     * pivots up so more of the frame counts as shadow; negative does the
     * reverse. smoothstep edges keep the blend C1-continuous (no banding).
     */
    fun zoneWeights(luma: Float, balance: Float = 0f): FloatArray {
        val out = FloatArray(3)
        zoneWeightsInto(luma, balance, out)
        return out
    }

    /**
     * M16 allocation-free variant (§34): writes [shadow, mid, high] into
     * [out] (size >= 3), bit-identical to [zoneWeights].
     */
    fun zoneWeightsInto(luma: Float, balance: Float, out: FloatArray) {
        val shift = (balance.coerceIn(-100f, 100f) / 100f) * 0.2f
        val l = luma.coerceIn(0f, 1f)
        val s = 1f - smoothstep(0.25f + shift, 0.6f + shift, l)
        val h = smoothstep(0.45f + shift, 0.8f + shift, l)
        var m = 1f - s - h
        if (m < 0f) m = 0f
        val sum = s + m + h
        if (sum <= 0f) {
            out[0] = 1f
            out[1] = 0f
            out[2] = 0f
            return
        }
        out[0] = s / sum
        out[1] = m / sum
        out[2] = h / sum
    }

    fun tintFor(hueDeg: Float, sat: Float): FloatArray {
        val out = FloatArray(3)
        tintForInto(hueDeg, sat, out)
        return out
    }

    /**
     * M16 allocation-free variant (§34): bit-identical to [tintFor].
     */
    fun tintForInto(hueDeg: Float, sat: Float, out: FloatArray) {
        val s = sat.coerceIn(0f, 100f) / 100f
        hslToRgbInto(GradeAdjust.wrapHue(hueDeg), s, 0.5f, out)
    }

    fun zoneLift(adjust: GradeAdjust): FloatArray {
        val out = FloatArray(3)
        zoneLiftInto(adjust, out)
        return out
    }

    /**
     * M16 allocation-free variant (§34): bit-identical to [zoneLift].
     * Uses the caller-provided [scratch] (size >= 3) for the tint lookup so
     * no temporary array is allocated.
     */
    fun zoneLiftInto(adjust: GradeAdjust, out: FloatArray, scratch: FloatArray = FloatArray(3)) {
        tintForInto(adjust.hue, adjust.sat, scratch)
        val k = (adjust.sat.coerceIn(0f, 100f) / 100f) * 2f
        val lum = adjust.lum.coerceIn(-100f, 100f) / 100f * 0.25f
        out[0] = (scratch[0] - 0.5f) * k + lum
        out[1] = (scratch[1] - 0.5f) * k + lum
        out[2] = (scratch[2] - 0.5f) * k + lum
    }

    fun applyGrade(r: Float, g: Float, b: Float, grade: GradeParams): FloatArray {
        val out = FloatArray(3)
        applyGradeInto(r, g, b, grade, out)
        return out
    }

    /**
     * M16 allocation-free variant (§34): writes the graded pixel into [out]
     * (size >= 3), bit-identical to [applyGrade]. [scratch] (size >= 18:
     * 3 weights + 4x3 lifts + 3 tint temp) backs the intermediate zone math
     * so a hot per-pixel loop allocates nothing. Callers must provide a
     * thread-local scratch; the default allocates (parity path for tests).
     */
    fun applyGradeInto(
        r: Float, g: Float, b: Float, grade: GradeParams, out: FloatArray,
        scratch: FloatArray = FloatArray(18)
    ) {
        if (grade.isDefault()) {
            out[0] = r
            out[1] = g
            out[2] = b
            return
        }
        val blend = grade.blending.coerceIn(0f, 100f) / 100f
        if (blend <= 0f) {
            out[0] = r
            out[1] = g
            out[2] = b
            return
        }
        // Layout: [0..2] weights, [3..5] shadow lift, [6..8] mid, [9..11]
        // highlight, [12..14] global, [15..17] tint temp. Single buffer,
        // zero per-pixel allocs.
        zoneWeightsInto(lumaOf(r, g, b), grade.balance, scratch)
        val w0 = scratch[0]
        val w1 = scratch[1]
        val w2 = scratch[2]
        zoneLiftInto(grade.shadows, scratch, scratch, base = 3, tintBase = 15)
        zoneLiftInto(grade.midtones, scratch, scratch, base = 6, tintBase = 15)
        zoneLiftInto(grade.highlights, scratch, scratch, base = 9, tintBase = 15)
        zoneLiftInto(grade.global, scratch, scratch, base = 12, tintBase = 15)
        val liftR = w0 * scratch[3] + w1 * scratch[6] + w2 * scratch[9] + scratch[12]
        val liftG = w0 * scratch[4] + w1 * scratch[7] + w2 * scratch[10] + scratch[13]
        val liftB = w0 * scratch[5] + w1 * scratch[8] + w2 * scratch[11] + scratch[14]
        out[0] = (r + liftR * blend).coerceIn(0f, 1f)
        out[1] = (g + liftG * blend).coerceIn(0f, 1f)
        out[2] = (b + liftB * blend).coerceIn(0f, 1f)
    }

    private fun zoneLiftInto(adjust: GradeAdjust, out: FloatArray, buf: FloatArray, base: Int, tintBase: Int = 15) {
        val hue = GradeAdjust.wrapHue(adjust.hue)
        val s = adjust.sat.coerceIn(0f, 100f) / 100f
        hslToRgbInto(hue, s, 0.5f, buf, tintBase)
        val k = (adjust.sat.coerceIn(0f, 100f) / 100f) * 2f
        val lum = adjust.lum.coerceIn(-100f, 100f) / 100f * 0.25f
        out[base] = (buf[tintBase] - 0.5f) * k + lum
        out[base + 1] = (buf[tintBase + 1] - 0.5f) * k + lum
        out[base + 2] = (buf[tintBase + 2] - 0.5f) * k + lum
    }
}

/**
 * M6 point color math (§18, pure JVM). Hue-distance falloff: full effect
 * inside 40% of [PointColorParams.hueRange], smoothstep feather to zero at
 * the edge. Sat applies multiplicatively (matches the HSL stage scale),
 * lum additively at the same 0.0025/unit scale PreviewRenderer uses, so a
 * +100 point-lum equals a +100 HSL-lum on a fully-selected pixel.
 */
object PointColorMath {
    fun hueDistance(aDeg: Float, bDeg: Float): Float {
        var d = kotlin.math.abs(aDeg - bDeg) % 360f
        if (d > 180f) d = 360f - d
        return d
    }

    fun falloffWeight(hueDeg: Float, centerDeg: Float, rangeDeg: Float): Float {
        val range = rangeDeg.coerceIn(PointColorParams.MIN_RANGE, PointColorParams.MAX_RANGE)
        val d = hueDistance(hueDeg, centerDeg)
        if (d >= range) return 0f
        if (d <= 0f) return 1f
        return 1f - GradeMath.smoothstep(range * 0.4f, range, d)
    }

    fun applyPoint(r: Float, g: Float, b: Float, point: PointColorParams): FloatArray {
        val out = FloatArray(3)
        applyPointInto(r, g, b, point, out)
        return out
    }

    /**
     * M16 allocation-free variant (§34): writes the point-corrected pixel
     * into [out] (size >= 3), bit-identical to [applyPoint]. [scratch]
     * (size >= 3) backs the HSL round-trip so a hot per-pixel loop allocates
     * nothing. The default allocates (parity path for tests).
     */
    fun applyPointInto(
        r: Float, g: Float, b: Float, point: PointColorParams, out: FloatArray,
        scratch: FloatArray = FloatArray(3)
    ) {
        if (point.isDefault()) {
            out[0] = r
            out[1] = g
            out[2] = b
            return
        }
        GradeHsl.rgbToHslInto(r, g, b, scratch)
        val hue = scratch[0]
        val sat0 = scratch[1]
        val lum0 = scratch[2]
        val w = falloffWeight(hue, point.hueCenter, point.hueRange)
        if (w <= 0f) {
            out[0] = r
            out[1] = g
            out[2] = b
            return
        }
        val s = (sat0 * (1f + w * point.satAdjust / 100f)).coerceIn(0f, 1f)
        val l = (lum0 + w * point.lumAdjust * 0.0025f).coerceIn(0f, 1f)
        GradeHsl.hslToRgbInto(hue, s, l, out)
    }
}

/**
 * M6 Light Auto (§15, pure JVM). Honest auto-levels, not AI: per-channel
 * robust min/max analysis (2nd/98th percentiles, outlier-proof) on the
 * preview-size histogram, mapped to the pipeline's gamma-domain knobs.
 * Exposure centers the average channel median on mid-grey; contrast stretches
 * the leftover headroom (full-range frames suggest ~0). Color untouched.
 */
object AutoLevels {
    const val LO_FRAC = 0.02f
    const val HI_FRAC = 0.98f
    const val MID_TARGET = 0.46f

    data class Suggestion(val exposure: Float, val contrast: Float) {
        fun isZero(): Boolean = exposure == 0f && contrast == 0f
    }

    fun channelBounds(channel: IntArray): Pair<Float, Float> {
        val bins = channel.size
        if (bins <= 0) return 0f to 1f
        var total = 0L
        for (c in channel) total += c.toLong().coerceAtLeast(0L)
        if (total <= 0L) return 0f to 1f
        val lo = quantile(channel, bins, total, LO_FRAC)
        val hi = quantile(channel, bins, total, HI_FRAC)
        return if (hi <= lo) lo to lo else lo to hi
    }

    fun channelMedian(channel: IntArray): Float {
        val bins = channel.size
        if (bins <= 0) return 0.5f
        var total = 0L
        for (c in channel) total += c.toLong().coerceAtLeast(0L)
        if (total <= 0L) return 0.5f
        return quantile(channel, bins, total, 0.5f)
    }

    private fun quantile(channel: IntArray, bins: Int, total: Long, frac: Float): Float {
        val target = (total * frac).toLong().coerceAtLeast(1L)
        var acc = 0L
        for (i in channel.indices) {
            acc += channel[i].toLong().coerceAtLeast(0L)
            if (acc >= target) return (i + 1).toFloat() / bins.toFloat()
        }
        return 1f
    }

    fun suggest(histogram: Array<IntArray>): Suggestion {
        if (histogram.size < 3) return Suggestion(0f, 0f)
        val chans = listOf(histogram[0], histogram[1], histogram[2])
        if (chans.any { it.isEmpty() }) return Suggestion(0f, 0f)
        var grandTotal = 0L
        for (ch in chans) for (c in ch) grandTotal += c.toLong().coerceAtLeast(0L)
        if (grandTotal <= 0L) return Suggestion(0f, 0f)
        var loSum = 0f
        var hiSum = 0f
        var midSum = 0f
        for (ch in chans) {
            val (lo, hi) = channelBounds(ch)
            loSum += lo
            hiSum += hi
            midSum += channelMedian(ch)
        }
        val lo = loSum / 3f
        val hi = hiSum / 3f
        val mid = midSum / 3f
        val range = hi - lo
        if (range < 0.02f) return Suggestion(0f, 0f)
        val exposure = if (mid <= 1e-3f) {
            2f
        } else {
            log2((MID_TARGET / mid).toDouble().coerceIn(0.25, 4.0)).toFloat()
        }.coerceIn(-2f, 2f)
        val contrast = ((0.96f - range) * 80f).coerceIn(0f, 60f)
        return Suggestion(exposure, contrast)
    }

    /** Test helper: folding 0..255 samples into a 64-bin channel histogram. */
    fun channelFromSamples(samples: IntArray, bins: Int = 64): IntArray {
        val out = IntArray(bins)
        if (bins <= 0) return out
        for (v in samples) {
            val bin = ((v.coerceIn(0, 255) * bins) shr 8).coerceIn(0, bins - 1)
            out[bin]++
        }
        return out
    }
}
