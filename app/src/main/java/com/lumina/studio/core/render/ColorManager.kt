package com.lumina.studio.core.render

import kotlin.math.pow

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
}

interface ColorManager<B : Any> {
    fun toWorking(rgb: FloatArray): FloatArray
    fun fromWorking(working: FloatArray): FloatArray
    fun convert(image: B, src: RenderColorSpace, dst: RenderColorSpace): B
}
