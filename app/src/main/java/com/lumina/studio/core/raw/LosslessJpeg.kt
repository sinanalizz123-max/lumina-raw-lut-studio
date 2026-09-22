package com.lumina.studio.core.raw

sealed interface LosslessResult {
    data class Ok(val samples: IntArray, val width: Int, val height: Int, val bitDepth: Int) : LosslessResult
    data class Err(val reason: String) : LosslessResult
}

object LosslessJpeg {
    const val SUPPORTED: Boolean = false
    const val DISABLE_REASON: String =
        "Lossless-JPEG (SOF3) DNG strips are preview-only: the pure-Kotlin Huffman + " +
            "predictor 1-7 path below is experimental and DISABLED until synthetic round-trip " +
            "and sample-file tests pass on CI. Enabling an unvalidated SOF3 decoder would " +
            "risk corrupt pixels; sensor data is preserved untouched and the embedded " +
            "preview is shown instead."

    fun decodeStrip(data: ByteArray, width: Int, height: Int): LosslessResult {
        if (!SUPPORTED) return LosslessResult.Err(DISABLE_REASON)
        return decodeStripInternal(data, width, height)
    }

    fun decodeStripIfEnabled(data: ByteArray, width: Int, height: Int, enabled: Boolean): LosslessResult {
        if (!enabled) return LosslessResult.Err(DISABLE_REASON)
        return decodeStripInternal(data, width, height)
    }

    private fun decodeStripInternal(data: ByteArray, width: Int, height: Int): LosslessResult {
        try {
            if (data.size < 4) return LosslessResult.Err("SOF3 strip truncated")
            if (width <= 0 || height <= 0 || width > DngParser.MAX_DIM || height > DngParser.MAX_DIM) {
                return LosslessResult.Err("SOF3 bad dimensions")
            }
            if (data[0] != 0xFF.toByte() || data[1] != 0xD8.toByte()) {
                return LosslessResult.Err("SOF3 strip missing SOI")
            }
            val frame = parseFrameHeader(data)
                ?: return LosslessResult.Err("SOF3 frame header unreadable (experimental path)")
            if (frame.width != width || frame.height != height) {
                return LosslessResult.Err("SOF3 frame size mismatch")
            }
            return LosslessResult.Err(
                "SOF3 scan decode not yet validated (frame ${frame.width}x${frame.height} " +
                    "prec=${frame.precision} comps=${frame.components}); preview-only"
            )
        } catch (e: Exception) {
            return LosslessResult.Err("SOF3 decode failed: ${e.message}")
        }
    }

    data class Sof3Frame(
        val precision: Int,
        val height: Int,
        val width: Int,
        val components: Int,
        val predictor: Int
    )

    fun parseFrameHeader(data: ByteArray): Sof3Frame? {
        try {
            var pos = 2
            while (pos + 4 <= data.size) {
                if (data[pos] != 0xFF.toByte()) {
                    pos++
                    continue
                }
                val marker = data[pos + 1].toInt() and 0xFF
                if (marker == 0xD8 || marker == 0xD9) {
                    pos += 2
                    continue
                }
                if (marker == 0x01 || (marker in 0xD0..0xD7)) {
                    pos += 2
                    continue
                }
                if (pos + 4 > data.size) return null
                val len = ((data[pos + 2].toInt() and 0xFF) shl 8) or
                    (data[pos + 3].toInt() and 0xFF)
                if (len < 2) return null
                if (marker == 0xC3) {
                    if (pos + 4 + 6 > data.size) return null
                    val prec = data[pos + 4].toInt() and 0xFF
                    val hh = ((data[pos + 5].toInt() and 0xFF) shl 8) or
                        (data[pos + 6].toInt() and 0xFF)
                    val ww = ((data[pos + 7].toInt() and 0xFF) shl 8) or
                        (data[pos + 8].toInt() and 0xFF)
                    val comps = data[pos + 9].toInt() and 0xFF
                    if (prec !in 2..16 || ww <= 0 || hh <= 0 || comps !in 1..4) return null
                    return Sof3Frame(prec, hh, ww, comps, 1)
                }
                pos += 2 + len
            }
            return null
        } catch (_: Exception) {
            return null
        }
    }

    class BitReader(val data: ByteArray, var pos: Int) {
        private var bitBuf = 0
        private var bitsLeft = 0

        fun readBit(): Int? {
            try {
                if (bitsLeft == 0) {
                    if (pos >= data.size) return null
                    var b = data[pos++].toInt() and 0xFF
                    if (b == 0xFF) {
                        if (pos >= data.size) return null
                        val n = data[pos++].toInt() and 0xFF
                        if (n == 0x00) {
                            b = 0xFF
                        } else if (n == 0xD9) {
                            return null
                        } else if (n in 0xD0..0xD7) {
                            bitsLeft = 0
                            return readBit()
                        } else {
                            return null
                        }
                    }
                    bitBuf = b
                    bitsLeft = 8
                }
                bitsLeft--
                return (bitBuf shr bitsLeft) and 1
            } catch (_: Exception) {
                return null
            }
        }

        fun readBits(n: Int): Int? {
            if (n <= 0 || n > 16) return null
            var v = 0
            for (i in 0 until n) {
                val b = readBit() ?: return null
                v = (v shl 1) or b
            }
            return v
        }
    }

    class HuffmanTable(val codes: IntArray, val values: IntArray, val sizes: IntArray) {
        fun decode(br: BitReader): Int? {
            var code = 0
            var len = 0
            for (i in values.indices) {
                val b = br.readBit() ?: return null
                code = (code shl 1) or b
                len++
                for (j in values.indices) {
                    if (sizes[j] == len && codes[j] == code) return values[j]
                }
                if (len > 16) return null
            }
            return null
        }

        companion object {
            fun build(bits: IntArray, vals: IntArray): HuffmanTable? {
                try {
                    if (bits.size != 16 || vals.isEmpty()) return null
                    val sizes = IntArray(vals.size)
                    var k = 0
                    for (len in 1..16) {
                        val count = bits[len - 1]
                        if (count < 0 || k + count > vals.size) return null
                        for (i in 0 until count) {
                            sizes[k++] = len
                        }
                    }
                    if (k != vals.size) return null
                    val codes = IntArray(vals.size)
                    var code = 0
                    var curLen = sizes[0]
                    for (i in vals.indices) {
                        while (sizes[i] > curLen) {
                            code = code shl 1
                            curLen++
                        }
                        codes[i] = code
                        code++
                    }
                    return HuffmanTable(codes, vals.copyOf(), sizes)
                } catch (_: Exception) {
                    return null
                }
            }
        }
    }

    fun predict(predictor: Int, a: Int, b: Int, c: Int): Int = when (predictor) {
        1 -> a
        2 -> b
        3 -> c
        4 -> a + b - c
        5 -> a + ((b - c) shr 1)
        6 -> b + ((a - c) shr 1)
        7 -> (a + b) shr 1
        else -> a
    }

    fun reconstructRow(
        diffs: IntArray, prevRow: IntArray?, out: IntArray, predictor: Int, pointTransform: Int
    ): Boolean {
        try {
            if (diffs.size != out.size) return false
            val pt = pointTransform.coerceIn(0, 15)
            for (i in diffs.indices) {
                val a = if (i > 0) out[i - 1] else if (prevRow != null && prevRow.isNotEmpty()) prevRow[0] else 0
                val b = prevRow?.getOrNull(i) ?: 0
                val c = if (i > 0) prevRow?.getOrNull(i - 1) ?: 0 else if (prevRow != null) 0 else 0
                val pred = predict(predictor.coerceIn(1, 7), a, b, c)
                out[i] = (pred + (diffs[i] shl pt))
            }
            return true
        } catch (_: Exception) {
            return false
        }
    }

    fun extendSign(v: Int, t: Int): Int {
        if (t == 0) return 0
        val vt = 1 shl (t - 1)
        return if (v < vt) v - (1 shl t) + 1 else v
    }
}
