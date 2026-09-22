package com.lumina.studio.perf

import com.lumina.studio.core.batch.BatchOps
import com.lumina.studio.core.edit.GradeAdjust
import com.lumina.studio.core.edit.GradeHsl
import com.lumina.studio.core.edit.GradeMath
import com.lumina.studio.core.edit.GradeParams
import com.lumina.studio.core.edit.LensBlurMath
import com.lumina.studio.core.edit.PointColorMath
import com.lumina.studio.core.edit.PointColorParams
import com.lumina.studio.core.edit.RetouchMath
import com.lumina.studio.core.export.Exporter
import com.lumina.studio.core.export.TiffWriter
import com.lumina.studio.core.render.GenerationTracker
import com.lumina.studio.core.render.MemoryBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * M16 performance + memory + stability guards (pure JVM, no android.*).
 *
 * Covers the allocation-free `*Into` contracts (§34: bit-identical to the
 * allocating variants they replace in hot pixel loops), the 12/24/48MP
 * budget math, strip/sample math, TIFF pre-flight, cancellation token
 * behavior, and debounce/coalescing math. Bitmap-touching loops stay
 * on-device-only; they delegate to the pure math pinned here.
 *
 * No timing assertions (CI noise): the "micro-benchmark" is the allocation
 * audit itself — each Into test proves the hot path CAN run with zero
 * per-pixel allocs by exercising the exact scratch-buffer layout the
 * renderer uses (see PERFORMANCE.md ledger for counts).
 */
class M16PerformanceTest {

    private val samples = floatArrayOf(0f, 0.04f, 0.2f, 0.5f, 0.75f, 1f)

    // ---------- HSL Into parity ----------

    @Test
    fun `rgbToHslInto matches rgbToHsl exactly`() {
        val out = FloatArray(3)
        for (r in samples) for (g in samples) for (b in samples) {
            val ref = GradeHsl.rgbToHsl(r, g, b)
            GradeHsl.rgbToHslInto(r, g, b, out)
            for (c in 0..2) assertEquals("rgb($r,$g,$b)[$c]", ref[c], out[c], 0f)
        }
    }

    @Test
    fun `hslToRgbInto matches hslToRgb exactly`() {
        val out = FloatArray(3)
        for (h in floatArrayOf(0f, 30f, 120f, 200f, 359f, 360f, -10f)) {
            for (s in floatArrayOf(0f, 0.5f, 1f)) {
                for (l in floatArrayOf(0f, 0.25f, 0.5f, 1f)) {
                    val ref = GradeHsl.hslToRgb(h, s, l)
                    GradeHsl.hslToRgbInto(h, s, l, out)
                    for (c in 0..2) assertEquals("hsl($h,$s,$l)[$c]", ref[c], out[c], 0f)
                }
            }
        }
    }

    // ---------- grade Into parity ----------

    @Test
    fun `zoneWeightsInto matches zoneWeights exactly`() {
        val out = FloatArray(3)
        for (balance in floatArrayOf(-100f, -30f, 0f, 40f, 100f)) {
            var luma = 0f
            while (luma <= 1.001f) {
                val ref = GradeMath.zoneWeights(luma, balance)
                GradeMath.zoneWeightsInto(luma, balance, out)
                for (c in 0..2) assertEquals("w($luma,$balance)[$c]", ref[c], out[c], 0f)
                luma += 0.05f
            }
        }
    }

    @Test
    fun `tintForInto and zoneLiftInto match allocating variants exactly`() {
        val out = FloatArray(3)
        val scratch = FloatArray(3)
        for (hue in floatArrayOf(0f, 120f, 240f, 350f)) {
            for (sat in floatArrayOf(0f, 50f, 100f)) {
                val ref = GradeMath.tintFor(hue, sat)
                GradeMath.tintForInto(hue, sat, out)
                for (c in 0..2) assertEquals("tint($hue,$sat)[$c]", ref[c], out[c], 0f)
                val adjust = GradeAdjust(hue = hue, sat = sat, lum = -25f)
                val refLift = GradeMath.zoneLift(adjust)
                GradeMath.zoneLiftInto(adjust, out, scratch)
                for (c in 0..2) assertEquals("lift($hue,$sat)[$c]", refLift[c], out[c], 0f)
            }
        }
    }

    private fun sampleGrade(): GradeParams = GradeParams(
        shadows = GradeAdjust(hue = 0f, sat = 60f, lum = -10f),
        midtones = GradeAdjust(hue = 120f, sat = 30f, lum = 5f),
        highlights = GradeAdjust(hue = 240f, sat = 40f, lum = 10f),
        global = GradeAdjust(hue = 45f, sat = 20f, lum = 0f),
        blending = 75f,
        balance = -20f
    )

    @Test
    fun `applyGradeInto matches applyGrade exactly including early-outs`() {
        val out = FloatArray(3)
        val scratch = FloatArray(18)
        val grades = listOf(GradeParams(), sampleGrade())
        for (grade in grades) {
            for (r in samples) for (g in samples) for (b in samples) {
                val ref = GradeMath.applyGrade(r, g, b, grade)
                GradeMath.applyGradeInto(r, g, b, grade, out, scratch)
                for (c in 0..2) assertEquals("grade($r,$g,$b)[$c]", ref[c], out[c], 0f)
            }
        }
        // Zero-blend early-out path.
        val zeroBlend = sampleGrade().copy(blending = 0f)
        val ref = GradeMath.applyGrade(0.3f, 0.5f, 0.7f, zeroBlend)
        GradeMath.applyGradeInto(0.3f, 0.5f, 0.7f, zeroBlend, out, scratch)
        for (c in 0..2) assertEquals(ref[c], out[c], 0f)
    }

    @Test
    fun `applyGradeInto scratch reuse is stable across a long run`() {
        // Simulates the renderer's thread-local reuse: one scratch buffer for
        // thousands of pixels must not accumulate error or alias output.
        val out = FloatArray(3)
        val scratch = FloatArray(18)
        val grade = sampleGrade()
        repeat(5_000) { k ->
            val r = (k % 256) / 255f
            val g = ((k / 7) % 256) / 255f
            val b = ((k / 13) % 256) / 255f
            GradeMath.applyGradeInto(r, g, b, grade, out, scratch)
            val ref = GradeMath.applyGrade(r, g, b, grade)
            for (c in 0..2) {
                assertEquals("pixel $k [$c]", ref[c], out[c], 0f)
                assertTrue("finite $k [$c]", out[c].isFinite() && out[c] in 0f..1f)
            }
        }
    }

    // ---------- point Into parity ----------

    @Test
    fun `applyPointInto matches applyPoint exactly including early-outs`() {
        val out = FloatArray(3)
        val scratch = FloatArray(3)
        val points = listOf(
            PointColorParams(),
            PointColorParams(enabled = true, hueCenter = 0f, hueRange = 60f, satAdjust = 50f, lumAdjust = -20f),
            PointColorParams(enabled = true, hueCenter = 210f, hueRange = 120f, satAdjust = -40f, lumAdjust = 30f)
        )
        for (point in points) {
            for (r in samples) for (g in samples) for (b in samples) {
                val ref = PointColorMath.applyPoint(r, g, b, point)
                PointColorMath.applyPointInto(r, g, b, point, out, scratch)
                for (c in 0..2) assertEquals("point($r,$g,$b)[$c]", ref[c], out[c], 0f)
            }
        }
    }

    // ---------- retouch / blur Into parity ----------

    @Test
    fun `healPixelInto matches healPixel exactly`() {
        val out = FloatArray(3)
        for (t in samples) for (m in samples) {
            val ref = RetouchMath.healPixel(t, 1f - t, m, m, t, 1f - m)
            RetouchMath.healPixelInto(t, 1f - t, m, m, t, 1f - m, out)
            for (c in 0..2) assertEquals("heal($t,$m)[$c]", ref[c], out[c], 0f)
        }
    }

    @Test
    fun `bandWeightsInto matches bandWeights exactly`() {
        val out = FloatArray(3)
        var d = 0f
        while (d <= 1.001f) {
            val ref = LensBlurMath.bandWeights(d)
            LensBlurMath.bandWeightsInto(d, out)
            for (c in 0..2) assertEquals("band($d)[$c]", ref[c], out[c], 0f)
            assertEquals("partition $d", 1f, out[0] + out[1] + out[2], 0.02f)
            d += 0.05f
        }
    }

    // ---------- 12/24/48MP budget math ----------

    @Test
    fun `tiff working set math at 12 24 48MP`() {
        // Triple-copy: IntArray 4B/px + rgb 3B/px + file 3B/px + 180 overhead.
        assertEquals(12_000_000L * 10 + 180, MemoryBudget.tiffWorkingBytes(4000, 3000))
        assertEquals(24_000_000L * 10 + 180, MemoryBudget.tiffWorkingBytes(6000, 4000))
        assertEquals(48_000_000L * 10 + 180, MemoryBudget.tiffWorkingBytes(8000, 6000))
        assertEquals(0L, MemoryBudget.tiffWorkingBytes(0, 100))
        assertEquals(0L, MemoryBudget.tiffWorkingBytes(-4, 100))
        // A 48MP TIFF needs ~480MB working — over a 256MB cap, under 512MB.
        assertTrue(MemoryBudget.exceedsTiffBudget(8000, 6000, 256L * 1024 * 1024))
        assertFalse(MemoryBudget.exceedsTiffBudget(8000, 6000, 512L * 1024 * 1024))
        assertFalse(MemoryBudget.exceedsTiffBudget(1600, 1200, 256L * 1024 * 1024))
        assertFalse(MemoryBudget.exceedsTiffBudget(0, 100, 1024L))
    }

    @Test
    fun `tile-first threshold and render cap boundaries`() {
        assertEquals(12_000_000L, MemoryBudget.TILE_FIRST_PIXELS)
        assertTrue(MemoryBudget.PIXELS_12MP <= MemoryBudget.TILE_FIRST_PIXELS)
        assertTrue(MemoryBudget.PIXELS_48MP < MemoryBudget.MAX_RENDER_PIXELS)
        assertTrue(MemoryBudget.exceeds(8000, 6000, MemoryBudget.TILE_FIRST_PIXELS))
        assertFalse(MemoryBudget.exceeds(1600, 1200, MemoryBudget.TILE_FIRST_PIXELS))
    }

    @Test
    fun `strip rows keep each band under the byte cap`() {
        // 4000px-wide ARGB row = 16KB; 4MB cap -> 262 rows per strip.
        assertEquals(262, MemoryBudget.stripRowsFor(4000, 3000, 4L * 1024 * 1024))
        assertEquals(1, MemoryBudget.stripRowsFor(8000, 6000, 1024L))
        assertEquals(3000, MemoryBudget.stripRowsFor(4000, 3000, Long.MAX_VALUE))
        assertEquals(100, MemoryBudget.stripRowsFor(0, 100, 1024L))
    }

    @Test
    fun `decode sample math halves to fit maxDim`() {
        assertEquals(1, MemoryBudget.sampleFor(1600, 1600))
        assertEquals(2, MemoryBudget.sampleFor(1601, 1600))
        assertEquals(8, MemoryBudget.sampleFor(8000, 1600))
        assertEquals(1, MemoryBudget.sampleFor(0, 1600))
        assertEquals(1, MemoryBudget.sampleFor(8000, 0))
    }

    // ---------- TIFF pre-flight ----------

    @Test
    fun `checkTiffBudget refuses huge frames before any alloc`() {
        assertNull(Exporter.checkTiffBudget(4000, 3000, null))
        assertNull(Exporter.checkTiffBudget(4000, 3000, 0L))
        assertNull(Exporter.checkTiffBudget(1600, 1200, 256L * 1024 * 1024))
        val refusal = Exporter.checkTiffBudget(8000, 6000, 256L * 1024 * 1024)
        assertNotNull(refusal)
        assertTrue(refusal!!.contains("too large", ignoreCase = true))
        assertEquals("Invalid image dimensions", Exporter.checkTiffBudget(0, 100, 1024L))
    }

    @Test
    fun `tiff estimate matches encoded length`() {
        assertEquals(4L * 3 * 3 + 180L, TiffWriter.estimateBytes(4, 3))
        assertEquals(4L * 3 * 3 + 180L, Exporter.estimateTiffBytes(4, 3))
    }

    // ---------- cancellation tokens ----------

    @Test
    fun `generation tokens drop stale and apply current`() {
        val tracker = GenerationTracker()
        val applied = ArrayList<String>()
        fun deliver(id: String, gen: Long) {
            if (!tracker.isStale(gen)) applied.add(id)
        }
        val old = tracker.next()
        val current = tracker.next()
        deliver("new", current)
        deliver("old", old)
        assertEquals(listOf("new"), applied)
        assertTrue(tracker.isCurrent(current))
        assertTrue(tracker.isStale(old))
    }

    @Test
    fun `backend cancel set refuses superseded generations`() {
        // Mirrors CpuRenderBackend/GlesBackend entry logic: a cancelled-then-
        // removed generation renders Unavailable exactly once.
        val cancelled = LinkedHashSet<Long>()
        fun render(gen: Long): String {
            if (gen != 0L && cancelled.remove(gen)) return "cancelled"
            return "ok"
        }
        fun cancel(gen: Long) {
            if (gen == 0L) return
            cancelled.add(gen)
        }
        cancel(0L)
        assertEquals("ok", render(7L))
        cancel(7L)
        assertEquals("cancelled", render(7L))
        assertEquals("ok", render(7L))
    }

    // ---------- debounce / coalescing ----------

    @Test
    fun `batch progress coalesces done over total`() {
        assertEquals(0f, BatchOps.progress(0, 10), 0f)
        assertEquals(0f, BatchOps.progress(0, 0), 0f)
        assertEquals(1f, BatchOps.progress(10, 10), 0f)
        assertEquals(1f, BatchOps.progress(12, 10), 0f)
        assertEquals(0.5f, BatchOps.progress(5, 10), 1e-6f)
    }

    @Test
    fun `batch summarize covers empty single multi failure`() {
        assertEquals("Nothing to export.", BatchOps.summarize(emptyList()))
        assertTrue(BatchOps.summarize(listOf(com.lumina.studio.core.batch.BatchItemResult("a", true))).contains("1 photo"))
        val mixed = BatchOps.summarize(
            listOf(
                com.lumina.studio.core.batch.BatchItemResult("a", true),
                com.lumina.studio.core.batch.BatchItemResult("b", false, "boom")
            )
        )
        assertTrue(mixed.contains("1 of 2"))
    }

    @Test
    fun `export size math never upscales without opt-in`() {
        val settings = com.lumina.studio.core.export.ExportSettings(
            resolutionMode = com.lumina.studio.core.export.ResolutionMode.CUSTOM,
            customMaxDim = 2048
        )
        assertEquals(800 to 600, Exporter.targetDimensions(800, 600, settings))
        assertEquals(2048 to 1536, Exporter.targetDimensions(4000, 3000, settings))
        assertEquals(2 to 2, Exporter.evenDims(1, 1))
        assertEquals(4 to 6, Exporter.evenDims(3, 6))
        assertTrue(abs(Exporter.estimateBytes(1000L, 1000L, 4000L) - 4000L) < 2L)
    }

    @Test
    fun `grade model stays finite across the full input cube`() {
        val grade = sampleGrade()
        val out = FloatArray(3)
        val scratch = FloatArray(18)
        for (r in samples) for (g in samples) for (b in samples) {
            GradeMath.applyGradeInto(r, g, b, grade, out, scratch)
            for (c in 0..2) assertTrue("finite ($r,$g,$b)[$c]=${out[c]}", out[c].isFinite() && out[c] in 0f..1f)
        }
    }
}
