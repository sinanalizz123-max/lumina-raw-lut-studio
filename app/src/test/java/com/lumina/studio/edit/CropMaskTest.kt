package com.lumina.studio.edit

import com.lumina.studio.core.edit.CropParams
import com.lumina.studio.core.edit.CropRatio
import com.lumina.studio.core.edit.EditMask
import com.lumina.studio.core.edit.EditParams
import com.lumina.studio.core.edit.EditParamsJson
import com.lumina.studio.core.edit.MaskPoint
import com.lumina.studio.core.edit.MaskTool
import com.lumina.studio.core.edit.StepKey
import com.lumina.studio.core.edit.StepsEnabled
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 3 pure-JVM guards for Crop + Masks + Steps (no android.*, no Robolectric).
 *
 * Covers: CropParams defaults/wrap/clamps/aspects, EditMask defaults/clamps/
 * points cap, MAX_MASKS enforcement, StepsEnabled toggles, JSON round-trip of
 * crop+masks+steps, and Phase-2 adjust-only JSON decoding with new groups
 * at defaults.
 *
 * Bitmap math in PreviewRenderer.cropBitmap / applyMasks (rotation matrices,
 * radial/linear/stroke alpha formulae, blendPixel exposure*temp gains) needs
 * android.graphics.Bitmap and is untestable on plain JVM.
 */
class CropMaskTest {

    // ---------- CropParams ----------

    @Test
    fun `crop defaults are free zero no flips`() {
        val crop = CropParams()
        assertEquals(CropRatio.FREE, crop.ratio)
        assertEquals(0, crop.rotationSteps)
        assertEquals(0f, crop.straightenDeg, 0.0f)
        assertFalse(crop.flipH)
        assertFalse(crop.flipV)
        assertTrue(crop.isDefault())
        assertTrue(EditParams.DEFAULT.isCropDefault())
        assertTrue(EditParams.DEFAULT.crop.isDefault())
    }

    @Test
    fun `non-default crop detected`() {
        assertFalse(CropParams(ratio = CropRatio.R1_1).isDefault())
        assertFalse(CropParams(rotationSteps = 1).isDefault())
        assertFalse(CropParams(straightenDeg = 5f).isDefault())
        assertFalse(CropParams(flipH = true).isDefault())
        assertFalse(CropParams(flipV = true).isDefault())
    }

    @Test
    fun `rotationSteps wraps zero to three`() {
        assertEquals(0, CropParams().withRotationSteps(4).rotationSteps)
        assertEquals(0, CropParams().withRotationSteps(0).rotationSteps)
        assertEquals(1, CropParams().withRotationSteps(5).rotationSteps)
        assertEquals(3, CropParams().withRotationSteps(-1).rotationSteps)
        assertEquals(3, CropParams().withRotationSteps(-5).rotationSteps)
        assertEquals(2, CropParams().withRotationSteps(2).rotationSteps)
        // rotated90 increments with wrap.
        assertEquals(1, CropParams().rotated90().rotationSteps)
        assertEquals(0, CropParams(rotationSteps = 3).rotated90().rotationSteps)
        // EditParams plumbing.
        assertEquals(1, EditParams().rotateCrop90().crop.rotationSteps)
    }

    @Test
    fun `straighten clamps minus45 plus45`() {
        assertEquals(45f, CropParams().withStraighten(100f).straightenDeg, 0.0f)
        assertEquals(-45f, CropParams().withStraighten(-100f).straightenDeg, 0.0f)
        assertEquals(10f, CropParams().withStraighten(10f).straightenDeg, 0.0f)
        assertEquals(0f, CropParams().withStraighten(0f).straightenDeg, 0.0f)
    }

    @Test
    fun `aspect values match spec`() {
        assertEquals(1f, CropRatio.R1_1.aspect!!, 0.0001f)
        assertEquals(1.5f, CropRatio.R3_2.aspect!!, 0.0001f)
        assertEquals(16f / 9f, CropRatio.R16_9.aspect!!, 0.0005f)
        assertEquals(9f / 16f, CropRatio.R9_16.aspect!!, 0.0005f)
        assertEquals(0.8f, CropRatio.R4_5.aspect!!, 0.0001f)
        assertEquals(null, CropRatio.FREE.aspect)
        assertEquals(null, CropRatio.ORIGINAL.aspect)
        // 16:9 approx 1.778, 9:16 approx 0.5625 per spec.
        assertEquals(1.778f, CropRatio.R16_9.aspect!!, 0.001f)
        assertEquals(0.5625f, CropRatio.R9_16.aspect!!, 0.001f)
    }

    @Test
    fun `cropRatio fromKey falls back to free`() {
        assertEquals(CropRatio.R1_1, CropRatio.fromKey("1_1"))
        assertEquals(CropRatio.FREE, CropRatio.fromKey("nope"))
        assertEquals(CropRatio.FREE, CropRatio.fromKey(null))
    }

    // ---------- EditMask ----------

    @Test
    fun `mask defaults`() {
        val mask = EditMask(id = "m1")
        assertEquals(MaskTool.BRUSH, mask.tool)
        assertEquals(80f, mask.sizePx, 0.0f)
        assertEquals(0.5f, mask.feather, 0.0f)
        assertEquals(1f, mask.opacity, 0.0f)
        assertFalse(mask.inverted)
        assertTrue(mask.visible)
        assertEquals(0.5f, mask.centerX, 0.0f)
        assertEquals(0.5f, mask.centerY, 0.0f)
        assertEquals(0.3f, mask.radius, 0.0f)
        assertEquals(0f, mask.angleDeg, 0.0f)
        assertEquals(0.5f, mask.position, 0.0f)
        assertTrue(mask.points.isEmpty())
        assertEquals(0f, mask.exposure, 0.0f)
        assertEquals(0f, mask.temperature, 0.0f)
    }

    @Test
    fun `mask feather and opacity clamp zero to one`() {
        assertEquals(1f, EditMask(id = "x").withFeather(5f).feather, 0.0f)
        assertEquals(0f, EditMask(id = "x").withFeather(-5f).feather, 0.0f)
        assertEquals(1f, EditMask(id = "x").withOpacity(5f).opacity, 0.0f)
        assertEquals(0f, EditMask(id = "x").withOpacity(-5f).opacity, 0.0f)
        assertEquals(0.2f, EditMask(id = "x").withFeather(0.2f).feather, 0.0f)
    }

    @Test
    fun `mask exposure clamps minus5 plus5 and temperature minus100 plus100`() {
        assertEquals(5f, EditMask(id = "x").withExposure(99f).exposure, 0.0f)
        assertEquals(-5f, EditMask(id = "x").withExposure(-99f).exposure, 0.0f)
        assertEquals(100f, EditMask(id = "x").withTemperature(999f).temperature, 0.0f)
        assertEquals(-100f, EditMask(id = "x").withTemperature(-999f).temperature, 0.0f)
        assertEquals(1.5f, EditMask(id = "x").withExposure(1.5f).exposure, 0.0f)
        assertEquals(-40f, EditMask(id = "x").withTemperature(-40f).temperature, 0.0f)
    }

    @Test
    fun `mask points cap at 64`() {
        assertEquals(64, EditMask.MAX_POINTS)
        val many = (0 until 100).map { i -> MaskPoint(i / 100f, (100 - i) / 100f) }
        val sanitized = EditMask.sanitizePoints(many)
        assertEquals(64, sanitized.size)
        for (p in sanitized) {
            assertTrue("x ${p.x}", p.x in 0f..1f)
            assertTrue("y ${p.y}", p.y in 0f..1f)
        }
        // Small lists pass through clamped but unthinned.
        val few = listOf(MaskPoint(-1f, 2f), MaskPoint(0.5f, 0.5f))
        val clamped = EditMask.sanitizePoints(few)
        assertEquals(2, clamped.size)
        assertEquals(0f, clamped[0].x, 0.0f)
        assertEquals(1f, clamped[0].y, 0.0f)
        assertEquals(emptyList<MaskPoint>(), EditMask.sanitizePoints(null))
        assertEquals(emptyList<MaskPoint>(), EditMask.sanitizePoints(emptyList()))
    }

    @Test
    fun `max masks three enforced in addMask`() {
        assertEquals(3, EditParams.MAX_MASKS)
        var p = EditParams.DEFAULT
        p = p.addMask(MaskTool.BRUSH)
        assertEquals(1, p.masks.size)
        p = p.addMask(MaskTool.RADIAL)
        p = p.addMask(MaskTool.LINEAR)
        assertEquals(3, p.masks.size)
        val full = p
        val overflow = p.addMask(MaskTool.BRUSH)
        assertEquals(3, overflow.masks.size)
        assertSame(full, overflow)
        // Removal frees a slot.
        val removed = full.removeMask(full.masks.first().id)
        assertEquals(2, removed.masks.size)
        assertEquals(3, removed.addMask(MaskTool.ERASER).masks.size)
    }

    // ---------- StepsEnabled ----------

    @Test
    fun `steps all true default`() {
        val steps = StepsEnabled()
        assertTrue(steps.isAllEnabled())
        for (key in StepKey.entries) {
            assertTrue("default $key", steps.get(key))
        }
        assertTrue(EditParams.DEFAULT.steps.isAllEnabled())
        assertTrue(EditParams.DEFAULT.isStepEnabled(StepKey.CROP))
    }

    @Test
    fun `per-step toggle isolates one step`() {
        val off = StepsEnabled().with(StepKey.CROP, false)
        assertFalse(off.get(StepKey.CROP))
        assertTrue(off.get(StepKey.MASKS))
        assertTrue(!off.isAllEnabled())
        val backOn = off.with(StepKey.CROP, true)
        assertTrue(backOn.isAllEnabled())
        // Same-value toggle returns same instance.
        assertSame(off, off.with(StepKey.CROP, false))
        // EditParams plumbing.
        val p = EditParams().withStep(StepKey.MASKS, false)
        assertFalse(p.isStepEnabled(StepKey.MASKS))
        assertTrue(p.isStepEnabled(StepKey.CROP))
        assertTrue(!p.steps.isAllEnabled())
        assertTrue(p.resetSteps().steps.isAllEnabled())
    }

    // ---------- JSON ----------

    @Test
    fun `json round-trip crop masks steps`() {
        var p = EditParams()
            .withCropRatio(CropRatio.R16_9)
            .rotateCrop90()
            .withStraighten(10f)
            .toggleFlipH()
            .withStep(StepKey.CROP, false)
            .withStep(StepKey.MASKS, true)
            .addMask(MaskTool.RADIAL)
        val maskId = p.masks.first().id
        p = p.updateMask(maskId) {
            it.copy(centerX = 0.25f, centerY = 0.75f, radius = 0.4f, exposure = 1.5f, temperature = -20f)
        }
        val encoded = EditParamsJson.encode(p)
        assertTrue(encoded.contains("\"crop_ratio\":\"16_9\""))
        assertTrue(encoded.contains("\"crop_rotation\":"))
        assertTrue(encoded.contains("\"crop_straighten\":"))
        assertTrue(encoded.contains("\"crop_flipH\":true"))
        assertTrue(encoded.contains("\"step_crop\":false"))
        assertTrue(encoded.contains("\"masks\":["))
        val decoded = EditParamsJson.decode(encoded)
        assertEquals(CropRatio.R16_9, decoded.crop.ratio)
        assertEquals(1, decoded.crop.rotationSteps)
        assertEquals(10f, decoded.crop.straightenDeg, 0.001f)
        assertTrue(decoded.crop.flipH)
        assertFalse(decoded.crop.flipV)
        assertFalse(decoded.isStepEnabled(StepKey.CROP))
        assertTrue(decoded.isStepEnabled(StepKey.MASKS))
        assertEquals(1, decoded.masks.size)
        val mask = decoded.masks.first()
        assertEquals(MaskTool.RADIAL, mask.tool)
        assertEquals(0.25f, mask.centerX, 0.001f)
        assertEquals(0.75f, mask.centerY, 0.001f)
        assertEquals(0.4f, mask.radius, 0.001f)
        assertEquals(1.5f, mask.exposure, 0.001f)
        assertEquals(-20f, mask.temperature, 0.001f)
    }

    @Test
    fun `old adjust-only json decodes with crop masks steps at defaults`() {
        val oldJson = "{\"exposure\":2.0,\"contrast\":15.0,\"presetId\":null,\"presetIntensity\":1.0}"
        val decoded = EditParamsJson.decode(oldJson)
        assertEquals(2f, decoded.exposure, 0.001f)
        assertTrue(decoded.isCropDefault())
        assertTrue(decoded.crop.isDefault())
        assertEquals(CropRatio.FREE, decoded.crop.ratio)
        assertTrue(decoded.isMasksDefault())
        assertTrue(decoded.masks.isEmpty())
        assertTrue(decoded.steps.isAllEnabled())
        for (key in StepKey.entries) {
            assertTrue("step $key", decoded.isStepEnabled(key))
        }
    }
}
