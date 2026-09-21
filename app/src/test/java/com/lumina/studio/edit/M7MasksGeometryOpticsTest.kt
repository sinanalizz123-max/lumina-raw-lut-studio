package com.lumina.studio.edit

import com.lumina.studio.core.edit.CropParams
import com.lumina.studio.core.edit.CropRatio
import com.lumina.studio.core.edit.EditMask
import com.lumina.studio.core.edit.EditParams
import com.lumina.studio.core.edit.EditParamsJson
import com.lumina.studio.core.edit.GeometryMath
import com.lumina.studio.core.edit.MaskOp
import com.lumina.studio.core.edit.MaskRangeMath
import com.lumina.studio.core.edit.MaskTool
import com.lumina.studio.core.edit.OpticsMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M7 pure-JVM guards (§22-24, no android.*, no Robolectric).
 *
 * Covers: color/luma range falloff, op compositing on synthetic alphas,
 * perspective corner mapping sanity, CA shift direction, vignette gain
 * center=1, JSON round-trips + old-JSON defaults. Bitmap loops in
 * PreviewRenderer (optics remap, mask alpha fields, perspective warp) stay
 * on-device-only; they delegate to the pure math pinned here.
 */
class M7MasksGeometryOpticsTest {

    @Test
    fun `color range weight is one at center zero outside`() {
        assertEquals(1f, MaskRangeMath.colorWeight(10f, 10f, 60f), 0f)
        assertEquals(0f, MaskRangeMath.colorWeight(10f + 60f, 10f, 60f), 0f)
        assertEquals(0f, MaskRangeMath.colorWeight(10f + 90f, 10f, 60f), 0f)
        val mid = MaskRangeMath.colorWeight(10f + 42f, 10f, 60f)
        assertTrue("mid $mid in 0..1", mid in 0f..1f)
        assertTrue("wrap", MaskRangeMath.colorWeight(350f, 10f, 60f) > 0f)
    }

    @Test
    fun `luma weight band is one inside zero outside smooth edges`() {
        assertEquals(1f, MaskRangeMath.lumaWeight(0.5f, 0.2f, 0.8f, 0.2f), 0.02f)
        assertEquals(0f, MaskRangeMath.lumaWeight(0f, 0.4f, 0.6f, 0.2f), 0.02f)
        assertEquals(0f, MaskRangeMath.lumaWeight(1f, 0.4f, 0.6f, 0.2f), 0.02f)
        val edge = MaskRangeMath.lumaWeight(0.4f, 0.4f, 0.6f, 0.5f)
        assertTrue("edge $edge in 0..1", edge in 0f..1f)
        assertEquals(1f, MaskRangeMath.lumaWeight(0.5f, 0f, 1f, 0.2f), 0.001f)
        // Hard band with zero feather.
        assertEquals(1f, MaskRangeMath.lumaWeight(0.5f, 0.4f, 0.6f, 0f), 0f)
        assertEquals(0f, MaskRangeMath.lumaWeight(0.7f, 0.4f, 0.6f, 0f), 0f)
    }

    @Test
    fun `op compositing on synthetic alphas`() {
        // ADD unions.
        assertEquals(1f, MaskRangeMath.combineAcc(0.5f, 1f, MaskOp.ADD), 1e-5f)
        assertEquals(0.75f, MaskRangeMath.combineAcc(0.5f, 0.5f, MaskOp.ADD), 1e-5f)
        assertEquals(0f, MaskRangeMath.combineAcc(0f, 0f, MaskOp.ADD), 1e-5f)
        // SUBTRACT cuts out.
        assertEquals(0f, MaskRangeMath.combineAcc(0.8f, 1f, MaskOp.SUBTRACT), 1e-5f)
        assertEquals(0.4f, MaskRangeMath.combineAcc(0.8f, 0.5f, MaskOp.SUBTRACT), 1e-5f)
        // INTERSECT keeps overlap.
        assertEquals(0.25f, MaskRangeMath.combineAcc(0.5f, 0.5f, MaskOp.INTERSECT), 1e-5f)
        assertEquals(0f, MaskRangeMath.combineAcc(0.5f, 0f, MaskOp.INTERSECT), 1e-5f)
        // Effective alphas.
        assertEquals(0.6f, MaskRangeMath.effectiveAlpha(0.9f, 0.6f, MaskOp.ADD), 1e-5f)
        assertEquals(0.54f, MaskRangeMath.effectiveAlpha(0.9f, 0.6f, MaskOp.INTERSECT), 1e-4f)
        assertEquals(0.54f, MaskRangeMath.effectiveAlpha(0.9f, 0.6f, MaskOp.SUBTRACT), 1e-4f)
        assertEquals(0f, MaskRangeMath.effectiveAlpha(0f, 0.6f, MaskOp.INTERSECT), 1e-5f)
    }

    @Test
    fun `perspective identity at zero and sane corners otherwise`() {
        val w = 400
        val h = 300
        val identity = GeometryMath.perspectiveDst(w, h, 0f, 0f)
        assertEquals(0f, identity[0], 0f)
        assertEquals(0f, identity[1], 0f)
        assertEquals(w.toFloat(), identity[2], 0f)
        assertEquals(h.toFloat(), identity[7], 0f)
        assertTrue(GeometryMath.isPerspectiveDefault(0f, 0f))
        assertFalse(GeometryMath.isPerspectiveDefault(1f, 0f))
        // Positive vertical narrows the top edge symmetrically.
        val v = GeometryMath.perspectiveDst(w, h, 100f, 0f)
        val dx = w * 0.15f
        assertEquals(dx, v[0], 0.01f)
        assertEquals(w - dx, v[2], 0.01f)
        assertEquals(w + dx, v[4], 0.01f)
        // Positive horizontal shifts left-edge corners vertically.
        val hz = GeometryMath.perspectiveDst(w, h, 0f, 100f)
        val dy = h * 0.15f
        assertEquals(dy, hz[1], 0.01f)
        assertEquals(h - dy, hz[7], 0.01f)
    }

    @Test
    fun `ca shift is zero at center and grows outward with sign`() {
        assertEquals(0f, OpticsMath.caShiftPx(0f, 10f, 1000), 0f)
        assertEquals(0f, OpticsMath.caShiftPx(1f, 0f, 1000), 0f)
        val pos = OpticsMath.caShiftPx(1f, 10f, 1000)
        val neg = OpticsMath.caShiftPx(1f, -10f, 1000)
        assertTrue("positive $pos > 0", pos > 0f)
        assertTrue("negative $neg < 0", neg < 0f)
        assertEquals(-pos, neg, 1e-5f)
        val half = OpticsMath.caShiftPx(0.5f, 10f, 1000)
        assertEquals(pos / 2f, half, 1e-4f)
    }

    @Test
    fun `vignette gain is one at center and lifts corners when positive`() {
        assertEquals(1f, OpticsMath.vignetteGain(0f, 100f), 0f)
        assertEquals(1f, OpticsMath.vignetteGain(1f, 0f), 0f)
        val lift = OpticsMath.vignetteGain(1f, 100f)
        assertTrue("lift $lift > 1", lift > 1f)
        val drop = OpticsMath.vignetteGain(1f, -100f)
        assertTrue("drop $drop < 1", drop < 1f)
    }

    @Test
    fun `distortion remap keeps center and is monotonic`() {
        val k1 = OpticsMath.distortionK1(100f)
        assertTrue(k1 > 0f)
        assertEquals(0f, OpticsMath.remapRadius(0f, k1), 0f)
        val r1 = OpticsMath.remapRadius(0.5f, k1)
        val r2 = OpticsMath.remapRadius(1f, k1)
        assertTrue("monotonic $r1 < $r2", r1 < r2)
        val inv = OpticsMath.inverseRemapRadius(r2, k1)
        assertEquals(1f, inv, 0.05f)
    }

    @Test
    fun `new ratios and custom aspect`() {
        assertEquals(4f / 3f, CropRatio.R4_3.aspect!!, 1e-5f)
        assertEquals(5f / 4f, CropRatio.R5_4.aspect!!, 1e-5f)
        val custom = CropParams(ratio = CropRatio.CUSTOM, customW = 16f, customH = 9f)
        assertEquals(16f / 9f, custom.effectiveAspect()!!, 1e-4f)
        assertFalse(custom.isDefault())
        assertTrue(CropParams().isDefault())
        assertFalse(CropParams(perspectiveV = 5f).isDefault())
    }

    @Test
    fun `mask model clamps and round-trips`() {
        val mask = EditMask(id = "m1", tool = MaskTool.COLOR)
            .withHueCenter(400f)
            .withHueRange(500f)
            .withSaturation(150f)
            .withClarity(-150f)
            .withBlur(99f)
            .withLumaLo(-1f)
            .withLumaHi(2f)
            .withOp(MaskOp.INTERSECT)
        assertEquals(40f, mask.hueCenter, 0f)
        assertEquals(180f, mask.hueRange, 0f)
        assertEquals(100f, mask.saturation, 0f)
        assertEquals(-100f, mask.clarity, 0f)
        assertEquals(EditMask.MAX_BLUR, mask.blur, 0f)
        assertEquals(0f, mask.lumaLo, 0f)
        assertEquals(1f, mask.lumaHi, 0f)
        val params = EditParams.DEFAULT.copy(masks = listOf(mask))
            .withVignetteCorr(50f)
            .withCaShift(5f)
            .withDistortion(-20f)
            .withPerspectiveV(10f)
            .withCustomAspect(16f, 9f)
            .withCropRatio(CropRatio.CUSTOM)
        val decoded = EditParamsJson.decode(EditParamsJson.encode(params))
        assertEquals(params, decoded)
    }

    @Test
    fun `old json decodes with m7 defaults`() {
        val old = "{\"exposure\":1.0,\"presetId\":null,\"presetIntensity\":1.0}"
        val decoded = EditParamsJson.decode(old)
        assertTrue(decoded.isOpticsDefault())
        assertTrue(decoded.crop.isDefault())
        assertTrue(decoded.masks.isEmpty())
        assertEquals(0f, decoded.optics.vignetteCorr, 0f)
        assertEquals(4f, decoded.crop.customW, 0f)
        val oldMask = "{\"masks\":[{\"id\":\"a\",\"tool\":\"BRUSH\",\"sizePx\":80,\"feather\":0.5," +
            "\"opacity\":1,\"inverted\":false,\"visible\":true,\"centerX\":0.5,\"centerY\":0.5," +
            "\"radius\":0.3,\"angleDeg\":0,\"position\":0.5,\"exposure\":1,\"temperature\":0," +
            "\"points\":[[0.5,0.5]]}]}"
        val withMask = EditParamsJson.decode(oldMask)
        assertEquals(1, withMask.masks.size)
        assertEquals(MaskOp.ADD, withMask.masks[0].op)
        assertEquals(0f, withMask.masks[0].saturation, 0f)
        assertEquals(60f, withMask.masks[0].hueRange, 0f)
    }
}
