package com.lumina.studio.core.render

import kotlin.math.pow

/**
 * M5 high-precision pipeline model (§10). Pure JVM (no android.*): mirrors the
 * per-pixel stage math of [PreviewRenderer] at two intermediate precisions so
 * unit tests bound FINAL-vs-PREVIEW divergence without Bitmaps.
 *
 * Precision architecture:
 * - INTERACTIVE (Preview/Thumb): sRGB-math stages with ARGB_8888
 *   intermediates. Each stage's float output is rounded to 8-bit before the
 *   next stage reads it ([previewQuantize]). Fast; golden behavior.
 * - FINAL (Export/Fullscreen/Tile): identical recipe and stage order, but
 *   intermediates are RGBA_F16 (minSdk 26). Half-float stores every 8-bit
 *   integer exactly and keeps fractional matrix-stage output that PREVIEW
 *   rounds away, so [finalKeep] passes floats through unrounded. The final
 *   store (encode/display) is still 8-bit.
 *
 * The measurable difference is therefore bounded by accumulated 8-bit
 * rounding: at most ~1/2 LSB per quantized handoff. [FINAL_PREVIEW_MAX_DELTA]
 * (3 LSB + float slack) is the CI-pinned consistency bound: the same recipe
 * on the same ramp must never diverge more than this per channel.
 */
object ColorPipeline {

    const val FINAL_PREVIEW_MAX_DELTA = 0.012f

    fun previewQuantize(v: Float): Float =
        (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt().coerceIn(0, 255) / 255f

    fun finalKeep(v: Float): Float = v.coerceIn(0f, 1f)

    fun exposureGain(ev: Float): Float =
        2f.pow(ev).coerceIn(0.1f, 8f)

    fun applyExposure(rgb: FloatArray, ev: Float): FloatArray {
        val g = exposureGain(ev)
        return floatArrayOf(rgb[0] * g, rgb[1] * g, rgb[2] * g)
    }

    fun applySaturation(rgb: FloatArray, factor: Float): FloatArray {
        val luma = 0.2126f * rgb[0] + 0.7152f * rgb[1] + 0.0722f * rgb[2]
        return floatArrayOf(
            luma + (rgb[0] - luma) * factor,
            luma + (rgb[1] - luma) * factor,
            luma + (rgb[2] - luma) * factor
        )
    }

    fun applyCurve(v: Float, lut256: FloatArray): Float {
        val idx = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt().coerceIn(0, 255)
        return lut256[idx].coerceIn(0f, 1f)
    }

    fun identityLut256(): FloatArray = FloatArray(256) { i -> i / 255f }

    fun sCurveLut256(amount: Float = 0.2f): FloatArray {
        val a = amount.coerceIn(0f, 0.5f)
        return FloatArray(256) { i ->
            val x = i / 255f
            (x + a * x * (1f - x) * (x - 0.5f) * 4f).coerceIn(0f, 1f)
        }
    }

    // One representative grade chain (exposure -> saturation -> per-channel
    // curve) at PREVIEW precision: 8-bit rounding between stages, mirroring
    // ARGB_8888 intermediates. LUT trilinear is omitted here (covered by the
    // LutExportMathTest trilinear mirror); the rounding model is identical.
    fun gradePreviewPixel(
        rgb: FloatArray,
        exposureEv: Float,
        saturation: Float,
        curve: FloatArray
    ): FloatArray {
        var r = rgb[0]
        var g = rgb[1]
        var b = rgb[2]
        val exp = applyExposure(floatArrayOf(r, g, b), exposureEv)
        r = previewQuantize(exp[0])
        g = previewQuantize(exp[1])
        b = previewQuantize(exp[2])
        val sat = applySaturation(floatArrayOf(r, g, b), saturation)
        r = previewQuantize(sat[0])
        g = previewQuantize(sat[1])
        b = previewQuantize(sat[2])
        r = previewQuantize(applyCurve(r, curve))
        g = previewQuantize(applyCurve(g, curve))
        b = previewQuantize(applyCurve(b, curve))
        return floatArrayOf(r, g, b)
    }

    // Same chain at FINAL precision: no intermediate rounding, mirroring
    // RGBA_F16 intermediates (8-bit integers exact, fractions preserved).
    // Only the returned triple is implicitly 8-bit-bound at final store.
    fun gradeFinalPixel(
        rgb: FloatArray,
        exposureEv: Float,
        saturation: Float,
        curve: FloatArray
    ): FloatArray {
        var r = rgb[0]
        var g = rgb[1]
        var b = rgb[2]
        val exp = applyExposure(floatArrayOf(r, g, b), exposureEv)
        r = finalKeep(exp[0])
        g = finalKeep(exp[1])
        b = finalKeep(exp[2])
        val sat = applySaturation(floatArrayOf(r, g, b), saturation)
        r = finalKeep(sat[0])
        g = finalKeep(sat[1])
        b = finalKeep(sat[2])
        r = finalKeep(applyCurve(r, curve))
        g = finalKeep(applyCurve(g, curve))
        b = finalKeep(applyCurve(b, curve))
        return floatArrayOf(r, g, b)
    }

    fun maxChannelDelta(a: FloatArray, b: FloatArray): Float {
        require(a.size == 3 && b.size == 3) { "need rgb triples" }
        return maxOf(
            kotlin.math.abs(a[0] - b[0]),
            kotlin.math.abs(a[1] - b[1]),
            kotlin.math.abs(a[2] - b[2])
        )
    }
}
