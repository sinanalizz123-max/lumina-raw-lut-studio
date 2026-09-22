package com.lumina.studio.raw

import com.lumina.studio.core.raw.LosslessJpeg
import com.lumina.studio.core.raw.LosslessResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LosslessJpegTest {

    @Test
    fun `decoder stays disabled with exact preview reason`() {
        assertFalse(LosslessJpeg.SUPPORTED)
        assertTrue(LosslessJpeg.DISABLE_REASON.contains("preview-only", ignoreCase = true))
        assertTrue(LosslessJpeg.DISABLE_REASON.contains("SOF3"))
        val res = LosslessJpeg.decodeStrip(byteArrayOf(1, 2, 3, 4), 4, 4)
        assertTrue(res is LosslessResult.Err)
        assertEquals(LosslessJpeg.DISABLE_REASON, (res as LosslessResult.Err).reason)
    }

    @Test
    fun `predictors 1 to 7 match spec math`() {
        val a = 100
        val b = 60
        val c = 40
        assertEquals(100, LosslessJpeg.predict(1, a, b, c))
        assertEquals(60, LosslessJpeg.predict(2, a, b, c))
        assertEquals(40, LosslessJpeg.predict(3, a, b, c))
        assertEquals(120, LosslessJpeg.predict(4, a, b, c))
        assertEquals(110, LosslessJpeg.predict(5, a, b, c))
        assertEquals(90, LosslessJpeg.predict(6, a, b, c))
        assertEquals(80, LosslessJpeg.predict(7, a, b, c))
    }

    @Test
    fun `reconstruct row predictor 1 accumulates diffs`() {
        val diffs = intArrayOf(10, 5, -3)
        val out = IntArray(3)
        assertTrue(LosslessJpeg.reconstructRow(diffs, null, out, 1, 0))
        assertEquals(10, out[0])
        assertEquals(15, out[1])
        assertEquals(12, out[2])
    }

    @Test
    fun `huffman table build and decode round trip`() {
        val bits = IntArray(16)
        bits[0] = 1
        bits[1] = 1
        val vals = intArrayOf(0, 1)
        val table = LosslessJpeg.HuffmanTable.build(bits, vals)
        assertNotNull(table)
        val br = LosslessJpeg.BitReader(byteArrayOf(0x40.toByte()), 0)
        val first = table!!.decode(br)
        assertEquals(0, first)
    }

    @Test
    fun `bit reader handles stuffed FF00`() {
        val br = LosslessJpeg.BitReader(byteArrayOf(0xFF.toByte(), 0x00.toByte(), 0xAA.toByte()), 0)
        var ones = 0
        repeat(8) {
            if (br.readBit() == 1) ones++
        }
        assertEquals(8, ones)
    }

    @Test
    fun `frame header parser rejects non jpeg`() {
        assertNull(LosslessJpeg.parseFrameHeader(byteArrayOf(1, 2, 3, 4)))
        assertNull(LosslessJpeg.parseFrameHeader(ByteArray(0)))
    }

    @Test
    fun `extend sign matches jpeg spec`() {
        assertEquals(0, LosslessJpeg.extendSign(0, 0))
        assertEquals(-3, LosslessJpeg.extendSign(0, 2))
        assertEquals(2, LosslessJpeg.extendSign(2, 2))
    }
}
