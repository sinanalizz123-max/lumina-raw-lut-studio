package com.lumina.studio.util

import com.lumina.studio.core.util.CapabilityStatus
import com.lumina.studio.core.util.FormatCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FormatCapabilitiesTest {

    private val allExts = listOf(
        "jpg", "jpeg", "png", "webp", "heic", "heif", "avif", "bmp", "gif",
        "tif", "tiff", "dng",
        "cr2", "cr3", "nef", "nrw", "arw", "raf", "rw2", "orf", "pef", "srw"
    )

    @Test
    fun `every listed ext has a table entry`() {
        for (ext in allExts) {
            val cap = FormatCapabilities.capabilityOf(ext)
            assertNotNull("missing entry for $ext", cap)
            assertEquals(ext, cap!!.extension)
        }
        assertEquals(allExts.size, FormatCapabilities.TABLE.size)
    }

    @Test
    fun `editable set matches spec`() {
        for (ext in listOf("jpg", "jpeg", "png", "webp", "bmp", "gif", "heic", "heif", "avif")) {
            assertEquals(
                "$ext should be EDITABLE",
                CapabilityStatus.EDITABLE,
                FormatCapabilities.statusOf(ext, null)
            )
        }
    }

    @Test
    fun `preview only set matches spec`() {
        for (ext in listOf("tif", "tiff", "dng", "cr2", "cr3", "nef", "nrw", "arw", "raf", "rw2", "orf", "pef", "srw")) {
            assertEquals(
                "$ext should be PREVIEW_ONLY",
                CapabilityStatus.PREVIEW_ONLY,
                FormatCapabilities.statusOf(ext, null)
            )
        }
    }

    @Test
    fun `raw family is never editable`() {
        for (ext in FormatCapabilities.RAW_EXTENSIONS) {
            assertFalse(
                "$ext must never be EDITABLE",
                FormatCapabilities.statusOf(ext, null) == CapabilityStatus.EDITABLE
            )
        }
    }

    @Test
    fun `heic heif need api 28 and avif needs api 31`() {
        assertEquals(28, FormatCapabilities.capabilityOf("heic")!!.minSdk)
        assertEquals(28, FormatCapabilities.capabilityOf("heif")!!.minSdk)
        assertEquals(31, FormatCapabilities.capabilityOf("avif")!!.minSdk)
        assertTrue(FormatCapabilities.capabilityOf("heic")!!.reason.contains("28"))
        assertTrue(FormatCapabilities.capabilityOf("avif")!!.reason.contains("31"))
    }

    @Test
    fun `unknown ext is unsupported`() {
        assertEquals(CapabilityStatus.UNSUPPORTED, FormatCapabilities.statusOf("xyz", null))
        assertEquals(CapabilityStatus.UNSUPPORTED, FormatCapabilities.statusOf("", null))
        assertEquals(CapabilityStatus.UNSUPPORTED, FormatCapabilities.statusOf("", "text/plain"))
        assertFalse(FormatCapabilities.isSupported("xyz", null))
    }

    @Test
    fun `empty ext with image mime stays supported via fallback`() {
        assertTrue(FormatCapabilities.isSupported("", "image/jpeg"))
        assertEquals(CapabilityStatus.EDITABLE, FormatCapabilities.statusOf("", "image/jpeg"))
    }

    @Test
    fun `each entry has badge and reason`() {
        for (ext in allExts) {
            val cap = FormatCapabilities.capabilityOf(ext)!!
            assertTrue("$ext badge blank", cap.badge.isNotBlank())
            assertTrue("$ext reason blank", cap.reason.isNotBlank())
        }
    }
}
