package com.lumina.studio.edit

import com.lumina.studio.core.edit.AdjustControl
import com.lumina.studio.core.edit.EditParams
import com.lumina.studio.core.edit.EditParamsJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 2 pure-JVM guards for EditStack (no Robolectric, no Bitmap).
 *
 * Covers: 13 defaults, with() clamping, resetControl/resetAll (preset
 * preservation), isDefault, and EditParamsJson manual-JSON round-trips
 * including quoted/escaped presetId and intensity.
 */
class EditParamsTest {

    // ---------- defaults ----------

    @Test
    fun `13 controls all default to 0`() {
        assertEquals(13, AdjustControl.entries.size)
        for (control in AdjustControl.entries) {
            assertEquals("default for ${control.key}", 0f, control.default, 0.0f)
            assertEquals("EditParams default for ${control.key}", 0f, EditParams.DEFAULT.get(control), 0.0f)
            assertEquals("fresh EditParams for ${control.key}", 0f, EditParams().get(control), 0.0f)
        }
    }

    @Test
    fun `preset fields default to null and 1f`() {
        val p = EditParams()
        assertNull(p.presetId)
        assertEquals(1f, p.presetIntensity, 0.0f)
        assertNull(EditParams.DEFAULT.presetId)
        assertEquals(1f, EditParams.DEFAULT.presetIntensity, 0.0f)
    }

    @Test
    fun `control ranges match spec`() {
        assertEquals(-5f, AdjustControl.EXPOSURE.min, 0.0f)
        assertEquals(5f, AdjustControl.EXPOSURE.max, 0.0f)
        // Sharpness is the only control floored at 0.
        assertEquals(0f, AdjustControl.SHARPNESS.min, 0.0f)
        assertEquals(100f, AdjustControl.SHARPNESS.max, 0.0f)
        for (control in AdjustControl.entries) {
            if (control == AdjustControl.EXPOSURE || control == AdjustControl.SHARPNESS) continue
            assertEquals("${control.key} min", -100f, control.min, 0.0f)
            assertEquals("${control.key} max", 100f, control.max, 0.0f)
        }
    }

    // ---------- with() clamping ----------

    @Test
    fun `with clamps exposure to minus5 plus5`() {
        assertEquals(5f, EditParams().with(AdjustControl.EXPOSURE, 99f).exposure, 0.0f)
        assertEquals(-5f, EditParams().with(AdjustControl.EXPOSURE, -99f).exposure, 0.0f)
        assertEquals(2.5f, EditParams().with(AdjustControl.EXPOSURE, 2.5f).exposure, 0.0f)
    }

    @Test
    fun `with clamps bipolar controls to minus100 plus100`() {
        assertEquals(100f, EditParams().with(AdjustControl.CONTRAST, 250f).contrast, 0.0f)
        assertEquals(-100f, EditParams().with(AdjustControl.CONTRAST, -250f).contrast, 0.0f)
        assertEquals(42f, EditParams().with(AdjustControl.SATURATION, 42f).saturation, 0.0f)
        assertEquals(-100f, EditParams().with(AdjustControl.TEMPERATURE, -500f).temperature, 0.0f)
    }

    @Test
    fun `with clamps sharpness floor at 0`() {
        assertEquals(0f, EditParams().with(AdjustControl.SHARPNESS, -10f).sharpness, 0.0f)
        assertEquals(100f, EditParams().with(AdjustControl.SHARPNESS, 500f).sharpness, 0.0f)
        assertEquals(60f, EditParams().with(AdjustControl.SHARPNESS, 60f).sharpness, 0.0f)
    }

    @Test
    fun `with is immutable and scoped to one control`() {
        val base = EditParams()
        val changed = base.with(AdjustControl.CONTRAST, 30f)
        assertEquals(0f, base.contrast, 0.0f)
        assertEquals(30f, changed.contrast, 0.0f)
        assertEquals(0f, changed.exposure, 0.0f)
        assertEquals(0f, changed.saturation, 0.0f)
    }

    @Test
    fun `every control clamps through with`() {
        for (control in AdjustControl.entries) {
            val hi = EditParams().with(control, control.max + 1000f).get(control)
            val lo = EditParams().with(control, control.min - 1000f).get(control)
            assertEquals("${control.key} hi clamp", control.max, hi, 0.0f)
            assertEquals("${control.key} lo clamp", control.min, lo, 0.0f)
        }
    }

    // ---------- reset / isDefault ----------

    @Test
    fun `resetControl restores default leaving others`() {
        val p = EditParams().with(AdjustControl.CONTRAST, 40f).with(AdjustControl.EXPOSURE, 2f)
        val reset = p.resetControl(AdjustControl.CONTRAST)
        assertEquals(0f, reset.contrast, 0.0f)
        assertEquals(2f, reset.exposure, 0.0f)
    }

    @Test
    fun `resetAll clears adjusts but preserves preset fields`() {
        val p = EditParams()
            .with(AdjustControl.CONTRAST, 50f)
            .with(AdjustControl.EXPOSURE, -2f)
            .copy(presetId = "builtin_noir", presetIntensity = 0.5f)
        val reset = p.resetAll()
        for (control in AdjustControl.entries) {
            assertEquals("reset ${control.key}", 0f, reset.get(control), 0.0f)
        }
        assertEquals("builtin_noir", reset.presetId)
        assertEquals(0.5f, reset.presetIntensity, 0.0f)
    }

    @Test
    fun `isDefault ignores preset fields`() {
        assertTrue(EditParams.DEFAULT.isDefault())
        assertTrue(EditParams().isDefault())
        assertFalse(EditParams().with(AdjustControl.CLARITY, 1f).isDefault())
        // Adjusts at default + preset set is still "default" per impl.
        assertTrue(EditParams(presetId = "builtin_mono", presetIntensity = 0.3f).isDefault())
    }

    @Test
    fun `fromKey toMap fromMap round-trip`() {
        assertEquals(AdjustControl.EXPOSURE, AdjustControl.fromKey("exposure"))
        assertNull(AdjustControl.fromKey("nope"))
        val p = EditParams().with(AdjustControl.EXPOSURE, 1.5f).with(AdjustControl.VIBRANCE, -20f)
        val map = p.toMap()
        assertEquals(13, map.size)
        val back = EditParams.fromMap(map, presetId = "x", presetIntensity = 0.7f)
        assertEquals(1.5f, back.exposure, 0.0f)
        assertEquals(-20f, back.vibrance, 0.0f)
        assertEquals("x", back.presetId)
        // fromMap clamps out-of-range entries.
        val clamped = EditParams.fromMap(mapOf("contrast" to 999f))
        assertEquals(100f, clamped.contrast, 0.0f)
    }

    // ---------- JSON ----------

    @Test
    fun `json round-trip of defaults`() {
        val encoded = EditParamsJson.encode(EditParams.DEFAULT)
        // All 13 keys plus preset fields must be present.
        for (control in AdjustControl.entries) {
            assertTrue("encoded must contain ${control.key}", encoded.contains("\"${control.key}\":"))
        }
        assertTrue(encoded.contains("\"presetId\":null"))
        assertTrue(encoded.contains("\"presetIntensity\":"))
        val decoded = EditParamsJson.decode(encoded)
        assertEquals(EditParams.DEFAULT, decoded)
    }

    @Test
    fun `json round-trip with values and intensity`() {
        val p = EditParams()
            .with(AdjustControl.EXPOSURE, 1.5f)
            .with(AdjustControl.CONTRAST, -30f)
            .with(AdjustControl.SHARPNESS, 80f)
            .copy(presetId = "builtin_golden_hour", presetIntensity = 0.65f)
        val decoded = EditParamsJson.decode(EditParamsJson.encode(p))
        assertEquals(1.5f, decoded.exposure, 0.001f)
        assertEquals(-30f, decoded.contrast, 0.001f)
        assertEquals(80f, decoded.sharpness, 0.001f)
        assertEquals("builtin_golden_hour", decoded.presetId)
        assertEquals(0.65f, decoded.presetIntensity, 0.001f)
    }

    @Test
    fun `json round-trip preserves presetId with quotes backslash and spaces`() {
        val tricky = "my \"cool\" \\ preset  spaced"
        val p = EditParams(presetId = tricky, presetIntensity = 0.9f)
        val encoded = EditParamsJson.encode(p)
        // Encoder must escape both characters.
        assertTrue(encoded.contains("\\\""))
        assertTrue(encoded.contains("\\\\"))
        val decoded = EditParamsJson.decode(encoded)
        assertEquals(tricky, decoded.presetId)
        assertEquals(0.9f, decoded.presetIntensity, 0.001f)
    }

    @Test
    fun `json null presetId round-trips`() {
        val decoded = EditParamsJson.decode(EditParamsJson.encode(EditParams(presetId = null)))
        assertNull(decoded.presetId)
    }

    @Test
    fun `json decode of blank null or garbage returns DEFAULT without throwing`() {
        assertEquals(EditParams.DEFAULT, EditParamsJson.decode(null))
        assertEquals(EditParams.DEFAULT, EditParamsJson.decode(""))
        assertEquals(EditParams.DEFAULT, EditParamsJson.decode("   "))
        assertEquals(EditParams.DEFAULT, EditParamsJson.decode("not json at all {{{"))
        // Missing keys fall back to defaults; unknown keys ignored.
        val partial = EditParamsJson.decode("{\"contrast\":25}")
        assertEquals(25f, partial.contrast, 0.001f)
        assertEquals(0f, partial.exposure, 0.0f)
    }

    @Test
    fun `json decode clamps out-of-range numbers`() {
        val decoded = EditParamsJson.decode("{\"exposure\":99,\"sharpness\":-5}")
        assertEquals(5f, decoded.exposure, 0.0f)
        assertEquals(0f, decoded.sharpness, 0.0f)
    }
}
