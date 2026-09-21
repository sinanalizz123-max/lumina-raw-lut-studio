package com.lumina.studio.edit

import com.lumina.studio.core.edit.CurveChannel
import com.lumina.studio.core.edit.DetailControl
import com.lumina.studio.core.edit.EditParams
import com.lumina.studio.core.edit.EditParamsJson
import com.lumina.studio.core.edit.HslAdjust
import com.lumina.studio.core.edit.HslColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 3 pure-JVM guards for HSL + Details (no android.*, no Robolectric).
 *
 * Covers: HslColor buckets, HSL defaults/clamps/resets, globalSat/Vib,
 * DetailControl defaults/clamps, JSON round-trip of new groups, and
 * Phase-2 adjust-only JSON decoding with new groups at defaults.
 *
 * Per-pixel HSL application (PreviewRenderer.applyHslPerColor) and detail
 * matrices touch android.graphics.Bitmap and are untestable on plain JVM.
 */
class HslDetailsTest {

    // ---------- HslColor buckets ----------

    @Test
    fun `nearestForHue picks correct bucket for primary hues`() {
        assertEquals(HslColor.RED, HslColor.nearestForHue(0f))
        assertEquals(HslColor.GREEN, HslColor.nearestForHue(120f))
        assertEquals(HslColor.BLUE, HslColor.nearestForHue(240f))
    }

    @Test
    fun `nearestForHue picks correct bucket for secondary centers`() {
        assertEquals(HslColor.ORANGE, HslColor.nearestForHue(30f))
        assertEquals(HslColor.YELLOW, HslColor.nearestForHue(60f))
        assertEquals(HslColor.AQUA, HslColor.nearestForHue(180f))
        assertEquals(HslColor.PURPLE, HslColor.nearestForHue(275f))
        assertEquals(HslColor.MAGENTA, HslColor.nearestForHue(315f))
    }

    @Test
    fun `nearestForHue wraps around circle`() {
        assertEquals(HslColor.RED, HslColor.nearestForHue(360f))
        assertEquals(HslColor.RED, HslColor.nearestForHue(-10f))
        assertEquals(HslColor.RED, HslColor.nearestForHue(720f))
        assertEquals(HslColor.GREEN, HslColor.nearestForHue(120f + 360f))
    }

    @Test
    fun `hslColor fromKey round-trips`() {
        assertEquals(HslColor.RED, HslColor.fromKey("red"))
        assertEquals(HslColor.GREEN, HslColor.fromKey("green"))
        assertEquals(HslColor.BLUE, HslColor.fromKey("blue"))
        assertEquals(null, HslColor.fromKey("nope"))
    }

    // ---------- HSL defaults / clamps / resets ----------

    @Test
    fun `hsl defaults all zero`() {
        val p = EditParams.DEFAULT
        assertEquals(0f, p.globalSat, 0.0f)
        assertEquals(0f, p.globalVib, 0.0f)
        for (color in HslColor.entries) {
            assertEquals("default $color", HslAdjust(), p.getHsl(color))
        }
        assertTrue(p.isHslDefault())
        assertFalse(p.hasPerColorHsl())
    }

    @Test
    fun `withHsl clamps to minus100 plus100`() {
        val p = EditParams().withHsl(
            HslColor.RED,
            HslAdjust(hue = 500f, sat = -500f, lum = 250f)
        )
        val a = p.getHsl(HslColor.RED)
        assertEquals(100f, a.hue, 0.0f)
        assertEquals(-100f, a.sat, 0.0f)
        assertEquals(100f, a.lum, 0.0f)
        // Other buckets untouched.
        assertEquals(HslAdjust(), p.getHsl(HslColor.GREEN))
    }

    @Test
    fun `withHslHue Sat Lum clamp individually`() {
        assertEquals(100f, EditParams().withHslHue(HslColor.GREEN, 999f).getHsl(HslColor.GREEN).hue, 0.0f)
        assertEquals(-100f, EditParams().withHslHue(HslColor.GREEN, -999f).getHsl(HslColor.GREEN).hue, 0.0f)
        assertEquals(100f, EditParams().withHslSat(HslColor.BLUE, 999f).getHsl(HslColor.BLUE).sat, 0.0f)
        assertEquals(-100f, EditParams().withHslSat(HslColor.BLUE, -999f).getHsl(HslColor.BLUE).sat, 0.0f)
        assertEquals(100f, EditParams().withHslLum(HslColor.AQUA, 999f).getHsl(HslColor.AQUA).lum, 0.0f)
        assertEquals(-100f, EditParams().withHslLum(HslColor.AQUA, -999f).getHsl(HslColor.AQUA).lum, 0.0f)
        assertEquals(42f, EditParams().withHslSat(HslColor.RED, 42f).getHsl(HslColor.RED).sat, 0.0f)
    }

    @Test
    fun `resetHslColor clears single bucket`() {
        val p = EditParams()
            .withHslSat(HslColor.RED, 40f)
            .withHslSat(HslColor.GREEN, 30f)
        val reset = p.resetHslColor(HslColor.RED)
        assertEquals(HslAdjust(), reset.getHsl(HslColor.RED))
        assertEquals(30f, reset.getHsl(HslColor.GREEN).sat, 0.0f)
        assertTrue(!reset.isHslDefault())
    }

    @Test
    fun `resetHslAll clears globals and per-color`() {
        val p = EditParams()
            .withHslSat(HslColor.RED, 40f)
            .withGlobalSat(20f)
            .withGlobalVib(-15f)
        assertTrue(!p.isHslDefault())
        val reset = p.resetHslAll()
        assertTrue(reset.isHslDefault())
        assertEquals(0f, reset.globalSat, 0.0f)
        assertEquals(0f, reset.globalVib, 0.0f)
    }

    @Test
    fun `globalSat and globalVib clamp`() {
        assertEquals(100f, EditParams().withGlobalSat(999f).globalSat, 0.0f)
        assertEquals(-100f, EditParams().withGlobalSat(-999f).globalSat, 0.0f)
        assertEquals(100f, EditParams().withGlobalVib(999f).globalVib, 0.0f)
        assertEquals(-100f, EditParams().withGlobalVib(-999f).globalVib, 0.0f)
        assertEquals(25f, EditParams().withGlobalSat(25f).globalSat, 0.0f)
    }

    // ---------- DetailControl ----------

    @Test
    fun `detail defaults match spec`() {
        val p = EditParams.DEFAULT
        assertEquals(0f, p.getDetail(DetailControl.SHARPEN_AMOUNT), 0.0f)
        assertEquals(25f, p.getDetail(DetailControl.SHARPEN_RADIUS), 0.0f)
        assertEquals(0f, p.getDetail(DetailControl.NR_LUMA), 0.0f)
        assertEquals(25f, p.getDetail(DetailControl.NR_COLOR), 0.0f)
        assertEquals(0f, p.getDetail(DetailControl.TEXTURE), 0.0f)
        assertEquals(0f, p.getDetail(DetailControl.CLARITY_ADV), 0.0f)
        assertEquals(0f, p.getDetail(DetailControl.DEHAZE_ADV), 0.0f)
        assertTrue(p.isDetailsDefault())
        // Enum-level defaults agree.
        assertEquals(0f, DetailControl.SHARPEN_AMOUNT.default, 0.0f)
        assertEquals(25f, DetailControl.SHARPEN_RADIUS.default, 0.0f)
        assertEquals(0f, DetailControl.NR_LUMA.default, 0.0f)
        assertEquals(25f, DetailControl.NR_COLOR.default, 0.0f)
    }

    @Test
    fun `detail clamps via withDetail`() {
        for (control in DetailControl.entries) {
            val hi = EditParams().withDetail(control, control.max + 1000f).getDetail(control)
            val lo = EditParams().withDetail(control, control.min - 1000f).getDetail(control)
            assertEquals("${control.key} hi", control.max, hi, 0.0f)
            assertEquals("${control.key} lo", control.min, lo, 0.0f)
        }
        // Spot checks per spec ranges.
        assertEquals(100f, EditParams().withDetail(DetailControl.SHARPEN_AMOUNT, 500f).sharpenAmount, 0.0f)
        assertEquals(0f, EditParams().withDetail(DetailControl.SHARPEN_RADIUS, -5f).sharpenRadius, 0.0f)
        assertEquals(0f, EditParams().withDetail(DetailControl.NR_LUMA, -1f).nrLuma, 0.0f)
        assertEquals(100f, EditParams().withDetail(DetailControl.NR_COLOR, 999f).nrColor, 0.0f)
    }

    @Test
    fun `resetDetails restores defaults`() {
        val p = EditParams()
            .withDetail(DetailControl.TEXTURE, 50f)
            .withDetail(DetailControl.SHARPEN_RADIUS, 80f)
            .withDetail(DetailControl.NR_COLOR, 90f)
        assertTrue(!p.isDetailsDefault())
        val reset = p.resetDetails()
        assertTrue(reset.isDetailsDefault())
        assertEquals(25f, reset.sharpenRadius, 0.0f)
        assertEquals(25f, reset.nrColor, 0.0f)
    }

    // ---------- JSON ----------

    @Test
    fun `json round-trip of hsl global details and curves`() {
        val p = EditParams()
            .withHsl(HslColor.RED, HslAdjust(hue = 10f, sat = -20f, lum = 30f))
            .withHslSat(HslColor.GREEN, 55f)
            .withGlobalSat(25f)
            .withGlobalVib(-10f)
            .withDetail(DetailControl.TEXTURE, 40f)
            .withDetail(DetailControl.SHARPEN_AMOUNT, 60f)
            .withDetail(DetailControl.SHARPEN_RADIUS, 80f)
            .withDetail(DetailControl.NR_LUMA, 35f)
            .withDetail(DetailControl.NR_COLOR, 70f)
        val decoded = EditParamsJson.decode(EditParamsJson.encode(p))
        assertEquals(10f, decoded.getHsl(HslColor.RED).hue, 0.001f)
        assertEquals(-20f, decoded.getHsl(HslColor.RED).sat, 0.001f)
        assertEquals(30f, decoded.getHsl(HslColor.RED).lum, 0.001f)
        assertEquals(55f, decoded.getHsl(HslColor.GREEN).sat, 0.001f)
        assertEquals(HslAdjust(), decoded.getHsl(HslColor.BLUE))
        assertEquals(25f, decoded.globalSat, 0.001f)
        assertEquals(-10f, decoded.globalVib, 0.001f)
        assertEquals(40f, decoded.texture, 0.001f)
        assertEquals(60f, decoded.sharpenAmount, 0.001f)
        assertEquals(80f, decoded.sharpenRadius, 0.001f)
        assertEquals(35f, decoded.nrLuma, 0.001f)
        assertEquals(70f, decoded.nrColor, 0.001f)
        // Per-color flag survives.
        assertTrue(decoded.hasPerColorHsl())
        assertTrue(!decoded.isHslDefault())
        assertTrue(!decoded.isDetailsDefault())
        // Curve group untouched -> still default.
        assertTrue(decoded.isCurvesDefault())
    }

    @Test
    fun `old adjust-only json decodes with new groups at defaults`() {
        // Phase-2 payload: adjusts + preset fields only, no hsl_*/curve_*/detail keys.
        val oldJson = "{\"exposure\":1.5,\"contrast\":-30.0,\"saturation\":42.0," +
            "\"presetId\":null,\"presetIntensity\":1.0}"
        val decoded = EditParamsJson.decode(oldJson)
        assertEquals(1.5f, decoded.exposure, 0.001f)
        assertEquals(-30f, decoded.contrast, 0.001f)
        assertEquals(42f, decoded.saturation, 0.001f)
        assertTrue("hsl default", decoded.isHslDefault())
        assertEquals(0f, decoded.globalSat, 0.0f)
        assertEquals(0f, decoded.globalVib, 0.0f)
        assertTrue("curves default", decoded.isCurvesDefault())
        for (channel in CurveChannel.entries) {
            assertTrue(decoded.isCurveDiagonal(channel))
        }
        assertTrue("details default", decoded.isDetailsDefault())
        assertTrue("crop default", decoded.isCropDefault())
        assertTrue("masks default", decoded.isMasksDefault())
        assertTrue("steps all enabled", decoded.steps.isAllEnabled())
    }
}
