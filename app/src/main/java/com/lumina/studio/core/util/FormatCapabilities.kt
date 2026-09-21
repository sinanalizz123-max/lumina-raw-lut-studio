package com.lumina.studio.core.util

enum class CapabilityStatus {
    EDITABLE,
    PREVIEW_ONLY,
    UNSUPPORTED
}

data class FormatCapability(
    val extension: String,
    val status: CapabilityStatus,
    val minSdk: Int? = null,
    val reason: String,
    val badge: String
)

object FormatCapabilities {

    private fun editable(ext: String, badge: String, reason: String, minSdk: Int? = null) =
        FormatCapability(ext, CapabilityStatus.EDITABLE, minSdk, reason, badge)

    private fun previewOnly(ext: String, badge: String, reason: String, minSdk: Int? = null) =
        FormatCapability(ext, CapabilityStatus.PREVIEW_ONLY, minSdk, reason, badge)

    val TABLE: Map<String, FormatCapability> = listOf(
        editable("jpg", "JPEG", "Fully editable"),
        editable("jpeg", "JPEG", "Fully editable"),
        editable("png", "PNG", "Fully editable (lossless)"),
        editable("webp", "WEBP", "Fully editable"),
        editable("heic", "HEIC", "Editable on Android 9+ (API 28+); older devices may not decode", 28),
        editable("heif", "HEIF", "Editable on Android 9+ (API 28+); older devices may not decode", 28),
        editable("avif", "AVIF", "Editable on Android 12+ (API 31+); older devices may not decode", 31),
        editable("bmp", "BMP", "Fully editable (uncompressed)"),
        editable("gif", "GIF", "First frame editable as a still"),
        previewOnly("tif", "TIFF", "Preview only — TIFF decode varies by device"),
        previewOnly("tiff", "TIFF", "Preview only — TIFF decode varies by device"),
        previewOnly("dng", "DNG", "Embedded preview only — sensor data preserved untouched"),
        previewOnly("cr2", "RAW", "Embedded preview only — proprietary RAW is not fully decoded"),
        previewOnly("cr3", "RAW", "Embedded preview only — proprietary RAW is not fully decoded"),
        previewOnly("nef", "RAW", "Embedded preview only — proprietary RAW is not fully decoded"),
        previewOnly("nrw", "RAW", "Embedded preview only — proprietary RAW is not fully decoded"),
        previewOnly("arw", "RAW", "Embedded preview only — proprietary RAW is not fully decoded"),
        previewOnly("raf", "RAW", "Embedded preview only — proprietary RAW is not fully decoded"),
        previewOnly("rw2", "RAW", "Embedded preview only — proprietary RAW is not fully decoded"),
        previewOnly("orf", "RAW", "Embedded preview only — proprietary RAW is not fully decoded"),
        previewOnly("pef", "RAW", "Embedded preview only — proprietary RAW is not fully decoded"),
        previewOnly("srw", "RAW", "Embedded preview only — proprietary RAW is not fully decoded")
    ).associateBy { it.extension }

    val RAW_EXTENSIONS: Set<String> = setOf(
        "dng", "cr2", "cr3", "nef", "nrw", "arw", "raf", "rw2", "orf", "pef", "srw"
    )

    val SUPPORTED_EXTENSIONS: Set<String> = TABLE.keys

    const val FOOTER = "RAW / DNG / JPEG / PNG / WebP / TIFF / HEIC / AVIF / BMP / GIF"

    fun capabilityOf(extension: String): FormatCapability? {
        val ext = extension.trim().lowercase(java.util.Locale.US)
        if (ext.isEmpty()) return null
        return TABLE[ext]
    }

    fun statusOf(extension: String, mime: String? = null): CapabilityStatus {
        val ext = extension.trim().lowercase(java.util.Locale.US)
        if (ext.isNotEmpty()) {
            return TABLE[ext]?.status ?: CapabilityStatus.UNSUPPORTED
        }
        if (mime != null && mime.trim().lowercase(java.util.Locale.US).startsWith("image/")) {
            return CapabilityStatus.EDITABLE
        }
        return CapabilityStatus.UNSUPPORTED
    }

    fun isSupported(extension: String, mime: String?): Boolean =
        statusOf(extension, mime) != CapabilityStatus.UNSUPPORTED

    fun isEditable(extension: String, mime: String? = null): Boolean =
        statusOf(extension, mime) == CapabilityStatus.EDITABLE

    fun isPreviewOnly(extension: String, mime: String? = null): Boolean =
        statusOf(extension, mime) == CapabilityStatus.PREVIEW_ONLY

    fun unsupportedMessage(extension: String, mime: String?): String {
        val label = extension.trim().ifEmpty { mime?.trim().orEmpty().ifEmpty { "unknown" } }
        return "Unsupported format: $label. Supported: JPG, PNG, WebP, TIFF, HEIC, AVIF, BMP, GIF, DNG and RAW."
    }
}
