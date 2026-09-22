package com.lumina.studio.core.raw

import com.lumina.studio.core.render.RawRecipe
import kotlin.math.pow

sealed interface DevelopResult {
    data class Ok(val argb: IntArray, val width: Int, val height: Int) : DevelopResult
    data class Err(val reason: String) : DevelopResult
}

object DngDevelop {
    const val MAX_PIXELS = 120_000_000L

    val XYZ_TO_LINEAR_SRGB_D65 = floatArrayOf(
        3.2404542f, -1.5371385f, -0.4985314f,
        -0.9692660f, 1.8760108f, 0.0415560f,
        0.0556434f, -0.2040259f, 1.0572252f
    )

    val XYZ_D50_TO_D65_BRADFORD = floatArrayOf(
        1.0478112f, 0.0228866f, -0.0501270f,
        0.0295424f, 0.9904844f, -0.0170491f,
        -0.0092345f, 0.0150436f, 0.7521316f
    )

    fun isDevelopable(info: DngInfo): Developability = DngCapabilities.developability(info)

    fun developToArgb(info: DngInfo, stripData: ByteArray, recipe: RawRecipe? = null): DevelopResult {
        try {
            val check = isDevelopable(info)
            if (!check.developable) return DevelopResult.Err(check.reason)
            val pixels = info.width.toLong() * info.height.toLong()
            if (pixels <= 0L || pixels > MAX_PIXELS) {
                return DevelopResult.Err("Refusing develop: bad dimensions")
            }
            val mosaic = unpackMosaic(info, stripData) ?: return DevelopResult.Err(unpackError)
            val r = recipe ?: RawRecipe()
            val normalized = normalizeMosaic(mosaic, info, r.exposureEv)
            val rgbLinear = demosaicBilinear(normalized, info)
            val gains = wbGains(info, r)
            applyWbInPlace(rgbLinear, gains)
            applyColorMatrixInPlace(rgbLinear, info)
            val argb = encodeSrgb(rgbLinear, info.width, info.height)
            val oriented = applyOrientation(argb, info.width, info.height, info.orientation)
            return DevelopResult.Ok(oriented.first, oriented.second, oriented.third)
        } catch (_: OutOfMemoryError) {
            return DevelopResult.Err("Out of memory during develop")
        } catch (e: Exception) {
            return DevelopResult.Err("Develop failed: ${e.message}")
        }
    }

    private var unpackError: String = "Strip layout mismatch"

    fun unpackMosaic(info: DngInfo, stripData: ByteArray): IntArray? {
        fun fail(reason: String): IntArray? {
            unpackError = reason
            return null
        }
        val w = info.width
        val h = info.height
        val n = w.toLong() * h.toLong()
        if (n <= 0L || n > MAX_PIXELS) {
            return fail("Bad mosaic size")
        }
        val count = n.toInt()
        var expectedUnpacked = -1L
        var expectedPacked12 = -1L
        when (info.bitsPerSample) {
            8 -> expectedUnpacked = n
            16 -> expectedUnpacked = n * 2L
            12 -> {
                expectedUnpacked = n * 2L
                expectedPacked12 = ((w.toLong() * 12L + 7L) / 8L) * h.toLong()
            }
            14 -> expectedUnpacked = n * 2L
            else -> return fail("Unsupported BitsPerSample ${info.bitsPerSample}")
        }
        val total = stripData.size.toLong()
        val out = try {
            IntArray(count)
        } catch (_: OutOfMemoryError) {
            return fail("Mosaic too large")
        }
        if (info.bitsPerSample == 8) {
            if (total != expectedUnpacked) return fail("8-bit strip size mismatch")
            for (i in 0 until count) out[i] = stripData[i].toInt() and 0xFF
            return out
        }
        if (info.bitsPerSample == 16 || info.bitsPerSample == 14) {
            if (total != expectedUnpacked) return fail("${info.bitsPerSample}-bit strip size mismatch")
            if (info.isLittleEndian) {
                for (i in 0 until count) {
                    val lo = stripData[i * 2].toInt() and 0xFF
                    val hi = stripData[i * 2 + 1].toInt() and 0xFF
                    out[i] = lo or (hi shl 8)
                }
            } else {
                for (i in 0 until count) {
                    val hi = stripData[i * 2].toInt() and 0xFF
                    val lo = stripData[i * 2 + 1].toInt() and 0xFF
                    out[i] = (hi shl 8) or lo
                }
            }
            return out
        }
        if (info.bitsPerSample == 12) {
            if (total == expectedUnpacked) {
                if (info.isLittleEndian) {
                    for (i in 0 until count) {
                        val lo = stripData[i * 2].toInt() and 0xFF
                        val hi = stripData[i * 2 + 1].toInt() and 0xFF
                        out[i] = (lo or (hi shl 8)) and 0xFFF
                    }
                } else {
                    for (i in 0 until count) {
                        val hi = stripData[i * 2].toInt() and 0xFF
                        val lo = stripData[i * 2 + 1].toInt() and 0xFF
                        out[i] = ((hi shl 8) or lo) and 0xFFF
                    }
                }
                return out
            }
            if (total == expectedPacked12) {
                var dst = 0
                var src = 0
                val rowBytes = ((w.toLong() * 12L + 7L) / 8L).toInt()
                for (y in 0 until h) {
                    var x = 0
                    while (x + 1 < w) {
                        if (src + 2 >= stripData.size) return fail("Packed 12-bit truncated")
                        val b0 = stripData[src].toInt() and 0xFF
                        val b1 = stripData[src + 1].toInt() and 0xFF
                        val b2 = stripData[src + 2].toInt() and 0xFF
                        out[dst++] = (b0 or ((b1 and 0x0F) shl 8)) and 0xFFF
                        out[dst++] = (((b1 ushr 4) and 0x0F) or (b2 shl 4)) and 0xFFF
                        src += 3
                        x += 2
                    }
                    if (x < w) {
                        if (src + 1 >= stripData.size) return fail("Packed 12-bit truncated")
                        val b0 = stripData[src].toInt() and 0xFF
                        val b1 = stripData[src + 1].toInt() and 0xFF
                        out[dst++] = (b0 or ((b1 and 0x0F) shl 8)) and 0xFFF
                        src += rowBytes - ((w / 2) * 3)
                        if (w % 2 == 0) src -= 0
                    } else {
                        val consumed = (w / 2) * 3 + (w % 2) * 2
                        val pad = rowBytes - consumed
                        src += pad
                    }
                }
                return out
            }
            return fail("12-bit strip size mismatch")
        }
        return fail("Unsupported packing")
    }

    fun blackForPixel(info: DngInfo, x: Int, y: Int): Double {
        val rr = info.blackRepeatRows.coerceAtLeast(1)
        val cc = info.blackRepeatCols.coerceAtLeast(1)
        val idx = (y % rr) * cc + (x % cc)
        if (idx < 0 || idx >= info.blackLevels.size) return 0.0
        return info.blackLevels[idx]
    }

    fun normalizeSample(raw: Int, black: Double, white: Long, exposureEv: Float): Float {
        val range = (white - black).coerceAtLeast(1.0)
        var v = ((raw - black) / range).toFloat()
        if (v < 0f) v = 0f
        if (v > 4f) v = 4f
        if (!v.isFinite()) v = 0f
        val ev = exposureEv.coerceIn(-5f, 5f)
        if (ev != 0f) {
            v = (v * 2f.pow(ev)).coerceIn(0f, 4f)
        }
        return softClipHighlight(v)
    }

    fun softClipHighlight(v: Float): Float {
        if (!v.isFinite()) return 0f
        if (v <= 1f) return v.coerceIn(0f, 1f)
        val over = v - 1f
        return (1f + over / (1f + over * 3f)).coerceIn(0f, 1.6f)
    }

    fun normalizeMosaic(mosaic: IntArray, info: DngInfo, exposureEv: Float): FloatArray {
        val w = info.width
        val h = info.height
        val out = FloatArray(mosaic.size)
        var i = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                out[i] = normalizeSample(mosaic[i], blackForPixel(info, x, y), info.whiteLevel, exposureEv)
                i++
            }
        }
        return out
    }

    fun cfaColorAt(info: DngInfo, x: Int, y: Int): Int {
        val r = y % info.cfaDimRows.coerceAtLeast(1)
        val c = x % info.cfaDimCols.coerceAtLeast(1)
        val idx = r * info.cfaDimCols + c
        if (idx < 0 || idx >= info.cfaPattern.size) return 1
        val v = info.cfaPattern[idx]
        val plane = info.cfaPlaneColor
        if (plane != null && v >= 0 && v < plane.size) {
            return plane[v]
        }
        return v
    }

    fun demosaicBilinear(normalized: FloatArray, info: DngInfo): FloatArray {
        val w = info.width
        val h = info.height
        val out = FloatArray(w * h * 3)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val center = normalized[y * w + x]
                val color = cfaColorAt(info, x, y)
                var r: Float
                var g: Float
                var b: Float
                when (color) {
                    0 -> {
                        r = center
                        g = avgCross(normalized, w, h, x, y, 1)
                        b = avgDiag(normalized, w, h, x, y)
                    }
                    2 -> {
                        b = center
                        g = avgCross(normalized, w, h, x, y, 1)
                        r = avgDiag(normalized, w, h, x, y)
                    }
                    else -> {
                        g = center
                        val rAvg = avgNeighborsOfColor(normalized, info, w, h, x, y, 0)
                        val bAvg = avgNeighborsOfColor(normalized, info, w, h, x, y, 2)
                        r = rAvg
                        b = bAvg
                    }
                }
                val o = (y * w + x) * 3
                out[o] = r
                out[o + 1] = g
                out[o + 2] = b
            }
        }
        return out
    }

    private fun sampleClamped(a: FloatArray, w: Int, h: Int, x: Int, y: Int): Float {
        val cx = x.coerceIn(0, w - 1)
        val cy = y.coerceIn(0, h - 1)
        return a[cy * w + cx]
    }

    private fun avgCross(a: FloatArray, w: Int, h: Int, x: Int, y: Int, want: Int): Float {
        var sum = 0f
        var n = 0
        val pts = arrayOf(x - 1 to y, x + 1 to y, x to y - 1, x to y + 1)
        for ((px, py) in pts) {
            if (px < 0 || py < 0 || px >= w || py >= h) continue
            sum += sampleClamped(a, w, h, px, py)
            n++
        }
        return if (n == 0) sampleClamped(a, w, h, x, y) else sum / n
    }

    private fun avgDiag(a: FloatArray, w: Int, h: Int, x: Int, y: Int): Float {
        var sum = 0f
        var n = 0
        val pts = arrayOf(x - 1 to y - 1, x + 1 to y - 1, x - 1 to y + 1, x + 1 to y + 1)
        for ((px, py) in pts) {
            if (px < 0 || py < 0 || px >= w || py >= h) continue
            sum += sampleClamped(a, w, h, px, py)
            n++
        }
        return if (n == 0) sampleClamped(a, w, h, x, y) else sum / n
    }

    private fun avgNeighborsOfColor(
        a: FloatArray, info: DngInfo, w: Int, h: Int, x: Int, y: Int, wantColor: Int
    ): Float {
        var sum = 0f
        var n = 0
        for (dy in -1..1) {
            for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                val px = x + dx
                val py = y + dy
                if (px < 0 || py < 0 || px >= w || py >= h) continue
                if (cfaColorAt(info, px, py) == wantColor) {
                    sum += sampleClamped(a, w, h, px, py)
                    n++
                }
            }
        }
        return if (n == 0) sampleClamped(a, w, h, x, y) else sum / n
    }

    fun wbGains(info: DngInfo, recipe: RawRecipe): FloatArray {
        val base = baseNeutralGains(info)
        val temp = recipe.tempGain.coerceIn(0.2f, 5f)
        val tint = recipe.tintGain.coerceIn(0.2f, 5f)
        return floatArrayOf(base[0] * temp, base[1], base[2] * tint)
    }

    fun baseNeutralGains(info: DngInfo): FloatArray {
        val asn = info.asShotNeutral
        if (asn != null && asn.size == 3 && asn.all { it.isFinite() && it > 1e-6 }) {
            val r = asn[0]
            val g = asn[1]
            val b = asn[2]
            val gr = (g / r).toFloat().coerceIn(0.2f, 5f)
            val gg = 1f
            val gb = (g / b).toFloat().coerceIn(0.2f, 5f)
            if (gr.isFinite() && gb.isFinite()) return floatArrayOf(gr, gg, gb)
        }
        val xy = info.asShotWhiteXY
        if (xy != null && xy.size == 2) {
            val g = xyToNeutralGains(xy[0], xy[1])
            if (g != null) return g
        }
        val ab = info.analogBalance
        if (ab != null && ab.size == 3 && ab.all { it.isFinite() && it > 1e-6 }) {
            val r = (1.0 / ab[0]).toFloat().coerceIn(0.2f, 5f)
            val g = (1.0 / ab[1]).toFloat().coerceIn(0.2f, 5f)
            val b = (1.0 / ab[2]).toFloat().coerceIn(0.2f, 5f)
            val gg = g.coerceAtLeast(1e-3f)
            return floatArrayOf((r / gg).coerceIn(0.2f, 5f), 1f, (b / gg).coerceIn(0.2f, 5f))
        }
        return floatArrayOf(1f, 1f, 1f)
    }

    fun wbSource(info: DngInfo): String {
        val asn = info.asShotNeutral
        if (asn != null && asn.size == 3 && asn.all { it.isFinite() && it > 1e-6 }) return "AsShotNeutral"
        val xy = info.asShotWhiteXY
        if (xy != null && xy.size == 2 && xy.all { it.isFinite() }) return "AsShotWhiteXY"
        val ab = info.analogBalance
        if (ab != null && ab.size == 3 && ab.all { it.isFinite() && it > 1e-6 }) return "AnalogBalance"
        return "DaylightEstimate"
    }

    private fun xyToNeutralGains(x: Double, y: Double): FloatArray? {
        try {
            if (!x.isFinite() || !y.isFinite() || y <= 1e-6 || x <= 0.0) return null
            val X = x / y
            val Z = (1.0 - x - y) / y
            val xyz = floatArrayOf(X.toFloat(), 1f, Z.toFloat())
            val lin = FloatArray(3)
            for (r in 0 until 3) {
                lin[r] = XYZ_TO_LINEAR_SRGB_D65[r * 3] * xyz[0] +
                    XYZ_TO_LINEAR_SRGB_D65[r * 3 + 1] * xyz[1] +
                    XYZ_TO_LINEAR_SRGB_D65[r * 3 + 2] * xyz[2]
            }
            if (lin.any { !it.isFinite() || it <= 1e-6f }) return null
            val g = lin[1]
            return floatArrayOf(
                (g / lin[0]).coerceIn(0.2f, 5f),
                1f,
                (g / lin[2]).coerceIn(0.2f, 5f)
            )
        } catch (_: Exception) {
            return null
        }
    }

    fun applyWbInPlace(rgb: FloatArray, gains: FloatArray) {
        var i = 0
        while (i < rgb.size) {
            rgb[i] = (rgb[i] * gains[0]).coerceIn(0f, 4f)
            rgb[i + 1] = (rgb[i + 1] * gains[1]).coerceIn(0f, 4f)
            rgb[i + 2] = (rgb[i + 2] * gains[2]).coerceIn(0f, 4f)
            i += 3
        }
    }

    fun applyColorMatrixInPlace(rgb: FloatArray, info: DngInfo) {
        val cm = info.colorMatrix1 ?: return
        if (cm.size != 9 || cm.any { !it.isFinite() }) return
        var i = 0
        while (i < rgb.size) {
            val r = rgb[i]
            val g = rgb[i + 1]
            val b = rgb[i + 2]
            val Xr = cm[0] * r + cm[1] * g + cm[2] * b
            val Xg = cm[3] * r + cm[4] * g + cm[5] * b
            val Xb = cm[6] * r + cm[7] * g + cm[8] * b
            val aX = XYZ_D50_TO_D65_BRADFORD[0] * Xr + XYZ_D50_TO_D65_BRADFORD[1] * Xg +
                XYZ_D50_TO_D65_BRADFORD[2] * Xb
            val aY = XYZ_D50_TO_D65_BRADFORD[3] * Xr + XYZ_D50_TO_D65_BRADFORD[4] * Xg +
                XYZ_D50_TO_D65_BRADFORD[5] * Xb
            val aZ = XYZ_D50_TO_D65_BRADFORD[6] * Xr + XYZ_D50_TO_D65_BRADFORD[7] * Xg +
                XYZ_D50_TO_D65_BRADFORD[8] * Xb
            val lr = XYZ_TO_LINEAR_SRGB_D65[0] * aX + XYZ_TO_LINEAR_SRGB_D65[1] * aY +
                XYZ_TO_LINEAR_SRGB_D65[2] * aZ
            val lg = XYZ_TO_LINEAR_SRGB_D65[3] * aX + XYZ_TO_LINEAR_SRGB_D65[4] * aY +
                XYZ_TO_LINEAR_SRGB_D65[5] * aZ
            val lb = XYZ_TO_LINEAR_SRGB_D65[6] * aX + XYZ_TO_LINEAR_SRGB_D65[7] * aY +
                XYZ_TO_LINEAR_SRGB_D65[8] * aZ
            rgb[i] = lr.coerceIn(0f, 4f)
            rgb[i + 1] = lg.coerceIn(0f, 4f)
            rgb[i + 2] = lb.coerceIn(0f, 4f)
            i += 3
        }
    }

    fun linearToSrgb(v: Float): Float {
        val c = v.coerceIn(0f, 4f)
        return if (c <= 0.0031308f) c * 12.92f
        else 1.055f * c.toDouble().pow(1.0 / 2.4).toFloat() - 0.055f
    }

    fun encodeSrgb(rgbLinear: FloatArray, w: Int, h: Int): IntArray {
        val out = IntArray(w * h)
        var s = 0
        var d = 0
        while (d < out.size && s + 2 < rgbLinear.size) {
            val r = (linearToSrgb(rgbLinear[s]) * 255f + 0.5f).toInt().coerceIn(0, 255)
            val g = (linearToSrgb(rgbLinear[s + 1]) * 255f + 0.5f).toInt().coerceIn(0, 255)
            val b = (linearToSrgb(rgbLinear[s + 2]) * 255f + 0.5f).toInt().coerceIn(0, 255)
            out[d] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            s += 3
            d++
        }
        return out
    }

    fun srgbToLinear8(v: Int): Float {
        val s = (v.coerceIn(0, 255)) / 255f
        return if (s <= 0.04045f) s / 12.92f
        else ((s + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()
    }

    fun wbGainsFromPickerPixel(argb: Int): FloatArray {
        val r8 = (argb shr 16) and 0xFF
        val g8 = (argb shr 8) and 0xFF
        val b8 = argb and 0xFF
        val r = srgbToLinear8(r8).coerceAtLeast(1e-4f)
        val g = srgbToLinear8(g8).coerceAtLeast(1e-4f)
        val b = srgbToLinear8(b8).coerceAtLeast(1e-4f)
        val temp = (g / r).coerceIn(0.2f, 5f)
        val tint = (g / b).coerceIn(0.2f, 5f)
        return floatArrayOf(temp, tint)
    }

    fun applyOrientation(argb: IntArray, w: Int, h: Int, orientation: Int): Triple<IntArray, Int, Int> {
        val o = if (orientation in 1..8) orientation else 1
        if (o == 1) return Triple(argb, w, h)
        if (w <= 0 || h <= 0 || argb.size != w * h) return Triple(argb, w, h)
        return try {
            when (o) {
                2 -> {
                    val out = IntArray(argb.size)
                    for (y in 0 until h) for (x in 0 until w) {
                        out[y * w + x] = argb[y * w + (w - 1 - x)]
                    }
                    Triple(out, w, h)
                }
                3 -> {
                    val out = IntArray(argb.size)
                    for (y in 0 until h) for (x in 0 until w) {
                        out[y * w + x] = argb[(h - 1 - y) * w + (w - 1 - x)]
                    }
                    Triple(out, w, h)
                }
                4 -> {
                    val out = IntArray(argb.size)
                    for (y in 0 until h) for (x in 0 until w) {
                        out[y * w + x] = argb[(h - 1 - y) * w + x]
                    }
                    Triple(out, w, h)
                }
                5 -> {
                    val out = IntArray(argb.size)
                    val nw = h
                    val nh = w
                    for (y in 0 until h) for (x in 0 until w) {
                        out[x * nw + y] = argb[y * w + x]
                    }
                    Triple(out, nw, nh)
                }
                6 -> {
                    val out = IntArray(argb.size)
                    val nw = h
                    val nh = w
                    for (dy in 0 until nh) for (dx in 0 until nw) {
                        out[dy * nw + dx] = argb[(h - 1 - dx) * w + dy]
                    }
                    Triple(out, nw, nh)
                }
                7 -> {
                    val out = IntArray(argb.size)
                    val nw = h
                    val nh = w
                    for (dy in 0 until nh) for (dx in 0 until nw) {
                        out[dy * nw + dx] = argb[(h - 1 - dx) * w + (w - 1 - dy)]
                    }
                    Triple(out, nw, nh)
                }
                else -> {
                    val out = IntArray(argb.size)
                    val nw = h
                    val nh = w
                    for (dy in 0 until nh) for (dx in 0 until nw) {
                        out[dy * nw + dx] = argb[dx * w + (w - 1 - dy)]
                    }
                    Triple(out, nw, nh)
                }
            }
        } catch (_: Exception) {
            Triple(argb, w, h)
        }
    }

    fun downscaleArgb(argb: IntArray, w: Int, h: Int, maxDim: Int): Triple<IntArray, Int, Int> {
        if (maxDim <= 0) return Triple(argb, w, h)
        val longest = maxOf(w, h)
        if (longest <= maxDim || w <= 0 || h <= 0) return Triple(argb, w, h)
        val scale = maxDim.toFloat() / longest.toFloat()
        val nw = (w * scale + 0.5f).toInt().coerceAtLeast(1)
        val nh = (h * scale + 0.5f).toInt().coerceAtLeast(1)
        if (nw >= w && nh >= h) return Triple(argb, w, h)
        return try {
            val out = IntArray(nw * nh)
            for (dy in 0 until nh) {
                val sy = ((dy + 0.5f) / scale - 0.5f).toInt().coerceIn(0, h - 1)
                for (dx in 0 until nw) {
                    val sx = ((dx + 0.5f) / scale - 0.5f).toInt().coerceIn(0, w - 1)
                    out[dy * nw + dx] = argb[sy * w + sx]
                }
            }
            Triple(out, nw, nh)
        } catch (_: Exception) {
            Triple(argb, w, h)
        }
    }
}
