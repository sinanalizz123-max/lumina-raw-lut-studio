package com.lumina.studio.core.util

data class OrientationTransform(
    val rotationDegrees: Int,
    val flipHorizontal: Boolean,
    val flipVertical: Boolean,
    val swapsDimensions: Boolean
)

object ExifOrientation {

    fun normalize(orientation: Int): Int =
        if (orientation in 1..8) orientation else 1

    fun swapsDimensions(orientation: Int): Boolean =
        normalize(orientation) in 5..8

    fun describe(orientation: Int): OrientationTransform {
        return when (normalize(orientation)) {
            2 -> OrientationTransform(0, true, false, false)
            3 -> OrientationTransform(180, false, false, false)
            4 -> OrientationTransform(0, false, true, false)
            5 -> OrientationTransform(270, true, false, true)
            6 -> OrientationTransform(90, false, false, true)
            7 -> OrientationTransform(90, true, false, true)
            8 -> OrientationTransform(270, false, false, true)
            else -> OrientationTransform(0, false, false, false)
        }
    }

    fun displaySize(srcW: Int, srcH: Int, orientation: Int): Pair<Int, Int> {
        if (srcW <= 0 || srcH <= 0) return 0 to 0
        return if (swapsDimensions(orientation)) srcH to srcW else srcW to srcH
    }

    fun affineMatrix(orientation: Int, width: Int, height: Int): FloatArray {
        val w = width.toFloat()
        val h = height.toFloat()
        val o = normalize(orientation)
        val m = FloatArray(9)
        fun set(a: Float, b: Float, c: Float, d: Float, e: Float, f: Float) {
            m[0] = a; m[1] = b; m[2] = c
            m[3] = d; m[4] = e; m[5] = f
            m[6] = 0f; m[7] = 0f; m[8] = 1f
        }
        when (o) {
            2 -> set(-1f, 0f, w, 0f, 1f, 0f)
            3 -> set(-1f, 0f, w, 0f, -1f, h)
            4 -> set(1f, 0f, 0f, 0f, -1f, h)
            5 -> set(0f, 1f, 0f, 1f, 0f, 0f)
            6 -> set(0f, -1f, h, 1f, 0f, 0f)
            7 -> set(0f, -1f, h, -1f, 0f, w)
            8 -> set(0f, 1f, 0f, -1f, 0f, w)
            else -> set(1f, 0f, 0f, 0f, 1f, 0f)
        }
        return m
    }

    fun mapDisplayRectToRaw(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        rawW: Int,
        rawH: Int,
        orientation: Int
    ): IntArray {
        val o = normalize(orientation)
        val l = left.coerceIn(0f, 1f)
        val t = top.coerceIn(0f, 1f)
        val r = right.coerceIn(0f, 1f)
        val b = bottom.coerceIn(0f, 1f)
        val w = rawW.coerceAtLeast(1).toFloat()
        val h = rawH.coerceAtLeast(1).toFloat()
        val (rl, rt, rr, rb) = when (o) {
            2 -> Quad(1f - r, t, 1f - l, b)
            3 -> Quad(1f - r, 1f - b, 1f - l, 1f - t)
            4 -> Quad(l, 1f - b, r, 1f - t)
            5 -> Quad(t, l, b, r)
            6 -> Quad(t, 1f - r, b, 1f - l)
            7 -> Quad(1f - b, 1f - r, 1f - t, 1f - l)
            8 -> Quad(1f - b, l, 1f - t, r)
            else -> Quad(l, t, r, b)
        }
        val leftPx = (rl * w).toInt().coerceIn(0, rawW - 1)
        val topPx = (rt * h).toInt().coerceIn(0, rawH - 1)
        val rightPx = (rr * w).toInt().coerceIn(1, rawW)
        val bottomPx = (rb * h).toInt().coerceIn(1, rawH)
        val fixedRight = maxOf(rightPx, leftPx + 1)
        val fixedBottom = maxOf(bottomPx, topPx + 1)
        return intArrayOf(leftPx, topPx, fixedRight, fixedBottom)
    }

    private data class Quad(val l: Float, val t: Float, val r: Float, val b: Float)
}
