package com.lumina.studio.core.render

import kotlin.math.pow

/**
 * M5 color-management math (§14, §86). Pure JVM (no android.*) so unit tests
 * pin the tolerances directly.
 *
 * Pipeline assumption (documented, not detected): every decoded [Bitmap]
 * entering the pipeline is treated as sRGB-encoded. BitmapFactory does not
 * apply embedded ICC profiles, so wide-gamut input profiles (Display P3 /
 * Adobe RGB JPEGs, HDR gain-map images) are NOT honored — they render as if
 * sRGB. Claiming otherwise is explicitly out of scope; see M16 follow-up.
 *
 * Working space: the grade math stays sRGB-math (gamma-encoded) on both
 * qualities for speed and golden stability. Linear-light handling exists only
 * where color science demands it: the sRGB<->Display P3 conversion below
 * linearizes first (piecewise sRGB EOTF), converts via D65 XYZ, then
 * re-encodes. sRGB and Display P3 share the identical transfer function, so
 * [linearToSrgb] is the correct re-encode for both — no separate P3 EOTF.
 *
 * Numerical approach: the D65 matrices are the IEC 61966-2-1 / SMPTE RP 431
 * reference values in float. The per-pixel CPU path ([CpuColorManager]) uses
 * [srgb8ToLinearFast] (a 256-entry table baked from [srgbToLinear], hence
 * bit-identical for 8-bit inputs) and the same [linearToSrgb] formula for
 * encode, so the table path matches [android.graphics.Color.convert] within
 * ±1 LSB per channel (float Δ ≤ [P3_MATRIX_TOLERANCE]). Per-pixel
 * Color.convert was rejected as ~100x slower (JNI per pixel).
 */
object ColorMatrices {
    val SRGB_TO_XYZ_D65 = floatArrayOf(
        0.4124564f, 0.3575761f, 0.1804375f,
        0.2126729f, 0.7151522f, 0.0721750f,
        0.0193339f, 0.1191920f, 0.9503041f
    )
    val XYZ_TO_SRGB_D65 = floatArrayOf(
        3.2404542f, -1.5371385f, -0.4985314f,
        -0.9692660f, 1.8760108f, 0.0415560f,
        0.0556434f, -0.2040259f, 1.0572252f
    )
    val P3_TO_XYZ_D65 = floatArrayOf(
        0.4865709f, 0.2656677f, 0.1982173f,
        0.2289746f, 0.6917385f, 0.0792869f,
        0f, 0.0451134f, 1.0439444f
    )
    val XYZ_TO_P3_D65 = floatArrayOf(
        2.4934969f, -0.9313836f, -0.4027108f,
        -0.8294890f, 1.7626641f, 0.0236247f,
        0.0358458f, -0.0761724f, 0.9568845f
    )

    fun mulVec(m: FloatArray, rgb: FloatArray): FloatArray {
        require(m.size == 9 && rgb.size == 3) { "mulVec needs a 3x3 matrix and an rgb triple" }
        val r = rgb[0]
        val g = rgb[1]
        val b = rgb[2]
        return floatArrayOf(
            m[0] * r + m[1] * g + m[2] * b,
            m[3] * r + m[4] * g + m[5] * b,
            m[6] * r + m[7] * g + m[8] * b
        )
    }

    fun srgbToLinear(c: Float): Float =
        if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)

    fun linearToSrgb(c: Float): Float =
        if (c <= 0.0031308f) c * 12.92f else 1.055f * c.pow(1f / 2.4f) - 0.055f

    fun srgbToDisplayP3(rgb: FloatArray): FloatArray {
        require(rgb.size == 3) { "srgbToDisplayP3 needs an rgb triple" }
        val linear = floatArrayOf(
            srgbToLinear(rgb[0]),
            srgbToLinear(rgb[1]),
            srgbToLinear(rgb[2])
        )
        val xyz = mulVec(SRGB_TO_XYZ_D65, linear)
        val p3Linear = mulVec(XYZ_TO_P3_D65, xyz)
        return floatArrayOf(
            linearToSrgb(p3Linear[0]).coerceIn(0f, 1f),
            linearToSrgb(p3Linear[1]).coerceIn(0f, 1f),
            linearToSrgb(p3Linear[2]).coerceIn(0f, 1f)
        )
    }

    fun displayP3ToSrgb(rgb: FloatArray): FloatArray {
        require(rgb.size == 3) { "displayP3ToSrgb needs an rgb triple" }
        val linear = floatArrayOf(
            srgbToLinear(rgb[0]),
            srgbToLinear(rgb[1]),
            srgbToLinear(rgb[2])
        )
        val xyz = mulVec(P3_TO_XYZ_D65, linear)
        val srgbLinear = mulVec(XYZ_TO_SRGB_D65, xyz)
        return floatArrayOf(
            linearToSrgb(srgbLinear[0]).coerceIn(0f, 1f),
            linearToSrgb(srgbLinear[1]).coerceIn(0f, 1f),
            linearToSrgb(srgbLinear[2]).coerceIn(0f, 1f)
        )
    }

    // ---------- M5 tolerances (pinned by ColorPipelineTest) ----------

    // sRGB<->P3 round-trip on in-gamut colors (all sRGB colors are inside the
    // P3 gamut, so no clipping): float per-channel Δ vs the input.
    const val P3_ROUNDTRIP_EPS = 0.005f
    // Matrix path vs platform Color.convert: ±1 LSB per channel.
    const val P3_MATRIX_TOLERANCE = 0.004f
    // sRGB EOTF invertibility: linearToSrgb(srgbToLinear(c)) vs c.
    const val TRANSFER_INVERT_EPS = 0.001f

    // ---------- M5 cached tables (speed path for per-pixel loops) ----------

    // 256-entry sRGB 8-bit -> linear table baked from srgbToLinear at table
    // build time, so lookups are bit-identical to calling the formula with
    // (byte/255f). Per-pixel loops over ARGB_8888 pixels always have 8-bit
    // quantized inputs, making this exact — not an approximation.
    private val SRGB8_TO_LINEAR: FloatArray =
        FloatArray(256) { i -> srgbToLinear(i / 255f) }

    fun srgb8ToLinearFast(byte: Int): Float =
        SRGB8_TO_LINEAR[byte.coerceIn(0, 255)]

    // Linear -> sRGB 8-bit via the same linearToSrgb formula + round-half-up.
    // Matches the float path within 1 LSB (quantization only).
    fun linearToSrgb8Fast(linear: Float): Int =
        (linearToSrgb(linear.coerceIn(0f, 1f)) * 255f + 0.5f).toInt().coerceIn(0, 255)

    // Combined sRGB 8-bit triple -> Display P3 8-bit triple using the cached
    // linearize table. Same matrices as srgbToDisplayP3; identical output to
    // the float path within 1 LSB per channel (table is exact, encode rounds).
    fun srgb8ToP38(r: Int, g: Int, b: Int): IntArray {
        val xyz = mulVec(
            SRGB_TO_XYZ_D65,
            floatArrayOf(
                srgb8ToLinearFast(r),
                srgb8ToLinearFast(g),
                srgb8ToLinearFast(b)
            )
        )
        val p3Linear = mulVec(XYZ_TO_P3_D65, xyz)
        return intArrayOf(
            linearToSrgb8Fast(p3Linear[0]),
            linearToSrgb8Fast(p3Linear[1]),
            linearToSrgb8Fast(p3Linear[2])
        )
    }

    fun p38ToSrgb8(r: Int, g: Int, b: Int): IntArray {
        val xyz = mulVec(
            P3_TO_XYZ_D65,
            floatArrayOf(
                srgb8ToLinearFast(r),
                srgb8ToLinearFast(g),
                srgb8ToLinearFast(b)
            )
        )
        val srgbLinear = mulVec(XYZ_TO_SRGB_D65, xyz)
        return intArrayOf(
            linearToSrgb8Fast(srgbLinear[0]),
            linearToSrgb8Fast(srgbLinear[1]),
            linearToSrgb8Fast(srgbLinear[2])
        )
    }
}

interface ColorManager<B : Any> {
    fun toWorking(rgb: FloatArray): FloatArray
    fun fromWorking(working: FloatArray): FloatArray
    fun convert(image: B, src: RenderColorSpace, dst: RenderColorSpace): B
}
