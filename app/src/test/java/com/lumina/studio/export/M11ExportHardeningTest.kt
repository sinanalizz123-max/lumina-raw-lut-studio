package com.lumina.studio.export

import com.lumina.studio.core.export.ExportColorSpace
import com.lumina.studio.core.export.ExportFormat
import com.lumina.studio.core.export.ExportSettings
import com.lumina.studio.core.export.ExportTransaction
import com.lumina.studio.core.export.ExportValidation
import com.lumina.studio.core.export.Exporter
import com.lumina.studio.core.export.OutputSharpen
import com.lumina.studio.core.export.TiffWriter
import com.lumina.studio.core.render.ColorPipeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * M11 pure-JVM guards for the hardened export engine (no android.* imports,
 * no Robolectric, no Bitmap).
 *
 * Covers: format availability matrix, longest-edge math (+upscale flag,
 * aspect lock, degenerate input), even-dims for HEIC, output-sharpen
 * identity at 0, transaction stage/cleanup order, validation checklist
 * execution (incl. TIFF header probe), metadata strip/remove-location
 * behavior, HEIC estimate heuristic, per-format colorspace coercion, and the
 * preview/export consistency golden on synthetic ramps. Android-touching
 * paths (Bitmap loops, MediaCodec, MediaStore, ExifInterface) stay
 * on-device-only and are covered here as source contracts.
 */
class M11ExportHardeningTest {

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

    // ---------- 1. format availability matrix ----------

    @Test
    fun `baseline formats always available in stable order`() {
        assertEquals(
            listOf(ExportFormat.JPEG, ExportFormat.PNG, ExportFormat.TIFF),
            Exporter.availableFormats(false, false)
        )
    }

    @Test
    fun `webp and heic gated on encoder presence`() {
        assertEquals(
            listOf(ExportFormat.JPEG, ExportFormat.PNG, ExportFormat.TIFF, ExportFormat.WEBP),
            Exporter.availableFormats(true, false)
        )
        assertEquals(
            listOf(
                ExportFormat.JPEG, ExportFormat.PNG, ExportFormat.TIFF,
                ExportFormat.WEBP, ExportFormat.HEIC
            ),
            Exporter.availableFormats(true, true)
        )
        // HEIC without WebP still appends in the same slot (no dead UI).
        assertEquals(
            listOf(ExportFormat.JPEG, ExportFormat.PNG, ExportFormat.TIFF, ExportFormat.HEIC),
            Exporter.availableFormats(false, true)
        )
    }

    @Test
    fun `new formats carry correct mime and extension`() {
        assertEquals("image/webp", ExportFormat.WEBP.mime)
        assertEquals("webp", ExportFormat.WEBP.extension)
        assertEquals("image/heic", ExportFormat.HEIC.mime)
        assertEquals("heic", ExportFormat.HEIC.extension)
        val heicName = Exporter.displayName(ExportFormat.HEIC, 0L)
        assertTrue("displayName $heicName", heicName.startsWith("lumina_") && heicName.endsWith(".heic"))
        val webpName = Exporter.displayName(ExportFormat.WEBP, 0L)
        assertTrue("displayName $webpName", webpName.startsWith("lumina_") && webpName.endsWith(".webp"))
    }

    // ---------- 2. longest-edge math ----------

    @Test
    fun `longest edge scales longest side and locks aspect`() {
        assertEquals(2000 to 1500, Exporter.longestEdgeDimensions(4000, 3000, 2000))
        assertEquals(1500 to 2000, Exporter.longestEdgeDimensions(3000, 4000, 2000))
        assertEquals(256 to 192, Exporter.longestEdgeDimensions(4000, 3000, 100))
    }

    @Test
    fun `longest edge never upscales unless allowed`() {
        assertEquals(800 to 600, Exporter.longestEdgeDimensions(800, 600, 2000, false))
        assertEquals(2000 to 1500, Exporter.longestEdgeDimensions(800, 600, 2000, true))
        assertEquals(2000 to 1500, Exporter.longestEdgeDimensions(2000, 1500, 2000, true))
    }

    @Test
    fun `longest edge off and degenerate inputs`() {
        assertEquals(4000 to 3000, Exporter.longestEdgeDimensions(4000, 3000, 0))
        assertEquals(4000 to 3000, Exporter.longestEdgeDimensions(4000, 3000, -50))
        assertEquals(0 to 0, Exporter.longestEdgeDimensions(0, 600, 2000))
        assertEquals(0 to 0, Exporter.longestEdgeDimensions(-5, 600, 2000))
        assertEquals(Exporter.LONGEST_EDGE_OFF, 0)
        assertEquals(256, Exporter.MIN_LONGEST_EDGE)
        assertEquals(8192, Exporter.MAX_LONGEST_EDGE)
    }

    @Test
    fun `targetDimensions honors longest edge as the single size control`() {
        val base = ExportSettings(
            resolutionMode = com.lumina.studio.core.export.ResolutionMode.CUSTOM,
            customMaxDim = 256,
            longestEdge = 2000
        )
        // Custom max-dim (256) is ignored once a longest edge is set.
        assertEquals(2000 to 1500, Exporter.targetDimensions(4000, 3000, base))
        // Off falls back to the legacy Original/Custom behavior.
        val legacy = base.copy(longestEdge = 0)
        assertEquals(256 to 192, Exporter.targetDimensions(4000, 3000, legacy))
    }

    @Test
    fun `new settings default to legacy behavior`() {
        val defaults = ExportSettings()
        assertEquals(0, defaults.longestEdge)
        assertFalse(defaults.allowUpscale)
        assertEquals(0, defaults.outputSharpen)
        assertFalse(defaults.stripMetadata)
        assertTrue(defaults.removeLocation)
        // Legacy path untouched: original stays original.
        assertEquals(
            4000 to 3000,
            Exporter.targetDimensions(
                4000, 3000,
                defaults.copy(resolutionMode = com.lumina.studio.core.export.ResolutionMode.ORIGINAL)
            )
        )
    }

    @Test
    fun `removeLocation is the inverse of includeLocation`() {
        assertTrue(ExportSettings(includeLocation = false).removeLocation)
        assertFalse(ExportSettings(includeLocation = true).removeLocation)
    }

    // ---------- even dims (HEIC YUV420) ----------

    @Test
    fun `evenDims rounds odd inputs up by one`() {
        assertEquals(4000 to 3000, Exporter.evenDims(4000, 3000))
        assertEquals(4002 to 3002, Exporter.evenDims(4001, 3001))
        assertEquals(2 to 2, Exporter.evenDims(1, 1))
        assertEquals(0 to 0, Exporter.evenDims(0, 5))
    }

    // ---------- 3. sharpen identity at 0 ----------

    @Test
    fun `sharpen strength mapping and identity`() {
        assertEquals(0f, OutputSharpen.strengthFor(0), 0f)
        assertEquals(0.5f, OutputSharpen.strengthFor(50), 1e-6f)
        assertEquals(1f, OutputSharpen.strengthFor(100), 0f)
        assertEquals(0f, OutputSharpen.strengthFor(-20), 0f)
        assertEquals(1f, OutputSharpen.strengthFor(500), 0f)
        assertTrue(OutputSharpen.isIdentity(0))
        assertTrue(OutputSharpen.isIdentity(-5))
        assertFalse(OutputSharpen.isIdentity(1))
    }

    @Test
    fun `sharpen blend is identity at 0 and exact unsharp above`() {
        for (orig in listOf(0f, 0.2f, 0.6f, 1f)) {
            for (blurred in listOf(0f, 0.4f, 0.9f)) {
                assertEquals(orig, OutputSharpen.blendChannel(orig, blurred, 0), 0f)
            }
        }
        // Full strength: out = orig + (orig - blurred).
        assertEquals(0.8f, OutputSharpen.blendChannel(0.6f, 0.4f, 100), 1e-6f)
        assertEquals(0.7f, OutputSharpen.blendChannel(0.6f, 0.4f, 50), 1e-6f)
        // Clamped to the rails.
        assertEquals(0f, OutputSharpen.blendChannel(0.2f, 0.9f, 100), 0f)
        assertEquals(1f, OutputSharpen.blendChannel(0.9f, 0.1f, 100), 0f)
    }

    // ---------- 4. transaction cleanup order ----------

    @Test
    fun `transaction stages run temp to finalize in order`() {
        assertEquals(
            listOf(
                ExportTransaction.Stage.RENDER,
                ExportTransaction.Stage.VALIDATE,
                ExportTransaction.Stage.METADATA,
                ExportTransaction.Stage.PENDING,
                ExportTransaction.Stage.FINALIZE
            ),
            ExportTransaction.ORDERED_STAGES
        )
    }

    @Test
    fun `success cleans only the temp file`() {
        assertEquals(
            listOf(ExportTransaction.Cleanup.DELETE_TEMP),
            ExportTransaction.cleanupFor(null)
        )
    }

    @Test
    fun `early failures delete temp then recycle bitmaps`() {
        val expected = listOf(
            ExportTransaction.Cleanup.DELETE_TEMP,
            ExportTransaction.Cleanup.RECYCLE_BITMAPS
        )
        assertEquals(expected, ExportTransaction.cleanupFor(ExportTransaction.Stage.RENDER))
        assertEquals(expected, ExportTransaction.cleanupFor(ExportTransaction.Stage.VALIDATE))
        assertEquals(expected, ExportTransaction.cleanupFor(ExportTransaction.Stage.METADATA))
    }

    @Test
    fun `late failures sweep the pending uri first`() {
        val expected = listOf(
            ExportTransaction.Cleanup.DELETE_PENDING_URI,
            ExportTransaction.Cleanup.DELETE_TEMP,
            ExportTransaction.Cleanup.RECYCLE_BITMAPS
        )
        assertEquals(expected, ExportTransaction.cleanupFor(ExportTransaction.Stage.PENDING))
        assertEquals(expected, ExportTransaction.cleanupFor(ExportTransaction.Stage.FINALIZE))
    }

    // ---------- 5. validation checklist ----------

    private fun validInput() = ExportValidation.Input(
        fileExists = true, sizeBytes = 1024L,
        actualW = 2000, actualH = 1500,
        expectedW = 2000, expectedH = 1500,
        decodes = true, tempGone = true
    )

    @Test
    fun `valid input passes with no failures`() {
        assertTrue(ExportValidation.check(validInput()).isEmpty())
        assertTrue(ExportValidation.isValid(validInput()))
    }

    @Test
    fun `each checklist rule fires independently`() {
        assertTrue(ExportValidation.check(validInput().copy(fileExists = false)).any { it.contains("missing") })
        assertTrue(ExportValidation.check(validInput().copy(sizeBytes = 0L)).any { it.contains("empty") })
        val dims = ExportValidation.check(validInput().copy(actualW = 100, actualH = 100))
        assertTrue(dims.any { it.contains("dimensions") && it.contains("match") })
        assertTrue(ExportValidation.check(validInput().copy(decodes = false)).any { it.contains("reopen") })
        assertTrue(ExportValidation.check(validInput().copy(tempGone = false)).any { it.contains("temp") })
        assertTrue(ExportValidation.check(validInput().copy(expectedW = 0)).any { it.contains("expected") })
    }

    @Test
    fun `tiff header probe round-trips writer output`() {
        val rgb = ByteArray(4 * 3 * 3) { (it % 256).toByte() }
        val bytes = TiffWriter.encodeTiff(4, 3, rgb)
        assertEquals(4 to 3, ExportValidation.tiffDimensions(bytes))
        assertNull(ExportValidation.tiffDimensions(ByteArray(0)))
        assertNull(ExportValidation.tiffDimensions(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)))
        val corrupt = bytes.clone()
        corrupt[0] = 'X'.code.toByte()
        assertNull(ExportValidation.tiffDimensions(corrupt))
    }

    // ---------- 6. metadata + colorspace (pure surface) ----------

    @Test
    fun `tiff coerces p3 to srgb while photo formats pass through`() {
        assertEquals(
            ExportColorSpace.SRGB,
            Exporter.colorSpaceForFormat(ExportFormat.TIFF, ExportColorSpace.DISPLAY_P3)
        )
        assertEquals(
            ExportColorSpace.DISPLAY_P3,
            Exporter.colorSpaceForFormat(ExportFormat.JPEG, ExportColorSpace.DISPLAY_P3)
        )
        assertEquals(
            ExportColorSpace.DISPLAY_P3,
            Exporter.colorSpaceForFormat(ExportFormat.HEIC, ExportColorSpace.DISPLAY_P3)
        )
        assertEquals(
            ExportColorSpace.SRGB,
            Exporter.colorSpaceForFormat(ExportFormat.WEBP, ExportColorSpace.SRGB)
        )
    }

    @Test
    fun `heic estimate scales a jpeg trial`() {
        // 1000 trial bytes over 100 px scaled to 400 px = 4000 JPEG, ~2400 HEIC.
        assertEquals(4000L, Exporter.estimateBytes(1000L, 100L, 400L))
        assertEquals(2400L, Exporter.estimateHeicBytes(1000L, 100L, 400L))
        assertEquals(0L, Exporter.estimateHeicBytes(0L, 100L, 400L))
    }

    // ---------- 7. preview/export consistency golden ----------

    private fun grayRamp(): List<FloatArray> =
        (0..255 step 17).map { i ->
            val v = i / 255f
            floatArrayOf(v, v, v)
        }

    private fun rampSamples(): List<FloatArray> {
        val out = ArrayList<FloatArray>()
        out.addAll(grayRamp())
        for (i in 0..255 step 17) {
            val v = i / 255f
            out.add(floatArrayOf(v, 0.15f, 0.1f))
            out.add(floatArrayOf(0.1f, v, 0.15f))
            out.add(floatArrayOf(0.85f * v + 0.05f, 0.65f * v + 0.05f, 0.55f * v + 0.05f))
        }
        return out
    }

    @Test
    fun `same recipe preview vs export math stays within the pinned bound`() {
        val curves = listOf(
            ColorPipeline.identityLut256(),
            ColorPipeline.sCurveLut256(0.2f)
        )
        val recipes = listOf(
            Triple(1f, 1.2f, 0),
            Triple(-1f, 0.8f, 1),
            Triple(2f, 1.5f, 1),
            Triple(0.5f, 1f, 0)
        )
        var worst = 0f
        for ((ev, sat, ci) in recipes) {
            val curve = curves[ci]
            for (rgb in rampSamples()) {
                val preview = ColorPipeline.gradePreviewPixel(rgb, ev, sat, curve)
                val export = ColorPipeline.gradeFinalPixel(rgb, ev, sat, curve)
                // M11 output sharpen at 0 contributes exactly zero delta, so
                // the export frame equals the FINAL math output.
                val sharpened = FloatArray(3) { c ->
                    OutputSharpen.blendChannel(export[c], export[c], 0)
                }
                assertEquals(0f, ColorPipeline.maxChannelDelta(export, sharpened), 0f)
                for (v in preview + export) assertTrue("finite $v", v.isFinite())
                worst = maxOf(worst, ColorPipeline.maxChannelDelta(preview, sharpened))
            }
        }
        assertTrue(
            "worst preview-export delta $worst exceeds ${ColorPipeline.FINAL_PREVIEW_MAX_DELTA}",
            worst <= ColorPipeline.FINAL_PREVIEW_MAX_DELTA
        )
    }

    // ---------- android-touching paths stay on-device (source contracts) ----------

    @Test
    fun `on-device export paths honor transaction metadata and cancellation`() {
        val src = mainSource("com/lumina/studio/core/export/Exporter.kt")
        // Strip-all short-circuit (clean file, no EXIF).
        assertTrue(src.contains("settings.stripMetadata || !settings.preserveExif"))
        // GPS stays gated on includeLocation with explicit null-out.
        assertTrue(src.contains("GPS_TAGS"))
        assertTrue(src.contains("settings.includeLocation"))
        // Orientation always normal on export.
        assertTrue(src.contains("ORIENTATION_NORMAL"))
        // Cancellation on all paths incl. RAW/sidecar.
        assertTrue(src.contains("ensureActive"))
        // MediaStore pending transaction with delete-on-failure.
        assertTrue(src.contains("IS_PENDING"))
        // Output sharpen early-out at 0.
        assertTrue(src.contains("OutputSharpen.isIdentity"))
        // WebP lossless/lossy split + HEIC MediaCodec path.
        assertTrue(src.contains("WEBP_LOSSY"))
        assertTrue(src.contains("WEBP_LOSSLESS"))
        assertTrue(src.contains("MIMETYPE_IMAGE_ANDROID_HEIC"))
        assertTrue(src.contains("hasHeicEncoder"))
        // Validated publish transaction entry point.
        assertTrue(src.contains("fun publishBytes"))
        assertTrue(src.contains("ExportValidationException"))
    }

    @Test
    fun `batch and ui run the validated pipeline with cancellation`() {
        val batch = mainSource("com/lumina/studio/core/export/BatchExporter.kt")
        assertTrue(batch.contains("publishBytes"))
        assertTrue(batch.contains("applyOutputSharpen"))
        assertTrue(batch.contains("colorSpaceForFormat"))
        assertTrue(batch.contains("ensureActive"))
        assertTrue(batch.contains("CancellationException"))
        val ui = mainSource("com/lumina/studio/ui/screens/ExportScreen.kt")
        assertTrue(ui.contains("availableFormats"))
        assertTrue(ui.contains("outputSharpen"))
        assertTrue(ui.contains("longestEdge"))
        assertTrue(ui.contains("stripMetadata"))
        assertTrue(ui.contains("Remove location"))
        assertTrue(ui.contains("Focal length"))
        assertTrue(ui.contains("publishBytes"))
    }
}
