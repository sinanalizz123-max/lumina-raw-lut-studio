package com.lumina.studio.export

import com.lumina.studio.core.edit.EditParams
import com.lumina.studio.core.export.ExportFormat
import com.lumina.studio.core.export.ExportSettings
import com.lumina.studio.core.export.Exporter
import com.lumina.studio.core.export.QualityPreset
import com.lumina.studio.core.export.ResolutionMode
import com.lumina.studio.core.lut.BuiltInPresets
import com.lumina.studio.core.lut.LutCube
import com.lumina.studio.core.lut.LutRenderer
import com.lumina.studio.core.lut.LutRegistry
import com.lumina.studio.core.lut.PresetCategory
import com.lumina.studio.core.render.PreviewRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Phase 2 JVM-safe math + contract guards for LutRenderer / Exporter /
 * BuiltInPresets / PreviewRenderer.isIdentity (no Robolectric, no Bitmap).
 *
 * `applyLut`, `render`, `computeHistogram`, `renderForExport` and `compress`
 * touch android.graphics.Bitmap/Canvas, which does not exist on the plain
 * JVM — they are covered here only as source contracts (asserting the
 * Bitmap dependency) plus mirrors of their inner math (intensity blend,
 * trilinear weights, histogram bin, estimator scaling). Behavioural
 * pixel proof needs an on-device / Robolectric-shadow run (see bottom test).
 */
class LutExportMathTest {

    private fun mainSource(relative: String): String {
        val candidates = listOf(
            File("src/main/java/$relative"),
            File("app/src/main/java/$relative"),
            File("/storage/emulated/0/opencode/Photoeditea/Lumina/app/src/main/java/$relative")
        )
        val found = candidates.firstOrNull { it.isFile }
        assertTrue(
            "Source not found: $relative (user.dir=${System.getProperty("user.dir")})",
            found != null
        )
        return found!!.readText()
    }

    // Mirrors LutRenderer.applyLut intensity blend (LutRenderer.kt lines ~36-38):
    //   val fr = if (full) out[0] else r0 + (out[0] - r0) * t
    private fun blend(orig: Float, mapped: Float, intensity: Float): Float {
        val t = intensity.coerceIn(0f, 1f)
        return orig + (mapped - orig) * t
    }

    // Mirrors LutRenderer.sample3D trilinear weights (lines ~75-81).
    private fun trilinearWeights(fx: Float, fy: Float, fz: Float): FloatArray {
        val wx = floatArrayOf(1f - fx, fx)
        val wy = floatArrayOf(1f - fy, fy)
        val wz = floatArrayOf(1f - fz, fz)
        val out = FloatArray(8)
        var k = 0
        for (bz in 0..1) for (gy in 0..1) for (rx in 0..1) {
            out[k++] = wx[rx] * wy[gy] * wz[bz]
        }
        return out
    }

    // Mirrors PreviewRenderer.computeHistogram binning: (v * bins) ushr 8.
    private fun histBin(v: Int, bins: Int): Int = (v * bins) ushr 8

    private fun linear1D(size: Int, title: String? = null): LutCube {
        val data = FloatArray(size * 3) { i -> (i / 3) / (size - 1f) }
        return LutCube(title = title, size = size, is3D = false, data = data)
    }

    private fun identity3D(size: Int): LutCube {
        val data = FloatArray(size * size * size * 3)
        var k = 0
        for (b in 0 until size) for (g in 0 until size) for (r in 0 until size) {
            val d = (size - 1).toFloat()
            data[k++] = r / d; data[k++] = g / d; data[k++] = b / d
        }
        return LutCube(title = null, size = size, is3D = true, data = data)
    }

    // ---------- estimator scaling (Exporter.estimateBytes is pure JVM) ----------

    @Test
    fun `estimateBytes scales linearly`() {
        assertEquals(4000L, Exporter.estimateBytes(1000L, 100L, 400L))
        assertEquals(666L, Exporter.estimateBytes(1000L, 3L, 2L))
        assertEquals(1000L, Exporter.estimateBytes(1000L, 50L, 50L))
    }

    @Test
    fun `estimateBytes guards non-positive inputs with 0`() {
        assertEquals(0L, Exporter.estimateBytes(0L, 100L, 100L))
        assertEquals(0L, Exporter.estimateBytes(-5L, 100L, 100L))
        assertEquals(0L, Exporter.estimateBytes(100L, 0L, 100L))
        assertEquals(0L, Exporter.estimateBytes(100L, 100L, 0L))
        assertEquals(0L, Exporter.estimateBytes(100L, -2L, 100L))
    }

    @Test
    fun `estimateBytes clamps tiny scaling to at least 1`() {
        // 1 byte for 1M pixels scaled to 1 pixel -> 1e-6 -> toLong 0 -> coerce to 1.
        assertEquals(1L, Exporter.estimateBytes(1L, 1_000_000L, 1L))
    }

    // ---------- intensity blend mirror ----------

    @Test
    fun `intensity blend endpoints and midpoint`() {
        assertEquals(0.2f, blend(0.2f, 0.8f, 0f), 0.0001f)
        assertEquals(0.8f, blend(0.2f, 0.8f, 1f), 0.0001f)
        assertEquals(0.5f, blend(0.2f, 0.8f, 0.5f), 0.0001f)
    }

    @Test
    fun `intensity blend clamps outside 0 to 1`() {
        assertEquals(0.2f, blend(0.2f, 0.8f, -1f), 0.0001f)
        assertEquals(0.8f, blend(0.2f, 0.8f, 2f), 0.0001f)
    }

    // ---------- trilinear weights mirror ----------

    @Test
    fun `trilinear weights sum to 1 and match corners`() {
        for ((fx, fy, fz) in listOf(
            Triple(0f, 0f, 0f), Triple(1f, 1f, 1f), Triple(0.5f, 0.5f, 0.5f),
            Triple(0.25f, 0.5f, 0.75f), Triple(0.1f, 0.9f, 0.3f)
        )) {
            val w = trilinearWeights(fx, fy, fz)
            assertEquals(8, w.size)
            assertEquals(1f, w.sum(), 0.0001f)
            for (v in w) assertTrue("weight $v in 0..1", v in 0f..1f)
        }
        val origin = trilinearWeights(0f, 0f, 0f)
        assertEquals(1f, origin[0], 0.0001f)
        val far = trilinearWeights(1f, 1f, 1f)
        assertEquals(1f, far[7], 0.0001f)
        for (v in trilinearWeights(0.5f, 0.5f, 0.5f)) {
            assertEquals(0.125f, v, 0.0001f)
        }
    }

    @Test
    fun `histogram bin formula stays in range`() {
        val bins = 64
        assertEquals(0, histBin(0, bins))
        assertEquals(bins - 1, histBin(255, bins))
        assertEquals(32, histBin(128, bins))
        for (v in 0..255) {
            val b = histBin(v, bins)
            assertTrue("bin $b for value $v", b in 0 until bins)
        }
    }

    // ---------- LutRenderer.effectiveTable (pure FloatArray math) ----------

    @Test
    fun `effectiveTable fast path returns same instance for small LUTs`() {
        assertEquals(33, LutRenderer.FAST_PATH_MAX_SIZE)
        assertEquals(32, LutRenderer.DOWNSAMPLED_SIZE)
        val small = identity3D(2)
        assertSame(small, LutRenderer.effectiveTable(small))
        assertSame(small, LutRenderer.effectiveTable(small))
    }

    @Test
    fun `effectiveTable downsamples oversized 1D-64 to 32 with finite data`() {
        val big = linear1D(64, title = "Big")
        val down = LutRenderer.effectiveTable(big)
        assertEquals(32, down.size)
        assertTrue(down.is3D)
        assertEquals(32 * 32 * 32 * 3, down.data.size)
        assertEquals("Big", down.title)
        for (v in down.data) {
            assertTrue("downsampled value $v finite 0..1", v.isFinite() && v in 0f..1f)
        }
        // Monotonic ramp preserved: first entry dark, last entry bright.
        assertTrue(down.data[0] < 0.1f)
        assertTrue(down.data.last() > 0.9f)
    }

    // ---------- BuiltInPresets + LutRegistry (pure until Bitmap SampleImage) ----------

    @Test
    fun `builtin presets are 8 unique finite LUTs`() {
        assertEquals(17, BuiltInPresets.LUT_SIZE)
        assertEquals(8, BuiltInPresets.list.size)
        val ids = BuiltInPresets.list.map { it.id }
        assertEquals(8, ids.toSet().size)
        for (preset in BuiltInPresets.list) {
            assertTrue(preset.id.isNotBlank())
            assertTrue(preset.name.isNotBlank())
            assertTrue(preset.defaultIntensity in 0f..1f)
            assertEquals(17, preset.lut.size)
            assertTrue(preset.lut.is3D)
            assertEquals(17 * 17 * 17 * 3, preset.lut.data.size)
            for (v in preset.lut.data) {
                assertTrue("preset ${preset.id} value $v", v.isFinite() && v in 0f..1f)
            }
            assertNotNull("byId(${preset.id})", BuiltInPresets.byId(preset.id))
        }
        assertNull(BuiltInPresets.byId("no_such_preset"))
        assertEquals(PresetCategory.FILM, PresetCategory.fromLabel(null))
        assertEquals(PresetCategory.FILM, PresetCategory.fromLabel("unknown"))
        assertEquals(PresetCategory.CINEMATIC, PresetCategory.fromLabel("cinematic"))
    }

    @Test
    fun `lutRegistry resolves builtins and caches parsed cubes`() {
        assertNotNull(LutRegistry.resolve("builtin_noir"))
        assertNull(LutRegistry.resolve("no_such_id"))
        assertNull(LutRegistry.resolve(null))
        val text = "TITLE \"T\"\nLUT_1D_SIZE 2\n0.0 0.0 0.0\n1.0 1.0 1.0\n"
        val id = "unit_test_parsed_lut"
        val first = LutRegistry.registerParsed(id, text)
        assertNotNull(first)
        assertEquals(2, first!!.size)
        // Second call with different text returns the cached instance.
        val second = LutRegistry.registerParsed(id, "TITLE Other\nLUT_1D_SIZE 3\n0.0 0.0 0.0\n0.5 0.5 0.5\n1.0 1.0 1.0\n")
        assertSame(first, second)
        // Invalid cube for an unknown id returns null without throwing.
        assertNull(LutRegistry.registerParsed("unit_test_bad_xyz", "garbage {{{"))
        // Explicit register round-trips.
        val custom = identity3D(2)
        LutRegistry.register("unit_test_custom_xyz", custom)
        assertSame(custom, LutRegistry.resolve("unit_test_custom_xyz"))
    }

    // ---------- PreviewRenderer.isIdentity + Exporter pure helpers ----------

    @Test
    fun `isIdentity true only for default params without LUT`() {
        assertEquals(1600, PreviewRenderer.MAX_PREVIEW_DIM)
        assertEquals(64, PreviewRenderer.HISTOGRAM_BINS)
        assertTrue(PreviewRenderer.isIdentity(EditParams.DEFAULT, false))
        assertTrue(!PreviewRenderer.isIdentity(EditParams.DEFAULT, true))
        val tweaked = EditParams.DEFAULT.copy(exposure = 1f)
        assertTrue(!PreviewRenderer.isIdentity(tweaked, false))
        assertTrue(!PreviewRenderer.isIdentity(tweaked, true))
    }

    @Test
    fun `targetDimensions keeps original unless custom cap is smaller`() {
        val orig = ExportSettings(resolutionMode = ResolutionMode.ORIGINAL, customMaxDim = 256)
        assertEquals(4000 to 3000, Exporter.targetDimensions(4000, 3000, orig))
        val custom = ExportSettings(resolutionMode = ResolutionMode.CUSTOM, customMaxDim = 2000)
        assertEquals(2000 to 1500, Exporter.targetDimensions(4000, 3000, custom))
        assertEquals(1500 to 2000, Exporter.targetDimensions(3000, 4000, custom))
        // Already smaller than cap: unchanged.
        assertEquals(800 to 600, Exporter.targetDimensions(800, 600, custom))
        // Degenerate input guarded.
        assertEquals(0 to 0, Exporter.targetDimensions(0, 600, custom))
        assertEquals(0 to 0, Exporter.targetDimensions(-5, 600, custom))
    }

    @Test
    fun `formatBytes effectiveQuality and displayName contracts`() {
        assertEquals("–", Exporter.formatBytes(0L))
        assertEquals("–", Exporter.formatBytes(-10L))
        assertEquals("512 B", Exporter.formatBytes(512L))
        assertTrue(Exporter.formatBytes(2048L).contains("KB"))
        assertTrue(Exporter.formatBytes(5L * 1024 * 1024).contains("MB"))
        assertTrue(Exporter.formatBytes(2L * 1024 * 1024 * 1024).contains("GB"))

        assertEquals(100, ExportSettings(qualityPreset = QualityPreset.MAXIMUM).effectiveQuality())
        assertEquals(90, ExportSettings(qualityPreset = QualityPreset.HIGH).effectiveQuality())
        assertEquals(73, ExportSettings(qualityPreset = QualityPreset.CUSTOM, customQuality = 73).effectiveQuality())
        assertEquals(1, ExportSettings(qualityPreset = QualityPreset.CUSTOM, customQuality = 0).effectiveQuality())
        assertEquals(100, ExportSettings(qualityPreset = QualityPreset.CUSTOM, customQuality = 500).effectiveQuality())

        val jpg = Exporter.displayName(ExportFormat.JPEG, 0L)
        assertTrue("displayName $jpg", jpg.startsWith("lumina_") && jpg.endsWith(".jpg"))
        val png = Exporter.displayName(ExportFormat.PNG, 0L)
        assertTrue("displayName $png", png.startsWith("lumina_") && png.endsWith(".png"))
    }

    // ---------- Bitmap-touching surface must stay on-device (source contract) ----------

    @Test
    fun `bitmap-touching render paths are documented as on-device-only`() {
        val lutSrc = mainSource("com/lumina/studio/core/lut/LutRenderer.kt")
        assertTrue(lutSrc.contains("android.graphics.Bitmap"))
        assertTrue(lutSrc.contains("getPixels"))
        assertTrue(lutSrc.contains("createBitmap"))

        val previewSrc = mainSource("com/lumina/studio/core/render/PreviewRenderer.kt")
        assertTrue(previewSrc.contains("android.graphics.Bitmap"))
        assertTrue(previewSrc.contains("getPixels") || previewSrc.contains("Canvas"))

        val exportSrc = mainSource("com/lumina/studio/core/export/Exporter.kt")
        assertTrue(exportSrc.contains("android.graphics.Bitmap"))
        assertTrue(exportSrc.contains("createScaledBitmap") || exportSrc.contains("compress"))

        val presetSrc = mainSource("com/lumina/studio/core/lut/BuiltInPresets.kt")
        assertTrue(presetSrc.contains("android.graphics.Bitmap"))

        // If any of the above were ever rewritten to be Bitmap-free, the
        // math mirrors at the top of this file (blend/trilinear/histBin)
        // should be replaced by direct calls and verified with shadows or
        // on-device screenshots. Until then: applyLut(src: Bitmap),
        // PreviewRenderer.render/computeHistogram/decodePreview,
        // Exporter.renderForExport/compress, and SampleImage.placeholder
        // are UNTESTABLE on plain JVM (android.graphics.Bitmap absent).
    }
}
