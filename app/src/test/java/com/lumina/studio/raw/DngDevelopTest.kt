package com.lumina.studio.raw

import com.lumina.studio.core.raw.DevelopResult
import com.lumina.studio.core.raw.DngDevelop
import com.lumina.studio.core.raw.DngParseResult
import com.lumina.studio.core.raw.DngParser
import com.lumina.studio.core.render.RawRecipe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class DngDevelopTest {

    private fun bayerMosaic(): IntArray = intArrayOf(
        800, 400, 800, 400,
        400, 200, 400, 200,
        800, 400, 800, 400,
        400, 200, 400, 200
    )

    private fun parseOk(mosaic: IntArray = bayerMosaic(), white: Long = 1000L, black: Long = 0L): com.lumina.studio.core.raw.DngInfo {
        val bytes = SyntheticDng.build(mosaic = mosaic, white = white, black = black)
        val res = DngParser.parse(bytes)
        assertTrue(res is DngParseResult.Ok)
        return (res as DngParseResult.Ok).info
    }

    @Test
    fun `black white scaling is hand computed`() {
        val v = DngDevelop.normalizeSample(600, 100.0, 1100L, 0f)
        assertEquals(0.5f, v, 0.001f)
        val blk = DngDevelop.normalizeSample(100, 100.0, 1100L, 0f)
        assertEquals(0f, blk, 0.001f)
        val wht = DngDevelop.normalizeSample(1100, 100.0, 1100L, 0f)
        assertEquals(1f, wht, 0.02f)
    }

    @Test
    fun `highlight soft clips instead of hard clip`() {
        val justOver = DngDevelop.softClipHighlight(1.2f)
        assertTrue(justOver >= 1f)
        assertTrue(justOver < 1.2f)
        val wayOver = DngDevelop.softClipHighlight(3f)
        assertTrue(wayOver < 3f)
        assertTrue(wayOver >= 1f)
        assertEquals(1f, DngDevelop.softClipHighlight(1f), 0.0001f)
        assertTrue(DngDevelop.softClipHighlight(1.01f) > DngDevelop.softClipHighlight(1f))
    }

    @Test
    fun `exposure EV applies linear gain pre gamma`() {
        val base = DngDevelop.normalizeSample(500, 0.0, 1000L, 0f)
        val plus = DngDevelop.normalizeSample(500, 0.0, 1000L, 1f)
        assertEquals((base * 2f).coerceAtMost(1.6f), plus, 0.05f)
        val minus = DngDevelop.normalizeSample(500, 0.0, 1000L, -1f)
        assertEquals(base * 0.5f, minus, 0.02f)
    }

    @Test
    fun `demosaic R site keeps R and interpolates G B`() {
        val info = parseOk()
        val strips = run {
            val bytes = SyntheticDng.build(mosaic = bayerMosaic())
            val parsed = (DngParser.parse(bytes) as DngParseResult.Ok).info
            DngParser.extractStripBytes(bytes, parsed)!!
        }
        val mosaic = DngDevelop.unpackMosaic(info, strips)!!
        assertEquals(800, mosaic[0])
        assertEquals(400, mosaic[1])
        val norm = DngDevelop.normalizeMosaic(mosaic, info, 0f)
        assertEquals(0.8f, norm[0], 0.001f)
        assertEquals(0.4f, norm[1], 0.001f)
        val rgb = DngDevelop.demosaicBilinear(norm, info)
        val r = rgb[0]
        val g = rgb[1]
        val b = rgb[2]
        assertEquals(0.8f, r, 0.001f)
        assertEquals(0.4f, g, 0.02f)
        assertEquals(0.2f, b, 0.02f)
    }

    @Test
    fun `demosaic B site keeps B`() {
        val info = parseOk()
        val bytes = SyntheticDng.build(mosaic = bayerMosaic())
        val parsed = (DngParser.parse(bytes) as DngParseResult.Ok).info
        val strips = DngParser.extractStripBytes(bytes, parsed)!!
        val mosaic = DngDevelop.unpackMosaic(info, strips)!!
        val norm = DngDevelop.normalizeMosaic(mosaic, info, 0f)
        val rgb = DngDevelop.demosaicBilinear(norm, info)
        val idx = (1 * 4 + 1) * 3
        assertEquals(0.2f, rgb[idx + 2], 0.001f)
        assertEquals(0.4f, rgb[idx + 1], 0.05f)
    }

    @Test
    fun `demosaic G site interpolates R and B`() {
        val info = parseOk()
        val bytes = SyntheticDng.build(mosaic = bayerMosaic())
        val parsed = (DngParser.parse(bytes) as DngParseResult.Ok).info
        val strips = DngParser.extractStripBytes(bytes, parsed)!!
        val mosaic = DngDevelop.unpackMosaic(info, strips)!!
        val norm = DngDevelop.normalizeMosaic(mosaic, info, 0f)
        val rgb = DngDevelop.demosaicBilinear(norm, info)
        val idx = (0 * 4 + 1) * 3
        assertEquals(0.4f, rgb[idx + 1], 0.001f)
        assertTrue(rgb[idx] > 0.5f)
        assertTrue(rgb[idx + 2] > 0.1f)
    }

    @Test
    fun `wb gains from AsShotNeutral are hand computed`() {
        val bytes = SyntheticDng.build(
            mosaic = bayerMosaic(),
            asShotNeutral = doubleArrayOf(0.5, 1.0, 0.5)
        )
        val info = (DngParser.parse(bytes) as DngParseResult.Ok).info
        val gains = DngDevelop.baseNeutralGains(info)
        assertEquals(2f, gains[0], 0.05f)
        assertEquals(1f, gains[1], 0.001f)
        assertEquals(2f, gains[2], 0.05f)
    }

    @Test
    fun `wb recipe temp tint multiply neutral gains`() {
        val info = parseOk()
        val recipe = RawRecipe(exposureEv = 0f, tempGain = 2f, tintGain = 0.5f)
        val gains = DngDevelop.wbGains(info, recipe)
        assertEquals(2f, gains[0], 0.01f)
        assertEquals(1f, gains[1], 0.01f)
        assertEquals(0.5f, gains[2], 0.01f)
    }

    @Test
    fun `wb fallback is daylight identity with source note`() {
        val info = parseOk()
        assertEquals("DaylightEstimate", DngDevelop.wbSource(info))
        val gains = DngDevelop.baseNeutralGains(info)
        assertEquals(1f, gains[0], 0.001f)
        assertEquals(1f, gains[1], 0.001f)
        assertEquals(1f, gains[2], 0.001f)
    }

    @Test
    fun `full develop produces expected sRGB pixel with tolerance`() {
        val info = parseOk()
        val bytes = SyntheticDng.build(mosaic = bayerMosaic())
        val parsed = (DngParser.parse(bytes) as DngParseResult.Ok).info
        val strips = DngParser.extractStripBytes(bytes, parsed)!!
        val res = DngDevelop.developToArgb(parsed, strips, RawRecipe())
        assertTrue(res is DevelopResult.Ok)
        val ok = res as DevelopResult.Ok
        assertEquals(4, ok.width)
        assertEquals(4, ok.height)
        assertEquals(16, ok.argb.size)
        val p = ok.argb[0]
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        assertTrue("R $r near 230", abs(r - 230) <= 12)
        assertTrue("G $g near 170", abs(g - 170) <= 12)
        assertTrue("B $b near 123", abs(b - 123) <= 14)
    }

    @Test
    fun `develop with exposure brightens`() {
        val bytes = SyntheticDng.build(mosaic = bayerMosaic())
        val parsed = (DngParser.parse(bytes) as DngParseResult.Ok).info
        val strips = DngParser.extractStripBytes(bytes, parsed)!!
        val base = DngDevelop.developToArgb(parsed, strips, RawRecipe(exposureEv = 0f)) as DevelopResult.Ok
        val bright = DngDevelop.developToArgb(parsed, strips, RawRecipe(exposureEv = 1f)) as DevelopResult.Ok
        val b0 = base.argb[0]
        val b1 = bright.argb[0]
        val lum0 = ((b0 shr 16) and 0xFF) + ((b0 shr 8) and 0xFF) + (b0 and 0xFF)
        val lum1 = ((b1 shr 16) and 0xFF) + ((b1 shr 8) and 0xFF) + (b1 and 0xFF)
        assertTrue(lum1 >= lum0)
    }

    @Test
    fun `orientation 6 swaps dimensions`() {
        val bytes = SyntheticDng.build(mosaic = IntArray(4 * 2) { 500 }, width = 4, height = 2, orientation = 6)
        val parsed = (DngParser.parse(bytes) as DngParseResult.Ok).info
        val strips = DngParser.extractStripBytes(bytes, parsed)!!
        val res = DngDevelop.developToArgb(parsed, strips, RawRecipe())
        assertTrue(res is DevelopResult.Ok)
        val ok = res as DevelopResult.Ok
        assertEquals(2, ok.width)
        assertEquals(4, ok.height)
    }

    @Test
    fun `eyedropper neutralizes gray pick`() {
        val gray = (0xFF shl 24) or (128 shl 16) or (128 shl 8) or 128
        val gains = DngDevelop.wbGainsFromPickerPixel(gray)
        assertEquals(1f, gains[0], 0.05f)
        assertEquals(1f, gains[1], 0.05f)
        val warm = (0xFF shl 24) or (200 shl 16) or (100 shl 8) or 50
        val g2 = DngDevelop.wbGainsFromPickerPixel(warm)
        assertTrue(g2[0] < 1f)
        assertTrue(g2[1] > 1f)
    }

    @Test
    fun `linearToSrgb round trip sanity`() {
        assertEquals(0f, DngDevelop.linearToSrgb(0f), 0.001f)
        assertEquals(1f, DngDevelop.linearToSrgb(1f), 0.01f)
        val mid = DngDevelop.linearToSrgb(0.5f)
        assertTrue(mid > 0.6f && mid < 0.8f)
    }
}
