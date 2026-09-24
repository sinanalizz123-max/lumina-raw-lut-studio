package com.lumina.studio.export

import com.lumina.studio.core.edit.EditParams
import com.lumina.studio.core.export.ExportColorSpace
import com.lumina.studio.core.export.Exporter
import com.lumina.studio.core.export.TiffWriter
import com.lumina.studio.core.render.PreviewRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 4 pure-JVM guards for the export workflow's JVM-testable surface
 * (no android.* imports, no Robolectric).
 *
 * Covers: ExportColorSpace.fromKey, sidecarNameFor/rawOriginalName exact
 * mapping, buildSidecarJson recipe + version + honesty note, the 3-arg
 * isRawSource(fileType, sourcePath, mimeType) overload, estimateTiffBytes
 * delegation, and PreviewRenderer preview-dimension gating
 * (previewMaxDimFor / effectivePreviewMaxDim / shouldAutoHistogram).
 *
 * Bitmap/Context-touching paths (renderForExport, compress, encodeTiff
 * Bitmap overload, saveToGallery, exportRawOriginal, exportSidecar,
 * withColorSpace, isWideGamutDisplay, isRawSource(Project)) stay
 * on-device-only and are intentionally not called here.
 */
class ExportWorkflowTest {

    // ---------- ExportColorSpace.fromKey ----------

    @Test
    fun `fromKey maps sRGB variants to SRGB`() {
        assertEquals(ExportColorSpace.SRGB, ExportColorSpace.fromKey("sRGB"))
        assertEquals(ExportColorSpace.SRGB, ExportColorSpace.fromKey("srgb"))
        assertEquals(ExportColorSpace.SRGB, ExportColorSpace.fromKey("SRGB"))
        assertEquals(ExportColorSpace.SRGB, ExportColorSpace.fromKey(" sRGB "))
    }

    @Test
    fun `fromKey maps Display P3 variants to DISPLAY_P3`() {
        assertEquals(ExportColorSpace.DISPLAY_P3, ExportColorSpace.fromKey("Display P3"))
        assertEquals(ExportColorSpace.DISPLAY_P3, ExportColorSpace.fromKey("display p3"))
        assertEquals(ExportColorSpace.DISPLAY_P3, ExportColorSpace.fromKey("display_p3"))
        assertEquals(ExportColorSpace.DISPLAY_P3, ExportColorSpace.fromKey("p3"))
        assertEquals(ExportColorSpace.DISPLAY_P3, ExportColorSpace.fromKey(" P3 "))
    }

    @Test
    fun `fromKey defaults garbage and null to SRGB`() {
        assertEquals(ExportColorSpace.SRGB, ExportColorSpace.fromKey("garbage"))
        assertEquals(ExportColorSpace.SRGB, ExportColorSpace.fromKey(""))
        assertEquals(ExportColorSpace.SRGB, ExportColorSpace.fromKey(null))
        assertEquals(ExportColorSpace.SRGB, ExportColorSpace.fromKey("AdobeRGB"))
    }

    // ---------- sidecar / raw names (exact fns) ----------

    @Test
    fun `sidecarNameFor maps raw name to lumina json`() {
        assertEquals("x.lumina.json", Exporter.sidecarNameFor("x.dng"))
        assertEquals("IMG_001.lumina.json", Exporter.sidecarNameFor("IMG_001.dng"))
        assertEquals("photo.lumina.json", Exporter.sidecarNameFor("photo.tif"))
        // No extension: base used as-is with the suffix appended.
        assertEquals("rawfile.lumina.json", Exporter.sidecarNameFor("rawfile"))
    }

    @Test
    fun `rawOriginalName preserves basename and defaults`() {
        assertEquals("IMG_001.dng", Exporter.rawOriginalName("/sdcard/DCIM/IMG_001.dng"))
        assertEquals("photo.cr3", Exporter.rawOriginalName("/sdcard/DCIM/photo.cr3"))
        assertEquals("lumina_raw.dng", Exporter.rawOriginalName(null))
        assertEquals("lumina_raw.dng", Exporter.rawOriginalName(""))
        // Bare name without an extension gains a .dng suffix.
        assertEquals("rawfile.dng", Exporter.rawOriginalName("/a/b/rawfile"))
    }

    // ---------- buildSidecarJson ----------

    @Test
    fun `buildSidecarJson contains recipe version and honesty note`() {
        val json = Exporter.buildSidecarJson(EditParams.DEFAULT, "1.0-test", "IMG_001.dng")
        assertTrue(json.contains("\"app\":\"Lumina RAW & LUT Studio\""))
        assertTrue(json.contains("\"appVersion\":\"1.0-test\""))
        assertTrue(json.contains("\"workflow\":\"raw-compatible\""))
        assertTrue(json.contains("\"sourceFile\":\"IMG_001.dng\""))
        // Recipe: embedded EditParams JSON carries the adjust keys.
        assertTrue(json.contains("\"recipe\":"))
        assertTrue(json.contains("\"exposure\""))
        // Honesty note: sensor data untouched, edits are a re-editable recipe.
        assertTrue(json.contains("\"note\":"))
        assertTrue(json.contains("untouched"))
        assertTrue(json.contains("re-editable"))
        assertTrue(json.contains("NOT baked in"))
    }

    @Test
    fun `buildSidecarJson embeds tweaked params`() {
        val tweaked = EditParams.DEFAULT.copy(exposure = 1.5f)
        val json = Exporter.buildSidecarJson(tweaked, "9.9", null)
        assertTrue(json.contains("\"exposure\":1.5"))
        assertTrue(json.contains("\"appVersion\":\"9.9\""))
        assertTrue(json.contains("\"sourceFile\":\"\""))
    }

    // ---------- isRawSource (3-arg pure overload) ----------

    @Test
    fun `isRawSource true for dng and cr3 paths`() {
        assertTrue(Exporter.isRawSource(null, "/sdcard/DCIM/IMG_001.dng", null))
        assertTrue(Exporter.isRawSource(null, "/sdcard/DCIM/IMG_002.cr3", null))
        assertTrue(Exporter.isRawSource(null, "/sdcard/DCIM/IMG_003.DNG", null))
        assertTrue(Exporter.isRawSource(null, "content://media/photo.nef?x=1", null))
    }

    @Test
    fun `isRawSource false for jpg`() {
        assertFalse(Exporter.isRawSource(null, "/sdcard/DCIM/photo.jpg", null))
        assertFalse(Exporter.isRawSource("JPEG", "/sdcard/DCIM/photo.jpg", "image/jpeg"))
        assertFalse(Exporter.isRawSource(null, "/sdcard/DCIM/photo.jpeg", "image/jpeg"))
        assertFalse(Exporter.isRawSource(null, null, "image/jpeg"))
    }

    @Test
    fun `isRawSource badge and mime variants`() {
        assertTrue(Exporter.isRawSource("DNG", null, null))
        assertTrue(Exporter.isRawSource("RAW", null, null))
        assertTrue(Exporter.isRawSource("dng", null, null))
        assertTrue(Exporter.isRawSource(null, null, "image/x-adobe-dng"))
        assertTrue(Exporter.isRawSource(null, null, "image/x-dng"))
        assertFalse(Exporter.isRawSource(null, null, null))
        assertFalse(Exporter.isRawSource("", "", ""))
    }

    // ---------- estimateTiffBytes delegation ----------

    @Test
    fun `estimateTiffBytes delegates to TiffWriter`() {
        assertEquals(TiffWriter.estimateBytes(1, 1), Exporter.estimateTiffBytes(1, 1))
        assertEquals(TiffWriter.estimateBytes(3, 2), Exporter.estimateTiffBytes(3, 2))
        assertEquals(3L * 2 * 3 + 180L, Exporter.estimateTiffBytes(3, 2))
        assertEquals(640L * 480 * 3 + 180L, Exporter.estimateTiffBytes(640, 480))
        assertEquals(0L, Exporter.estimateTiffBytes(0, 10))
    }

    @Test
    fun `requestedDecodeMaxDim only caps when source is larger`() {
        val settings = ExportSettings(
            resolutionMode = com.lumina.studio.core.export.ResolutionMode.CUSTOM,
            customMaxDim = 2048
        )
        assertEquals(2048, Exporter.requestedDecodeMaxDim(6000, 4000, settings))
        assertEquals(3000, Exporter.requestedDecodeMaxDim(3000, 2000, settings))

        val longest = settings.copy(longestEdge = 1500)
        assertEquals(1500, Exporter.requestedDecodeMaxDim(6000, 4000, longest))
        assertEquals(2000, Exporter.requestedDecodeMaxDim(2000, 1500, longest))
        assertEquals(2000, Exporter.requestedDecodeMaxDim(2000, 1500, longest.copy(allowUpscale = true)))
    }

    // ---------- PreviewRenderer preview gating (pure) ----------

    @Test
    fun `previewMaxDimFor mapping`() {
        assertEquals(1600, PreviewRenderer.PREVIEW_MAX_HIGH)
        assertEquals(1200, PreviewRenderer.PREVIEW_MAX_MEDIUM)
        assertEquals(800, PreviewRenderer.PREVIEW_MAX_LOW)
        assertEquals(1600, PreviewRenderer.previewMaxDimFor("High"))
        assertEquals(1200, PreviewRenderer.previewMaxDimFor("Medium"))
        assertEquals(800, PreviewRenderer.previewMaxDimFor("Low"))
        // Case-insensitive + trimmed; unknown quality falls back to High.
        assertEquals(1600, PreviewRenderer.previewMaxDimFor("HIGH"))
        assertEquals(1200, PreviewRenderer.previewMaxDimFor(" medium "))
        assertEquals(800, PreviewRenderer.previewMaxDimFor("low"))
        assertEquals(1600, PreviewRenderer.previewMaxDimFor("garbage"))
    }

    @Test
    fun `effectivePreviewMaxDim respects gpu gate`() {
        assertEquals(1600, PreviewRenderer.effectivePreviewMaxDim("High", true))
        assertEquals(800, PreviewRenderer.effectivePreviewMaxDim("Low", true))
        assertEquals(1200, PreviewRenderer.effectivePreviewMaxDim("Medium", true))
        // GPU off caps at 1200 regardless of High.
        assertEquals(1200, PreviewRenderer.GPU_OFF_MAX_PREVIEW_DIM)
        assertEquals(1200, PreviewRenderer.effectivePreviewMaxDim("High", false))
        assertEquals(1200, PreviewRenderer.effectivePreviewMaxDim("Medium", false))
        assertEquals(800, PreviewRenderer.effectivePreviewMaxDim("Low", false))
    }

    @Test
    fun `shouldAutoHistogram true only when gpu on`() {
        assertTrue(PreviewRenderer.shouldAutoHistogram(true))
        assertFalse(PreviewRenderer.shouldAutoHistogram(false))
    }
}
