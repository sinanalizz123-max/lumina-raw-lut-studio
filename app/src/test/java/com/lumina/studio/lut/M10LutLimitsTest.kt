package com.lumina.studio.lut

import com.lumina.studio.core.data.local.Preset
import com.lumina.studio.core.lut.BuiltInPresets
import com.lumina.studio.core.lut.CubeParser
import com.lumina.studio.core.lut.LutCube
import com.lumina.studio.core.lut.LutLimits
import com.lumina.studio.core.lut.LutRehydrator
import com.lumina.studio.core.lut.LutRegistry
import com.lumina.studio.core.lut.LutRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.file.Files

class M10LutLimitsTest {

    private fun tinyLut(): LutCube {
        val data = FloatArray(2 * 3) { i -> (i / 3).toFloat() }
        return LutCube(title = null, size = 2, is3D = false, data = data)
    }

    private fun tinyCubeText(title: String): String =
        "TITLE \"$title\"\nLUT_1D_SIZE 2\n0.0 0.0 0.0\n1.0 1.0 1.0\n"

    @Test
    fun `shared caps are unified at 8MB and 64`() {
        assertEquals(8 * 1024 * 1024, LutLimits.MAX_FILE_BYTES)
        assertEquals(8 * 1024 * 1024, LutLimits.MAX_TEXT_CHARS)
        assertEquals(2, LutLimits.MIN_SIZE)
        assertEquals(64, LutLimits.MAX_SIZE)
        assertEquals(64 * 64 * 64, LutLimits.MAX_DATA_POINTS)
        assertEquals(64 * 1024, LutLimits.INLINE_CUBE_THRESHOLD_BYTES)
        assertEquals(32, LutLimits.REGISTRY_MAX_ENTRIES)
    }

    @Test
    fun `parser caps point at the shared limits`() {
        assertEquals(LutLimits.MIN_SIZE, CubeParser.MIN_SIZE)
        assertEquals(LutLimits.MAX_SIZE, CubeParser.MAX_SIZE)
        assertEquals(LutLimits.MAX_TEXT_CHARS, CubeParser.MAX_TEXT_CHARS)
    }

    @Test
    fun `preview downsample constants unchanged with disclosure copy`() {
        assertEquals(33, LutRenderer.FAST_PATH_MAX_SIZE)
        assertEquals(32, LutRenderer.DOWNSAMPLED_SIZE)
        assertTrue(LutRenderer.LARGE_LUT_PREVIEW_NOTE.isNotBlank())
    }

    @Test
    fun `readBounded rejects over-cap streams before buffering them fully`() {
        val over = ByteArray(LutLimits.MAX_FILE_BYTES + 1) { 0x41 }
        assertNull(LutLimits.readBounded(ByteArrayInputStream(over)))
        val atCap = ByteArray(1024) { 0x42 }
        assertNotNull(LutLimits.readBounded(ByteArrayInputStream(atCap), 1024))
        assertNull(LutLimits.readBounded(ByteArrayInputStream(ByteArray(1025) { 0x43 }), 1024))
    }

    @Test
    fun `inline threshold boundary`() {
        assertTrue(LutLimits.shouldInline(LutLimits.INLINE_CUBE_THRESHOLD_BYTES - 1))
        assertFalse(LutLimits.shouldInline(LutLimits.INLINE_CUBE_THRESHOLD_BYTES))
        assertFalse(LutLimits.shouldInline(LutLimits.INLINE_CUBE_THRESHOLD_BYTES + 1))
    }

    @Test
    fun `file marker round-trips and rejects traversal`() {
        val name = LutLimits.fileNameFor("some preset id/with spaces!")
        assertFalse(name.contains('/'))
        assertTrue(name.endsWith(".cube"))
        val ref = LutLimits.fileRefFor(name)
        assertTrue(LutLimits.isFileRef(ref))
        assertEquals(name, LutLimits.fileNameFromRef(ref))
        assertFalse(LutLimits.isFileRef(tinyCubeText("T")))
        assertNull(LutLimits.fileNameFromRef(tinyCubeText("T")))
        assertNull(LutLimits.fileNameFromRef("LUT_FILE:../evil.cube"))
        assertNull(LutLimits.fileNameFromRef("LUT_FILE:sub/dir.cube"))
        assertNull(LutLimits.fileNameFromRef("LUT_FILE:"))
    }

    @Test
    fun `registry evicts eldest transient entries and keeps builtins`() {
        LutRegistry.clearTransient()
        try {
            for (n in 0 until LutLimits.REGISTRY_MAX_ENTRIES + 8) {
                LutRegistry.register("m10_evict_$n", tinyLut())
            }
            assertTrue(LutRegistry.entryCount() <= LutLimits.REGISTRY_MAX_ENTRIES)
            assertNull(LutRegistry.resolve("m10_evict_0"))
            assertNotNull(LutRegistry.resolve("m10_evict_${LutLimits.REGISTRY_MAX_ENTRIES + 7}"))
            for (preset in BuiltInPresets.list) {
                assertNotNull("builtin ${preset.id} survives eviction", LutRegistry.resolve(preset.id))
            }
            assertTrue(LutRegistry.estimatedBytes() > 0L)
        } finally {
            LutRegistry.clearTransient()
        }
    }

    @Test
    fun `registry ignores file refs in registerParsed`() {
        LutRegistry.clearTransient()
        try {
            assertNull(LutRegistry.registerParsed("m10_ref_xyz", "LUT_FILE:abc.cube"))
        } finally {
            LutRegistry.clearTransient()
        }
    }

    @Test
    fun `disk round-trip through temp dir`() {
        val dir = Files.createTempDirectory("m10_luts").toFile()
        try {
            val text = tinyCubeText("Disk")
            val file = LutLimits.writeAtomically(dir, "disk.cube", text.toByteArray(Charsets.UTF_8))
            assertNotNull(file)
            assertEquals(text, LutLimits.readBoundedFile(file!!))
            assertNull(LutLimits.writeAtomically(dir, "big.cube", ByteArray(0)))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `rehydrator registers inline and file-backed presets`() {
        val dir = Files.createTempDirectory("m10_rehydrate").toFile()
        LutRegistry.clearTransient()
        try {
            val text = tinyCubeText("FileBacked")
            dir.resolve("fb.cube").writeText(text, Charsets.UTF_8)
            val presets = listOf(
                Preset(id = "m10_inline", name = "Inline", cubeText = tinyCubeText("Inline")),
                Preset(id = "m10_file", name = "File", cubeText = LutLimits.fileRefFor("fb.cube")),
                Preset(id = "m10_missing", name = "Missing", cubeText = LutLimits.fileRefFor("gone.cube"))
            )
            val report = LutRehydrator.rehydrate(presets, dir)
            assertEquals(2, report.registered)
            assertEquals(1, report.failed)
            assertNotNull(LutRegistry.resolve("m10_inline"))
            assertNotNull(LutRegistry.resolve("m10_file"))
            assertNull(LutRegistry.resolve("m10_missing"))
        } finally {
            LutRegistry.clearTransient()
            dir.deleteRecursively()
        }
    }
}
