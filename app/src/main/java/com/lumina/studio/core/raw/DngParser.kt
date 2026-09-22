package com.lumina.studio.core.raw

sealed interface DngParseResult {
    data class Ok(val info: DngInfo) : DngParseResult
    data class Err(val reason: String) : DngParseResult
}

data class DngPreviewInfo(
    val width: Int,
    val height: Int,
    val compression: Int,
    val offset: Long,
    val byteCount: Long
)

data class DngInfo(
    val width: Int,
    val height: Int,
    val bitsPerSample: Int,
    val samplesPerPixel: Int,
    val compression: Int,
    val photometric: Int,
    val planarConfig: Int,
    val orientation: Int,
    val rowsPerStrip: Int,
    val stripOffsets: LongArray,
    val stripByteCounts: LongArray,
    val cfaDimRows: Int,
    val cfaDimCols: Int,
    val cfaPattern: IntArray,
    val cfaPlaneColor: IntArray?,
    val blackRepeatRows: Int,
    val blackRepeatCols: Int,
    val blackLevels: DoubleArray,
    val whiteLevel: Long,
    val colorMatrix1: FloatArray?,
    val colorMatrix2: FloatArray?,
    val asShotNeutral: DoubleArray?,
    val asShotWhiteXY: DoubleArray?,
    val analogBalance: DoubleArray?,
    val calibrationIlluminant1: Int?,
    val calibrationIlluminant2: Int?,
    val isLittleEndian: Boolean,
    val previews: List<DngPreviewInfo>
) {
    val bits: Int get() = bitsPerSample
}

object DngParser {
    const val MAX_DIM = 30000
    const val MAX_PIXELS = 120_000_000L
    const val MAX_IFD_ENTRIES = 512
    const val MAX_IFDS = 16
    const val MAX_STRIPS = 8192
    const val MAX_FILE_BYTES = 250_000_000L

    const val TAG_IMAGE_WIDTH = 256
    const val TAG_IMAGE_LENGTH = 257
    const val TAG_BITS_PER_SAMPLE = 258
    const val TAG_COMPRESSION = 259
    const val TAG_PHOTOMETRIC = 262
    const val TAG_STRIP_OFFSETS = 273
    const val TAG_SAMPLES_PER_PIXEL = 277
    const val TAG_ROWS_PER_STRIP = 278
    const val TAG_STRIP_BYTE_COUNTS = 279
    const val TAG_PLANAR_CONFIG = 284
    const val TAG_ORIENTATION = 274
    const val TAG_SUB_IFDS = 330
    const val TAG_SAMPLE_FORMAT = 339
    const val TAG_JPEG_OFFSET = 513
    const val TAG_JPEG_LENGTH = 514
    const val TAG_CFA_REPEAT_DIM = 33421
    const val TAG_CFA_PATTERN = 33422
    const val TAG_CFA_PLANE_COLOR = 50710
    const val TAG_BLACK_REPEAT_DIM = 50713
    const val TAG_BLACK_LEVEL = 50714
    const val TAG_WHITE_LEVEL = 50717
    const val TAG_COLOR_MATRIX_1 = 50721
    const val TAG_COLOR_MATRIX_2 = 50722
    const val TAG_ANALOG_BALANCE = 50727
    const val TAG_AS_SHOT_NEUTRAL = 50728
    const val TAG_AS_SHOT_WHITE_XY = 50729
    const val TAG_CALIB_ILLUM_1 = 50778
    const val TAG_CALIB_ILLUM_2 = 50779

    const val COMP_UNCOMPRESSED = 1
    const val COMP_LOSSLESS_JPEG = 7
    const val COMP_LOSSY_JPEG = 34892

    const val PHOTO_CFA = 32803
    const val PHOTO_LINEAR_RAW = 34892

    private const val TYPE_BYTE = 1
    private const val TYPE_ASCII = 2
    private const val TYPE_SHORT = 3
    private const val TYPE_LONG = 4
    private const val TYPE_RATIONAL = 5
    private const val TYPE_SBYTE = 6
    private const val TYPE_UNDEFINED = 7
    private const val TYPE_SSHORT = 8
    private const val TYPE_SLONG = 9
    private const val TYPE_SRATIONAL = 10
    private const val TYPE_FLOAT = 11
    private const val TYPE_DOUBLE = 12

    @Synchronized
    fun parse(bytes: ByteArray): DngParseResult {
        try {
            if (bytes.size < 8) return DngParseResult.Err("Truncated file: smaller than TIFF header")
            if (bytes.size.toLong() > MAX_FILE_BYTES) {
                return DngParseResult.Err("File too large for safe parse")
            }
            val little: Boolean = when {
                bytes[0] == 'I'.code.toByte() && bytes[1] == 'I'.code.toByte() -> true
                bytes[0] == 'M'.code.toByte() && bytes[1] == 'M'.code.toByte() -> false
                else -> return DngParseResult.Err("Not a TIFF/DNG file: bad byte-order mark")
            }
            val magic = readU16(bytes, 2, little) ?: return DngParseResult.Err("Truncated TIFF header")
            if (magic != 42) return DngParseResult.Err("Not a TIFF/DNG file: bad magic $magic")
            val firstIfd = readU32(bytes, 4, little) ?: return DngParseResult.Err("Truncated TIFF header")
            if (firstIfd < 8L || firstIfd > bytes.size.toLong()) {
                return DngParseResult.Err("Truncated file: first IFD offset out of bounds")
            }
            val ifdOffsets = ArrayList<Long>()
            var next = firstIfd
            val seen = HashSet<Long>()
            var guard = 0
            while (next != 0L && guard < MAX_IFDS) {
                guard++
                if (next < 0L || next + 2 > bytes.size) {
                    return DngParseResult.Err("Truncated file: IFD offset out of bounds")
                }
                if (!seen.add(next)) return DngParseResult.Err("Malformed TIFF: IFD loop")
                ifdOffsets.add(next)
                val count = readU16(bytes, next.toInt(), little)
                    ?: return DngParseResult.Err("Truncated file: IFD entry count out of bounds")
                if (count <= 0 || count > MAX_IFD_ENTRIES) {
                    return DngParseResult.Err("Malformed TIFF: bad IFD entry count $count")
                }
                val entriesEnd = next + 2 + count.toLong() * 12L
                if (entriesEnd + 4 > bytes.size) {
                    return DngParseResult.Err("Truncated file: IFD entries exceed file")
                }
                val afterEntries = readU32(bytes, entriesEnd.toInt(), little)
                    ?: return DngParseResult.Err("Truncated file: next-IFD pointer out of bounds")
                next = afterEntries
            }
            if (ifdOffsets.isEmpty()) return DngParseResult.Err("Malformed TIFF: no IFDs")
            val mainEntries = readEntries(bytes, ifdOffsets[0], little)
                as? EntryMap ?: return (mainEntries as DngParseResult.Err)
            val main = buildMainInfo(bytes, mainEntries, little)
                ?: return DngParseResult.Err((buildMainInfoError))
            val previews = ArrayList<DngPreviewInfo>()
            for (i in 1 until ifdOffsets.size) {
                val em = readEntries(bytes, ifdOffsets[i], little)
                if (em is EntryMap) {
                    previewFromEntries(bytes, em, little)?.let { previews.add(it) }
                }
            }
            val subIfds = mainEntries.longs(TAG_SUB_IFDS, bytes, little)
            if (subIfds != null) {
                if (subIfds.size > MAX_IFDS) return DngParseResult.Err("Malformed TIFF: too many SubIFDs")
                for (off in subIfds) {
                    if (off <= 0L || off >= bytes.size) continue
                    if (!seen.add(off)) continue
                    val em = readEntries(bytes, off, little)
                    if (em is EntryMap) {
                        previewFromEntries(bytes, em, little)?.let { previews.add(it) }
                    }
                }
            }
            val jpegOff = mainEntries.longs(TAG_JPEG_OFFSET, bytes, little)?.firstOrNull()
            val jpegLen = mainEntries.longs(TAG_JPEG_LENGTH, bytes, little)?.firstOrNull()
            if (jpegOff != null && jpegLen != null && jpegOff > 0 && jpegLen > 0) {
                if (jpegOff + jpegLen <= bytes.size.toLong()) {
                    previews.add(DngPreviewInfo(main.width, main.height, 6, jpegOff, jpegLen))
                }
            }
            return DngParseResult.Ok(main.copy(previews = previews.toList()))
        } catch (_: OutOfMemoryError) {
            return DngParseResult.Err("File too large to parse safely")
        } catch (e: Exception) {
            return DngParseResult.Err("Parse failed: ${e.message}")
        }
    }

    private var buildMainInfoError: String = "Malformed DNG"

    private fun buildMainInfo(bytes: ByteArray, em: EntryMap, little: Boolean): DngInfo? {
        fun fail(reason: String): DngInfo? {
            buildMainInfoError = reason
            return null
        }
        val widthL = em.longs(TAG_IMAGE_WIDTH, bytes, little)?.firstOrNull()
            ?: return fail("Missing ImageWidth")
        val heightL = em.longs(TAG_IMAGE_LENGTH, bytes, little)?.firstOrNull()
            ?: return fail("Missing ImageLength")
        if (widthL <= 0 || heightL <= 0) return fail("Malformed DNG: bad dimensions $widthL x $heightL")
        if (widthL > MAX_DIM || heightL > MAX_DIM) {
            return fail("Evil dimensions rejected: $widthL x $heightL")
        }
        if (widthL * heightL > MAX_PIXELS) return fail("Image too large to develop safely")
        val width = widthL.toInt()
        val height = heightL.toInt()
        val bitsArr = em.longs(TAG_BITS_PER_SAMPLE, bytes, little)
        val bits = (bitsArr?.firstOrNull() ?: 0L).toInt()
        if (bits != 8 && bits != 12 && bits != 14 && bits != 16) {
            return fail("Unsupported BitsPerSample $bits (need 8/12/14/16)")
        }
        val samples = (em.longs(TAG_SAMPLES_PER_PIXEL, bytes, little)?.firstOrNull() ?: 1L).toInt()
        if (samples != 1 && samples != 3) return fail("Unsupported SamplesPerPixel $samples")
        val comp = (em.longs(TAG_COMPRESSION, bytes, little)?.firstOrNull() ?: 1L).toInt()
        val photo = (em.longs(TAG_PHOTOMETRIC, bytes, little)?.firstOrNull() ?: PHOTO_CFA.toLong()).toInt()
        val planar = (em.longs(TAG_PLANAR_CONFIG, bytes, little)?.firstOrNull() ?: 1L).toInt()
        val orient = (em.longs(TAG_ORIENTATION, bytes, little)?.firstOrNull() ?: 1L).toInt()
        val orientation = if (orient in 1..8) orient else 1
        val rowsPerStrip = (em.longs(TAG_ROWS_PER_STRIP, bytes, little)?.firstOrNull()
            ?: height.toLong()).toInt().coerceAtLeast(1)
        val offsets = em.longs(TAG_STRIP_OFFSETS, bytes, little)
            ?: return fail("Missing StripOffsets")
        val counts = em.longs(TAG_STRIP_BYTE_COUNTS, bytes, little)
            ?: return fail("Missing StripByteCounts")
        if (offsets.isEmpty() || counts.isEmpty()) return fail("Empty strip tables")
        if (offsets.size != counts.size) return fail("StripOffsets/ByteCounts count mismatch")
        if (offsets.size > MAX_STRIPS) return fail("Too many strips")
        val fileSize = bytes.size.toLong()
        for (i in offsets.indices) {
            val off = offsets[i]
            val cnt = counts[i]
            if (cnt <= 0L || cnt > fileSize) return fail("Strip $i byte count out of bounds")
            if (off < 0L || off >= fileSize) return fail("Strip $i offset out of bounds")
            if (off + cnt > fileSize || off + cnt < 0L) {
                return fail("Strip $i extends past end of file (truncated)")
            }
        }
        var cfaRows = 2
        var cfaCols = 2
        var cfaPat = intArrayOf(0, 1, 1, 2)
        var cfaPlane: IntArray? = null
        if (photo == PHOTO_CFA) {
            val dim = em.longs(TAG_CFA_REPEAT_DIM, bytes, little)
            if (dim != null && dim.size >= 2) {
                val r = dim[0].toInt()
                val c = dim[1].toInt()
                if (r <= 0 || c <= 0 || r > 8 || c > 8) {
                    return fail("Malformed CFAPatternDim ${r}x${c}")
                }
                cfaRows = r
                cfaCols = c
            }
            val pat = em.bytesOf(TAG_CFA_PATTERN, bytes, little)
            if (pat == null || pat.isEmpty()) return fail("Missing CFAPattern for CFA image")
            if (pat.size != cfaRows * cfaCols) {
                return fail("CFAPattern size mismatch: got ${pat.size}, need ${cfaRows * cfaCols}")
            }
            for (v in pat) {
                if (v < 0 || v > 5) return fail("Bad CFAPattern value $v")
            }
            cfaPat = pat
            val plane = em.longs(TAG_CFA_PLANE_COLOR, bytes, little)
            if (plane != null) {
                if (plane.size > 4) return fail("Bad CFAPlaneColor count")
                cfaPlane = plane.map { it.toInt() }.toIntArray()
            }
        }
        var repRows = 1
        var repCols = 1
        val repDim = em.longs(TAG_BLACK_REPEAT_DIM, bytes, little)
        if (repDim != null && repDim.size >= 2) {
            repRows = repDim[0].toInt()
            repCols = repDim[1].toInt()
            if (repRows <= 0 || repCols <= 0 || repRows > 8 || repCols > 8) {
                return fail("Malformed BlackLevelRepeatDim")
            }
            if (repRows.toLong() * repCols > 64L) return fail("BlackLevel repeat too large")
        }
        val blacks: DoubleArray = em.rationals(TAG_BLACK_LEVEL, bytes, little)
            ?: DoubleArray(repRows * repCols) { 0.0 }
        if (blacks.size != repRows * repCols) {
            if (blacks.size == 1 && repRows * repCols > 1) {
                val fill = blacks[0]
                val expanded = DoubleArray(repRows * repCols) { fill }
                return buildInfo(
                    em, bytes, little, width, height, bits, samples, comp, photo, planar,
                    orientation, rowsPerStrip, offsets, counts, cfaRows, cfaCols, cfaPat,
                    cfaPlane, repRows, repCols, expanded
                )
            }
            return fail("BlackLevel count mismatch")
        }
        return buildInfo(
            em, bytes, little, width, height, bits, samples, comp, photo, planar,
            orientation, rowsPerStrip, offsets, counts, cfaRows, cfaCols, cfaPat,
            cfaPlane, repRows, repCols, blacks
        )
    }

    private fun buildInfo(
        em: EntryMap, bytes: ByteArray, little: Boolean, width: Int, height: Int, bits: Int,
        samples: Int, comp: Int, photo: Int, planar: Int, orientation: Int,
        rowsPerStrip: Int, offsets: LongArray, counts: LongArray,
        cfaRows: Int, cfaCols: Int, cfaPat: IntArray, cfaPlane: IntArray?,
        repRows: Int, repCols: Int, blacks: DoubleArray
    ): DngInfo? {
        val white = em.longs(TAG_WHITE_LEVEL, bytes, little)?.firstOrNull()
            ?: ((1L shl bits) - 1L)
        if (white <= 0L || white > 65536L) {
            buildMainInfoError = "Bad WhiteLevel $white"
            return null
        }
        val cm1 = em.srationals(TAG_COLOR_MATRIX_1, bytes, little)?.takeIf { it.size == 9 }
            ?.map { it.toFloat() }?.toFloatArray()
        val cm2 = em.srationals(TAG_COLOR_MATRIX_2, bytes, little)?.takeIf { it.size == 9 }
            ?.map { it.toFloat() }?.toFloatArray()
        val asn = em.rationals(TAG_AS_SHOT_NEUTRAL, bytes, little)?.takeIf { it.size == 3 }
        val axy = em.rationals(TAG_AS_SHOT_WHITE_XY, bytes, little)?.takeIf { it.size == 2 }
        val ab = em.rationals(TAG_ANALOG_BALANCE, bytes, little)?.takeIf { it.size == 3 }
        val ill1 = em.longs(TAG_CALIB_ILLUM_1, bytes, little)?.firstOrNull()?.toInt()
        val ill2 = em.longs(TAG_CALIB_ILLUM_2, bytes, little)?.firstOrNull()?.toInt()
        return DngInfo(
            width = width, height = height, bitsPerSample = bits,
            samplesPerPixel = samples, compression = comp, photometric = photo,
            planarConfig = planar, orientation = orientation, rowsPerStrip = rowsPerStrip,
            stripOffsets = offsets, stripByteCounts = counts,
            cfaDimRows = cfaRows, cfaDimCols = cfaCols, cfaPattern = cfaPat,
            cfaPlaneColor = cfaPlane, blackRepeatRows = repRows, blackRepeatCols = repCols,
            blackLevels = blacks, whiteLevel = white, colorMatrix1 = cm1,
            colorMatrix2 = cm2, asShotNeutral = asn, asShotWhiteXY = axy,
            analogBalance = ab, calibrationIlluminant1 = ill1,
            calibrationIlluminant2 = ill2, isLittleEndian = little,
            previews = emptyList()
        )
    }

    private var lastEntryMap: EntryMap? = null

    private sealed interface EntryRead {
        data class Map(val map: EntryMap) : EntryRead
        data class Fail(val reason: String) : EntryRead
    }

    private class RawEntry(val tag: Int, val type: Int, val count: Long, val valueOffset: Int)

    private class EntryMap(val entries: Map<Int, RawEntry>) {
        fun longs(tag: Int, bytes: ByteArray, little: Boolean): LongArray? {
            val e = entries[tag] ?: return null
            return readLongs(bytes, e, little) ?: LongArray(0)
        }

        fun bytesOf(tag: Int, bytes: ByteArray, little: Boolean): IntArray? {
            val e = entries[tag] ?: return null
            if (e.type != TYPE_BYTE && e.type != TYPE_UNDEFINED) return null
            if (e.count <= 0L || e.count > 4096L) return null
            val n = e.count.toInt()
            val out = IntArray(n)
            if (e.count <= 4L) {
                for (i in 0 until n) {
                    out[i] = bytes[e.valueOffset + i].toInt() and 0xFF
                }
                return out
            }
            val off = readU32(bytes, e.valueOffset, little) ?: return null
            if (off < 0L || off + n > bytes.size) return null
            for (i in 0 until n) out[i] = bytes[(off + i).toInt()].toInt() and 0xFF
            return out
        }

        fun rationals(tag: Int, bytes: ByteArray, little: Boolean): DoubleArray? {
            val e = entries[tag] ?: return null
            return when (e.type) {
                TYPE_RATIONAL -> readRationals(bytes, e, little)
                TYPE_SHORT -> readLongs(bytes, e, little)?.map { it.toDouble() }?.toDoubleArray()
                TYPE_LONG -> readLongs(bytes, e, little)?.map { it.toDouble() }?.toDoubleArray()
                else -> null
            }
        }

        fun srationals(tag: Int, bytes: ByteArray, little: Boolean): DoubleArray? {
            val e = entries[tag] ?: return null
            if (e.type != TYPE_SRATIONAL) return null
            return readSRationals(bytes, e, little)
        }
    }

    private fun readEntries(bytes: ByteArray, ifdOff: Long, little: Boolean): Any {
        if (ifdOff < 0L || ifdOff + 2 > bytes.size) return DngParseResult.Err("IFD out of bounds")
        val count = readU16(bytes, ifdOff.toInt(), little) ?: return DngParseResult.Err("IFD truncated")
        if (count <= 0 || count > MAX_IFD_ENTRIES) return DngParseResult.Err("Bad IFD count $count")
        if (ifdOff + 2 + count.toLong() * 12L + 4 > bytes.size) {
            return DngParseResult.Err("IFD entries exceed file")
        }
        val map = HashMap<Int, RawEntry>()
        for (i in 0 until count) {
            val base = (ifdOff + 2 + i.toLong() * 12L).toInt()
            val tag = readU16(bytes, base, little) ?: return DngParseResult.Err("IFD entry truncated")
            val type = readU16(bytes, base + 2, little) ?: return DngParseResult.Err("IFD entry truncated")
            val cnt = readU32(bytes, base + 4, little) ?: return DngParseResult.Err("IFD entry truncated")
            if (!isKnownType(type)) return DngParseResult.Err("Bad TIFF field type $type")
            val typeSize = typeSizeOf(type)
            if (cnt > 16_777_216L) return DngParseResult.Err("Evil field count $cnt")
            val total: Long = try {
                Math.multiplyExact(cnt, typeSize.toLong())
            } catch (_: ArithmeticException) {
                return DngParseResult.Err("Evil field size")
            }
            if (total > bytes.size) return DngParseResult.Err("Field larger than file")
            if (total > 4L) {
                val off = readU32(bytes, base + 8, little)
                    ?: return DngParseResult.Err("IFD entry truncated")
                if (off < 0L || off + total > bytes.size.toLong() || off + total < 0L) {
                    return DngParseResult.Err("Field value out of bounds (truncated)")
                }
            }
            if (!map.containsKey(tag)) {
                map[tag] = RawEntry(tag, type, cnt, base + 8)
            }
        }
        val em = EntryMap(map)
        lastEntryMap = em
        return em
    }

    private fun previewFromEntries(bytes: ByteArray, em: EntryMap, little: Boolean): DngPreviewInfo? {
        return try {
            val w = em.longs(TAG_IMAGE_WIDTH, bytes, little)?.firstOrNull()?.toInt() ?: return null
            val h = em.longs(TAG_IMAGE_LENGTH, bytes, little)?.firstOrNull()?.toInt() ?: return null
            if (w <= 0 || h <= 0 || w > MAX_DIM || h > MAX_DIM) return null
            val comp = em.longs(TAG_COMPRESSION, bytes, little)?.firstOrNull()?.toInt() ?: 1
            val offs = em.longs(TAG_STRIP_OFFSETS, bytes, little)
            val cnts = em.longs(TAG_STRIP_BYTE_COUNTS, bytes, little)
            if (offs != null && cnts != null && offs.isNotEmpty() && offs.size == cnts.size) {
                val o = offs[0]
                var total = 0L
                for (c in cnts) total += c
                if (o >= 0 && o + total <= bytes.size) {
                    return DngPreviewInfo(w, h, comp, o, total)
                }
            }
            val jo = em.longs(TAG_JPEG_OFFSET, bytes, little)?.firstOrNull()
            val jl = em.longs(TAG_JPEG_LENGTH, bytes, little)?.firstOrNull()
            if (jo != null && jl != null && jo > 0 && jl > 0 && jo + jl <= bytes.size) {
                return DngPreviewInfo(w, h, 6, jo, jl)
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun readLongs(bytes: ByteArray, e: RawEntry, little: Boolean): LongArray? {
        if (e.count <= 0L || e.count > 1_048_576L) return LongArray(0)
        val n = e.count.toInt()
        val out = LongArray(n)
        when (e.type) {
            TYPE_SHORT -> {
                if (e.count == 1L) {
                    val v = if (little) {
                        ((bytes[e.valueOffset].toInt() and 0xFF) or
                            ((bytes[e.valueOffset + 1].toInt() and 0xFF) shl 8)).toLong()
                    } else {
                        (((bytes[e.valueOffset].toInt() and 0xFF) shl 8) or
                            (bytes[e.valueOffset + 1].toInt() and 0xFF)).toLong()
                    }
                    out[0] = v
                    return out
                }
                if (e.count == 2L) {
                    for (i in 0 until 2) {
                        val b = e.valueOffset + i * 2
                        out[i] = readU16(bytes, b, little)?.toLong() ?: return null
                    }
                    return out
                }
                val off = readU32(bytes, e.valueOffset, little) ?: return null
                if (off + n * 2L > bytes.size) return null
                for (i in 0 until n) {
                    out[i] = readU16(bytes, (off + i * 2).toInt(), little)?.toLong() ?: return null
                }
                return out
            }
            TYPE_LONG -> {
                if (e.count == 1L) {
                    out[0] = readU32(bytes, e.valueOffset, little) ?: return null
                    return out
                }
                val off = readU32(bytes, e.valueOffset, little) ?: return null
                if (off + n * 4L > bytes.size) return null
                for (i in 0 until n) {
                    out[i] = readU32(bytes, (off + i * 4).toInt(), little) ?: return null
                }
                return out
            }
            else -> return null
        }
    }

    private fun readRationals(bytes: ByteArray, e: RawEntry, little: Boolean): DoubleArray? {
        if (e.count <= 0L || e.count > 64L) return null
        val n = e.count.toInt()
        val off = readU32(bytes, e.valueOffset, little) ?: return null
        if (off + n * 8L > bytes.size) return null
        val out = DoubleArray(n)
        for (i in 0 until n) {
            val num = readU32(bytes, (off + i * 8).toInt(), little) ?: return null
            val den = readU32(bytes, (off + i * 8 + 4).toInt(), little) ?: return null
            out[i] = if (den == 0L) 0.0 else num.toDouble() / den.toDouble()
        }
        return out
    }

    private fun readSRationals(bytes: ByteArray, e: RawEntry, little: Boolean): DoubleArray? {
        if (e.count <= 0L || e.count > 64L) return null
        val n = e.count.toInt()
        val off = readU32(bytes, e.valueOffset, little) ?: return null
        if (off + n * 8L > bytes.size) return null
        val out = DoubleArray(n)
        for (i in 0 until n) {
            val num = readS32(bytes, (off + i * 8).toInt(), little) ?: return null
            val den = readS32(bytes, (off + i * 8 + 4).toInt(), little) ?: return null
            out[i] = if (den == 0) 0.0 else num.toDouble() / den.toDouble()
        }
        return out
    }

    private fun isKnownType(t: Int): Boolean = t in 1..12

    private fun typeSizeOf(t: Int): Int = when (t) {
        TYPE_BYTE, TYPE_ASCII, TYPE_SBYTE, TYPE_UNDEFINED -> 1
        TYPE_SHORT, TYPE_SSHORT -> 2
        TYPE_LONG, TYPE_SLONG, TYPE_FLOAT -> 4
        TYPE_RATIONAL, TYPE_SRATIONAL, TYPE_DOUBLE -> 8
        else -> 1
    }

    fun extractStripBytes(fileBytes: ByteArray, info: DngInfo): ByteArray? {
        try {
            var total = 0L
            for (c in info.stripByteCounts) {
                total += c
                if (total < 0L || total > MAX_FILE_BYTES) return null
            }
            if (total <= 0L || total > fileBytes.size) return null
            if (total > Int.MAX_VALUE) return null
            val out = try {
                ByteArray(total.toInt())
            } catch (_: OutOfMemoryError) {
                return null
            }
            var dst = 0
            for (i in info.stripOffsets.indices) {
                val off = info.stripOffsets[i]
                val cnt = info.stripByteCounts[i].toInt()
                if (off < 0L || off + cnt > fileBytes.size || cnt <= 0) return null
                fileBytes.copyInto(out, dst, off.toInt(), off.toInt() + cnt)
                dst += cnt
            }
            return out
        } catch (_: Exception) {
            return null
        }
    }

    fun quickDevelopable(bytes: ByteArray, maxHeaderBytes: Int = 262144): Pair<Boolean, String>? {
        try {
            val head = if (bytes.size <= maxHeaderBytes) bytes else bytes.copyOf(maxHeaderBytes)
            val res = parse(head)
            return when (res) {
                is DngParseResult.Ok -> {
                    val d = DngCapabilities.developability(res.info)
                    Pair(d.developable, d.reason)
                }
                is DngParseResult.Err -> null
            }
        } catch (_: Exception) {
            return null
        }
    }

    private fun readU16(bytes: ByteArray, off: Int, little: Boolean): Int? {
        if (off < 0 || off + 2 > bytes.size) return null
        val b0 = bytes[off].toInt() and 0xFF
        val b1 = bytes[off + 1].toInt() and 0xFF
        return if (little) b0 or (b1 shl 8) else (b0 shl 8) or b1
    }

    private fun readU32(bytes: ByteArray, off: Int, little: Boolean): Long? {
        if (off < 0 || off + 4 > bytes.size) return null
        val b0 = bytes[off].toLong() and 0xFF
        val b1 = bytes[off + 1].toLong() and 0xFF
        val b2 = bytes[off + 2].toLong() and 0xFF
        val b3 = bytes[off + 3].toLong() and 0xFF
        return if (little) b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
        else (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
    }

    private fun readS32(bytes: ByteArray, off: Int, little: Boolean): Int? {
        val u = readU32(bytes, off, little) ?: return null
        return u.toInt()
    }
}
