package com.lumina.studio.raw

import com.lumina.studio.core.raw.DngCapabilities
import com.lumina.studio.core.raw.DngParseResult
import com.lumina.studio.core.raw.DngParser
import com.lumina.studio.core.raw.LosslessJpeg
import com.lumina.studio.core.render.RawCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DngCapabilitiesTest {

    private fun mosaic(): IntArray = IntArray(16) { 500 }

    @Test
    fun `uncompressed CFA is developable`() {
        val bytes = SyntheticDng.build(mosaic = mosaic())
        val info = (DngParser.parse(bytes) as DngParseResult.Ok).info
        val d = DngCapabilities.developability(info)
        assertTrue(d.developable)
        assertTrue(d.reason.isNotBlank())
    }

    @Test
    fun `lossless jpeg is preview only with exact reason`() {
        assertFalse(com.lumina.studio.core.raw.LosslessJpegGate.losslessJpegSupported)
        val bytes = SyntheticDng.build(mosaic = mosaic(), compression = 7)
        val info = (DngParser.parse(bytes) as DngParseResult.Ok).info
        val d = DngCapabilities.developability(info)
        assertFalse(d.developable)
        assertTrue(d.reason.contains("SOF3") || d.reason.contains("lossless", ignoreCase = true))
        assertTrue(LosslessJpeg.DISABLE_REASON.contains("preview-only", ignoreCase = true))
    }

    @Test
    fun `lossy 34892 is preview only`() {
        val bytes = SyntheticDng.build(mosaic = mosaic(), compression = 34892)
        val info = (DngParser.parse(bytes) as DngParseResult.Ok).info
        val d = DngCapabilities.developability(info)
        assertFalse(d.developable)
        assertTrue(d.reason.contains("34892") || d.reason.contains("lossy", ignoreCase = true))
    }

    @Test
    fun `no proprietary raw is ever developable`() {
        for (ext in RawCapabilities.PROPRIETARY_FORMATS) {
            assertFalse("proprietary $ext must never be editable", DngCapabilities.isProprietaryEditable(ext))
        }
        for (ext in listOf("cr2", "cr3", "nef", "arw", "raf", "rw2", "orf", "pef", "srw", "nrw")) {
            assertFalse(DngCapabilities.isProprietaryEditable(ext))
        }
    }

    @Test
    fun `static honesty tables stay conservative`() {
        for (cap in RawCapabilities.TABLE) {
            assertFalse("static table ${cap.format} stays non-editable (per-file gate decides)", cap.editable)
            assertTrue(cap.note.isNotBlank())
        }
        assertTrue(RawCapabilities.capabilityOf("dng")!!.note.contains("preview", ignoreCase = true))
    }

    @Test
    fun `single source of truth reasons match`() {
        val dngStatic = RawCapabilities.capabilityOf("dng")!!.note
        val fmtStatic = com.lumina.studio.core.util.FormatCapabilities.capabilityOf("dng")!!.reason
        assertEquals(dngStatic, fmtStatic)
        assertTrue(DngCapabilities.UNCOMPRESSED_OK.isNotBlank())
        assertTrue(DngCapabilities.LOSSLESS_JPEG_PREVIEW_REASON.contains("preview", ignoreCase = true))
        assertTrue(DngCapabilities.LOSSY_JPEG_PREVIEW_REASON.contains("preview", ignoreCase = true))
    }

    @Test
    fun `non CFA photometric is preview only`() {
        val bytes = SyntheticDng.build(mosaic = mosaic(), photometric = 2)
        val res = DngParser.parse(bytes)
        assertTrue(res is DngParseResult.Ok)
        val d = DngCapabilities.developability((res as DngParseResult.Ok).info)
        assertFalse(d.developable)
    }
}
