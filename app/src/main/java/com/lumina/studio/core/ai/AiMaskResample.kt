package com.lumina.studio.core.ai

/**
 * M12 alpha-field resampling (pure JVM). Bilinear upscale/downscale of a
 * cached analysis-size field to the current render size. Identity sizes
 * return a copy. Invalid inputs return an empty array (never null, never
 * throw) so render fallbacks stay branch-simple.
 */
object AiMaskResample {
    fun resample(src: FloatArray, srcW: Int, srcH: Int, dstW: Int, dstH: Int): FloatArray {
        if (srcW <= 0 || srcH <= 0 || dstW <= 0 || dstH <= 0) return FloatArray(0)
        if (src.size != srcW * srcH) return FloatArray(dstW * dstH)
        if (srcW == dstW && srcH == dstH) return src.copyOf()
        val out = FloatArray(dstW * dstH)
        for (y in 0 until dstH) {
            val sy = if (dstH == 1) 0f else y.toFloat() / (dstH - 1).toFloat() * (srcH - 1)
            val y0 = sy.toInt().coerceIn(0, srcH - 1)
            val y1 = (y0 + 1).coerceIn(0, srcH - 1)
            val fy = (sy - y0).coerceIn(0f, 1f)
            for (x in 0 until dstW) {
                val sx = if (dstW == 1) 0f else x.toFloat() / (dstW - 1).toFloat() * (srcW - 1)
                val x0 = sx.toInt().coerceIn(0, srcW - 1)
                val x1 = (x0 + 1).coerceIn(0, srcW - 1)
                val fx = (sx - x0).coerceIn(0f, 1f)
                val top = src[y0 * srcW + x0] * (1f - fx) + src[y0 * srcW + x1] * fx
                val bottom = src[y1 * srcW + x0] * (1f - fx) + src[y1 * srcW + x1] * fx
                out[y * dstW + x] = (top * (1f - fy) + bottom * fy).coerceIn(0f, 1f)
            }
        }
        return out
    }
}
