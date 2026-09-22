package com.lumina.studio.core.raw

object DngCapabilities {
    const val DEVELOPED_NOTE =
        "Uncompressed DNG develops from sensor data — editable; JPEG-compressed DNG stays preview-only"
    const val UNCOMPRESSED_OK =
        "Uncompressed DNG sensor data develops to editable pixels"
    const val LOSSLESS_JPEG_PREVIEW_REASON =
        "Preview only — lossless-JPEG (SOF3) DNG decode is experimental and disabled " +
            "pending synthetic+sample validation on CI; sensor data preserved untouched"
    const val LOSSY_JPEG_PREVIEW_REASON =
        "Preview only — lossy-JPEG compressed DNG (compression 34892) carries no " +
            "lossless sensor mosaic; sensor data preserved untouched"
    const val OTHER_COMP_PREVIEW_REASON =
        "Preview only — unsupported DNG compression; sensor data preserved untouched"
    const val NON_CFA_PREVIEW_REASON =
        "Preview only — only 2x2 Bayer CFA mosaics develop; this DNG layout is preserved untouched"
    const val PROPRIETARY_NOTE =
        "Embedded preview only — proprietary RAW is not fully decoded"

    fun developability(info: DngInfo): Developability {
        if (info.compression != DngParser.COMP_UNCOMPRESSED) {
            val reason = when (info.compression) {
                DngParser.COMP_LOSSLESS_JPEG -> LOSSLESS_JPEG_PREVIEW_REASON
                DngParser.COMP_LOSSY_JPEG -> LOSSY_JPEG_PREVIEW_REASON
                else -> OTHER_COMP_PREVIEW_REASON + " (compression=${info.compression})"
            }
            return Developability(false, reason)
        }
        if (info.photometric != DngParser.PHOTO_CFA) {
            return Developability(false, NON_CFA_PREVIEW_REASON)
        }
        if (info.samplesPerPixel != 1) {
            return Developability(false, NON_CFA_PREVIEW_REASON)
        }
        if (info.cfaDimRows != 2 || info.cfaDimCols != 2) {
            return Developability(false, NON_CFA_PREVIEW_REASON + " (CFA ${info.cfaDimRows}x${info.cfaDimCols})")
        }
        if (info.bitsPerSample != 8 && info.bitsPerSample != 12 &&
            info.bitsPerSample != 14 && info.bitsPerSample != 16
        ) {
            return Developability(false, "Preview only — unsupported BitsPerSample ${info.bitsPerSample}")
        }
        if (!LosslessJpegGate.uncompressedSupported) {
            return Developability(false, OTHER_COMP_PREVIEW_REASON)
        }
        return Developability(true, UNCOMPRESSED_OK)
    }

    fun reasonForCompression(compression: Int): String = when (compression) {
        DngParser.COMP_UNCOMPRESSED -> UNCOMPRESSED_OK
        DngParser.COMP_LOSSLESS_JPEG -> LOSSLESS_JPEG_PREVIEW_REASON
        DngParser.COMP_LOSSY_JPEG -> LOSSY_JPEG_PREVIEW_REASON
        else -> OTHER_COMP_PREVIEW_REASON
    }

    fun isProprietaryEditable(extension: String): Boolean = false
}

data class Developability(val developable: Boolean, val reason: String)

object LosslessJpegGate {
    const val uncompressedSupported: Boolean = true
    const val losslessJpegSupported: Boolean = false
    const val losslessJpegReason: String =
        "Lossless-JPEG (SOF3) DNG remains preview-only: pure-Kotlin Huffman+predictor " +
            "path is present as experimental code but DISABLED until synthetic round-trip " +
            "and sample-file tests pass on CI; enabling it now would risk corrupt output."
}
