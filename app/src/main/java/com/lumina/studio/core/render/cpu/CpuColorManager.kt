package com.lumina.studio.core.render.cpu

import android.graphics.Bitmap
import android.graphics.ColorSpace
import com.lumina.studio.core.render.ColorManager
import com.lumina.studio.core.render.ColorMatrices
import com.lumina.studio.core.render.RenderColorSpace

/**
 * M5 CPU color management (§14, §86).
 *
 * Input assumption: [Bitmap] pixels are sRGB-encoded (see [ColorMatrices]).
 * Working space is sRGB-math; the only true gamut conversion is sRGB<->
 * Display P3 at output time.
 *
 * Numerical approach for [convertP3]/[convertToSrgb]: linearize each 8-bit
 * channel via the cached [ColorMatrices.srgb8ToLinearFast] table (exact for
 * 8-bit inputs), sRGB --XYZ(D65)--> P3 matrix, re-encode with the sRGB
 * transfer (identical to the P3 transfer). Matches
 * android.graphics.Color.convert within ±1 LSB per channel
 * (float Δ ≤ [ColorMatrices.P3_MATRIX_TOLERANCE]); per-pixel Color.convert
 * was rejected as ~100x slower (JNI per pixel). Round-trip sRGB->P3->sRGB on
 * in-gamut colors stays within [ColorMatrices.P3_ROUNDTRIP_EPS] (pinned JVM).
 */
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
        return if (dst == RenderColorSpace.DISPLAY_P3) convertP3(image)
        else convertToSrgb(image)
    }

    // M5 real conversion (replaces container-only tagging): converted pixels
    // in a P3-tagged container. Returns the input on empty/OOM/failure.
    // Preview P3 uses this same function (never tag without converting).
    fun convertP3(image: Bitmap): Bitmap {
        val w = runCatching { image.width }.getOrDefault(0)
        val h = runCatching { image.height }.getOrDefault(0)
        if (w <= 0 || h <= 0) return image
        return try {
            val total = w * h
            val pixels = IntArray(total)
            image.getPixels(pixels, 0, w, 0, 0, w, h)
            for (i in pixels.indices) {
                val p = pixels[i]
                val mapped = ColorMatrices.srgb8ToP38(
                    (p shr 16) and 0xFF,
                    (p shr 8) and 0xFF,
                    p and 0xFF
                )
                val a = p ushr 24
                pixels[i] = (a shl 24) or
                    (mapped[0] shl 16) or
                    (mapped[1] shl 8) or
                    mapped[2]
            }
            outBitmap(w, h, RenderColorSpace.DISPLAY_P3, pixels) ?: image
        } catch (_: OutOfMemoryError) {
            image
        } catch (_: Exception) {
            image
        }
    }

    fun convertToSrgb(image: Bitmap): Bitmap {
        val w = runCatching { image.width }.getOrDefault(0)
        val h = runCatching { image.height }.getOrDefault(0)
        if (w <= 0 || h <= 0) return image
        return try {
            val total = w * h
            val pixels = IntArray(total)
            image.getPixels(pixels, 0, w, 0, 0, w, h)
            for (i in pixels.indices) {
                val p = pixels[i]
                val mapped = ColorMatrices.p38ToSrgb8(
                    (p shr 16) and 0xFF,
                    (p shr 8) and 0xFF,
                    p and 0xFF
                )
                val a = p ushr 24
                pixels[i] = (a shl 24) or
                    (mapped[0] shl 16) or
                    (mapped[1] shl 8) or
                    mapped[2]
            }
            outBitmap(w, h, RenderColorSpace.SRGB, pixels) ?: image
        } catch (_: OutOfMemoryError) {
            image
        } catch (_: Exception) {
            image
        }
    }

    private fun outBitmap(
        w: Int,
        h: Int,
        space: RenderColorSpace,
        pixels: IntArray
    ): Bitmap? {
        // P3 output is tagged with the platform Display P3 colorspace so the
        // system compositor and encoders treat the converted pixels correctly.
        // minSdk 26: the ColorSpace API exists; any failure falls back below.
        if (space == RenderColorSpace.DISPLAY_P3) {
            runCatching {
                val p3 = ColorSpace.get(ColorSpace.Named.DISPLAY_P3)
                val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888, true, p3)
                out.setPixels(pixels, 0, w, 0, 0, w, h)
                return out
            }
        }
        return runCatching {
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            out.setPixels(pixels, 0, w, 0, 0, w, h)
            out
        }.getOrNull()
    }
}
