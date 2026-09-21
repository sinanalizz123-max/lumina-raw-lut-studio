package com.lumina.studio.core.render.cpu

import android.graphics.Bitmap
import com.lumina.studio.core.render.ColorManager
import com.lumina.studio.core.render.ColorMatrices
import com.lumina.studio.core.render.RenderColorSpace

object CpuColorManager : ColorManager<Bitmap> {

    override fun toWorking(rgb: FloatArray): FloatArray {
        require(rgb.size == 3) { "toWorking needs an rgb triple" }
        return floatArrayOf(
            ColorMatrices.srgbToLinear(rgb[0]),
            ColorMatrices.srgbToLinear(rgb[1]),
            ColorMatrices.srgbToLinear(rgb[2])
        )
    }

    override fun fromWorking(working: FloatArray): FloatArray {
        require(working.size == 3) { "fromWorking needs a working-space triple" }
        return floatArrayOf(
            ColorMatrices.linearToSrgb(working[0]).coerceIn(0f, 1f),
            ColorMatrices.linearToSrgb(working[1]).coerceIn(0f, 1f),
            ColorMatrices.linearToSrgb(working[2]).coerceIn(0f, 1f)
        )
    }

    override fun convert(image: Bitmap, src: RenderColorSpace, dst: RenderColorSpace): Bitmap {
        if (src == dst) return image
        val w = runCatching { image.width }.getOrDefault(0)
        val h = runCatching { image.height }.getOrDefault(0)
        if (w <= 0 || h <= 0) return image
        return try {
            val total = w * h
            val pixels = IntArray(total)
            image.getPixels(pixels, 0, w, 0, 0, w, h)
            val toP3 = src == RenderColorSpace.SRGB && dst == RenderColorSpace.DISPLAY_P3
            for (i in pixels.indices) {
                val p = pixels[i]
                val rgb = floatArrayOf(
                    ((p shr 16) and 0xFF) / 255f,
                    ((p shr 8) and 0xFF) / 255f,
                    (p and 0xFF) / 255f
                )
                val mapped = if (toP3) {
                    ColorMatrices.srgbToDisplayP3(rgb)
                } else {
                    ColorMatrices.displayP3ToSrgb(rgb)
                }
                val a = p ushr 24
                pixels[i] = (a shl 24) or
                    ((mapped[0] * 255f + 0.5f).toInt().coerceIn(0, 255) shl 16) or
                    ((mapped[1] * 255f + 0.5f).toInt().coerceIn(0, 255) shl 8) or
                    (mapped[2] * 255f + 0.5f).toInt().coerceIn(0, 255)
            }
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            out.setPixels(pixels, 0, w, 0, 0, w, h)
            out
        } catch (_: OutOfMemoryError) {
            image
        } catch (_: Exception) {
            image
        }
    }
}
