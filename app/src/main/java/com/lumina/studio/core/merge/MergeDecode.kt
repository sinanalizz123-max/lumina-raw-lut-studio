package com.lumina.studio.core.merge

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.lumina.studio.core.render.Dims
import com.lumina.studio.core.render.MemoryBudget
import com.lumina.studio.core.util.ImageOrientation
import java.io.File

/**
 * M17 shared Bitmap edge for the merge services (Android-only).
 *
 * decodeAtEdge owns the memory discipline: bounds-first, inSampleSize via
 * [MemoryBudget.sampleFor] so the longest edge fits [edge], ARGB_8888
 * intermediates, EXIF-orientation normalization. Callers convert to float
 * RGB immediately and recycle — at most one decoded Bitmap is inflated per
 * frame at a time (see HdrMerger/ PanoramaStitcher loops).
 */
internal object MergeDecode {
    fun headerOf(file: File, count: Int = 8): ByteArray {
        return try {
            if (!file.isFile) return ByteArray(0)
            file.inputStream().use { input ->
                val buf = ByteArray(count)
                var read = 0
                while (read < count) {
                    val n = input.read(buf, read, count - read)
                    if (n <= 0) break
                    read += n
                }
                buf.copyOf(read)
            }
        } catch (_: Exception) {
            ByteArray(0)
        }
    }

    /** Orientation-normalized dims without inflating pixels. Null on failure. */
    fun orientedDims(file: File): Dims? {
        return try {
            if (!file.isFile) return null
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, opts)
            val rawW = opts.outWidth
            val rawH = opts.outHeight
            if (rawW <= 0 || rawH <= 0) return null
            val (w, h) = ImageOrientation.orientedBounds(file, rawW, rawH)
            if (w <= 0 || h <= 0) null else Dims(w, h)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Decode capped to [edge] longest edge + orientation normalize.
     * Null when the file cannot be decoded (caller maps to DecodeFailed).
     * OOM propagates (caller maps to OomBudget — never a crash).
     */
    fun decodeAtEdge(file: File, edge: Int): Bitmap? {
        try {
            if (!file.isFile) return null
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, opts)
            if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
            val longest = maxOf(opts.outWidth, opts.outHeight)
            val sample = MemoryBudget.sampleFor(longest, edge).coerceAtLeast(1)
            val decode = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val raw = BitmapFactory.decodeFile(file.absolutePath, decode) ?: return null
            return ImageOrientation.normalizeBitmap(raw, ImageOrientation.orientationOf(file))
        } catch (e: OutOfMemoryError) {
            throw e
        } catch (_: Exception) {
            return null
        }
    }

    /**
     * Decoder-rounding normalization: inSampleSize can leave dims ±1px off
     * the budgeted target. Bilinear-resamples to exactly [targetW]x[targetH]
     * (documented ≤1px rounding fix, not a look change). Recycles [src]
     * when replaced.
     */
    fun exactSize(src: Bitmap, targetW: Int, targetH: Int): Bitmap {
        if (src.width == targetW && src.height == targetH) return src
        if (targetW <= 0 || targetH <= 0) return src
        return try {
            val out = Bitmap.createScaledBitmap(src, targetW, targetH, true)
            if (out !== src) runCatching { if (!src.isRecycled) src.recycle() }
            out
        } catch (_: OutOfMemoryError) {
            src
        } catch (_: Exception) {
            src
        }
    }

    /** Longest-edge scale helper (mirrors the ladder math in MergeLimits). */
    fun scaledDims(srcW: Int, srcH: Int, edge: Int): Dims {
        if (srcW <= 0 || srcH <= 0) return Dims(0, 0)
        val longest = maxOf(srcW, srcH)
        if (longest <= edge) return Dims(srcW, srcH)
        val scale = edge.toDouble() / longest.toDouble()
        return Dims(
            ((srcW * scale) + 0.5).toInt().coerceAtLeast(1),
            ((srcH * scale) + 0.5).toInt().coerceAtLeast(1)
        )
    }

    fun floatRgb(bitmap: Bitmap): FloatArray {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val out = FloatArray(w * h * 3)
        var s = 0
        var d = 0
        while (s < pixels.size) {
            val p = pixels[s]
            out[d] = ((p shr 16) and 0xFF) / 255f
            out[d + 1] = ((p shr 8) and 0xFF) / 255f
            out[d + 2] = (p and 0xFF) / 255f
            s++
            d += 3
        }
        return out
    }

    fun argbFromFloat(rgb: FloatArray): IntArray {
        val px = rgb.size / 3
        val out = IntArray(px)
        for (i in 0 until px) {
            val s = i * 3
            val r = (rgb[s] * 255f + 0.5f).toInt().coerceIn(0, 255)
            val g = (rgb[s + 1] * 255f + 0.5f).toInt().coerceIn(0, 255)
            val b = (rgb[s + 2] * 255f + 0.5f).toInt().coerceIn(0, 255)
            out[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return out
    }

    /**
     * Fused-float store: RGBA_F16 (minSdk 26, no gate) so the merged base
     * keeps half-float precision into the editor; ARGB_8888 fallback on
     * edge devices. Mirrors PreviewRenderer.createWorkingBitmap.
     */
    fun storeMerged(width: Int, height: Int, pixels: IntArray): Bitmap {
        var f16: Bitmap? = null
        try {
            f16 = Bitmap.createBitmap(width, height, Bitmap.Config.RGBA_F16)
            f16.setPixels(pixels, 0, width, 0, 0, width, height)
            val done = f16
            f16 = null
            return done
        } catch (_: OutOfMemoryError) {
            recycleQuietly(f16)
            throw OutOfMemoryError("merged frame")
        } catch (_: Exception) {
            recycleQuietly(f16)
        }
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, width, 0, 0, width, height)
        return out
    }

    fun recycleQuietly(bitmap: Bitmap?) {
        if (bitmap == null) return
        runCatching { if (!bitmap.isRecycled) bitmap.recycle() }
    }
}
