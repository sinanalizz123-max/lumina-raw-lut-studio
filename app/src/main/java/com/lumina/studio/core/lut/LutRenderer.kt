package com.lumina.studio.core.lut

import android.graphics.Bitmap
import kotlin.math.floor

object LutRenderer {
    const val FAST_PATH_MAX_SIZE = 33
    const val DOWNSAMPLED_SIZE = 32

    // Zoom-tile perf: cache the downsampled effective table per LutCube
    // instance. Large imported LUTs (>33) cost ~32k trilinear samples to
    // downsample; preview + tile renders share the same registry instance so
    // the second render reuses it. Reference-equality key avoids expensive
    // FloatArray content comparison.
    @Volatile
    private var cachedEffectiveSource: LutCube? = null
    @Volatile
    private var cachedEffectiveResult: LutCube? = null

    // M5: [outConfig] selects the intermediate precision. PREVIEW callers use
    // ARGB_8888 (default, golden behavior); FINAL callers pass RGBA_F16 so the
    // trilinear float output lands in a half-float store instead of rounding
    // to 8-bit between stages. Pixel math is identical either way.
    fun applyLut(
        src: Bitmap,
        lut: LutCube,
        intensity: Float,
        outConfig: Bitmap.Config = Bitmap.Config.ARGB_8888
    ): Bitmap {
        val t = intensity.coerceIn(0f, 1f)
        if (t <= 0f) return src
        if (src.width <= 0 || src.height <= 0) return src
        val table = effectiveTable(lut)
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        val size = table.size
        val data = table.data
        val invRange = FloatArray(3) { c ->
            val span = table.domainMax[c] - table.domainMin[c]
            if (span > 1e-6f) 1f / span else 1f
        }
        val full = t >= 1f
        for (i in pixels.indices) {
            val p = pixels[i]
            val a = p ushr 24
            val r0 = ((p shr 16) and 0xFF) / 255f
            val g0 = ((p shr 8) and 0xFF) / 255f
            val b0 = (p and 0xFF) / 255f
            val r = ((r0 - table.domainMin[0]) * invRange[0]).coerceIn(0f, 1f)
            val g = ((g0 - table.domainMin[1]) * invRange[1]).coerceIn(0f, 1f)
            val b = ((b0 - table.domainMin[2]) * invRange[2]).coerceIn(0f, 1f)
            val out = if (table.is3D) sample3D(data, size, r, g, b) else sample1D(data, size, r, g, b)
            val fr = if (full) out[0] else r0 + (out[0] - r0) * t
            val fg = if (full) out[1] else g0 + (out[1] - g0) * t
            val fb = if (full) out[2] else b0 + (out[2] - b0) * t
            pixels[i] = (a shl 24) or
                ((fr * 255f + 0.5f).toInt().coerceIn(0, 255) shl 16) or
                ((fg * 255f + 0.5f).toInt().coerceIn(0, 255) shl 8) or
                (fb * 255f + 0.5f).toInt().coerceIn(0, 255)
        }
        val out = try {
            Bitmap.createBitmap(w, h, outConfig)
        } catch (_: Exception) {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        }
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }

    private val scratch = ThreadLocal.withInitial { FloatArray(3) }

    private fun sample3D(data: FloatArray, size: Int, r: Float, g: Float, b: Float): FloatArray {
        val out = scratch.get()
        val max = (size - 1).toFloat()
        val x = (r * max).coerceIn(0f, max)
        val y = (g * max).coerceIn(0f, max)
        val z = (b * max).coerceIn(0f, max)
        val x0 = floor(x).toInt().coerceIn(0, size - 1)
        val y0 = floor(y).toInt().coerceIn(0, size - 1)
        val z0 = floor(z).toInt().coerceIn(0, size - 1)
        val x1 = (x0 + 1).coerceAtMost(size - 1)
        val y1 = (y0 + 1).coerceAtMost(size - 1)
        val z1 = (z0 + 1).coerceAtMost(size - 1)
        val fx = x - x0
        val fy = y - y0
        val fz = z - z0
        for (c in 0..2) {
            val c000 = data[(cellIndex(x0, y0, z0, size)) * 3 + c]
            val c100 = data[(cellIndex(x1, y0, z0, size)) * 3 + c]
            val c010 = data[(cellIndex(x0, y1, z0, size)) * 3 + c]
            val c110 = data[(cellIndex(x1, y1, z0, size)) * 3 + c]
            val c001 = data[(cellIndex(x0, y0, z1, size)) * 3 + c]
            val c101 = data[(cellIndex(x1, y0, z1, size)) * 3 + c]
            val c011 = data[(cellIndex(x0, y1, z1, size)) * 3 + c]
            val c111 = data[(cellIndex(x1, y1, z1, size)) * 3 + c]
            val c00 = c000 + (c100 - c000) * fx
            val c10 = c010 + (c110 - c010) * fx
            val c01 = c001 + (c101 - c001) * fx
            val c11 = c011 + (c111 - c011) * fx
            val c0 = c00 + (c10 - c00) * fy
            val c1 = c01 + (c11 - c01) * fy
            out[c] = (c0 + (c1 - c0) * fz).coerceIn(0f, 1f)
        }
        return out
    }

    private fun sample1D(data: FloatArray, size: Int, r: Float, g: Float, b: Float): FloatArray {
        val out = scratch.get()
        val inputs = floatArrayOf(r, g, b)
        for (c in 0..2) {
            val pos = (inputs[c] * (size - 1)).coerceIn(0f, (size - 1).toFloat())
            val i0 = floor(pos).toInt().coerceIn(0, size - 1)
            val i1 = (i0 + 1).coerceAtMost(size - 1)
            val f = pos - i0
            val v0 = data[i0 * 3 + c]
            val v1 = data[i1 * 3 + c]
            out[c] = (v0 + (v1 - v0) * f).coerceIn(0f, 1f)
        }
        return out
    }

    private fun cellIndex(r: Int, g: Int, b: Int, size: Int): Int = (b * size + g) * size + r

    fun effectiveTable(lut: LutCube): LutCube {
        if (lut.size <= FAST_PATH_MAX_SIZE) return lut
        if (lut === cachedEffectiveSource) {
            cachedEffectiveResult?.let { return it }
        }
        val target = DOWNSAMPLED_SIZE
        val out = FloatArray(target * target * target * 3)
        for (b in 0 until target) {
            for (g in 0 until target) {
                for (r in 0 until target) {
                    val sample = if (lut.is3D) {
                        sample3D(
                            lut.data, lut.size,
                            r / (target - 1f), g / (target - 1f), b / (target - 1f)
                        )
                    } else {
                        sample1D(
                            lut.data, lut.size,
                            r / (target - 1f), g / (target - 1f), b / (target - 1f)
                        )
                    }
                    val base = ((b * target + g) * target + r) * 3
                    out[base] = sample[0]
                    out[base + 1] = sample[1]
                    out[base + 2] = sample[2]
                }
            }
        }
        val result = LutCube(
            title = lut.title,
            size = target,
            is3D = true,
            domainMin = lut.domainMin.copyOf(),
            domainMax = lut.domainMax.copyOf(),
            data = out
        )
        cachedEffectiveSource = lut
        cachedEffectiveResult = result
        return result
    }
}
