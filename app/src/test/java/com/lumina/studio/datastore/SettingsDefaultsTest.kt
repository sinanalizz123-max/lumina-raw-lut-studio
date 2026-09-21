package com.lumina.studio.datastore

import android.graphics.Bitmap
import android.net.Uri
import com.lumina.studio.core.data.cache.CacheFileManager
import com.lumina.studio.core.data.datastore.SettingsRepository
import com.lumina.studio.core.util.ExifReader
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File

/**
 * Phase 1 DataStore + cache guards under Robolectric (same proven config as
 * SettingsRepositoryTest: sdk=34, Conscrypt OFF, LEGACY graphics/looper —
 * required on Termux Linux/aarch64 with bionic libc).
 *
 * Covers every Phase-1 setting key default + round-trip, CacheFileManager
 * JVM-testable behavior (persistable-permission runCatching, copy/save never
 * throw), and ExifReader behavioral never-throw (missing/corrupt -> hidden).
 * Room runtime tests are intentionally absent: SQLite natives are
 * unavailable on Termux aarch64 (documented).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@LooperMode(LooperMode.Mode.LEGACY)
class SettingsDefaultsTest {

    private fun repository(): SettingsRepository =
        SettingsRepository(RuntimeEnvironment.getApplication())

    private fun cacheManager(): CacheFileManager =
        CacheFileManager(RuntimeEnvironment.getApplication())

    // ---------- Phase-1 defaults ----------

    @Test
    fun `phase1 defaults match spec`() = runBlocking {
        val repo = repository()
        assertEquals("dark", repo.theme.first())
        assertEquals(true, repo.gpuAcceleration.first())
        assertEquals("High", repo.rawQuality.first())
        assertEquals("High", repo.previewQuality.first())
        assertEquals("JPEG", repo.exportFormat.first())
        assertEquals(90, repo.exportQuality.first())
        assertEquals("Original", repo.exportResolution.first())
        assertEquals("sRGB", repo.exportColorSpace.first())
        assertEquals(true, repo.exportIncludeMetadata.first())
        assertEquals(true, repo.hapticEnabled.first())
        assertEquals(true, repo.animationsEnabled.first())
    }

    // ---------- Phase-1 round-trips ----------

    @Test
    fun `appearance and performance keys round-trip`() = runBlocking {
        val repo = repository()
        repo.setTheme("light")
        assertEquals("light", repo.theme.first())
        repo.setTheme("dark")
        assertEquals("dark", repo.theme.first())

        repo.setGpuAcceleration(false)
        assertEquals(false, repo.gpuAcceleration.first())
        repo.setGpuAcceleration(true)
        assertEquals(true, repo.gpuAcceleration.first())

        repo.setRawQuality("Medium")
        assertEquals("Medium", repo.rawQuality.first())
        repo.setRawQuality("High")
        assertEquals("High", repo.rawQuality.first())

        repo.setPreviewQuality("Low")
        assertEquals("Low", repo.previewQuality.first())
        repo.setPreviewQuality("High")
        assertEquals("High", repo.previewQuality.first())

        repo.setHapticEnabled(false)
        assertEquals(false, repo.hapticEnabled.first())
        repo.setHapticEnabled(true)
        assertEquals(true, repo.hapticEnabled.first())

        repo.setAnimationsEnabled(false)
        assertEquals(false, repo.animationsEnabled.first())
        repo.setAnimationsEnabled(true)
        assertEquals(true, repo.animationsEnabled.first())
    }

    @Test
    fun `export keys round-trip`() = runBlocking {
        val repo = repository()
        repo.setExportFormat("PNG")
        assertEquals("PNG", repo.exportFormat.first())
        repo.setExportFormat("JPEG")
        assertEquals("JPEG", repo.exportFormat.first())

        repo.setExportQuality(80)
        assertEquals(80, repo.exportQuality.first())
        repo.setExportQuality(90)
        assertEquals(90, repo.exportQuality.first())

        repo.setExportResolution("2048px")
        assertEquals("2048px", repo.exportResolution.first())
        repo.setExportResolution("Original")
        assertEquals("Original", repo.exportResolution.first())

        repo.setExportColorSpace("Display P3")
        assertEquals("Display P3", repo.exportColorSpace.first())
        repo.setExportColorSpace("sRGB")
        assertEquals("sRGB", repo.exportColorSpace.first())

        repo.setExportIncludeMetadata(false)
        assertEquals(false, repo.exportIncludeMetadata.first())
        repo.setExportIncludeMetadata(true)
        assertEquals(true, repo.exportIncludeMetadata.first())
    }

    // ---------- CacheFileManager (JVM-testable) ----------

    @Test
    fun `cacheFileManager wraps persistable permission in runCatching`() {
        val candidates = listOf(
            File("src/main/java/com/lumina/studio/core/data/cache/CacheFileManager.kt"),
            File("app/src/main/java/com/lumina/studio/core/data/cache/CacheFileManager.kt"),
            File("/storage/emulated/0/opencode/Photoeditea/Lumina/app/src/main/java/com/lumina/studio/core/data/cache/CacheFileManager.kt")
        )
        val src = candidates.firstOrNull { it.isFile }!!.readText()
        assertTrue("copyUriToCache must guard takePersistableUriPermission with runCatching", src.contains("runCatching"))
        assertTrue(src.contains("takePersistableUriPermission"))
    }

    @Test
    fun `cache dirs sizes and clear behave`() {
        val mgr = cacheManager()
        assertEquals(mgr.cacheDir(), RuntimeEnvironment.getApplication().cacheDir)
        val probe = File(mgr.cacheDir(), "lumina_probe_${System.nanoTime()}.bin")
        try {
            probe.writeBytes(ByteArray(2048) { 0x41 })
            assertTrue(mgr.cacheSizeBytes() >= 2048)
            assertTrue(mgr.cacheSizeDisplay().isNotBlank())
            assertTrue(mgr.previewFile(probe.name).absolutePath.startsWith(mgr.cacheDir().absolutePath))
        } finally {
            probe.delete()
        }
        assertTrue(mgr.clearCache())
        assertEquals(0L, mgr.cacheSizeBytes())
        assertEquals("0 B", mgr.cacheSizeDisplay())
    }

    @Test
    fun `copyUriToCache with unresolvable uri returns null without throwing`() {
        val mgr = cacheManager()
        val bogus = Uri.parse("content://com.lumina.studio.unknown/missing")
        // Must not throw even though no provider / no persistable permission exists.
        val result = mgr.copyUriToCache(bogus, "photo.jpg")
        assertNull(result)
    }

    @Test
    fun `saveBitmapToCache never throws and keeps jpg suffix when it succeeds`() {
        val mgr = cacheManager()
        val bmp = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val result = mgr.saveBitmapToCache(bmp, "camera")
        // Shadow bitmap path may or may not persist bytes; contract is never-throw.
        if (result != null) {
            assertTrue("saved file must end with .jpg: ${result.name}", result.name.endsWith(".jpg"))
            assertTrue(result.absolutePath.startsWith(mgr.cacheDir().absolutePath))
            result.delete()
        }
        bmp.recycle()
    }

    // ---------- ExifReader behavioral (needs Robolectric: ExifInterface static init uses android.util.Log) ----------

    @Test
    fun `exif missing file returns hidden empty info without throwing`() {
        val missing = File.createTempFile("lumina-missing", ".jpg").also { it.delete() }
        val info = ExifReader.read(missing)
        assertTrue("missing EXIF must be hidden (isEmpty)", info.isEmpty)
    }

    @Test
    fun `exif corrupt file returns hidden empty info without throwing`() {
        val corrupt = File.createTempFile("lumina-corrupt", ".jpg")
        try {
            corrupt.writeBytes(byteArrayOf(0x00, 0x01, 0x02, 0x03, 0x04, 0x05))
            val info = ExifReader.read(corrupt)
            assertTrue("corrupt EXIF must be hidden (isEmpty)", info.isEmpty)
        } finally {
            corrupt.delete()
        }
    }
}
