package com.lumina.studio.edit

import com.lumina.studio.core.edit.AutoLevels
import com.lumina.studio.core.edit.EditParams
import com.lumina.studio.core.edit.EditParamsJson
import com.lumina.studio.core.edit.GradeAdjust
import com.lumina.studio.core.edit.GradeMath
import com.lumina.studio.core.edit.GradeParams
import com.lumina.studio.core.edit.GradeZone
import com.lumina.studio.core.edit.PointColorMath
import com.lumina.studio.core.edit.PointColorParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * M6 pure-JVM guards for color grading + point color + Auto levels
 * (no android.*, no Robolectric).
 *
 * Covers: zone-weight partition-of-unity + smoothness, hue->tint primaries,
 * grade early-out identity, JSON round-trip + pre-M6 defaults, point-color
 * hue falloff, and auto-levels direction on synthetic ramps. Per-pixel
 * Bitmap loops (PreviewRenderer.applyGrading/applyPointColor) stay
 * on-device-only; they delegate to the pure math pinned here.
 */
class GradePointColorTest {

    // ---------- zone weights ----------

    @Test
    fun `zone weights sum to one across luma and balance`() {
        for (balance in listOf(-100f, -50f, 0f, 50f, 100f)) {
            var luma = 0f
            while (luma <= 1.001f) {
                val w = GradeMath.zoneWeights(luma, balance)
                assertEquals(3, w.size)
                for (v in w) {
                    assertTrue("weight $v in 0..1 at luma $luma", v.isFinite() && v in 0f..1f)
                }
                assertEquals(
                    "weights sum at luma=$luma balance=$balance",
                    1f, w[0] + w[1] + w[2], 1e-5f
                )
                luma += 0.01f
            }
        }
    }

    @Test
    fun `zone weights are smooth without steps`() {
        for (balance in listOf(-100f, 0f, 100f)) {
            var luma = 0f
            while (luma < 1f) {
                val a = GradeMath.zoneWeights(luma, balance)
                val b = GradeMath.zoneWeights((luma + 0.005f).coerceAtMost(1f), balance)
                for (k in 0..2) {
                    assertTrue(
                        "smooth zone $k at $luma: ${a[k]} -> ${b[k]}",
                        abs(a[k] - b[k]) < 0.08f
                    )
                }
                luma += 0.005f
            }
        }
    }

    @Test
    fun `zone weights favor the right zone at extremes and middle`() {
        val dark = GradeMath.zoneWeights(0f, 0f)
        assertTrue("shadow ${dark[0]} dominates black", dark[0] > 0.99f)
        val bright = GradeMath.zoneWeights(1f, 0f)
        assertTrue("highlight ${bright[2]} dominates white", bright[2] > 0.99f)
        val mid = GradeMath.zoneWeights(0.5f, 0f)
        assertTrue("mid ${mid[1]} dominates midtone", mid[1] > 0.5f)
    }

    // ---------- tint ----------

    @Test
    fun `tint maps primary hues to rgb`() {
        val red = GradeMath.tintFor(0f, 100f)
        assertEquals(1f, red[0], 0.01f)
        assertEquals(0f, red[1], 0.01f)
        assertEquals(0f, red[2], 0.01f)
        val green = GradeMath.tintFor(120f, 100f)
        assertEquals(0f, green[0], 0.01f)
        assertEquals(1f, green[1], 0.01f)
        assertEquals(0f, green[2], 0.01f)
        val blue = GradeMath.tintFor(240f, 100f)
        assertEquals(0f, blue[0], 0.01f)
        assertEquals(0f, blue[1], 0.01f)
        assertEquals(1f, blue[2], 0.01f)
    }

    @Test
    fun `zero saturation tint is neutral grey`() {
        val grey = GradeMath.tintFor(200f, 0f)
        assertEquals(0.5f, grey[0], 1e-5f)
        assertEquals(0.5f, grey[1], 1e-5f)
        assertEquals(0.5f, grey[2], 1e-5f)
    }

    // ---------- grade pixel ----------

    @Test
    fun `grade pixel is identity when default or blending zero`() {
        val rgb = floatArrayOf(0.2f, 0.4f, 0.6f)
        val idle = GradeMath.applyGrade(rgb[0], rgb[1], rgb[2], GradeParams())
        assertEquals(rgb[0], idle[0], 0f)
        assertEquals(rgb[1], idle[1], 0f)
        assertEquals(rgb[2], idle[2], 0f)
        val zeroBlend = GradeParams(
            shadows = GradeAdjust(hue = 0f, sat = 100f, lum = 0f),
            blending = 0f
        )
        val same = GradeMath.applyGrade(rgb[0], rgb[1], rgb[2], zeroBlend)
        assertEquals(rgb[0], same[0], 1e-6f)
        assertEquals(rgb[1], same[1], 1e-6f)
        assertEquals(rgb[2], same[2], 1e-6f)
    }

    @Test
    fun `red shadow tint lifts red in dark pixels`() {
        val grade = GradeParams(
            shadows = GradeAdjust(hue = 0f, sat = 100f, lum = 0f),
            blending = 100f
        )
        val out = GradeMath.applyGrade(0.2f, 0.2f, 0.2f, grade)
        assertTrue("red lifted: ${out.toList()}", out[0] > 0.2f)
        assertTrue("red leads green: ${out.toList()}", out[0] > out[1])
        for (v in out) assertTrue("finite in range $v", v.isFinite() && v in 0f..1f)
    }

    // ---------- grade model ----------

    @Test
    fun `grade clamps wraps and resets`() {
        val p = EditParams()
            .withGradeSat(GradeZone.SHADOWS, 500f)
            .withGradeLum(GradeZone.HIGHLIGHTS, -500f)
            .withGradeHue(GradeZone.MIDTONES, -10f)
            .withGradeBlending(999f)
            .withGradeBalance(-999f)
        assertEquals(100f, p.getGrade(GradeZone.SHADOWS).sat, 0f)
        assertEquals(-100f, p.getGrade(GradeZone.HIGHLIGHTS).lum, 0f)
        assertEquals(350f, p.getGrade(GradeZone.MIDTONES).hue, 0f)
        assertEquals(100f, p.grade.blending, 0f)
        assertEquals(-100f, p.grade.balance, 0f)
        assertFalse(p.isGradeDefault())
        assertFalse(p.isDefault())
        val resetZone = p.resetGradeZone(GradeZone.SHADOWS)
        assertEquals(GradeAdjust(), resetZone.getGrade(GradeZone.SHADOWS))
        assertTrue(resetZone.resetGradeAll().isGradeDefault())
    }

    // ---------- JSON ----------

    @Test
    fun `json round-trip of grade and point color`() {
        val p = EditParams()
            .withGrade(GradeZone.SHADOWS, GradeAdjust(hue = 210f, sat = 40f, lum = -10f))
            .withGrade(GradeZone.HIGHLIGHTS, GradeAdjust(hue = 45f, sat = 60f, lum = 20f))
            .withGradeBlending(75f)
            .withGradeBalance(-30f)
            .withPointSample(0xFFCC3344.toInt(), 350f)
            .withPointHueRange(90f)
            .withPointSat(50f)
            .withPointLum(-25f)
        val decoded = EditParamsJson.decode(EditParamsJson.encode(p))
        assertEquals(p, decoded)
        assertEquals(210f, decoded.getGrade(GradeZone.SHADOWS).hue, 0.001f)
        assertEquals(75f, decoded.grade.blending, 0.001f)
        assertTrue(decoded.pointColor.enabled)
        assertEquals(0xFFCC3344.toInt(), decoded.pointColor.sampledRgb)
        assertEquals(90f, decoded.pointColor.hueRange, 0.001f)
    }

    @Test
    fun `old adjust-only json decodes with grade and point defaults`() {
        val oldJson = "{\"exposure\":1.5,\"contrast\":-30.0,\"saturation\":42.0," +
            "\"presetId\":null,\"presetIntensity\":1.0}"
        val decoded = EditParamsJson.decode(oldJson)
        assertTrue(decoded.isGradeDefault())
        assertTrue(decoded.isPointColorDefault())
        assertEquals(50f, decoded.grade.blending, 0f)
        assertEquals(0f, decoded.grade.balance, 0f)
        assertEquals(60f, decoded.pointColor.hueRange, 0f)
        assertEquals(false, decoded.pointColor.enabled)
        assertEquals(null, decoded.pointColor.sampledRgb)
    }

    @Test
    fun `point color clamps range and wraps hue`() {
        val p = EditParams()
            .withPointHueRange(500f)
            .withPointHueCenter(-30f)
            .withPointSat(999f)
        assertEquals(180f, p.pointColor.hueRange, 0f)
        assertEquals(330f, p.pointColor.hueCenter, 0f)
        assertEquals(100f, p.pointColor.satAdjust, 0f)
    }

    // ---------- point falloff ----------

    @Test
    fun `hue distance wraps around the circle`() {
        assertEquals(20f, PointColorMath.hueDistance(350f, 10f), 1e-4f)
        assertEquals(0f, PointColorMath.hueDistance(120f, 120f), 1e-4f)
        assertEquals(180f, PointColorMath.hueDistance(0f, 180f), 1e-4f)
    }

    @Test
    fun `falloff weight is one at center zero at edge and monotonic`() {
        val center = 10f
        val range = 60f
        assertEquals(1f, PointColorMath.falloffWeight(center, center, range), 0f)
        assertEquals(0f, PointColorMath.falloffWeight(center + range, center, range), 0f)
        assertEquals(0f, PointColorMath.falloffWeight(center + range + 20f, center, range), 0f)
        var prev = 2f
        var d = 0f
        while (d <= range) {
            val w = PointColorMath.falloffWeight(center + d, center, range)
            assertTrue("weight $w in 0..1 at d=$d", w in 0f..1f)
            assertTrue("monotonic at d=$d: $w <= $prev", w <= prev + 1e-6f)
            prev = w
            d += 5f
        }
        assertTrue("mid-range partial", PointColorMath.falloffWeight(center + range * 0.7f, center, range) in 0f..1f)
    }

    @Test
    fun `point pixel is identity when disabled or outside range`() {
        val rgb = floatArrayOf(0.8f, 0.1f, 0.1f)
        val disabled = PointColorParams(enabled = false, satAdjust = 100f)
        val idle = PointColorMath.applyPoint(rgb[0], rgb[1], rgb[2], disabled)
        assertEquals(rgb[0], idle[0], 0f)
        val redOnly = PointColorParams(
            enabled = true, hueCenter = 0f, hueRange = 30f, satAdjust = 100f
        )
        val blue = PointColorMath.applyPoint(0.1f, 0.1f, 0.8f, redOnly)
        assertEquals(0.1f, blue[0], 1e-3f)
        assertEquals(0.1f, blue[1], 1e-3f)
        assertEquals(0.8f, blue[2], 1e-3f)
    }

    @Test
    fun `point pixel boosts selected hue only`() {
        val redOnly = PointColorParams(
            enabled = true, hueCenter = 0f, hueRange = 60f, satAdjust = 100f
        )
        val before = floatArrayOf(0.6f, 0.2f, 0.2f)
        val after = PointColorMath.applyPoint(before[0], before[1], before[2], redOnly)
        assertFalse(
            "red pixel changes: ${before.toList()} -> ${after.toList()}",
            abs(after[0] - before[0]) < 1e-4f &&
                abs(after[1] - before[1]) < 1e-4f &&
                abs(after[2] - before[2]) < 1e-4f
        )
        for (v in after) assertTrue("finite in range $v", v.isFinite() && v in 0f..1f)
    }

    // ---------- auto levels ----------

    private fun grayHistogram(values: IntArray): Array<IntArray> {
        val channel = AutoLevels.channelFromSamples(values)
        return arrayOf(channel.copyOf(), channel.copyOf(), channel.copyOf())
    }

    @Test
    fun `auto levels stays near zero on a full-range ramp`() {
        val full = IntArray(256) { it }
        val suggestion = AutoLevels.suggest(grayHistogram(full))
        assertTrue("exposure ${suggestion.exposure} near zero", abs(suggestion.exposure) <= 0.25f)
        assertTrue("contrast ${suggestion.contrast} near zero", suggestion.contrast <= 5f)
    }

    @Test
    fun `auto levels brightens a dark ramp`() {
        val dark = IntArray(51) { it }
        val suggestion = AutoLevels.suggest(grayHistogram(dark))
        assertTrue("exposure ${suggestion.exposure} positive", suggestion.exposure > 1f)
        assertTrue("contrast ${suggestion.contrast} stretches", suggestion.contrast > 20f)
    }

    @Test
    fun `auto levels ignores empty histograms`() {
        val empty = Array(3) { IntArray(64) }
        val suggestion = AutoLevels.suggest(empty)
        assertTrue(suggestion.isZero())
    }
}
