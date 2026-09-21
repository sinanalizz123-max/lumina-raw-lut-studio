package com.lumina.studio.core.render.cpu

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.net.toUri
import androidx.exifinterface.media.ExifInterface
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
        if (bytes.isEmpty()) return null
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

object UnsupportedRaw : RawDecoder<Bitmap> {

    fun handles(extension: String): Boolean {
        val ext = extension.trim().lowercase(Locale.US)
        return ext in RawCapabilities.PROPRIETARY_FORMATS
    }

    override fun capabilities(): List<RawCapability> =
        RawCapabilities.TABLE.filter { it.format != DngPreviewDecoder.EXT_DNG }

    override fun develop(source: RenderSource, maxDim: Int, recipe: RawRecipe?): Bitmap? = null
}
