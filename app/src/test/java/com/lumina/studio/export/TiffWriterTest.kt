package com.lumina.studio.export

import com.lumina.studio.core.export.TiffWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Phase 4 pure-JVM guards for TiffWriter (no android.*, no Robolectric).
 *
 * Covers: "II" + magic 42 header, IFD width/height round-trip via a
 * little-endian parser over the bytes we wrote, require() on rgb size
 * mismatch, estimateBytes == w*h*3 + overhead, 1x1 minimal round-trip,
 * and exact byte length for non-default widths (3x2).
 */
class TiffWriterTest {

    private fun u16le(bytes: ByteArray, off: Int): Int =
        (bytes[off].toInt() and 0xFF) or ((bytes[off + 1].toInt() and 0xFF) shl 8)

    private fun u32le(bytes: ByteArray, off: Int): Long =
        (bytes[off].toLong() and 0xFF) or
            ((bytes[off + 1].toLong() and 0xFF) shl 8) or
            ((bytes[off + 2].toLong() and 0xFF) shl 16) or
            ((bytes[off + 3].toLong() and 0xFF) shl 24)

    /** Value/offset word of the IFD entry with the given tag, or null. */
    private fun tagValue(bytes: ByteArray, tag: Int): Long? {
        val count = u16le(bytes, 8)
        for (i in 0 until count) {
            val off = 10 + i * 12
            if (u16le(bytes, off) == tag) return u32le(bytes, off + 8)
        }
        return null
    }

    private fun solidRgb(w: Int, h: Int, r: Int = 10, g: Int = 20, b: Int = 30): ByteArray {
        val rgb = ByteArray(w * h * 3)
        var o = 0
        repeat(w * h) {
            rgb[o++] = r.toByte()
            rgb[o++] = g.toByte()
            rgb[o++] = b.toByte()
        }
        return rgb
    }

    @Test
    fun `overhead constant is 180`() {
        assertEquals(180L, TiffWriter.TIFF_FILE_OVERHEAD_BYTES)
    }

    @Test
    fun `encodeTiff starts with II and magic 42`() {
        val bytes = TiffWriter.encodeTiff(2, 2, solidRgb(2, 2))
        assertEquals('I'.code.toByte(), bytes[0])
        assertEquals('I'.code.toByte(), bytes[1])
        assertEquals(42, u16le(bytes, 2))
        assertEquals(8L, u32le(bytes, 4))
        assertEquals(12, u16le(bytes, 8))
    }

    @Test
    fun `width and height read back from IFD`() {
        val w = 4
        val h = 3
        val bytes = TiffWriter.encodeTiff(w, h, solidRgb(w, h))
        assertEquals(w.toLong(), tagValue(bytes, 256))
        assertEquals(h.toLong(), tagValue(bytes, 257))
        // Sanity: strip offset points at the 180-byte overhead, byte count is pixel bytes.
        assertEquals(180L, tagValue(bytes, 273))
        assertEquals((w * h * 3).toLong(), tagValue(bytes, 279))
    }

    @Test
    fun `rgb length mismatch throws IllegalArgumentException`() {
        try {
            TiffWriter.encodeTiff(2, 2, ByteArray(11))
            fail("expected require() for short rgb")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("does not match"))
        }
        try {
            TiffWriter.encodeTiff(2, 2, ByteArray(13))
            fail("expected require() for long rgb")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("does not match"))
        }
        try {
            TiffWriter.encodeTiff(2, 2, ByteArray(0))
            fail("expected require() for empty rgb")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("does not match"))
        }
    }

    @Test
    fun `invalid dimensions throw IllegalArgumentException`() {
        try {
            TiffWriter.encodeTiff(0, 2, ByteArray(0))
            fail("expected require() for zero width")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("Invalid TIFF dimensions"))
        }
        try {
            TiffWriter.encodeTiff(-1, 2, ByteArray(0))
            fail("expected require() for negative width")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("Invalid TIFF dimensions"))
        }
    }

    @Test
    fun `estimateBytes equals pixel bytes plus overhead`() {
        assertEquals(1L * 1 * 3 + 180L, TiffWriter.estimateBytes(1, 1))
        assertEquals(3L * 2 * 3 + 180L, TiffWriter.estimateBytes(3, 2))
        assertEquals(4L * 3 * 3 + 180L, TiffWriter.estimateBytes(4, 3))
        assertEquals(640L * 480 * 3 + 180L, TiffWriter.estimateBytes(640, 480))
        // Encoded length matches the estimate exactly.
        val w = 4
        val h = 3
        val bytes = TiffWriter.encodeTiff(w, h, solidRgb(w, h))
        assertEquals(TiffWriter.estimateBytes(w, h), bytes.size.toLong())
    }

    @Test
    fun `estimateBytes guards non-positive dimensions with 0`() {
        assertEquals(0L, TiffWriter.estimateBytes(0, 10))
        assertEquals(0L, TiffWriter.estimateBytes(10, 0))
        assertEquals(0L, TiffWriter.estimateBytes(-3, 2))
        assertEquals(0L, TiffWriter.estimateBytes(2, -3))
    }

    @Test
    fun `1x1 minimal image round-trips dimensions`() {
        val rgb = byteArrayOf(255.toByte(), 128.toByte(), 0.toByte())
        val bytes = TiffWriter.encodeTiff(1, 1, rgb)
        assertEquals(183, bytes.size)
        assertEquals(183L, TiffWriter.estimateBytes(1, 1))
        assertEquals(1L, tagValue(bytes, 256))
        assertEquals(1L, tagValue(bytes, 257))
        // Pixel payload preserved at offset 180.
        assertEquals(rgb[0], bytes[180])
        assertEquals(rgb[1], bytes[181])
        assertEquals(rgb[2], bytes[182])
    }

    @Test
    fun `3x2 non-default width has exact byte length`() {
        val w = 3
        val h = 2
        val rgb = ByteArray(w * h * 3) { (it % 256).toByte() }
        val bytes = TiffWriter.encodeTiff(w, h, rgb)
        assertEquals(198, bytes.size)
        assertEquals(198L, TiffWriter.estimateBytes(w, h))
        assertEquals(w.toLong(), tagValue(bytes, 256))
        assertEquals(h.toLong(), tagValue(bytes, 257))
        // Payload preserved verbatim after the 180-byte header.
        for (i in rgb.indices) {
            assertEquals("payload byte $i", rgb[i], bytes[180 + i])
        }
    }
}
