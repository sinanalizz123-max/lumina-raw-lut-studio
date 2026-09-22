package com.lumina.studio.edit

import com.lumina.studio.core.edit.DustMath
import com.lumina.studio.core.edit.EditParams
import com.lumina.studio.core.edit.EditParamsJson
import com.lumina.studio.core.edit.LensBlurMath
import com.lumina.studio.core.edit.LensBlurParams
import com.lumina.studio.core.edit.RetouchKind
import com.lumina.studio.core.edit.RetouchMath
import com.lumina.studio.core.edit.RetouchOp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * M8 pure-JVM guards (§26-28, no android.*, no Robolectric).
 *
 * Covers: heal annulus-median math, clone feather blend, onion-peel
 * convergence on a synthetic step, depth-field shape, dust-candidate
 * precision on synthetic dots, JSON round-trips + old-JSON defaults.
 * Bitmap loops in PreviewRenderer (disc rasterization, 3-level blur blend,
 * downsampled scan) stay on-device-only; they delegate to the pure math
 * pinned here.
 */
class M8RetouchBlurDustTest {

    @Test
    fun `median is order independent`() {
        assertEquals(2f, RetouchMath.median(floatArrayOf(3f, 1f, 2f)), 0f)
        assertEquals(2.5f, RetouchMath.median(floatArrayOf(1f, 4f, 2f, 3f)), 1e-6f)
        assertEquals(0f, RetouchMath.median(floatArrayOf()), 0f)
    }

    @Test
    fun `heal preserves target luminance and shifts color toward median`() {
        val tR = 0.5f
        val tG = 0.4f
        val tB = 0.6f
        val out = RetouchMath.healPixel(tR, tG, tB, 0.8f, 0.2f, 0.2f)
        val targetLum = RetouchMath.luma(tR, tG, tB)
        assertEquals(targetLum, RetouchMath.luma(out[0], out[1], out[2]), 1e-5f)
        // Detail is preserved: shifting the target shifts the output equally.
        // (Shift blue DOWN: shifting red up would clip at 1.0 and the
        // coerceIn clip is correct render behavior, not a math bug.)
        val out2 = RetouchMath.healPixel(tR, tG, tB - 0.1f, 0.8f, 0.2f, 0.2f)
        assertEquals(-0.1f, out2[2] - out[2], 1e-5f)
        assertEquals(out[0], out2[0], 1e-5f)
    }

    @Test
    fun `heal on flat input returns the median color`() {
        val mLum = RetouchMath.luma(0.8f, 0.2f, 0.2f)
        // Flat target exactly at the median luma: output equals the median.
        val out = RetouchMath.healPixel(mLum, mLum, mLum, 0.8f, 0.2f, 0.2f)
        assertEquals(0.8f, out[0], 1e-5f)
        assertEquals(0.2f, out[1], 1e-5f)
        assertEquals(0.2f, out[2], 1e-5f)
    }

    @Test
    fun `feather alpha is one inside zero outside smooth between`() {
        assertEquals(1f, RetouchMath.featherAlpha(0f, 10f, 0.5f), 0f)
        assertEquals(1f, RetouchMath.featherAlpha(5f, 10f, 0.5f), 0f)
        assertEquals(0f, RetouchMath.featherAlpha(10f, 10f, 0.5f), 0f)
        assertEquals(0f, RetouchMath.featherAlpha(11f, 10f, 0.5f), 0f)
        val mid = RetouchMath.featherAlpha(7.5f, 10f, 0.5f)
        assertTrue("mid $mid in 0..1", mid > 0f && mid < 1f)
        // Hard edge with zero feather.
        assertEquals(1f, RetouchMath.featherAlpha(10f, 10f, 0f), 0f)
        assertEquals(0f, RetouchMath.featherAlpha(10.1f, 10f, 0f), 0f)
        // Clone blend helper.
        assertEquals(0.25f, RetouchMath.mix(0f, 1f, 0.25f), 1e-6f)
        assertEquals(0.5f, RetouchMath.mix(0.5f, 0.9f, 0f), 1e-6f)
    }

    @Test
    fun `onion peel converges on synthetic step within bounded passes`() {
        val w = 11
        val h = 11
        val surround = 0.8f
        val r = FloatArray(w * h) { surround }
        val g = FloatArray(w * h) { surround }
        val b = FloatArray(w * h) { surround }
        val mask = BooleanArray(w * h)
        for (y in 4..6) {
            for (x in 4..6) {
                val i = y * w + x
                r[i] = 0f
                g[i] = 0f
                b[i] = 0f
                mask[i] = true
            }
        }
        val before = RetouchMath.maskedEnergy(r, g, b, mask, w, h)
        assertTrue("energy $before > 0", before > 0.5f)
        // Two bounded passes already reduce the masked energy.
        val r2 = r.clone()
        val g2 = g.clone()
        val b2 = b.clone()
        val mask2 = mask.clone()
        val usedFew = RetouchMath.onionPeelInpaint(r2, g2, b2, mask2, w, h, 2)
        assertEquals(2, usedFew)
        val partial = RetouchMath.maskedEnergy(r2, g2, b2, mask2, w, h)
        assertTrue("partial $partial < before $before", partial < before)
        // Generous bound fills the 3x3 block completely (energy zero).
        val used = RetouchMath.onionPeelInpaint(r, g, b, mask, w, h, 16)
        assertTrue("passes $used in 1..16", used in 1..16)
        assertFalse(mask.any { it })
        assertEquals(0f, RetouchMath.maskedEnergy(r, g, b, mask, w, h), 0f)
        for (y in 4..6) {
            for (x in 4..6) {
                assertEquals(surround, r[y * w + x], 1e-5f)
            }
        }
    }

    @Test
    fun `depth field is zero at focus one far away monotonic`() {
        val fx = 0.5f
        val fy = 0.5f
        val radius = 0.25f
        val transition = 0.5f
        assertEquals(0f, LensBlurMath.depthAt(fx, fy, fx, fy, radius, transition, 1f), 0f)
        assertEquals(
            0f,
            LensBlurMath.depthAt(fx + 0.1f, fy, fx, fy, radius, transition, 1f),
            0f
        )
        val far = LensBlurMath.depthAt(0f, 0f, fx, fy, radius, transition, 1f)
        assertTrue("far $far near 1", far > 0.9f)
        // Monotonic along a ray out of focus.
        var prev = 0f
        var t = 0f
        while (t <= 0.7f) {
            val d = LensBlurMath.depthAt(fx + t, fy, fx, fy, radius, transition, 1f)
            assertTrue("depth $d >= prev $prev at t=$t", d >= prev - 1e-6f)
            prev = d
            t += 0.05f
        }
    }

    @Test
    fun `larger transition means smoother depth edge`() {
        fun maxGradient(transition: Float): Float {
            var peak = 0f
            var prev = LensBlurMath.depthAt(0.5f, 0.5f, 0.5f, 0.5f, 0.25f, transition, 1f)
            var t = 0.51f
            while (t <= 1f) {
                val d = LensBlurMath.depthAt(t, 0.5f, 0.5f, 0.5f, 0.25f, transition, 1f)
                peak = maxOf(peak, abs(d - prev))
                prev = d
                t += 0.01f
            }
            return peak
        }
        val sharp = maxGradient(0f)
        val smooth = maxGradient(1f)
        assertTrue("smooth $smooth < sharp $sharp", smooth < sharp)
    }

    @Test
    fun `band weights favor near at focus far at distance`() {
        val near = LensBlurMath.bandWeights(0f)
        assertEquals(1f, near[0], 1e-5f)
        assertEquals(0f, near[2], 1e-5f)
        val far = LensBlurMath.bandWeights(1f)
        assertEquals(0f, far[0], 1e-5f)
        assertEquals(1f, far[2], 1e-5f)
        for (d in listOf(0f, 0.2f, 0.5f, 0.8f, 1f)) {
            val weights = LensBlurMath.bandWeights(d)
            assertEquals(1f, weights[0] + weights[1] + weights[2], 1e-5f)
            for (weight in weights) assertTrue(weight in 0f..1f)
        }
        // Blur levels: off is sharp, strong blurs more than mild.
        assertEquals(1f, LensBlurMath.downscaleFactor(0f, 1), 0f)
        assertEquals(1f, LensBlurMath.downscaleFactor(0f, 2), 0f)
        val mild = LensBlurMath.downscaleFactor(100f, 1)
        val strong = LensBlurMath.downscaleFactor(100f, 2)
        assertTrue("mild $mild < 1", mild < 1f)
        assertTrue("strong $strong < mild $mild", strong < mild)
    }

    @Test
    fun `detector finds synthetic dots and ignores texture`() {
        val w = 64
        val h = 64
        val field = FloatArray(w * h) { 0.75f }
        val dots = listOf(Pair(16, 16), Pair(40, 20), Pair(24, 44))
        for ((dx, dy) in dots) {
            for (oy in -1..1) {
                for (ox in -1..1) {
                    field[(dy + oy) * w + (dx + ox)] = 0.2f
                }
            }
        }
        // Textured decoy: high-contrast checkerboard (high ring variance).
        for (y in 46..58) {
            for (x in 46..58) {
                field[y * w + x] = if ((x + y) % 2 == 0) 0.6f else 0.9f
            }
        }
        val found = DustMath.detectCandidates(field, w, h, 50f)
        assertTrue("found ${found.size} in 1..200", found.isNotEmpty())
        assertTrue(found.size <= DustMath.MAX_CANDIDATES)
        // Recall: every dot has a candidate within 4px.
        for ((dx, dy) in dots) {
            val hit = found.any { c ->
                val px = c.cx * w
                val py = c.cy * h
                abs(px - dx) <= 4f && abs(py - dy) <= 4f
            }
            assertTrue("dot at $dx,$dy found in ${found.size}", hit)
        }
        // Precision: nothing inside the textured decoy rect.
        val decoyHits = found.count { c ->
            val px = c.cx * w
            val py = c.cy * h
            px >= 44f && px <= 60f && py >= 44f && py <= 60f
        }
        assertEquals(0, decoyHits)
        // Clean field finds nothing.
        val clean = DustMath.detectCandidates(FloatArray(w * h) { 0.75f }, w, h, 100f)
        assertTrue(clean.isEmpty())
        // Sensitivity ordering: higher sensitivity never raises the threshold.
        assertTrue(DustMath.thresholdFor(100f) <= DustMath.thresholdFor(0f))
    }

    @Test
    fun `retouch model clamps`() {
        val op = RetouchOp(id = "r1", kind = RetouchKind.CLONE)
            .withCenter(2f, -1f)
            .withSource(2f, -1f)
            .withRadius(9f)
            .withFeather(9f)
            .withOpacity(-1f)
        assertEquals(1f, op.cx, 0f)
        assertEquals(0f, op.cy, 0f)
        assertEquals(1f, op.sx, 0f)
        assertEquals(0f, op.sy, 0f)
        assertEquals(RetouchOp.MAX_RADIUS, op.radius, 0f)
        assertEquals(1f, op.feather, 0f)
        assertEquals(0f, op.opacity, 0f)
        val blur = LensBlurParams()
            .withFocus(2f, -1f)
            .withAmount(500f)
            .withTransition(-5f)
            .withFocusRadius(5f)
        assertEquals(1f, blur.focusX, 0f)
        assertEquals(0f, blur.focusY, 0f)
        assertEquals(100f, blur.amount, 0f)
        assertEquals(0f, blur.transition, 0f)
        assertEquals(1f, blur.focusRadius, 0f)
        assertTrue(LensBlurParams().isDefault())
        assertFalse(LensBlurParams(amount = 1f).isDefault())
    }

    @Test
    fun `m8 params round-trip through json`() {
        val params = EditParams.DEFAULT
            .addRetouchAt(RetouchKind.HEAL, 0.2f, 0.3f)
            .addRetouchAt(RetouchKind.CLONE, 0.7f, 0.8f)
            .addRetouchAt(RetouchKind.ERASE, 0.1f, 0.9f)
            .withLensFocus(0.4f, 0.6f)
            .withLensAmount(60f)
            .withLensTransition(0.7f)
            .withLensFocusRadius(0.3f)
        val withRadius = params.updateRetouch(params.retouch[1].id) {
            it.withSource(0.15f, 0.25f).withRadius(0.12f).withOpacity(0.8f)
        }
        val decoded = EditParamsJson.decode(EditParamsJson.encode(withRadius))
        assertEquals(withRadius, decoded)
    }

    @Test
    fun `old json decodes with m8 defaults`() {
        val old = "{\"exposure\":1.0,\"presetId\":null,\"presetIntensity\":1.0}"
        val decoded = EditParamsJson.decode(old)
        assertTrue(decoded.isRetouchDefault())
        assertTrue(decoded.isLensBlurDefault())
        assertTrue(decoded.retouch.isEmpty())
        assertEquals(0f, decoded.lensBlur.amount, 0f)
        assertEquals(0.5f, decoded.lensBlur.focusX, 0f)
        val oldMask = "{\"masks\":[{\"id\":\"a\",\"tool\":\"BRUSH\",\"sizePx\":80,\"feather\":0.5," +
            "\"opacity\":1,\"inverted\":false,\"visible\":true,\"centerX\":0.5,\"centerY\":0.5," +
            "\"radius\":0.3,\"angleDeg\":0,\"position\":0.5,\"exposure\":1,\"temperature\":0," +
            "\"points\":[[0.5,0.5]]}]}"
        val withMask = EditParamsJson.decode(oldMask)
        assertEquals(1, withMask.masks.size)
        assertTrue(withMask.isRetouchDefault())
        assertTrue(withMask.isLensBlurDefault())
        // Unknown retouch kind falls back to HEAL; out-of-range values clamp.
        val odd = "{\"retouch\":[{\"id\":\"r\",\"kind\":\"NOPE\",\"cx\":9,\"cy\":-9," +
            "\"sx\":0.1,\"sy\":0.2,\"radius\":99,\"feather\":9,\"opacity\":9}]}"
        val parsed = EditParamsJson.decode(odd)
        assertEquals(1, parsed.retouch.size)
        assertEquals(RetouchKind.HEAL, parsed.retouch[0].kind)
        assertEquals(1f, parsed.retouch[0].cx, 0f)
        assertEquals(0f, parsed.retouch[0].cy, 0f)
        assertEquals(RetouchOp.MAX_RADIUS, parsed.retouch[0].radius, 0f)
    }
}
