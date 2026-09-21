package com.lumina.studio.util

import com.lumina.studio.core.util.ExifOrientation
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExifOrientationTest {

    @Test
    fun `normalize clamps out of range to 1`() {
        assertEquals(1, ExifOrientation.normalize(0))
        assertEquals(1, ExifOrientation.normalize(-3))
        assertEquals(1, ExifOrientation.normalize(9))
        for (o in 1..8) assertEquals(o, ExifOrientation.normalize(o))
    }

    @Test
    fun `describe maps 1 to 8`() {
        val d1 = ExifOrientation.describe(1)
        assertEquals(0, d1.rotationDegrees)
        assertFalse(d1.flipHorizontal)
        assertFalse(d1.flipVertical)
        assertFalse(d1.swapsDimensions)

        val d2 = ExifOrientation.describe(2)
        assertEquals(0, d2.rotationDegrees)
        assertTrue(d2.flipHorizontal)

        assertEquals(180, ExifOrientation.describe(3).rotationDegrees)

        val d4 = ExifOrientation.describe(4)
        assertTrue(d4.flipVertical)

        val d5 = ExifOrientation.describe(5)
        assertEquals(270, d5.rotationDegrees)
        assertTrue(d5.flipHorizontal)
        assertTrue(d5.swapsDimensions)

        val d6 = ExifOrientation.describe(6)
        assertEquals(90, d6.rotationDegrees)
        assertTrue(d6.swapsDimensions)

        val d7 = ExifOrientation.describe(7)
        assertEquals(90, d7.rotationDegrees)
        assertTrue(d7.flipHorizontal)
        assertTrue(d7.swapsDimensions)

        val d8 = ExifOrientation.describe(8)
        assertEquals(270, d8.rotationDegrees)
        assertTrue(d8.swapsDimensions)
    }

    @Test
    fun `swaps dimensions only for 5 to 8`() {
        for (o in 1..4) assertFalse(ExifOrientation.swapsDimensions(o))
        for (o in 5..8) assertTrue(ExifOrientation.swapsDimensions(o))
    }

    @Test
    fun `display size swaps for 5 to 8`() {
        for (o in 1..4) assertEquals(400 to 300, ExifOrientation.displaySize(400, 300, o))
        for (o in 5..8) assertEquals(300 to 400, ExifOrientation.displaySize(400, 300, o))
        assertEquals(0 to 0, ExifOrientation.displaySize(0, 300, 6))
    }

    @Test
    fun `affine matrix values match exif mapping`() {
        val w = 400
        val h = 300
        fun m(o: Int) = ExifOrientation.affineMatrix(o, w, h)
        assertArrayEquals(floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f), m(1), 0f)
        assertArrayEquals(floatArrayOf(-1f, 0f, 400f, 0f, 1f, 0f, 0f, 0f, 1f), m(2), 0f)
        assertArrayEquals(floatArrayOf(-1f, 0f, 400f, 0f, -1f, 300f, 0f, 0f, 1f), m(3), 0f)
        assertArrayEquals(floatArrayOf(1f, 0f, 0f, 0f, -1f, 300f, 0f, 0f, 1f), m(4), 0f)
        assertArrayEquals(floatArrayOf(0f, 1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f), m(5), 0f)
        assertArrayEquals(floatArrayOf(0f, -1f, 300f, 1f, 0f, 0f, 0f, 0f, 1f), m(6), 0f)
        assertArrayEquals(floatArrayOf(0f, -1f, 300f, -1f, 0f, 400f, 0f, 0f, 1f), m(7), 0f)
        assertArrayEquals(floatArrayOf(0f, 1f, 0f, -1f, 0f, 400f, 0f, 0f, 1f), m(8), 0f)
    }

    @Test
    fun `full frame display rect maps to full raw rect for all orientations`() {
        for (o in 1..8) {
            val rect = ExifOrientation.mapDisplayRectToRaw(0f, 0f, 1f, 1f, 400, 300, o)
            assertArrayEquals("orientation $o", intArrayOf(0, 0, 400, 300), rect)
        }
    }

    @Test
    fun `display left half maps to expected raw halves`() {
        // Orientation 1: left half stays left half.
        assertArrayEquals(
            intArrayOf(0, 0, 200, 300),
            ExifOrientation.mapDisplayRectToRaw(0f, 0f, 0.5f, 1f, 400, 300, 1)
        )
        // Orientation 6 (90 CW): displayed left half comes from raw top half.
        assertArrayEquals(
            intArrayOf(0, 150, 400, 300),
            ExifOrientation.mapDisplayRectToRaw(0f, 0f, 0.5f, 1f, 400, 300, 6)
        )
        // Orientation 3 (180): displayed left half comes from raw right half.
        assertArrayEquals(
            intArrayOf(200, 0, 400, 300),
            ExifOrientation.mapDisplayRectToRaw(0f, 0f, 0.5f, 1f, 400, 300, 3)
        )
    }
}
