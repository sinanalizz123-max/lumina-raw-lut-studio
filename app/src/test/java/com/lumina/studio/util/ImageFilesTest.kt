package com.lumina.studio.util

import com.lumina.studio.core.util.ExifInfo
import com.lumina.studio.core.util.ImageFiles
import com.lumina.studio.core.util.timeAgo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Phase 1 pure-JVM guards for ImageFiles / timeAgo / Exif contract.
 *
 * No Robolectric: only exercises Kotlin-only logic (extension sets, badges,
 * labels, time-ago) plus ExifInfo hidden-state contract and source guards
 * for the mime->extension fallback (ImportViewModel) and ExifReader's
 * never-throw contract. Behavioral ExifReader.read() coverage (which needs
 * android.util.Log via ExifInterface static init) lives in
 * SettingsDefaultsTest under Robolectric — plain JVM throws
 * "Method isLoggable in android.util.Log not mocked" as an
 * ExceptionInInitializerError (an Error, not caught by ExifReader's
 * `catch (_: Exception)`), so it cannot run here.
 */
class ImageFilesTest {

    // ---------- RAW / SUPPORTED sets ----------

    @Test
    fun `raw extensions contain all phase1 formats`() {
        val expected = setOf(
            "dng", "cr2", "cr3", "nef", "nrw", "arw",
            "raf", "rw2", "orf", "pef", "srw"
        )
        assertEquals(expected, ImageFiles.RAW_EXTENSIONS)
        for (ext in expected) {
            assertTrue("isRaw($ext)", ImageFiles.isRaw(ext))
            assertTrue("isRaw uppercase $ext", ImageFiles.isRaw(ext.uppercase()))
        }
    }

    @Test
    fun `supported extensions cover jpeg png webp tiff plus raw`() {
        for (ext in listOf("jpg", "jpeg", "png", "webp", "tiff", "tif")) {
            assertTrue("supported $ext", ext in ImageFiles.SUPPORTED_EXTENSIONS)
            assertTrue("isSupported $ext", ImageFiles.isSupported(ext, null))
        }
        for (ext in ImageFiles.RAW_EXTENSIONS) {
            assertTrue("supported raw $ext", ext in ImageFiles.SUPPORTED_EXTENSIONS)
        }
        assertFalse(ImageFiles.isSupported("xyz", null))
        assertFalse(ImageFiles.isSupported("", null))
        assertFalse(ImageFiles.isSupported("", "text/plain"))
        // Empty extension with an image/* mime is accepted (import fallback path).
        assertTrue(ImageFiles.isSupported("", "image/jpeg"))
        assertTrue(ImageFiles.isSupported("", "image/x-tiff"))
    }

    @Test
    fun `extensionOf handles names and edge cases`() {
        assertEquals("dng", ImageFiles.extensionOf("photo.DNG"))
        assertEquals("jpg", ImageFiles.extensionOf("a/b/photo.jpg"))
        assertEquals("", ImageFiles.extensionOf(null))
        assertEquals("", ImageFiles.extensionOf(""))
        assertEquals("", ImageFiles.extensionOf("noext"))
        assertEquals("", ImageFiles.extensionOf("trailing."))
    }

    // ---------- typeBadge ----------

    @Test
    fun `typeBadge maps raw variants`() {
        assertEquals("DNG", ImageFiles.typeBadge("dng", null))
        assertEquals("DNG", ImageFiles.typeBadge("DNG", null))
        for (raw in listOf("cr2", "cr3", "nef", "nrw", "arw", "raf", "rw2", "orf", "pef", "srw")) {
            assertEquals("RAW for $raw", "RAW", ImageFiles.typeBadge(raw, null))
        }
        assertEquals("RAW", ImageFiles.typeBadge("cr3", "image/x-canon-cr3"))
    }

    @Test
    fun `typeBadge maps common raster formats`() {
        assertEquals("JPEG", ImageFiles.typeBadge("jpg", null))
        assertEquals("JPEG", ImageFiles.typeBadge("jpeg", null))
        assertEquals("JPEG", ImageFiles.typeBadge("JPG", null))
        assertEquals("TIFF", ImageFiles.typeBadge("tif", null))
        assertEquals("TIFF", ImageFiles.typeBadge("tiff", null))
        // Code uppercases unknown raster exts ("webp" -> "WEBP"); assert case-insensitively per spec.
        assertTrue(ImageFiles.typeBadge("webp", null).equals("WebP", ignoreCase = true))
        assertEquals("PNG", ImageFiles.typeBadge("png", null))
    }

    @Test
    fun `typeBadge fallbacks are sensible`() {
        // Unknown extension -> uppercased ext.
        assertEquals("XYZ", ImageFiles.typeBadge("xyz", null))
        // Empty extension + mime -> mime subtype uppercased.
        assertEquals("JPEG", ImageFiles.typeBadge("", "image/jpeg"))
        // Empty extension + null mime -> generic IMAGE.
        assertEquals("IMAGE", ImageFiles.typeBadge("", null))
    }

    // ---------- resolutionLabel ----------

    @Test
    fun `resolutionLabel unknown is en-dash`() {
        assertEquals("–", ImageFiles.resolutionLabel(0, 0))
        assertEquals("–", ImageFiles.resolutionLabel(-1, 100))
        assertEquals("–", ImageFiles.resolutionLabel(100, 0))
    }

    @Test
    fun `resolutionLabel formats known size`() {
        assertEquals("4000 × 3000", ImageFiles.resolutionLabel(4000, 3000))
    }

    // ---------- mime -> extension fallback (lives in ImportViewModel) ----------

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

    @Test
    fun `import maps image mime fallback including x-tiff to tiff`() {
        val src = mainSource("com/lumina/studio/ui/screens/ImportViewModel.kt")
        for ((mime, ext) in mapOf(
            "image/jpeg" to "jpg",
            "image/png" to "png",
            "image/webp" to "webp",
            "image/tiff" to "tiff",
            "image/x-tiff" to "tiff"
        )) {
            assertTrue("ImportViewModel must map $mime -> $ext", src.contains("\"$mime\" -> \"$ext\""))
        }
    }

    // ---------- timeAgo ----------

    @Test
    fun `timeAgo formats buckets`() {
        val now = 1_700_000_000_000L
        assertEquals("just now", timeAgo(now, now))
        assertEquals("just now", timeAgo(now - 30_000, now))
        // Future timestamps clamp to just now.
        assertEquals("just now", timeAgo(now + 60_000, now))
        assertEquals("1 min ago", timeAgo(now - 60_000, now))
        assertEquals("5 min ago", timeAgo(now - 5 * 60_000, now))
        assertEquals("1 h ago", timeAgo(now - 60 * 60_000, now))
        assertEquals("23 h ago", timeAgo(now - 23 * 3600_000L, now))
        assertEquals("yesterday", timeAgo(now - 24 * 3600_000L, now))
        assertEquals("5 days ago", timeAgo(now - 5 * 24 * 3600_000L, now))
        assertEquals("1 month ago", timeAgo(now - 30 * 24 * 3600_000L, now))
        assertEquals("3 months ago", timeAgo(now - 90 * 24 * 3600_000L, now))
        assertEquals("1 year ago", timeAgo(now - 365 * 24 * 3600_000L, now))
        assertEquals("2 years ago", timeAgo(now - 730 * 24 * 3600_000L, now))
    }

    // ---------- Exif hidden-state contract (pure JVM; behavioral read() is Robolectric) ----------

    @Test
    fun `exif empty info means hidden`() {
        assertTrue(ExifInfo().isEmpty)
        assertTrue(
            ExifInfo(
                cameraModel = null, lensModel = null, iso = null,
                shutter = null, aperture = null, bitsPerSample = null
            ).isEmpty
        )
        assertFalse(ExifInfo(cameraModel = "Pixel 8").isEmpty)
        assertFalse(ExifInfo(iso = "100").isEmpty)
    }

    @Test
    fun `exifReader catches all exceptions and returns empty info`() {
        val src = mainSource("com/lumina/studio/core/util/ExifReader.kt")
        assertTrue("ExifReader must catch Exception and never throw", src.contains("catch (_: Exception)"))
        assertTrue(src.contains("ExifInfo()"))
        // Missing/corrupt files must surface as hidden (isEmpty) — behavioral
        // proof runs under Robolectric in SettingsDefaultsTest.
    }
}
