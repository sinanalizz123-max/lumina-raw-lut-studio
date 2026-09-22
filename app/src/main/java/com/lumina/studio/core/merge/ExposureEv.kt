package com.lumina.studio.core.merge

import kotlin.math.log2

/**
 * M17 exposure-spread math (pure JVM, no android.*).
 *
 * Two EV sources, used uniformly across ALL frames of one merge (never
 * mixed — mixing EXIF EVs for some frames with luma-estimated EVs for
 * others would compare incommensurable scales):
 *
 * 1. EXIF mode: every frame parses exposure time (+ISO, +f-number when
 *    present) into a relative exposure index. Only the SPREAD matters, so
 *    missing components shared by all frames cancel out; a frame with no
 *    parsable shutter speed yields null and drops the whole merge to
 *    luma mode.
 * 2. Luma mode (documented fallback): relative EV_i = log2(median_i /
 *    median_0). This assumes the scene content matches (true for brackets)
 *    and that median luma tracks exposure monotonically (true for camera
 *    JPEGs over the 0.5+ EV spreads we require; tone curves compress the
 *    scale but preserve order and rough magnitude).
 */
object ExposureEv {
    /** Parses "1/250", "0.004", "1" (seconds). Null when unparsable. */
    fun parseExposureTime(raw: String?): Double? {
        if (raw.isNullOrBlank()) return null
        val t = raw.trim()
        return try {
            if ('/' in t) {
                val parts = t.split('/')
                if (parts.size != 2) return null
                val num = parts[0].trim().toDoubleOrNull() ?: return null
                val den = parts[1].trim().toDoubleOrNull() ?: return null
                if (!num.isFinite() || !den.isFinite() || den == 0.0 || num <= 0.0) return null
                num / den
            } else {
                val v = t.toDoubleOrNull() ?: return null
                if (!v.isFinite() || v <= 0.0) return null
                v
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Parses the first finite positive number ("100", "100, 100"). */
    fun parseIso(raw: String?): Double? {
        if (raw.isNullOrBlank()) return null
        val match = Regex("""\d+(\.\d+)?""").find(raw.trim()) ?: return null
        val v = match.value.toDoubleOrNull() ?: return null
        if (!v.isFinite() || v <= 0.0) return null
        return v
    }

    /** Parses "2.8" / "f/2.8". Null when unparsable. */
    fun parseFNumber(raw: String?): Double? {
        if (raw.isNullOrBlank()) return null
        val t = raw.trim().removePrefix("f/").removePrefix("F/").trim()
        val v = t.toDoubleOrNull() ?: return null
        if (!v.isFinite() || v <= 0.0) return null
        return v
    }

    /**
     * Relative exposure index: log2(t) + log2(iso/100) - 2*log2(N).
     * Higher = brighter capture. Null unless [exposureTime] parses; ISO and
     * f-number refine when present (default ISO 100, no aperture term).
     */
    fun evIndex(exposureTime: Double?, iso: Double?, fNumber: Double?): Double? {
        if (exposureTime == null || !exposureTime.isFinite() || exposureTime <= 0.0) return null
        var ev = log2(exposureTime)
        if (iso != null && iso.isFinite() && iso > 0.0) ev += log2(iso / 100.0)
        if (fNumber != null && fNumber.isFinite() && fNumber > 0.0) ev -= 2.0 * log2(fNumber)
        if (!ev.isFinite()) return null
        return ev
    }

    fun evIndexFromExifStrings(shutter: String?, iso: String?, aperture: String?): Double? =
        evIndex(parseExposureTime(shutter), parseIso(iso), parseFNumber(aperture))

    /**
     * Luma fallback: relative EVs from per-frame median luma 0..1.
     * Medians <= 0 are clamped to 1e-6 (documented: a fully-black frame
     * still yields a finite, very negative EV instead of NaN).
     */
    fun relativeEvsFromLuma(medians: List<Float>): List<Float> {
        if (medians.isEmpty()) return emptyList()
        val ref = medians[0].coerceIn(1e-6f, 1f)
        val refLog = log2(ref.toDouble())
        return medians.map { m ->
            (log2(m.coerceIn(1e-6f, 1f).toDouble()) - refLog).toFloat()
        }
    }

    fun spread(evs: List<Double>): Double {
        if (evs.isEmpty()) return 0.0
        return evs.max() - evs.min()
    }

    fun spreadF(evs: List<Float>): Float {
        if (evs.isEmpty()) return 0f
        return evs.max() - evs.min()
    }

    /**
     * HDR bracket gate: null when the spread qualifies, else
     * [MergeError.NoExposureSpread]. Identical exposures degrade to
     * noise-averaging, which is NOT HDR — reject, don't fake it.
     */
    fun validateSpread(spreadEv: Float): MergeError? =
        if (spreadEv >= MergeLimits.MIN_EV_SPREAD) null
        else MergeError.NoExposureSpread(spreadEv, MergeLimits.MIN_EV_SPREAD)
}
