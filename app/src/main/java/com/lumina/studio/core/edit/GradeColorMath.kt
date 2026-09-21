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
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val l = (max + min) / 2f
        if (max == min) return floatArrayOf(0f, 0f, l.coerceIn(0f, 1f))
        val d = max - min
        val s = if (l > 0.5f) d / (2f - max - min) else d / (max + min)
        var h = when (max) {
            r -> (g - b) / d + (if (g < b) 6f else 0f)
            g -> (b - r) / d + 2f
            else -> (r - g) / d + 4f
        }
        h *= 60f
        if (h < 0f) h += 360f
        return floatArrayOf(h, s.coerceIn(0f, 1f), l.coerceIn(0f, 1f))
    }

    fun hslToRgb(hDeg: Float, s: Float, l: Float): FloatArray {
        val h = (((hDeg % 360f) + 360f) % 360f) / 360f
        val sat = s.coerceIn(0f, 1f)
        val light = l.coerceIn(0f, 1f)
        if (sat == 0f) return floatArrayOf(light, light, light)
        val q = if (light < 0.5f) light * (1f + sat) else light + sat - light * sat
        val p = 2f * light - q
        return floatArrayOf(hueToRgb(p, q, h + 1f / 3f), hueToRgb(p, q, h), hueToRgb(p, q, h - 1f / 3f))
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
        val shift = (balance.coerceIn(-100f, 100f) / 100f) * 0.2f
        val l = luma.coerceIn(0f, 1f)
        val s = 1f - smoothstep(0.25f + shift, 0.6f + shift, l)
        val h = smoothstep(0.45f + shift, 0.8f + shift, l)
        var m = 1f - s - h
        if (m < 0f) m = 0f
        val sum = s + m + h
        if (sum <= 0f) return floatArrayOf(1f, 0f, 0f)
        return floatArrayOf(s / sum, m / sum, h / sum)
    }

    fun tintFor(hueDeg: Float, sat: Float): FloatArray {
        val s = sat.coerceIn(0f, 100f) / 100f
        return GradeHsl.hslToRgb(GradeAdjust.wrapHue(hueDeg), s, 0.5f)
    }

    fun zoneLift(adjust: GradeAdjust): FloatArray {
        val tint = tintFor(adjust.hue, adjust.sat)
        val k = (adjust.sat.coerceIn(0f, 100f) / 100f) * 2f
        val lum = adjust.lum.coerceIn(-100f, 100f) / 100f * 0.25f
        return floatArrayOf(
            (tint[0] - 0.5f) * k + lum,
            (tint[1] - 0.5f) * k + lum,
            (tint[2] - 0.5f) * k + lum
        )
    }

    fun applyGrade(r: Float, g: Float, b: Float, grade: GradeParams): FloatArray {
        if (grade.isDefault()) return floatArrayOf(r, g, b)
        val blend = grade.blending.coerceIn(0f, 100f) / 100f
        if (blend <= 0f) return floatArrayOf(r, g, b)
        val w = zoneWeights(lumaOf(r, g, b), grade.balance)
        val ls = zoneLift(grade.shadows)
        val lm = zoneLift(grade.midtones)
        val lh = zoneLift(grade.highlights)
        val lg = zoneLift(grade.global)
        val liftR = w[0] * ls[0] + w[1] * lm[0] + w[2] * lh[0] + lg[0]
        val liftG = w[0] * ls[1] + w[1] * lm[1] + w[2] * lh[1] + lg[1]
        val liftB = w[0] * ls[2] + w[1] * lm[2] + w[2] * lh[2] + lg[2]
        return floatArrayOf(
            (r + liftR * blend).coerceIn(0f, 1f),
            (g + liftG * blend).coerceIn(0f, 1f),
            (b + liftB * blend).coerceIn(0f, 1f)
        )
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
        if (point.isDefault()) return floatArrayOf(r, g, b)
        val hsl = GradeHsl.rgbToHsl(r, g, b)
        val w = falloffWeight(hsl[0], point.hueCenter, point.hueRange)
        if (w <= 0f) return floatArrayOf(r, g, b)
        val s = (hsl[1] * (1f + w * point.satAdjust / 100f)).coerceIn(0f, 1f)
        val l = (hsl[2] + w * point.lumAdjust * 0.0025f).coerceIn(0f, 1f)
        return GradeHsl.hslToRgb(hsl[0], s, l)
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
