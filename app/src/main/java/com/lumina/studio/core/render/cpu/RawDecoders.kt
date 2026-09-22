package com.lumina.studio.core.render.cpu

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.net.toUri
import androidx.exifinterface.media.ExifInterface
import com.lumina.studio.core.raw.DevelopResult
import com.lumina.studio.core.raw.DngCapabilities
import com.lumina.studio.core.raw.DngDevelop
import com.lumina.studio.core.raw.DngParseResult
import com.lumina.studio.core.raw.DngParser
import com.lumina.studio.core.render.PreviewRenderer
import com.lumina.studio.core.render.RawCapabilities
import com.lumina.studio.core.render.RawCapability
import com.lumina.studio.core.render.RawDecoder
import com.lumina.studio.core.render.RawRecipe
import com.lumina.studio.core.render.RenderSource
import com.lumina.studio.core.util.ImageOrientation
import java.io.File
import java.util.Locale

class DngPreviewDecoder(private val appContext: Context) : RawDecoder<Bitmap> {

    override fun capabilities(): List<RawCapability> = RawCapabilities.TABLE

    override fun develop(source: RenderSource, maxDim: Int, recipe: RawRecipe?): Bitmap? {
        val ext = extensionOf(source)
        if (ext != null && ext != EXT_DNG && UnsupportedRaw.handles(ext)) {
            return UnsupportedRaw.develop(source, maxDim, recipe)
        }
        return try {
            previewViaThumbnail(source, maxDim) ?: previewViaSampledDecode(source, maxDim)
        } catch (_: OutOfMemoryError) {
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun previewViaThumbnail(source: RenderSource, maxDim: Int): Bitmap? {
        val exif = openExif(source) ?: return null
        if (!runCatching { exif.hasThumbnail() }.getOrDefault(false)) return null
        val bytes = runCatching { exif.thumbnailBytes }.getOrNull() ?: return null
        if (bytes.isEmpty() || bytes.size > 20 * 1024 * 1024) return null
        val thumb = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val scaled = fitWithin(thumb, maxDim)
        val path = (source as? RenderSource.File)?.path
        return try {
            ImageOrientation.normalizeBitmap(scaled, ImageOrientation.orientationOfPath(path))
        } catch (_: Exception) {
            scaled
        }
    }

    private fun previewViaSampledDecode(source: RenderSource, maxDim: Int): Bitmap? {
        return when (source) {
            is RenderSource.File -> PreviewRenderer.decodePreview(source.path, maxDim)
            is RenderSource.Content -> {
                val decoded = try {
                    appContext.contentResolver.openInputStream(source.uri.toUri())?.use { input ->
                        val opts = BitmapFactory.Options().apply {
                            inPreferredConfig = Bitmap.Config.ARGB_8888
                        }
                        BitmapFactory.decodeStream(input, null, opts)
                    }
                } catch (_: Exception) {
                    null
                }
                if (decoded == null) null else fitWithin(decoded, maxDim)
            }
        }
    }

    private fun openExif(source: RenderSource): ExifInterface? {
        return try {
            when (source) {
                is RenderSource.File -> {
                    if (!File(source.path).isFile) null else ExifInterface(source.path)
                }
                is RenderSource.Content -> {
                    appContext.contentResolver.openInputStream(source.uri.toUri())?.use { input ->
                        ExifInterface(input)
                    }
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun fitWithin(bitmap: Bitmap, maxDim: Int): Bitmap {
        if (maxDim <= 0) return bitmap
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maxDim) return bitmap
        val scale = maxDim / longest.toFloat()
        val targetW = (bitmap.width * scale + 0.5f).toInt().coerceAtLeast(1)
        val targetH = (bitmap.height * scale + 0.5f).toInt().coerceAtLeast(1)
        return try {
            val scaled = Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
            if (scaled !== bitmap) {
                runCatching { if (!bitmap.isRecycled) bitmap.recycle() }
            }
            scaled
        } catch (_: OutOfMemoryError) {
            bitmap
        } catch (_: Exception) {
            bitmap
        }
    }

    private fun extensionOf(source: RenderSource): String? {
        val raw = when (source) {
            is RenderSource.File -> source.path.substringAfterLast('.', "").substringBefore('?')
            is RenderSource.Content -> return null
        }
        if (raw.isBlank()) return null
        return raw.lowercase(Locale.US)
    }

    companion object {
        const val EXT_DNG = "dng"
    }
}

class DngDevelopDecoder(private val appContext: Context) : RawDecoder<Bitmap> {

    private val previewFallback = DngPreviewDecoder(appContext)

    override fun capabilities(): List<RawCapability> = RawCapabilities.TABLE

    override fun isDevelopedRaw(source: RenderSource): Boolean {
        return try {
            val bytes = readSourceBytes(source, forProbe = true) ?: return false
            val parsed = DngParser.parse(bytes) as? DngParseResult.Ok ?: return false
            DngCapabilities.developability(parsed.info).developable
        } catch (_: OutOfMemoryError) {
            false
        } catch (_: Exception) {
            false
        }
    }

    override fun develop(source: RenderSource, maxDim: Int, recipe: RawRecipe?): Bitmap? {
        val ext = extensionOf(source)
        if (ext != null && ext != DngPreviewDecoder.EXT_DNG && UnsupportedRaw.handles(ext)) {
            return null
        }
        return try {
            developGenuine(source, maxDim, recipe)
                ?: previewFallback.develop(source, maxDim, recipe)
        } catch (_: OutOfMemoryError) {
            try {
                previewFallback.develop(source, maxDim, recipe)
            } catch (_: Exception) {
                null
            }
        } catch (_: Exception) {
            try {
                previewFallback.develop(source, maxDim, recipe)
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun developGenuine(source: RenderSource, maxDim: Int, recipe: RawRecipe?): Bitmap? {
        val bytes = readSourceBytes(source, forProbe = false) ?: return null
        val parsed = when (val res = DngParser.parse(bytes)) {
            is DngParseResult.Ok -> res.info
            is DngParseResult.Err -> return null
        }
        val dev = DngCapabilities.developability(parsed)
        if (!dev.developable) return null
        val strips = DngParser.extractStripBytes(bytes, parsed) ?: return null
        val result = DngDevelop.developToArgb(parsed, strips, recipe)
        val ok = result as? DevelopResult.Ok ?: return null
        var argb = ok.argb
        var w = ok.width
        var h = ok.height
        if (maxDim > 0) {
            val scaled = DngDevelop.downscaleArgb(argb, w, h, maxDim)
            argb = scaled.first
            w = scaled.second
            h = scaled.third
        }
        if (w <= 0 || h <= 0 || argb.size != w * h) return null
        if (w.toLong() * h > DngDevelop.MAX_PIXELS) return null
        return try {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.setPixels(argb, 0, w, 0, 0, w, h)
            bmp
        } catch (_: OutOfMemoryError) {
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun readSourceBytes(source: RenderSource, forProbe: Boolean): ByteArray? {
        return try {
            when (source) {
                is RenderSource.File -> {
                    val f = File(source.path)
                    if (!f.isFile) return null
                    val len = try {
                        f.length()
                    } catch (_: Exception) {
                        -1L
                    }
                    if (len <= 0L || len > MAX_RAW_BYTES) return null
                    if (len > Int.MAX_VALUE) return null
                    f.readBytes()
                }
                is RenderSource.Content -> {
                    appContext.contentResolver.openInputStream(source.uri.toUri())?.use { input ->
                        val cap = if (forProbe) PROBE_CAP_BYTES else MAX_RAW_BYTES
                        readCapped(input, cap)
                    }
                }
            }
        } catch (_: OutOfMemoryError) {
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun readCapped(input: java.io.InputStream, cap: Long): ByteArray? {
        return try {
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(32768)
            var total = 0L
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                total += n
                if (total > cap) return null
                out.write(buf, 0, n)
            }
            out.toByteArray()
        } catch (_: OutOfMemoryError) {
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun extensionOf(source: RenderSource): String? {
        val raw = when (source) {
            is RenderSource.File -> source.path.substringAfterLast('.', "").substringBefore('?')
            is RenderSource.Content -> return null
        }
        if (raw.isBlank()) return null
        return raw.lowercase(Locale.US)
    }

    companion object {
        const val MAX_RAW_BYTES = 120_000_000L
        const val PROBE_CAP_BYTES = 120_000_000L
    }
}

object UnsupportedRaw : RawDecoder<Bitmap> {

    fun handles(extension: String): Boolean {
        val ext = extension.trim().lowercase(Locale.US)
        return ext in RawCapabilities.PROPRIETARY_FORMATS
    }

    override fun capabilities(): List<RawCapability> =
        RawCapabilities.TABLE.filter { it.format != DngPreviewDecoder.EXT_DNG }

    override fun develop(source: RenderSource, maxDim: Int, recipe: RawRecipe?): Bitmap? = null
}
