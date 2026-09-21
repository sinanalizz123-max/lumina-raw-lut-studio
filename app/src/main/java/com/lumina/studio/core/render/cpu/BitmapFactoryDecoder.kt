package com.lumina.studio.core.render.cpu

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import androidx.core.net.toUri
import com.lumina.studio.core.render.Dims
import com.lumina.studio.core.render.ImageDecoder
import com.lumina.studio.core.render.PixelRect
import com.lumina.studio.core.render.PreviewRenderer
import com.lumina.studio.core.render.RenderSource
import java.io.File

class BitmapFactoryDecoder(private val appContext: Context) : ImageDecoder<Bitmap> {
    override val name: String = "BitmapFactory"

    override fun bounds(source: RenderSource): Dims? {
        return try {
            when (source) {
                is RenderSource.File -> {
                    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(source.path, opts)
                    if (opts.outWidth <= 0 || opts.outHeight <= 0) null
                    else Dims(opts.outWidth, opts.outHeight)
                }
                is RenderSource.Content -> {
                    appContext.contentResolver.openInputStream(source.uri.toUri())?.use { input ->
                        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeStream(input, null, opts)
                        if (opts.outWidth <= 0 || opts.outHeight <= 0) null
                        else Dims(opts.outWidth, opts.outHeight)
                    }
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    override fun decode(source: RenderSource, maxDim: Int): Bitmap? {
        return when (source) {
            is RenderSource.File -> decodeFile(source.path, maxDim)
            is RenderSource.Content -> decodeContent(source.uri)
        }
    }

    private fun decodeFile(path: String, maxDim: Int): Bitmap? {
        return try {
            PreviewRenderer.decodePreview(path, maxDim) ?: run {
                val opts = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                BitmapFactory.decodeFile(path, opts)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeContent(uri: String): Bitmap? {
        return try {
            appContext.contentResolver.openInputStream(uri.toUri())?.use { input ->
                BitmapFactory.decodeStream(input)
            }
        } catch (_: Exception) {
            null
        }
    }

    override fun region(source: RenderSource, rectPx: PixelRect, sample: Int): Bitmap? {
        if (rectPx.isEmpty()) return null
        val rect = Rect(rectPx.left, rectPx.top, rectPx.right, rectPx.bottom)
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample.coerceAtLeast(1)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return try {
            when (source) {
                is RenderSource.File -> {
                    if (!File(source.path).exists()) return null
                    val decoder = BitmapRegionDecoder.newInstance(source.path, false)
                        ?: return null
                    try {
                        decoder.decodeRegion(rect, opts)
                    } finally {
                        runCatching { decoder.recycle() }
                    }
                }
                is RenderSource.Content -> {
                    appContext.contentResolver.openInputStream(source.uri.toUri())?.use { input ->
                        val decoder = BitmapRegionDecoder.newInstance(input, false)
                            ?: return null
                        try {
                            decoder.decodeRegion(rect, opts)
                        } finally {
                            runCatching { decoder.recycle() }
                        }
                    }
                }
            }
        } catch (_: OutOfMemoryError) {
            null
        } catch (_: Exception) {
            null
        }
    }
}
