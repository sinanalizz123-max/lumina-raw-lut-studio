package com.lumina.studio.render

import com.lumina.studio.core.edit.EditMask
import com.lumina.studio.core.edit.EditParams
import com.lumina.studio.core.lut.LutCube
import com.lumina.studio.core.render.ColorManager
import com.lumina.studio.core.render.ColorMatrices
import com.lumina.studio.core.render.Dims
import com.lumina.studio.core.render.ExportRenderer
import com.lumina.studio.core.render.GenerationTracker
import com.lumina.studio.core.render.ImageDecoder
import com.lumina.studio.core.render.MaskEngine
import com.lumina.studio.core.render.MemoryBudget
import com.lumina.studio.core.render.PixelRect
import com.lumina.studio.core.render.RawCapabilities
import com.lumina.studio.core.render.RawDecoder
import com.lumina.studio.core.render.RawRecipe
import com.lumina.studio.core.render.RenderBackend
import com.lumina.studio.core.render.RenderColorSpace
import com.lumina.studio.core.render.RenderRequest
import com.lumina.studio.core.render.RenderResult
import com.lumina.studio.core.render.RenderSource
import com.lumina.studio.core.render.RenderTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M4 pure-JVM guards for the render backend abstractions (no android.*,
 * no Robolectric).
 *
 * Covers: GenerationTracker staleness (§53 stale-drop), the RenderBackend
 * interface contract via a fake image type, MemoryBudget refusal math,
 * ColorMatrices sRGB<->Display P3 + working-space round-trips, the
 * RawCapabilities honesty table (nothing claims editable), and the
 * ImageDecoder / MaskEngine / ColorManager / ExportRenderer contracts via
 * fakes. Bitmap-touching CPU implementations stay on-device-only.
 */
class RenderContractsTest {

    private data class FakeImage(val id: String, val width: Int, val height: Int)

    private class FakeBackend : RenderBackend<FakeImage> {
        val cancelled = mutableSetOf<Long>()
        var renders = 0

        override fun render(request: RenderRequest<FakeImage>): RenderResult<FakeImage> {
            renders++
            if (request.generation in cancelled) return RenderResult.Unavailable("cancelled")
            if (request.source.width <= 0 || request.source.height <= 0) {
                return RenderResult.Unavailable("empty source")
            }
            if (MemoryBudget.exceeds(request.source.width, request.source.height)) {
                return RenderResult.Unavailable("too large")
            }
            return RenderResult.Ok(request.source)
        }

        override fun cancel(generation: Long) {
            cancelled.add(generation)
        }
    }

    private class FakeDecoder(private val known: Map<String, Dims>) : ImageDecoder<FakeImage> {
        override val name: String = "fake"

        override fun bounds(source: RenderSource): Dims? = when (source) {
            is RenderSource.File -> known[source.path]
            is RenderSource.Content -> known[source.uri]
        }

        override fun decode(source: RenderSource, maxDim: Int): FakeImage? {
            val dims = bounds(source) ?: return null
            if (maxDim <= 0) return null
            return FakeImage(keyOf(source), dims.width, dims.height)
        }

        override fun region(source: RenderSource, rectPx: PixelRect, sample: Int): FakeImage? {
            if (rectPx.isEmpty() || sample < 1) return null
            if (bounds(source) == null) return null
            return FakeImage(keyOf(source), rectPx.width, rectPx.height)
        }

        private fun keyOf(source: RenderSource): String = when (source) {
            is RenderSource.File -> source.path
            is RenderSource.Content -> source.uri
        }
    }

    private class FakeRaw : RawDecoder<FakeImage> {
        override fun capabilities(): List<com.lumina.studio.core.render.RawCapability> =
            RawCapabilities.TABLE

        override fun develop(
            source: RenderSource,
            maxDim: Int,
            recipe: RawRecipe?
        ): FakeImage? = null
    }

    private class FakeColors : ColorManager<FakeImage> {
        override fun toWorking(rgb: FloatArray): FloatArray = floatArrayOf(
            ColorMatrices.srgbToLinear(rgb[0]),
            ColorMatrices.srgbToLinear(rgb[1]),
            ColorMatrices.srgbToLinear(rgb[2])
        )

        override fun fromWorking(working: FloatArray): FloatArray = floatArrayOf(
            ColorMatrices.linearToSrgb(working[0]).coerceIn(0f, 1f),
            ColorMatrices.linearToSrgb(working[1]).coerceIn(0f, 1f),
            ColorMatrices.linearToSrgb(working[2]).coerceIn(0f, 1f)
        )

        override fun convert(
            image: FakeImage,
            src: RenderColorSpace,
            dst: RenderColorSpace
        ): FakeImage = image
    }

    private class FakeMasks : MaskEngine<FakeImage> {
        override fun composite(base: FakeImage, masks: List<EditMask>): FakeImage = base
    }

    private class FakeExport : ExportRenderer<FakeImage> {
        override fun renderForExport(
            src: FakeImage,
            params: EditParams,
            lut: LutCube?,
            targetW: Int,
            targetH: Int
        ): FakeImage = src
    }

    private fun identityLut(): LutCube {
        val size = 2
        val data = FloatArray(size * size * size * 3)
        var k = 0
        for (b in 0 until size) for (g in 0 until size) for (r in 0 until size) {
            data[k++] = r.toFloat()
            data[k++] = g.toFloat()
            data[k++] = b.toFloat()
        }
        return LutCube(title = null, size = size, is3D = true, data = data)
    }

    // ---------- GenerationTracker (§53) ----------

    @Test
    fun `tracker issues increasing generations and flags stale`() {
        val tracker = GenerationTracker()
        val g1 = tracker.next()
        val g2 = tracker.next()
        val g3 = tracker.next()
        assertTrue(g1 < g2 && g2 < g3)
        assertEquals(g3, tracker.current())
        assertTrue(tracker.isCurrent(g3))
        assertTrue(tracker.isStale(g1))
        assertTrue(tracker.isStale(g2))
        assertFalse(tracker.isStale(g3))
        assertFalse(tracker.isCurrent(g1))
    }

    @Test
    fun `stale render results are dropped and current applied`() {
        val tracker = GenerationTracker()
        val backend = FakeBackend()
        val applied = ArrayList<String>()

        fun deliver(request: RenderRequest<FakeImage>) {
            when (val result = backend.render(request)) {
                is RenderResult.Ok -> {
                    if (!tracker.isStale(request.generation)) {
                        applied.add(result.bitmap.id)
                    }
                }
                is RenderResult.Unavailable -> Unit
                RenderResult.OomBudget -> Unit
            }
        }

        val old = RenderRequest(EditParams.DEFAULT, null, FakeImage("old", 8, 8), RenderTarget.Thumb, tracker.next())
        val current = RenderRequest(EditParams.DEFAULT, null, FakeImage("new", 8, 8), RenderTarget.Thumb, tracker.next())
        // The older request finishes last: it must be dropped.
        deliver(current)
        deliver(old)
        assertEquals(listOf("new"), applied)
    }

    @Test
    fun `cancelled generations render Unavailable`() {
        val backend = FakeBackend()
        val tracker = GenerationTracker()
        val gen = tracker.next()
        backend.cancel(gen)
        val request = RenderRequest(
            EditParams.DEFAULT, identityLut(), FakeImage("x", 16, 16),
            RenderTarget.Preview(1600), gen
        )
        val result = backend.render(request)
        assertTrue(result is RenderResult.Unavailable)
        assertEquals("cancelled", (result as RenderResult.Unavailable).reason)
        assertEquals(1, backend.renders)
    }

    @Test
    fun `request carries params lut source target generation`() {
        val lut = identityLut()
        val src = FakeImage("s", 4, 4)
        val target = RenderTarget.Export(200, 100)
        val request = RenderRequest(EditParams.DEFAULT, lut, src, target, 7L)
        assertEquals(EditParams.DEFAULT, request.params)
        assertSame(lut, request.lut)
        assertSame(src, request.source)
        assertEquals(target, request.target)
        assertEquals(7L, request.generation)
        assertNull(RenderRequest(EditParams.DEFAULT, null, src, target, 1L).lut)
    }

    // ---------- MemoryBudget ----------

    @Test
    fun `budget refuses oversized frames at the boundary`() {
        val cap = MemoryBudget.MAX_RENDER_PIXELS
        assertFalse(MemoryBudget.exceeds(8000, 6000))
        assertFalse(MemoryBudget.exceeds(Dims(4000, 3000)))
        assertFalse(MemoryBudget.exceeds(0, 100))
        assertFalse(MemoryBudget.exceeds(-5, 100))
        assertFalse(MemoryBudget.exceeds(null as Dims?))
        assertFalse(MemoryBudget.exceeds(Dims(0, 0)))
        assertFalse(MemoryBudget.exceeds(100, 100, capPixels = 10_000L))
        assertTrue(MemoryBudget.exceeds(100, 101, capPixels = 10_000L))
        // Exact large-frame math: bytes are computed before any allocation.
        assertEquals(4000L * 3000 * 4, MemoryBudget.bytesFor(4000, 3000))
        assertEquals(0L, MemoryBudget.bytesFor(0, 3000))
        assertEquals(0L, MemoryBudget.bytesFor(100, -2))
    }

    @Test
    fun `fake backend refuses too-large frames gracefully`() {
        val backend = FakeBackend()
        val huge = RenderRequest(
            EditParams.DEFAULT, null, FakeImage("huge", 20000, 20000),
            RenderTarget.Fullscreen(4096), 1L
        )
        val result = backend.render(huge)
        assertTrue(result is RenderResult.Unavailable)
        assertEquals("too large", (result as RenderResult.Unavailable).reason)
        val empty = RenderRequest(
            EditParams.DEFAULT, null, FakeImage("empty", 0, 0),
            RenderTarget.Thumb, 2L
        )
        assertTrue(backend.render(empty) is RenderResult.Unavailable)
    }

    // ---------- ColorMatrices ----------

    @Test
    fun `srgb to p3 to srgb round-trips approximately`() {
        val samples = listOf(
            floatArrayOf(0f, 0f, 0f),
            floatArrayOf(1f, 1f, 1f),
            floatArrayOf(1f, 0f, 0f),
            floatArrayOf(0f, 1f, 0f),
            floatArrayOf(0f, 0f, 1f),
            floatArrayOf(0.5f, 0.5f, 0.5f),
            floatArrayOf(0.2f, 0.6f, 0.9f),
            floatArrayOf(0.9f, 0.1f, 0.4f)
        )
        for (rgb in samples) {
            val p3 = ColorMatrices.srgbToDisplayP3(rgb)
            for (v in p3) assertTrue("p3 $v in 0..1", v.isFinite() && v in 0f..1f)
            val back = ColorMatrices.displayP3ToSrgb(p3)
            for (c in 0..2) {
                assertEquals("channel $c of ${rgb.toList()}", rgb[c], back[c], 0.004f)
            }
        }
    }

    @Test
    fun `working space round-trips and matrices stay finite`() {
        for (m in listOf(
            ColorMatrices.SRGB_TO_XYZ_D65,
            ColorMatrices.XYZ_TO_SRGB_D65,
            ColorMatrices.P3_TO_XYZ_D65,
            ColorMatrices.XYZ_TO_P3_D65
        )) {
            assertEquals(9, m.size)
            for (v in m) assertTrue("matrix value $v finite", v.isFinite())
        }
        val steps = listOf(0f, 0.04f, 0.25f, 0.5f, 0.75f, 1f)
        for (c in steps) {
            assertEquals(c, ColorMatrices.linearToSrgb(ColorMatrices.srgbToLinear(c)), 0.0005f)
        }
        val colors = FakeColors()
        val rgb = floatArrayOf(0.25f, 0.5f, 0.75f)
        val back = colors.fromWorking(colors.toWorking(rgb))
        for (c in 0..2) assertEquals(rgb[c], back[c], 0.0005f)
    }

    // ---------- RawCapabilities honesty ----------

    @Test
    fun `no raw format claims editable`() {
        val table = RawCapabilities.TABLE
        assertTrue(table.size >= 11)
        for (cap in table) {
            assertFalse("format ${cap.format} must not claim editable", cap.editable)
            assertTrue(cap.note.isNotBlank())
        }
        val dng = RawCapabilities.capabilityOf("dng")
        assertNotNull(dng)
        assertTrue(dng!!.note.contains("preview", ignoreCase = true))
        for (proprietary in listOf("cr2", "cr3", "nef", "arw", "raf", "rw2", "orf")) {
            val cap = RawCapabilities.capabilityOf(proprietary)
            assertNotNull(proprietary, cap)
            assertFalse(cap!!.editable)
        }
        assertNull(RawCapabilities.capabilityOf("jpg"))
        assertNull(RawCapabilities.capabilityOf(""))
        assertNotNull(RawCapabilities.capabilityOf(" DNG "))
    }

    @Test
    fun `fake raw decoder exposes the honesty table and develops nothing`() {
        val raw = FakeRaw()
        assertEquals(RawCapabilities.TABLE, raw.capabilities())
        assertTrue(raw.capabilities().none { it.editable })
        assertNull(raw.develop(RenderSource.File("/sdcard/IMG_001.dng"), 1600, RawRecipe()))
        assertNull(raw.develop(RenderSource.Content("content://x"), 800, null))
    }

    // ---------- ImageDecoder / MaskEngine / ExportRenderer contracts ----------

    @Test
    fun `fake decoder honors bounds decode region`() {
        val decoder = FakeDecoder(mapOf("a" to Dims(4000, 3000)))
        assertEquals("fake", decoder.name)
        assertEquals(Dims(4000, 3000), decoder.bounds(RenderSource.File("a")))
        assertNull(decoder.bounds(RenderSource.File("missing")))
        val decoded = decoder.decode(RenderSource.File("a"), 1600)
        assertNotNull(decoded)
        assertEquals("a", decoded!!.id)
        assertNull(decoder.decode(RenderSource.File("missing"), 1600))
        val tile = decoder.region(RenderSource.File("a"), PixelRect(0, 0, 64, 64), 2)
        assertNotNull(tile)
        assertEquals(64, tile!!.width)
        assertNull(decoder.region(RenderSource.File("a"), PixelRect(5, 5, 5, 5), 1))
        assertNull(decoder.region(RenderSource.File("missing"), PixelRect(0, 0, 8, 8), 1))
    }

    @Test
    fun `fake mask export and color backends pass images through`() {
        val base = FakeImage("base", 32, 32)
        assertSame(base, FakeMasks().composite(base, emptyList()))
        assertSame(base, FakeExport().renderForExport(base, EditParams.DEFAULT, null, 16, 16))
        assertSame(
            base,
            FakeColors().convert(base, RenderColorSpace.SRGB, RenderColorSpace.DISPLAY_P3)
        )
    }

    // ---------- RenderSource / value types ----------

    @Test
    fun `render source distinguishes files from content`() {
        val tmp = java.io.File.createTempFile("lumina_render_src", ".jpg")
        try {
            val file = RenderSource.of(tmp.absolutePath)
            assertTrue(file is RenderSource.File)
            assertEquals(tmp.absolutePath, (file as RenderSource.File).path)
        } finally {
            tmp.delete()
        }
        val content = RenderSource.of("content://media/external/images/1")
        assertTrue(content is RenderSource.Content)
        assertTrue(Dims(3, 4).pixels == 12L)
        assertTrue(Dims(0, 5).isEmpty())
        assertTrue(PixelRect(0, 0, 0, 8).isEmpty())
        assertFalse(PixelRect(0, 0, 8, 8).isEmpty())
    }
}
