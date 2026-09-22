package com.lumina.studio.core.export

/**
 * M11 result-validation checklist (§94). Pure JVM (no android.*): the
 * on-device [Exporter.validateBytes] decodes bounds / reopens the stream and
 * then feeds the outcome into [check], so every rule below is unit-pinned.
 *
 * A gallery file is published only when [check] returns empty. Any failure
 * aborts the transaction (error state + cleanup, never a corrupt file).
 */
object ExportValidation {

    data class Input(
        val fileExists: Boolean,
        val sizeBytes: Long,
        val actualW: Int,
        val actualH: Int,
        val expectedW: Int,
        val expectedH: Int,
        val decodes: Boolean,
        val tempGone: Boolean
    )

    /** Returns the list of failures; empty means the export is valid. */
    fun check(input: Input): List<String> {
        val failures = ArrayList<String>()
        if (!input.fileExists) failures.add("file missing")
        if (input.sizeBytes <= 0L) failures.add("empty file")
        if (input.expectedW <= 0 || input.expectedH <= 0) {
            failures.add("invalid expected dimensions")
        } else if (input.actualW != input.expectedW || input.actualH != input.expectedH) {
            failures.add(
                "dimensions ${input.actualW}x${input.actualH} " +
                    "do not match request ${input.expectedW}x${input.expectedH}"
            )
        }
        if (!input.decodes) failures.add("decoder cannot reopen the file")
        if (!input.tempGone) failures.add("temporary file left behind")
        return failures
    }

    fun isValid(input: Input): Boolean = check(input).isEmpty()

    /**
     * Minimal TIFF header probe (little-endian writer of [TiffWriter]):
     * verifies "II" + magic 42 and reads the IFD width (tag 256) and height
     * (tag 257) for SHORT(3)/LONG(4) count-1 entries. Null when the bytes are
     * not a decodable single-strip TIFF. BitmapFactory cannot decode TIFF,
     * so exports in TIFF format validate through here instead.
     */
    fun tiffDimensions(bytes: ByteArray): Pair<Int, Int>? {
        return try {
            if (bytes.size < 10) return null
            if (bytes[0] != 'I'.code.toByte() || bytes[1] != 'I'.code.toByte()) return null
            if (u16(bytes, 2) != 42) return null
            val ifd = u32(bytes, 4).toInt()
            if (ifd < 0 || ifd + 2 > bytes.size) return null
            val count = u16(bytes, ifd)
            if (count <= 0 || count > 64) return null
            var w: Int? = null
            var h: Int? = null
            for (i in 0 until count) {
                val off = ifd + 2 + i * 12
                if (off + 12 > bytes.size) return null
                val tag = u16(bytes, off)
                if (tag != 256 && tag != 257) continue
                val type = u16(bytes, off + 2)
                val n = u32(bytes, off + 4)
                if (n != 1L) continue
                val value = when (type) {
                    3 -> u16(bytes, off + 8)
                    4 -> u32(bytes, off + 8).toInt()
                    else -> continue
                }
                if (value <= 0) continue
                if (tag == 256) w = value else h = value
            }
            if (w == null || h == null) return null
            w to h
        } catch (_: Exception) {
            null
        }
    }

    private fun u16(bytes: ByteArray, off: Int): Int =
        (bytes[off].toInt() and 0xFF) or ((bytes[off + 1].toInt() and 0xFF) shl 8)

    private fun u32(bytes: ByteArray, off: Int): Long =
        (bytes[off].toLong() and 0xFF) or
            ((bytes[off + 1].toLong() and 0xFF) shl 8) or
            ((bytes[off + 2].toLong() and 0xFF) shl 16) or
            ((bytes[off + 3].toLong() and 0xFF) shl 24)
}
