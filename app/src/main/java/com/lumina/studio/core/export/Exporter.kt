package com.lumina.studio.core.export

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.WindowManager
import androidx.core.net.toUri
import androidx.exifinterface.media.ExifInterface
import com.lumina.studio.BuildConfig
import com.lumina.studio.core.data.local.Project
import com.lumina.studio.core.edit.EditParams
import com.lumina.studio.core.edit.EditParamsJson
import com.lumina.studio.core.lut.LutCube
import com.lumina.studio.core.render.PreviewRenderer
import com.lumina.studio.core.util.ImageFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ExportFormat(val mime: String, val extension: String) {
    JPEG("image/jpeg", "jpg"),
    PNG("image/png", "png"),
    TIFF("image/tiff", "tif")
}

enum class ExportColorSpace(val label: String, val key: String) {
    SRGB("sRGB", "sRGB"),
    DISPLAY_P3("Display P3 (device-supported output container)", "Display P3");

    companion object {
        fun fromKey(key: String?): ExportColorSpace {
            if (key == null) return SRGB
            val normalized = key.trim().lowercase(Locale.US)
            return if (normalized == "display p3" || normalized == "display_p3" || normalized == "p3") DISPLAY_P3
            else SRGB
        }
    }
}

enum class QualityPreset(val label: String) {
    MAXIMUM("Maximum"),
    HIGH("High"),
    CUSTOM("Custom")
}

enum class ResolutionMode(val label: String) {
    ORIGINAL("Original"),
    CUSTOM("Custom")
}

data class ExportSettings(
    val format: ExportFormat = ExportFormat.JPEG,
    val qualityPreset: QualityPreset = QualityPreset.MAXIMUM,
    val customQuality: Int = 90,
    val resolutionMode: ResolutionMode = ResolutionMode.ORIGINAL,
    val customMaxDim: Int = 2048,
    val preserveExif: Boolean = true,
    val includeLocation: Boolean = false,
    val colorSpace: ExportColorSpace = ExportColorSpace.SRGB
) {
    fun effectiveQuality(): Int = when (qualityPreset) {
        QualityPreset.MAXIMUM -> 100
        QualityPreset.HIGH -> 90
        QualityPreset.CUSTOM -> customQuality.coerceIn(1, 100)
    }
}

object Exporter {
    const val MIN_CUSTOM_DIM = 256
    const val MAX_CUSTOM_DIM = 8192
    const val RELATIVE_DIR = "Pictures/Lumina"
    const val RELATIVE_DOWNLOAD_DIR = "Download/Lumina"

    fun targetDimensions(srcW: Int, srcH: Int, settings: ExportSettings): Pair<Int, Int> {
        if (srcW <= 0 || srcH <= 0) return 0 to 0
        if (settings.resolutionMode != ResolutionMode.CUSTOM) return srcW to srcH
        val longest = maxOf(srcW, srcH)
        val cap = settings.customMaxDim.coerceIn(MIN_CUSTOM_DIM, MAX_CUSTOM_DIM)
        if (longest <= cap) return srcW to srcH
        val scale = cap / longest.toFloat()
        return (srcW * scale + 0.5f).toInt().coerceAtLeast(1) to
            (srcH * scale + 0.5f).toInt().coerceAtLeast(1)
    }

    fun estimateBytes(trialBytes: Long, trialPixels: Long, fullPixels: Long): Long {
        if (trialBytes <= 0L || trialPixels <= 0L || fullPixels <= 0L) return 0L
        val scaled = trialBytes.toDouble() * (fullPixels.toDouble() / trialPixels.toDouble())
        if (!scaled.isFinite()) return 0L
        return scaled.toLong().coerceIn(1L, Long.MAX_VALUE)
    }

    fun estimateTiffBytes(width: Int, height: Int): Long =
        TiffWriter.estimateBytes(width, height)

    fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "–"
        return when {
            bytes < 1024L -> "$bytes B"
            bytes < 1024L * 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
            bytes < 1024L * 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
            else -> String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        }
    }

    fun displayName(format: ExportFormat, nowMs: Long = System.currentTimeMillis()): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(nowMs))
        return "lumina_$stamp.${format.extension}"
    }

    fun renderForExport(
        src: Bitmap,
        params: EditParams,
        lut: LutCube?,
        targetW: Int,
        targetH: Int
    ): Bitmap {
        val rendered = PreviewRenderer.render(src, params, lut)
        if (targetW <= 0 || targetH <= 0) return rendered
        if (rendered.width == targetW && rendered.height == targetH) return rendered
        val rw = rendered.width
        val rh = rendered.height
        if (rw <= 0 || rh <= 0) return rendered
        // Crop-aware: target dims describe the pre-crop source, so a blind
        // scale to targetW x targetH would stretch a cropped/rotated render
        // back to the source frame and silently undo the crop. Fit instead:
        // keep the rendered bitmap when it already fits (allowing swapped
        // orientation for 90-degree rotations), else downscale preserving aspect.
        if ((rw <= targetW && rh <= targetH) || (rw <= targetH && rh <= targetW)) return rendered
        val scale = minOf(targetW.toFloat() / rw, targetH.toFloat() / rh).coerceIn(0f, 1f)
        if (scale <= 0f || !scale.isFinite()) return rendered
        val outW = (rw * scale + 0.5f).toInt().coerceIn(1, rw)
        val outH = (rh * scale + 0.5f).toInt().coerceIn(1, rh)
        if (outW == rw && outH == rh) return rendered
        val scaled = Bitmap.createScaledBitmap(rendered, outW, outH, true)
        if (scaled !== rendered && rendered !== src) rendered.recycle()
        return scaled
    }

    fun compress(bitmap: Bitmap, settings: ExportSettings): ByteArray {
        if (settings.format == ExportFormat.TIFF) return encodeTiff(bitmap)
        val stream = ByteArrayOutputStream()
        when (settings.format) {
            ExportFormat.JPEG -> bitmap.compress(
                Bitmap.CompressFormat.JPEG,
                settings.effectiveQuality(),
                stream
            )
            ExportFormat.PNG -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            ExportFormat.TIFF -> throw IllegalStateException("unreachable")
        }
        return stream.toByteArray()
    }

    fun bitmapToRgb(bitmap: Bitmap): ByteArray {
        val w = bitmap.width
        val h = bitmap.height
        require(w > 0 && h > 0) { "Invalid bitmap dimensions: $w x $h" }
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val rgb = ByteArray(w * h * 3)
        var o = 0
        for (pixel in pixels) {
            rgb[o++] = ((pixel shr 16) and 0xFF).toByte()
            rgb[o++] = ((pixel shr 8) and 0xFF).toByte()
            rgb[o++] = (pixel and 0xFF).toByte()
        }
        return rgb
    }

    fun encodeTiff(bitmap: Bitmap): ByteArray =
        TiffWriter.encodeTiff(bitmap.width, bitmap.height, bitmapToRgb(bitmap))

    // Wide-gamut detection via the real platform signals
    // (Configuration.isScreenWideColorGamut + Display.isWideColorGamut).
    fun isWideGamutDisplay(context: Context): Boolean = runCatching {
        val configWide = context.resources.configuration.isScreenWideColorGamut
        val displayWide = runCatching {
            val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                context.display
            } else {
                @Suppress("DEPRECATION")
                (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.defaultDisplay
            }
            display?.isWideColorGamut == true
        }.getOrDefault(false)
        configWide || displayWide
    }.getOrDefault(false)

    // The render pipeline stays sRGB-math for correctness; Display P3 is only
    // the output Bitmap container, requested when the device reports support.
    fun withColorSpace(bitmap: Bitmap, colorSpace: ExportColorSpace): Bitmap {
        if (colorSpace != ExportColorSpace.DISPLAY_P3) return bitmap
        return try {
            val p3 = ColorSpace.get(ColorSpace.Named.DISPLAY_P3)
            val out = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888, true, p3)
            Canvas(out).drawBitmap(bitmap, 0f, 0f, null)
            out
        } catch (_: Exception) {
            bitmap
        }
    }

    fun withSourceExif(
        context: Context,
        jpegBytes: ByteArray,
        settings: ExportSettings,
        sourcePath: String?
    ): ByteArray {
        if (settings.format != ExportFormat.JPEG || !settings.preserveExif) return jpegBytes
        if (sourcePath.isNullOrBlank()) return jpegBytes
        return try {
            val srcFile = File(sourcePath)
            if (!srcFile.exists()) return jpegBytes
            val tmp = File.createTempFile("lumina_export_", ".jpg", context.cacheDir)
            try {
                tmp.writeBytes(jpegBytes)
                copyExifAttributes(srcFile.absolutePath, tmp.absolutePath, settings.includeLocation)
                tmp.readBytes()
            } finally {
                tmp.delete()
            }
        } catch (_: Exception) {
            jpegBytes
        }
    }

    private fun copyExifAttributes(srcPath: String, dstPath: String, includeLocation: Boolean) {
        val src = ExifInterface(srcPath)
        val dst = ExifInterface(dstPath)
        for (tag in EXIF_TAGS) {
            if (!includeLocation && tag in GPS_TAGS) continue
            try {
                src.getAttribute(tag)?.let { dst.setAttribute(tag, it) }
            } catch (_: Exception) {
            }
        }
        // Displayed pixels are already EXIF-normalized at decode, so exported
        // files must always declare orientation 1 (normal) to avoid double rotation.
        try {
            dst.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
        } catch (_: Exception) {
        }
        if (!includeLocation) {
            for (tag in GPS_TAGS) {
                try {
                    dst.setAttribute(tag, null)
                } catch (_: Exception) {
                }
            }
        }
        try {
            dst.saveAttributes()
        } catch (_: Exception) {
        }
    }

    suspend fun saveToGallery(
        context: Context,
        bytes: ByteArray,
        format: ExportFormat,
        fileName: String
    ): Uri = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, format.mime)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, RELATIVE_DIR)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            } else {
                @Suppress("DEPRECATION")
                put(
                    MediaStore.Images.Media.DATA,
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                        .resolve("Lumina/$fileName").absolutePath
                )
            }
        }
        val uri = resolver.insert(collection, values)
            ?: throw IllegalStateException("MediaStore refused the export")
        try {
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: throw IllegalStateException("Could not write the exported file")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
        uri
    }

    fun isRawSource(project: Project?): Boolean {
        if (project == null) return false
        return isRawSource(project.fileType, project.photoUri, project.mimeType)
    }

    fun isRawSource(fileType: String?, sourcePath: String?, mimeType: String?): Boolean {
        if (!fileType.isNullOrBlank()) {
            val badge = fileType.trim().uppercase(Locale.US)
            if (badge == "DNG" || badge == "RAW") return true
        }
        if (!sourcePath.isNullOrBlank()) {
            val ext = sourcePath.substringAfterLast('.', "").substringBefore('?').lowercase(Locale.US)
            if (ext.isNotEmpty() && ImageFiles.isRaw(ext)) return true
        }
        if (!mimeType.isNullOrBlank()) {
            val mime = mimeType.trim().lowercase(Locale.US)
            if (mime == "image/x-adobe-dng" || mime == "image/x-dng") return true
        }
        return false
    }

    fun rawOriginalName(sourcePath: String?): String {
        val base = sourcePath?.substringAfterLast('/')?.substringAfterLast('\\')?.takeIf { it.isNotBlank() }
            ?: "lumina_raw"
        return if ('.' in base) base else "$base.dng"
    }

    fun sidecarNameFor(rawName: String): String {
        val base = rawName.substringBeforeLast('.', rawName.ifBlank { "lumina_raw" })
        return "$base.lumina.json"
    }

    fun appVersionName(context: Context): String = runCatching { BuildConfig.VERSION_NAME }.getOrNull()
        ?.takeIf { it.isNotBlank() } ?: "unknown"

    fun buildSidecarJson(params: EditParams, appVersion: String, sourceName: String?): String {
        val recipe = EditParamsJson.encode(params)
        val safeSource = (sourceName ?: "").replace("\\", "\\\\").replace("\"", "\\\"")
        val safeVersion = appVersion.replace("\\", "\\\\").replace("\"", "\\\"")
        return "{\"app\":\"Lumina RAW & LUT Studio\"," +
            "\"appVersion\":\"$safeVersion\"," +
            "\"workflow\":\"raw-compatible\"," +
            "\"sourceFile\":\"$safeSource\"," +
            "\"recipe\":$recipe," +
            "\"note\":\"Original sensor data preserved untouched. " +
            "Edits stored as a re-editable recipe, NOT baked in.\"}"
    }

    // Phase-future re-import note: a sidecar import path would read the JSON
    // written by exportSidecar(), recover the embedded \"recipe\" object and
    // apply it with EditParamsJson.decode() onto the matching RAW project.
    // That import UI is intentionally NOT built in Phase 4A.

    suspend fun exportRawOriginal(
        context: Context,
        sourcePath: String?,
        displayName: String? = null
    ): Uri = withContext(Dispatchers.IO) {
        val name = displayName?.takeIf { it.isNotBlank() } ?: rawOriginalName(sourcePath)
        val bytes = readSourceBytes(context, sourcePath)
            ?: throw IllegalStateException("Could not read the original source file")
        saveRawBytes(context, bytes, name, mimeForRawName(name))
    }

    suspend fun exportSidecar(
        context: Context,
        params: EditParams,
        sidecarName: String,
        sourceName: String? = null
    ): Uri = withContext(Dispatchers.IO) {
        val json = buildSidecarJson(params, appVersionName(context), sourceName)
        saveRawBytes(context, json.toByteArray(Charsets.UTF_8), sidecarName, "application/json")
    }

    private fun readSourceBytes(context: Context, sourcePath: String?): ByteArray? {
        if (sourcePath.isNullOrBlank()) return null
        return try {
            val file = File(sourcePath)
            if (file.exists()) {
                file.readBytes()
            } else {
                context.contentResolver.openInputStream(sourcePath.toUri())?.use { it.readBytes() }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun mimeForRawName(name: String): String {
        return when (name.substringAfterLast('.', "").lowercase(Locale.US)) {
            "dng" -> "image/x-adobe-dng"
            "json" -> "application/json"
            else -> "application/octet-stream"
        }
    }

    private fun saveRawBytes(
        context: Context,
        bytes: ByteArray,
        fileName: String,
        mime: String
    ): Uri {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, RELATIVE_DOWNLOAD_DIR)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(collection, values)
                ?: throw IllegalStateException("MediaStore refused the RAW export")
            try {
                resolver.openOutputStream(uri)?.use { it.write(bytes) }
                    ?: throw IllegalStateException("Could not write the RAW export")
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } catch (e: Exception) {
                runCatching { resolver.delete(uri, null, null) }
                throw e
            }
            return uri
        }
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            .resolve("Lumina")
        if (!dir.exists()) dir.mkdirs()
        val out = File(dir, fileName)
        out.writeBytes(bytes)
        return Uri.fromFile(out)
    }

    private val GPS_TAGS = setOf(
        ExifInterface.TAG_GPS_LATITUDE,
        ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE,
        ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE,
        ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_TIMESTAMP,
        ExifInterface.TAG_GPS_DATESTAMP
    )

    private val EXIF_TAGS = arrayOf(
        ExifInterface.TAG_ORIENTATION,
        ExifInterface.TAG_MAKE,
        ExifInterface.TAG_MODEL,
        ExifInterface.TAG_LENS_MAKE,
        ExifInterface.TAG_LENS_MODEL,
        ExifInterface.TAG_F_NUMBER,
        ExifInterface.TAG_APERTURE_VALUE,
        ExifInterface.TAG_EXPOSURE_TIME,
        ExifInterface.TAG_SHUTTER_SPEED_VALUE,
        ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
        ExifInterface.TAG_ISO_SPEED_RATINGS,
        ExifInterface.TAG_FOCAL_LENGTH,
        ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
        ExifInterface.TAG_FLASH,
        ExifInterface.TAG_WHITE_BALANCE,
        ExifInterface.TAG_EXPOSURE_MODE,
        ExifInterface.TAG_EXPOSURE_PROGRAM,
        ExifInterface.TAG_METERING_MODE,
        ExifInterface.TAG_DATETIME,
        ExifInterface.TAG_DATETIME_ORIGINAL,
        ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_OFFSET_TIME,
        ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
        ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
        ExifInterface.TAG_GPS_LATITUDE,
        ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE,
        ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE,
        ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_TIMESTAMP,
        ExifInterface.TAG_GPS_DATESTAMP,
        ExifInterface.TAG_IMAGE_DESCRIPTION,
        ExifInterface.TAG_COPYRIGHT,
        ExifInterface.TAG_X_RESOLUTION,
        ExifInterface.TAG_Y_RESOLUTION,
        ExifInterface.TAG_RESOLUTION_UNIT
    )
}
