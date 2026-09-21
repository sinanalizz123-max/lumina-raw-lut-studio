package com.lumina.studio.edit

import com.lumina.studio.core.edit.CurveChannel
import com.lumina.studio.core.edit.CurvePoint
import com.lumina.studio.core.edit.Curves
import com.lumina.studio.core.edit.EditParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 3 pure-JVM guards for Curves (no android.*, no Robolectric).
 *
 * Covers: diagonal identity ramp, clamping, sanitize (sort/min-2/dedupe),
 * single-point + empty fallback, Catmull-Rom monotonicity, channel count.
 *
 * Bitmap-touching curve application (PreviewRenderer.applyCurves / cropBitmap /
 * mask alpha rasterization) needs android.graphics.Bitmap and is untestable
 * on plain JVM — covered here only through the pure sampling math.
 */
class CurvesTest {

    private val eps = 1e-5f

    @Test
    fun `diagonal sampleCurve returns identity ramp`() {
        val out = Curves.sampleCurve(listOf(CurvePoint(0f, 0f), CurvePoint(1f, 1f)))
        assertEquals(256, out.size)
        for (i in 0 until 256) {
            val expected = i / 255f
            assertTrue(
                "index $i expected $expected got ${out[i]}",
                kotlin.math.abs(out[i] - expected) <= eps
            )
        }
    }

    @Test
    fun `sampleCurve output has 256 finite entries in unit range`() {
        val pts = listOf(CurvePoint(0f, 0f), CurvePoint(0.5f, 0.6f), CurvePoint(1f, 1f))
        val out = Curves.sampleCurve(pts)
        assertEquals(256, out.size)
        for (i in out.indices) {
            assertTrue("finite at $i: ${out[i]}", out[i].isFinite())
            assertTrue("range at $i: ${out[i]}", out[i] in 0f..1f)
        }
    }

    @Test
    fun `sanitize clamps out-of-range points to unit square`() {
        val sanitized = Curves.sanitize(
            listOf(CurvePoint(-0.5f, -0.2f), CurvePoint(0.5f, 0.5f), CurvePoint(1.5f, 1.8f))
        )
        assertEquals(3, sanitized.size)
        assertEquals(0f, sanitized[0].x, 0.0f)
        assertEquals(0f, sanitized[0].y, 0.0f)
        assertEquals(0.5f, sanitized[1].x, 0.0f)
        assertEquals(0.5f, sanitized[1].y, 0.0f)
        assertEquals(1f, sanitized[2].x, 0.0f)
        assertEquals(1f, sanitized[2].y, 0.0f)
    }

    @Test
    fun `sampleCurve clamps out-of-range diagonal to identity`() {
        // After clamping [(-0.5,-0.2),(1.5,1.8)] becomes diagonal -> identity ramp.
        val out = Curves.sampleCurve(listOf(CurvePoint(-0.5f, -0.2f), CurvePoint(1.5f, 1.8f)))
        assertEquals(256, out.size)
        for (i in 0 until 256) {
            assertTrue(
                "index $i",
                kotlin.math.abs(out[i] - i / 255f) <= 1e-4f
            )
        }
    }

    @Test
    fun `sanitize sorts by x`() {
        val sanitized = Curves.sanitize(listOf(CurvePoint(1f, 1f), CurvePoint(0f, 0f)))
        assertEquals(2, sanitized.size)
        assertEquals(0f, sanitized[0].x, 0.0f)
        assertEquals(1f, sanitized[1].x, 0.0f)
        assertEquals(Curves.DEFAULT_POINTS, sanitized)
    }

    @Test
    fun `sanitize dedupes same x keeping last`() {
        val sanitized = Curves.sanitize(
            listOf(
                CurvePoint(0f, 0f),
                CurvePoint(0.5f, 0.2f),
                CurvePoint(0.5f, 0.8f),
                CurvePoint(1f, 1f)
            )
        )
        assertEquals(3, sanitized.size)
        assertEquals(0.5f, sanitized[1].x, 0.0f)
        assertEquals(0.8f, sanitized[1].y, 0.0f)
    }

    @Test
    fun `sanitize empty returns diagonal default`() {
        assertEquals(Curves.DEFAULT_POINTS, Curves.sanitize(emptyList()))
    }

    @Test
    fun `sanitize null returns diagonal default`() {
        assertEquals(Curves.DEFAULT_POINTS, Curves.sanitize(null))
    }

    @Test
    fun `sanitize single point returns diagonal default`() {
        assertEquals(Curves.DEFAULT_POINTS, Curves.sanitize(listOf(CurvePoint(0.5f, 0.5f))))
        // Single point at an extreme also collapses (deduped size < 2).
        assertEquals(Curves.DEFAULT_POINTS, Curves.sanitize(listOf(CurvePoint(0f, 0f))))
    }

    @Test
    fun `sampleCurve single point falls back to diagonal identity`() {
        val out = Curves.sampleCurve(listOf(CurvePoint(0.5f, 0.9f)))
        assertEquals(256, out.size)
        for (i in 0 until 256) {
            assertTrue(
                "index $i got ${out[i]}",
                kotlin.math.abs(out[i] - i / 255f) <= eps
            )
        }
    }

    @Test
    fun `sampleCurve empty falls back to diagonal identity`() {
        val out = Curves.sampleCurve(emptyList())
        assertEquals(256, out.size)
        for (i in 0 until 256) {
            assertTrue(
                "index $i got ${out[i]}",
                kotlin.math.abs(out[i] - i / 255f) <= eps
            )
        }
    }

    @Test
    fun `catmullRom output monotonic for monotonic input`() {
        val pts = listOf(
            CurvePoint(0f, 0f),
            CurvePoint(0.25f, 0.2f),
            CurvePoint(0.5f, 0.5f),
            CurvePoint(0.75f, 0.8f),
            CurvePoint(1f, 1f)
        )
        val out = Curves.sampleCurve(pts)
        assertEquals(256, out.size)
        for (i in 1 until 256) {
            assertTrue(
                "non-monotonic at $i: ${out[i - 1]} -> ${out[i]}",
                out[i] >= out[i - 1] - 1e-4f
            )
        }
        // Endpoints pinned to the control points.
        assertEquals(0f, out[0], 1e-4f)
        assertEquals(1f, out[255], 1e-3f)
    }

    @Test
    fun `lifted shadows curve stays monotonic and brightens midtones`() {
        val pts = listOf(CurvePoint(0f, 0f), CurvePoint(0.5f, 0.6f), CurvePoint(1f, 1f))
        val out = Curves.sampleCurve(pts)
        for (i in 1 until 256) {
            assertTrue(
                "non-monotonic at $i: ${out[i - 1]} -> ${out[i]}",
                out[i] >= out[i - 1] - 1e-4f
            )
        }
        // Midtone lifted above identity.
        assertTrue("mid ${out[128]} should exceed identity", out[128] > 128 / 255f)
    }

    @Test
    fun `channel count is four`() {
        assertEquals(4, CurveChannel.entries.size)
        assertTrue(CurveChannel.entries.contains(CurveChannel.MASTER))
        assertTrue(CurveChannel.entries.contains(CurveChannel.RED))
        assertTrue(CurveChannel.entries.contains(CurveChannel.GREEN))
        assertTrue(CurveChannel.entries.contains(CurveChannel.BLUE))
    }

    @Test
    fun `editParams curves default to diagonal and round-trip custom curve`() {
        val defaults = EditParams.DEFAULT
        assertTrue(defaults.isCurvesDefault())
        for (channel in CurveChannel.entries) {
            assertTrue("default diagonal $channel", defaults.isCurveDiagonal(channel))
            assertEquals(Curves.DEFAULT_POINTS, defaults.getCurve(channel))
        }
        val custom = listOf(CurvePoint(0f, 0f), CurvePoint(0.5f, 0.7f), CurvePoint(1f, 1f))
        val withCustom = defaults.withCurve(CurveChannel.MASTER, custom)
        assertTrue(!withCustom.isCurveDiagonal(CurveChannel.MASTER))
        assertTrue(withCustom.isCurveDiagonal(CurveChannel.RED))
        assertTrue(!withCustom.isCurvesDefault())
        val reset = withCustom.resetCurve(CurveChannel.MASTER)
        assertTrue(reset.isCurvesDefault())
    }

    @Test
    fun `isDiagonal contracts`() {
        assertTrue(Curves.isDiagonal(null))
        assertTrue(Curves.isDiagonal(Curves.DEFAULT_POINTS))
        assertTrue(Curves.isDiagonal(listOf(CurvePoint(0.5f, 0.5f))))
        assertTrue(!Curves.isDiagonal(listOf(CurvePoint(0f, 0f), CurvePoint(0.5f, 0.7f), CurvePoint(1f, 1f))))
    }
}
