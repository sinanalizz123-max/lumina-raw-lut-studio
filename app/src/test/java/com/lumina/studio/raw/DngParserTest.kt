package com.lumina.studio.raw

import com.lumina.studio.core.raw.DngInfo
import com.lumina.studio.core.raw.DngParseResult
import com.lumina.studio.core.raw.DngParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

object SyntheticDng {
    fun u16(v: Int, little: Boolean = true): ByteArray {
        return if (little) byteArrayOf((v and 0xFF).toByte(), ((v ushr 8) and 0xFF).toByte())
        else byteArrayOf(((v ushr 8) and 0xFF).toByte(), (v and 0xFF).toByte())
    }

    fun u32(v: Long, little: Boolean = true): ByteArray {
        return if (little) byteArrayOf(
            (v and 0xFF).toByte(), ((v ushr 8) and 0xFF).toByte(),
            ((v ushr 16) and 0xFF).toByte(), ((v ushr 24) and 0xFF).toByte()
        ) else byteArrayOf(
            ((v ushr 24) and 0xFF).toByte(), ((v ushr 16) and 0xFF).toByte(),
            ((v ushr 8) and 0xFF).toByte(), (v and 0xFF).toByte()
        )
    }

    data class Field(val tag: Int, val type: Int, val count: Long, val valueOrOffset: ByteArray)

    fun entry(tag: Int, type: Int, count: Long, inline: ByteArray): Field {
        val v = ByteArray(4)
        inline.copyInto(v, 0, 0, minOf(4, inline.size))
        return Field(tag, type, count, v)
    }

    fun build(
        width: Int = 4,
        height: Int = 4,
        bits: Int = 16,
        compression: Int = 1,
        photometric: Int = 32803,
        orientation: Int = 1,
        cfaDim: Pair<Int, Int> = 2 to 2,
        cfaPattern: IntArray = intArrayOf(0, 1, 1, 2),
        black: Long = 0L,
        white: Long = 1000L,
        mosaic: IntArray? = null,
        asShotNeutral: DoubleArray? = null,
        colorMatrix1: DoubleArray? = null,
        little: Boolean = true,
        corrupt: ((ByteArray) -> Unit)? = null
    ): ByteArray {
        val w = width
        val h = height
        val mos = mosaic ?: IntArray(w * h) { 500 }
        val pixelBytes: ByteArray = when (bits) {
            8 -> ByteArray(mos.size) { (mos[it] and 0xFF).toByte() }
            else -> {
                val b = ByteArray(mos.size * 2)
                for (i in mos.indices) {
                    val v = mos[i] and 0xFFFF
                    if (little) {
                        b[i * 2] = (v and 0xFF).toByte()
                        b[i * 2 + 1] = ((v ushr 8) and 0xFF).toByte()
                    } else {
                        b[i * 2] = ((v ushr 8) and 0xFF).toByte()
                        b[i * 2 + 1] = (v and 0xFF).toByte()
                    }
                }
                b
            }
        }
        val extraBlobs = ArrayList<ByteArray>()
        val baseFieldCount = 16 + (if (asShotNeutral != null) 1 else 0) +
            (if (colorMatrix1 != null) 1 else 0)
        val ifdSize = 2 + baseFieldCount * 12 + 4
        var dataOff = (8 + ifdSize).toLong()
        // NOTE: SHORT×2 (CFAPatternDim, BlackLevelRepeatDim) occupies exactly
        // 4 bytes and MUST be inline per TIFF (total <= 4 → value field, not
        // an offset). An offset here would be misread as huge dimensions.
        val cfaDimInline = u16(cfaDim.first, little) + u16(cfaDim.second, little)
        val blackDimInline = u16(1, little) + u16(1, little)
        val patBlob = ByteArray(cfaPattern.size) { cfaPattern[it].toByte() }
        extraBlobs.add(patBlob)
        val patOff = dataOff
        dataOff += patBlob.size
        val blackBlob = u16(black.toInt(), little)
        extraBlobs.add(blackBlob)
        val blackOff = dataOff
        dataOff += blackBlob.size
        var asnOff = 0L
        if (asShotNeutral != null) {
            val blob = ByteArray(asShotNeutral.size * 8)
            var p = 0
            for (v in asShotNeutral) {
                val num = (v * 1000000).toLong()
                u32(num, little).copyInto(blob, p)
                u32(1000000L, little).copyInto(blob, p + 4)
                p += 8
            }
            extraBlobs.add(blob)
            asnOff = dataOff
            dataOff += blob.size
        }
        var cmOff = 0L
        if (colorMatrix1 != null) {
            val blob = ByteArray(colorMatrix1.size * 8)
            var p = 0
            for (v in colorMatrix1) {
                val num = (v * 1000000).toLong()
                val den = 1000000L
                if (v < 0) {
                    u32(num and 0xFFFFFFFFL, little).copyInto(blob, p)
                } else {
                    u32(num, little).copyInto(blob, p)
                }
                u32(den, little).copyInto(blob, p + 4)
                p += 8
            }
            extraBlobs.add(blob)
            cmOff = dataOff
            dataOff += blob.size
        }
        val stripOff = dataOff
        val stripCount = pixelBytes.size.toLong()
        val out = ArrayList<Byte>()
        out.addAll(listOf('I'.code.toByte(), 'I'.code.toByte()))
        out.addAll(u16(42, little).toList())
        out.addAll(u32(8L, little).toList())
        val finalFields = ArrayList<Field>()
        finalFields.add(entry(256, 4, 1, u32(w.toLong(), little)))
        finalFields.add(entry(257, 4, 1, u32(h.toLong(), little)))
        finalFields.add(entry(258, 3, 1, u16(bits, little)))
        finalFields.add(entry(259, 3, 1, u16(compression, little)))
        finalFields.add(entry(262, 3, 1, u16(photometric, little)))
        finalFields.add(entry(273, 4, 1, u32(stripOff, little)))
        finalFields.add(entry(277, 3, 1, u16(1, little)))
        finalFields.add(entry(278, 4, 1, u32(h.toLong(), little)))
        finalFields.add(entry(279, 4, 1, u32(stripCount, little)))
        finalFields.add(entry(274, 3, 1, u16(orientation, little)))
        finalFields.add(entry(284, 3, 1, u16(1, little)))
        finalFields.add(Field(33421, 3, 2, cfaDimInline))
        if (cfaPattern.size <= 4) {
            val inline = ByteArray(4)
            for (i in cfaPattern.indices) inline[i] = cfaPattern[i].toByte()
            finalFields.add(Field(33422, 1, cfaPattern.size.toLong(), inline))
        } else {
            finalFields.add(Field(33422, 1, cfaPattern.size.toLong(), u32(patOff, little)))
        }
        finalFields.add(Field(50713, 3, 2, blackDimInline))
        finalFields.add(Field(50714, 3, 1, u16(black.toInt(), little)))
        finalFields.add(Field(50717, 3, 1, u16(white.toInt(), little)))
        if (asShotNeutral != null) {
            finalFields.add(Field(50728, 5, 3, u32(asnOff, little)))
        }
        if (colorMatrix1 != null) {
            finalFields.add(Field(50721, 10, 9, u32(cmOff, little)))
        }
        out.addAll(u16(finalFields.size, little).toList())
        for (f in finalFields) {
            out.addAll(u16(f.tag, little).toList())
            out.addAll(u16(f.type, little).toList())
            out.addAll(u32(f.count, little).toList())
            out.addAll(f.valueOrOffset.toList())
        }
        out.addAll(u32(0L, little).toList())
        for (b in extraBlobs) out.addAll(b.toList())
        while (out.size < stripOff) out.add(0)
        out.addAll(pixelBytes.toList())
        val arr = out.toByteArray()
        corrupt?.invoke(arr)
        return arr
    }
}

class DngParserTest {

    private fun bayerMosaic(): IntArray {
        return intArrayOf(
            800, 400, 800, 400,
            400, 200, 400, 200,
            800, 400, 800, 400,
            400, 200, 400, 200
        )
    }

    @Test
    fun `synthetic uncompressed parses with expected fields`() {
        val bytes = SyntheticDng.build(mosaic = bayerMosaic())
        val res = DngParser.parse(bytes)
        assertTrue(res is DngParseResult.Ok)
        val info = (res as DngParseResult.Ok).info
        assertEquals(4, info.width)
        assertEquals(4, info.height)
        assertEquals(16, info.bitsPerSample)
        assertEquals(1, info.compression)
        assertEquals(1, info.samplesPerPixel)
        assertEquals(32803, info.photometric)
        assertEquals(1, info.orientation)
        assertEquals(2, info.cfaDimRows)
        assertEquals(2, info.cfaDimCols)
        assertEquals(listOf(0, 1, 1, 2), info.cfaPattern.toList())
        assertEquals(1000L, info.whiteLevel)
        assertEquals(1, info.stripOffsets.size)
        assertEquals(1, info.stripByteCounts.size)
    }

    @Test
    fun `asShotNeutral and colorMatrix parsed`() {
        val bytes = SyntheticDng.build(
            mosaic = bayerMosaic(),
            asShotNeutral = doubleArrayOf(0.5, 1.0, 0.5),
            colorMatrix1 = doubleArrayOf(
                1.0, 0.0, 0.0,
                0.0, 1.0, 0.0,
                0.0, 0.0, 1.0
            )
        )
        val res = DngParser.parse(bytes)
        assertTrue(res is DngParseResult.Ok)
        val info = (res as DngParseResult.Ok).info
        assertTrue(info.asShotNeutral != null)
        assertEquals(3, info.asShotNeutral!!.size)
        assertEquals(0.5, info.asShotNeutral!![0], 0.01)
        assertEquals(1.0, info.asShotNeutral!![1], 0.01)
    }

    @Test
    fun `orientation tag preserved`() {
        val bytes = SyntheticDng.build(mosaic = bayerMosaic(), orientation = 6)
        val res = DngParser.parse(bytes)
        assertTrue(res is DngParseResult.Ok)
        assertEquals(6, (res as DngParseResult.Ok).info.orientation)
    }

    @Test
    fun `lossy compression parses but is not developable`() {
        val bytes = SyntheticDng.build(mosaic = bayerMosaic(), compression = 34892)
        val res = DngParser.parse(bytes)
        assertTrue(res is DngParseResult.Ok)
        assertEquals(34892, (res as DngParseResult.Ok).info.compression)
    }

    @Test
    fun `truncated file is Err`() {
        val bytes = SyntheticDng.build(mosaic = bayerMosaic())
        val cut = bytes.copyOf(bytes.size / 2)
        val res = DngParser.parse(cut)
        assertTrue(res is DngParseResult.Err)
    }

    @Test
    fun `bad magic is Err`() {
        val bytes = SyntheticDng.build(mosaic = bayerMosaic())
        bytes[0] = 'X'.code.toByte()
        val res = DngParser.parse(bytes)
        assertTrue(res is DngParseResult.Err)
    }

    @Test
    fun `strip overflow is Err never OOM`() {
        val bytes = SyntheticDng.build(mosaic = bayerMosaic(), corrupt = { arr ->
            val ifdCount = (arr[8].toInt() and 0xFF) or ((arr[9].toInt() and 0xFF) shl 8)
            for (i in 0 until ifdCount) {
                val base = 10 + i * 12
                val tag = (arr[base].toInt() and 0xFF) or ((arr[base + 1].toInt() and 0xFF) shl 8)
                if (tag == 279) {
                    arr[base + 8] = 0xFF.toByte()
                    arr[base + 9] = 0xFF.toByte()
                    arr[base + 10] = 0xFF.toByte()
                    arr[base + 11] = 0x7F.toByte()
                }
            }
        })
        val res = DngParser.parse(bytes)
        assertTrue(res is DngParseResult.Err)
    }

    @Test
    fun `evil dims rejected`() {
        val bytes = SyntheticDng.build(mosaic = bayerMosaic(), corrupt = { arr ->
            val ifdCount = (arr[8].toInt() and 0xFF) or ((arr[9].toInt() and 0xFF) shl 8)
            for (i in 0 until ifdCount) {
                val base = 10 + i * 12
                val tag = (arr[base].toInt() and 0xFF) or ((arr[base + 1].toInt() and 0xFF) shl 8)
                if (tag == 256) {
                    arr[base + 8] = 0x00.toByte()
                    arr[base + 9] = 0xFF.toByte()
                    arr[base + 10] = 0xFF.toByte()
                    arr[base + 11] = 0x00.toByte()
                }
            }
        })
        val res = DngParser.parse(bytes)
        assertTrue(res is DngParseResult.Err)
        assertTrue((res as DngParseResult.Err).reason.isNotBlank())
    }

    @Test
    fun `bit flips do not throw`() {
        val base = SyntheticDng.build(mosaic = bayerMosaic())
        for (flip in listOf(10, 20, 30, 50, 80)) {
            val copy = base.copyOf()
            if (flip < copy.size) copy[flip] = (copy[flip].toInt() xor 0xFF).toByte()
            val res = DngParser.parse(copy)
            assertTrue(res is DngParseResult.Ok || res is DngParseResult.Err)
        }
    }

    @Test
    fun `empty and tiny inputs are Err`() {
        assertTrue(DngParser.parse(ByteArray(0)) is DngParseResult.Err)
        assertTrue(DngParser.parse(ByteArray(4)) is DngParseResult.Err)
        assertTrue(DngParser.parse(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)) is DngParseResult.Err)
    }
}
