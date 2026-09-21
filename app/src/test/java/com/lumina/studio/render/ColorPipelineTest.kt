package com.lumina.studio.render

import com.lumina.studio.core.export.ExportColorSpace
import com.lumina.studio.core.render.ColorMatrices
import com.lumina.studio.core.render.ColorPipeline
import com.lumina.studio.core.render.RenderTarget
import com.lumina.studio.core.render.qualityForTarget
import com.lumina.studio.core.render.RenderQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * M5 pure-JVM guards for genuine color management + the high-precision
 * pipeline (no android.*, no Robolectric, no Bitmap).
 *
 * Covers: Display P3 round-trip tolerance on sample colors including skin
 * tones and reds, sRGB transfer-function invertibility, cached-table parity
 * with the float path (≤1 LSB), Final-vs-Preview consistency bounds on small
 * test ramps via the [ColorPipeline] math helpers (not pixels), the
 * no-P3-on-unsupported gate ([ExportColorSpace.coerceForDisplay] + fromKey),
 * and the target->quality mapping. Bitmap-touching CPU paths
 * (CpuColorManager.convertP3, PreviewRenderer F16 intermediates,
 * Exporter.withColorSpace) stay on-device-only.
 */
class ColorPipelineTest {

    // ---------- P3 round-trip (sRGB -> P3 -> sRGB) ----------

    @Test
    fun `p3 round-trip stays within epsilon including skin tones and reds`() {
        val samples = listOf(
            floatArrayOf(0f, 0f, 0f),
            floatArrayOf(1f, 1f, 1f),
            floatArrayOf(1f, 0f, 0f),
            floatArrayOf(0f, 1f, 0f),
            floatArrayOf(0f, 0f, 1f),
            floatArrayOf(0.5f, 0.5f, 0.5f),
            // Skin tones.
            floatArrayOf(0.85f, 0.65f, 0.55f),
            floatArrayOf(0.72f, 0.48f, 0.38f),
            floatArrayOf(0.45f, 0.28f, 0.22f),
            // Reds (wide-gamut stress axis).
            floatArrayOf(0.8f, 0.1f, 0.1f),
            floatArrayOf(0.9f, 0.1f, 0.4f),
            floatArrayOf(0.6f, 0.05f, 0.05f),
            floatArrayOf(0.2f, 0.6f, 0.9f)
        )
        for (rgb in samples) {
            val p3 = ColorMatrices.srgbToDisplayP3(rgb)
            for (v in p3) assertTrue("p3 $v in 0..1", v.isFinite() && v in 0f..1f)
            val back = ColorMatrices.displayP3ToSrgb(p3)
            for (c in 0..2) {
                val delta = abs(rgb[c] - back[c])
                assertTrue(
                    "channel $c of ${rgb.toList()} delta $delta",
                    delta <= ColorMatrices.P3_ROUNDTRIP_EPS
                )
            }
        }
    }

    // ---------- Transfer-function invertibility ----------

    @Test
    fun `srgb transfer inverts within epsilon`() {
        val steps = listOf(0f, 0.01f, 0.04f, 0.04045f, 0.1f, 0.25f, 0.5f, 0.75f, 1f)
        for (c in steps) {
            val back = ColorMatrices.linearToSrgb(ColorMatrices.srgbToLinear(c))
            assertTrue(
                "transfer($c) delta ${abs(c - back)}",
                abs(c - back) <= ColorMatrices.TRANSFER_INVERT_EPS
            )
        }
        // Full 8-bit sweep: every encodable code value round-trips.
        var worst = 0f
        for (i in 0..255) {
            val c = i / 255f
            val back = ColorMatrices.linearToSrgb(ColorMatrices.srgbToLinear(c))
            worst = maxOf(worst, abs(c - back))
        }
        assertTrue("worst 8-bit transfer delta $worst", worst <= ColorMatrices.TRANSFER_INVERT_EPS)
    }

    // ---------- Cached-table parity (≤1 LSB) ----------

    @Test
    fun `fast linearize table matches formula exactly`() {
        for (i in 0..255) {
            assertEquals(
                "srgb8 table[$i]",
                ColorMatrices.srgbToLinear(i / 255f),
                ColorMatrices.srgb8ToLinearFast(i),
                0f
            )
        }
    }

    @Test
    fun `fast 8-bit p3 path matches float path within one lsb`() {
        val samples = listOf(
            Triple(0, 0, 0), Triple(255, 255, 255),
            Triple(255, 0, 0), Triple(0, 255, 0), Triple(0, 0, 255),
            Triple(217, 166, 140), Triple(184, 122, 97),
            Triple(204, 26, 26), Triple(128, 128, 128), Triple(51, 153, 230)
        )
        for ((r, g, b) in samples) {
            val fast = ColorMatrices.srgb8ToP38(r, g, b)
            val floatPath = ColorMatrices.srgbToDisplayP3(
                floatArrayOf(r / 255f, g / 255f, b / 255f)
            )
            val ref = intArrayOf(
                (floatPath[0] * 255f + 0.5f).toInt().coerceIn(0, 255),
                (floatPath[1] * 255f + 0.5f).toInt().coerceIn(0, 255),
                (floatPath[2] * 255f + 0.5f).toInt().coerceIn(0, 255)
            )
            for (c in 0..2) {
                assertTrue(
                    "rgb($r,$g,$b) ch$c fast=${fast[c]} ref=${ref[c]}",
                    abs(fast[c] - ref[c]) <= 1
                )
            }
        }
    }

    // ---------- Final-vs-Preview consistency (math helpers, not pixels) ----------

    @Test
    fun `identity recipe has zero final-preview divergence`() {
        val curve = ColorPipeline.identityLut256()
        val ramp = grayRamp()
        for (rgb in ramp) {
            val preview = ColorPipeline.gradePreviewPixel(rgb, 0f, 1f, curve)
            val final = ColorPipeline.gradeFinalPixel(rgb, 0f, 1f, curve)
            assertEquals(0f, ColorPipeline.maxChannelDelta(preview, final), 1e-6f)
        }
    }

    @Test
    fun `graded ramps stay within documented final-preview bound`() {
        val curves = listOf(
            ColorPipeline.identityLut256(),
            ColorPipeline.sCurveLut256(0.2f)
        )
        val recipes = listOf(
            Triple(1f, 1.2f, 0),
            Triple(-1f, 0.8f, 1),
            Triple(2f, 1.5f, 1),
            Triple(0.5f, 1f, 0)
        )
        var worst = 0f
        for ((ev, sat, ci) in recipes) {
            val curve = curves[ci]
            for (rgb in gradedRampSamples()) {
                val preview = ColorPipeline.gradePreviewPixel(rgb, ev, sat, curve)
                val final = ColorPipeline.gradeFinalPixel(rgb, ev, sat, curve)
                for (v in preview + final) assertTrue("finite $v", v.isFinite())
                worst = maxOf(worst, ColorPipeline.maxChannelDelta(preview, final))
            }
        }
        assertTrue(
            "worst final-preview delta $worst exceeds ${ColorPipeline.FINAL_PREVIEW_MAX_DELTA}",
            worst <= ColorPipeline.FINAL_PREVIEW_MAX_DELTA
        )
    }

    private fun grayRamp(): List<FloatArray> =
        (0..255 step 17).map { i ->
            val v = i / 255f
            floatArrayOf(v, v, v)
        }

    private fun gradedRampSamples(): List<FloatArray> {
        val out = ArrayList<FloatArray>()
        out.addAll(grayRamp())
        for (i in 0..255 step 17) {
            val v = i / 255f
            out.add(floatArrayOf(v, 0.15f, 0.1f))
            out.add(floatArrayOf(0.1f, v, 0.15f))
            out.add(floatArrayOf(0.85f * v + 0.05f, 0.65f * v + 0.05f, 0.55f * v + 0.05f))
        }
        return out
    }

    // ---------- no-P3-on-unsupported gate ----------

    @Test
    fun `fromKey plus display gate never offer p3 without wide gamut`() {
        // Garbage/unknown keys coerce to sRGB at parse time.
        assertEquals(ExportColorSpace.SRGB, ExportColorSpace.fromKey("AdobeRGB"))
        assertEquals(ExportColorSpace.SRGB, ExportColorSpace.fromKey(null))
        // Supported display keeps an explicit P3 request.
        assertEquals(
            ExportColorSpace.DISPLAY_P3,
            ExportColorSpace.coerceForDisplay(ExportColorSpace.DISPLAY_P3, true)
        )
        // Unsupported display coerces P3 back to sRGB.
        assertEquals(
            ExportColorSpace.SRGB,
            ExportColorSpace.coerceForDisplay(ExportColorSpace.DISPLAY_P3, false)
        )
        // sRGB passes through on both display classes.
        assertEquals(
            ExportColorSpace.SRGB,
            ExportColorSpace.coerceForDisplay(ExportColorSpace.SRGB, true)
        )
        assertEquals(
            ExportColorSpace.SRGB,
            ExportColorSpace.coerceForDisplay(ExportColorSpace.SRGB, false)
        )
    }

    // ---------- target -> quality mapping ----------

    @Test
    fun `interactive targets preview high-res targets finalize`() {
        assertEquals(RenderQuality.PREVIEW, qualityForTarget(RenderTarget.Preview(1600)))
        assertEquals(RenderQuality.PREVIEW, qualityForTarget(RenderTarget.Thumb))
        assertEquals(RenderQuality.FINAL, qualityForTarget(RenderTarget.Fullscreen(4096)))
        assertEquals(RenderQuality.FINAL, qualityForTarget(RenderTarget.Tile(1_500_000L)))
        assertEquals(RenderQuality.FINAL, qualityForTarget(RenderTarget.Export(4000, 3000)))
    }
}
