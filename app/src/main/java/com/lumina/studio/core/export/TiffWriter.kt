package com.lumina.studio.core.export

object TiffWriter {
    const val TIFF_FILE_OVERHEAD_BYTES = 180L

    fun estimateBytes(width: Int, height: Int): Long {
        if (width <= 0 || height <= 0) return 0L
        return width.toLong() * height.toLong() * 3L + TIFF_FILE_OVERHEAD_BYTES
    }

    fun encodeTiff(width: Int, height: Int, rgb: ByteArray): ByteArray {
        require(width > 0 && height > 0) { "Invalid TIFF dimensions: $width x $height" }
        val pixelBytes = width.toLong() * height.toLong() * 3L
        require(rgb.size.toLong() == pixelBytes) {
            "RGB buffer size ${rgb.size} does not match $width x $height x 3"
        }
        require(pixelBytes <= Int.MAX_VALUE - 1024) { "Image too large for single-strip TIFF" }

        val entryCount = 12
        val ifdSize = 2 + entryCount * 12 + 4
        val bitsOffset = 8 + ifdSize
        val xresOffset = bitsOffset + 6
        val yresOffset = xresOffset + 8
        val pixelOffset = yresOffset + 8
        val total = pixelOffset + rgb.size.toLong()

        val out = ByteArray(total.toInt())
        var p = 0

        fun u8(v: Int) { out[p++] = v.toByte() }
        fun u16(v: Int) { out[p++] = (v and 0xFF).toByte(); out[p++] = ((v ushr 8) and 0xFF).toByte() }
        fun u32(v: Long) {
            out[p++] = (v and 0xFF).toByte()
            out[p++] = ((v ushr 8) and 0xFF).toByte()
            out[p++] = ((v ushr 16) and 0xFF).toByte()
            out[p++] = ((v ushr 24) and 0xFF).toByte()
        }
        fun entry(tag: Int, type: Int, count: Long, value: Long) {
            u16(tag); u16(type); u32(count); u32(value)
        }

        u8('I'.code); u8('I'.code); u16(42); u32(8L)

        u16(entryCount)
        entry(256, 4, 1, width.toLong())
        entry(257, 4, 1, height.toLong())
        entry(258, 3, 3, bitsOffset.toLong())
        entry(259, 3, 1, 1)
        entry(262, 3, 1, 2)
        entry(273, 4, 1, pixelOffset.toLong())
        entry(277, 3, 1, 3)
        entry(278, 4, 1, height.toLong())
        entry(279, 4, 1, pixelBytes)
        entry(282, 5, 1, xresOffset.toLong())
        entry(283, 5, 1, yresOffset.toLong())
        entry(296, 3, 1, 2)
        u32(0L)

        u16(8); u16(8); u16(8)
        u32(72L); u32(1L)
        u32(72L); u32(1L)

        rgb.copyInto(out, pixelOffset.toInt())

        return out
    }
}
