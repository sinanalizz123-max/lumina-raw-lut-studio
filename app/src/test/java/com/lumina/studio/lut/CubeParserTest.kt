package com.lumina.studio.lut

import com.lumina.studio.core.lut.CubeParseResult
import com.lumina.studio.core.lut.CubeParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 2 pure-JVM guards for CubeParser (no Robolectric, no Bitmap).
 *
 * Covers: identity 3D / 1D Ok paths, malformed -> Err (never throws),
 * DOMAIN_MIN/MAX parsing + defaults, and size range 2..64.
 */
class CubeParserTest {

    private fun identity3D(size: Int, title: String? = "Identity $size"): String {
        val sb = StringBuilder()
        if (title != null) sb.append("TITLE \"$title\"\n")
        sb.append("LUT_3D_SIZE $size\n")
        for (b in 0 until size) {
            for (g in 0 until size) {
                for (r in 0 until size) {
                    val denom = (size - 1).toFloat()
                    sb.append("${r / denom} ${g / denom} ${b / denom}\n")
                }
            }
        }
        return sb.toString()
    }

    private fun identity1D(size: Int, title: String? = "Linear $size"): String {
        val sb = StringBuilder()
        if (title != null) sb.append("TITLE \"$title\"\n")
        sb.append("LUT_1D_SIZE $size\n")
        for (i in 0 until size) {
            val v = i / (size - 1).toFloat()
            sb.append("$v $v $v\n")
        }
        return sb.toString()
    }

    private fun errReason(text: String): String {
        // Proves never-throws: if parse threw, this helper would propagate
        // and the test would error instead of asserting Err.
        val result = CubeParser.parse(text)
        assertTrue("expected Err for input: ${text.take(120)}", result is CubeParseResult.Err)
        val reason = (result as CubeParseResult.Err).reason
        assertTrue("Err reason must be non-blank, was: '$reason'", reason.isNotBlank())
        return reason
    }

    // ---------- happy paths ----------

    @Test
    fun `identity 3D size 2 parses Ok with correct size title and data count`() {
        val result = CubeParser.parse(identity3D(2, "Identity 2"))
        assertTrue(result is CubeParseResult.Ok)
        val lut = (result as CubeParseResult.Ok).lut
        assertEquals(2, lut.size)
        assertTrue(lut.is3D)
        assertEquals("Identity 2", lut.title)
        assertEquals(2 * 2 * 2 * 3, lut.data.size)
    }

    @Test
    fun `identity 3D data values span corners`() {
        val lut = (CubeParser.parse(identity3D(2)) as CubeParseResult.Ok).lut
        // First triple (r=0,g=0,b=0) and last triple (r=1,g=1,b=1).
        assertEquals(0f, lut.data[0], 0.0001f)
        assertEquals(0f, lut.data[1], 0.0001f)
        assertEquals(0f, lut.data[2], 0.0001f)
        val n = lut.data.size
        assertEquals(1f, lut.data[n - 3], 0.0001f)
        assertEquals(1f, lut.data[n - 2], 0.0001f)
        assertEquals(1f, lut.data[n - 1], 0.0001f)
    }

    @Test
    fun `identity 3D size 3 parses Ok`() {
        val result = CubeParser.parse(identity3D(3, "Identity 3"))
        assertTrue(result is CubeParseResult.Ok)
        val lut = (result as CubeParseResult.Ok).lut
        assertEquals(3, lut.size)
        assertTrue(lut.is3D)
        assertEquals(27 * 3, lut.data.size)
    }

    @Test
    fun `1D size 4 parses Ok with is3D false`() {
        val result = CubeParser.parse(identity1D(4, "Linear 4"))
        assertTrue(result is CubeParseResult.Ok)
        val lut = (result as CubeParseResult.Ok).lut
        assertEquals(4, lut.size)
        assertTrue(!lut.is3D)
        assertEquals("Linear 4", lut.title)
        assertEquals(4 * 3, lut.data.size)
    }

    @Test
    fun `comments and blank lines are ignored`() {
        val text = "# a comment\n\nTITLE \"Spaced\"\n\n# another\nLUT_3D_SIZE 2\n\n" +
            "# data follows\n" + identity3D(2, null).substringAfter("LUT_3D_SIZE 2\n")
        val result = CubeParser.parse(text)
        assertTrue(result is CubeParseResult.Ok)
        assertEquals("Spaced", (result as CubeParseResult.Ok).lut.title)
    }

    @Test
    fun `quoted TITLE is unquoted, unquoted TITLE kept`() {
        val quoted = (CubeParser.parse(identity3D(2, "Hello World")) as CubeParseResult.Ok).lut
        assertEquals("Hello World", quoted.title)
        val raw = "TITLE BareName\nLUT_1D_SIZE 2\n0.0 0.0 0.0\n1.0 1.0 1.0\n"
        val lut = (CubeParser.parse(raw) as CubeParseResult.Ok).lut
        assertEquals("BareName", lut.title)
    }

    @Test
    fun `fallbackTitle used when TITLE absent`() {
        val noTitle = "LUT_1D_SIZE 2\n0.0 0.0 0.0\n1.0 1.0 1.0\n"
        val withFallback = (CubeParser.parse(noTitle, "Fallback") as CubeParseResult.Ok).lut
        assertEquals("Fallback", withFallback.title)
        val withoutFallback = (CubeParser.parse(noTitle) as CubeParseResult.Ok).lut
        assertTrue(withoutFallback.title == null)
    }

    // ---------- domains ----------

    @Test
    fun `DOMAIN_MIN and DOMAIN_MAX parsed`() {
        val text = "TITLE \"Dom\"\nLUT_3D_SIZE 2\nDOMAIN_MIN 0.1 0.2 0.3\nDOMAIN_MAX 0.9 0.8 0.7\n" +
            "0.0 0.0 0.0\n1.0 0.0 0.0\n0.0 1.0 0.0\n1.0 1.0 0.0\n" +
            "0.0 0.0 1.0\n1.0 0.0 1.0\n0.0 1.0 1.0\n1.0 1.0 1.0\n"
        val lut = (CubeParser.parse(text) as CubeParseResult.Ok).lut
        assertEquals(0.1f, lut.domainMin[0], 0.0001f)
        assertEquals(0.2f, lut.domainMin[1], 0.0001f)
        assertEquals(0.3f, lut.domainMin[2], 0.0001f)
        assertEquals(0.9f, lut.domainMax[0], 0.0001f)
        assertEquals(0.8f, lut.domainMax[1], 0.0001f)
        assertEquals(0.7f, lut.domainMax[2], 0.0001f)
    }

    @Test
    fun `DOMAIN defaults to 0 to 1 when absent`() {
        val lut = (CubeParser.parse(identity3D(2)) as CubeParseResult.Ok).lut
        assertEquals(0f, lut.domainMin[0], 0.0001f)
        assertEquals(0f, lut.domainMin[1], 0.0001f)
        assertEquals(0f, lut.domainMin[2], 0.0001f)
        assertEquals(1f, lut.domainMax[0], 0.0001f)
        assertEquals(1f, lut.domainMax[1], 0.0001f)
        assertEquals(1f, lut.domainMax[2], 0.0001f)
    }

    // ---------- size range ----------

    @Test
    fun `size constants are 2 to 64`() {
        assertEquals(2, CubeParser.MIN_SIZE)
        assertEquals(64, CubeParser.MAX_SIZE)
    }

    @Test
    fun `min sizes accepted`() {
        assertTrue(CubeParser.parse(identity3D(2)) is CubeParseResult.Ok)
        assertTrue(CubeParser.parse(identity1D(2)) is CubeParseResult.Ok)
    }

    @Test
    fun `max size 64 accepted via 1D (3D-64 would need 262k lines)`() {
        // Same MIN/MAX range check guards both keywords (shared branch),
        // so a 64-entry 1D parse proves the upper bound without a 2 MB string.
        val result = CubeParser.parse(identity1D(64))
        assertTrue(result is CubeParseResult.Ok)
        assertEquals(64, (result as CubeParseResult.Ok).lut.size)
    }

    @Test
    fun `size 1 rejected for both 1D and 3D`() {
        assertTrue(errReason("LUT_1D_SIZE 1\n0.0 0.0 0.0\n").isNotBlank())
        assertTrue(errReason("LUT_3D_SIZE 1\n0.0 0.0 0.0\n").isNotBlank())
    }

    @Test
    fun `size 65 rejected for both 1D and 3D`() {
        assertTrue(errReason("LUT_1D_SIZE 65\n").isNotBlank())
        assertTrue(errReason("LUT_3D_SIZE 65\n").isNotBlank())
    }

    // ---------- malformed -> Err, never throws ----------

    @Test
    fun `bad size text is Err`() {
        errReason("TITLE X\nLUT_3D_SIZE abc\n")
        errReason("TITLE X\nLUT_3D_SIZE 2.5\n")
    }

    @Test
    fun `truncated DATA is Err`() {
        // Size 2 needs 8 lines; give only 7.
        val lines = identity3D(2).split("\n").dropLast(2).joinToString("\n") + "\n"
        errReason(lines)
    }

    @Test
    fun `garbage and empty inputs are Err`() {
        errReason("this is not a lut at all\njust words 123\n")
        errReason("")
        errReason("   \n # only a comment\n  \n")
        errReason("TITLE OnlyATitle\n")
    }

    @Test
    fun `DATA before size header is Err`() {
        errReason("0.0 0.0 0.0\n1.0 1.0 1.0\nLUT_1D_SIZE 2\n0.0 0.0 0.0\n1.0 1.0 1.0\n")
    }

    @Test
    fun `duplicate size and both sizes are Err`() {
        errReason("LUT_3D_SIZE 2\nLUT_3D_SIZE 2\n" + "0.0 0.0 0.0\n".repeat(8))
        errReason("LUT_1D_SIZE 2\nLUT_3D_SIZE 2\n" + "0.0 0.0 0.0\n".repeat(8))
    }

    @Test
    fun `too many DATA lines is Err`() {
        // Size 2 3D needs 8; give 9.
        val text = identity3D(2) + "0.5 0.5 0.5\n"
        errReason(text)
    }

    @Test
    fun `non-numeric and non-finite DATA are Err`() {
        errReason("LUT_1D_SIZE 2\na b c\n1.0 1.0 1.0\n")
        errReason("LUT_1D_SIZE 2\nNaN 0.0 0.0\n1.0 1.0 1.0\n")
        errReason("LUT_1D_SIZE 2\nInfinity 0.0 0.0\n1.0 1.0 1.0\n")
    }

    @Test
    fun `DOMAIN_MIN above DOMAIN_MAX is Err`() {
        val text = "LUT_1D_SIZE 2\nDOMAIN_MIN 0.9 0.0 0.0\nDOMAIN_MAX 0.1 1.0 1.0\n" +
            "0.0 0.0 0.0\n1.0 1.0 1.0\n"
        errReason(text)
    }
}
