package com.lumina.studio.core.util

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.util.concurrent.ConcurrentHashMap

object ImageOrientation {

    private const val MAX_CACHED = 128
    private val cache = ConcurrentHashMap<String, Int>()

    fun orientationOf(file: File): Int {
        val key = try {
            file.absolutePath
        } catch (_: Exception) {
            return ExifInterface.ORIENTATION_NORMAL
        }
        cache[key]?.let { return it }
        val orientation = try {
            if (!file.isFile) ExifInterface.ORIENTATION_NORMAL
            else ExifInterface(file.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        } catch (_: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
        val normalized = ExifOrientation.normalize(orientation)
        if (cache.size >= MAX_CACHED) {
            runCatching { cache.clear() }
        }
        cache[key] = normalized
        return normalized
    }

    fun orientationOfPath(path: String?): Int {
        if (path.isNullOrBlank()) return ExifInterface.ORIENTATION_NORMAL
        return try {
            orientationOf(File(path))
        } catch (_: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
    }

    fun invalidate(path: String?) {
        if (path.isNullOrBlank()) return
        runCatching { cache.remove(path) }
    }

    fun normalizeBitmap(src: Bitmap, orientation: Int): Bitmap {
        val o = ExifOrientation.normalize(orientation)
        if (o == ExifInterface.ORIENTATION_NORMAL) return src
        val w = src.width
        val h = src.height
        if (w <= 0 || h <= 0) return src
        return try {
            val matrix = Matrix()
            matrix.setValues(ExifOrientation.affineMatrix(o, w, h))
            val out = Bitmap.createBitmap(src, 0, 0, w, h, matrix, true)
            if (out !== src) {
                runCatching { if (!src.isRecycled) src.recycle() }
            }
            out
        } catch (_: OutOfMemoryError) {
            src
        } catch (_: Exception) {
            src
        }
    }

    fun normalizeFile(bitmap: Bitmap, file: File): Bitmap =
        normalizeBitmap(bitmap, orientationOf(file))

    fun orientedBounds(file: File, rawW: Int, rawH: Int): Pair<Int, Int> {
        if (rawW <= 0 || rawH <= 0) return 0 to 0
        return try {
            ExifOrientation.displaySize(rawW, rawH, orientationOf(file))
        } catch (_: Exception) {
            rawW to rawH
        }
    }

    fun clearCache() {
        runCatching { cache.clear() }
    }
}
