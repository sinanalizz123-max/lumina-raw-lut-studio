package com.lumina.studio.core.ai

import android.graphics.Bitmap

/**
 * M12 Android bitmap adapters for the [AiProcessor] contract. Kept separate
 * from the pure-JVM ai files so unit tests never touch android.graphics.
 * Callers own recycling: [analysisBitmap] may return [src] itself when it is
 * already small enough — only recycle when the result is a different instance.
 */
object AiBitmaps {
    fun analysisBitmap(src: Bitmap, maxDim: Int = AiHeuristics.ANALYSIS_MAX_DIM): Bitmap? {
        return try {
            if (src.isRecycled) return null
            val w = src.width
            val h = src.height
            if (w <= 0 || h <= 0) return null
            val longest = maxOf(w, h)
            if (longest <= maxDim) return src
            val scale = maxDim.toFloat() / longest.toFloat()
            val sw = (w * scale + 0.5f).toInt().coerceIn(1, w)
            val sh = (h * scale + 0.5f).toInt().coerceIn(1, h)
            Bitmap.createScaledBitmap(src, sw, sh, true)
        } catch (_: Exception) {
            null
        }
    }

    fun argbOf(bitmap: Bitmap): IntArray? {
        return try {
            val w = bitmap.width
            val h = bitmap.height
            if (w <= 0 || h <= 0 || bitmap.isRecycled) return null
            val out = IntArray(w * h)
            bitmap.getPixels(out, 0, w, 0, 0, w, h)
            out
        } catch (_: Exception) {
            null
        }
    }
}
