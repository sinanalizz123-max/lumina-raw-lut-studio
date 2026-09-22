package com.lumina.studio.merge

import com.lumina.studio.core.edit.EditParams
import com.lumina.studio.core.merge.AlignMath
import com.lumina.studio.core.merge.ExposureEv
import com.lumina.studio.core.merge.HdrMath
import com.lumina.studio.core.merge.MergeBytes
import com.lumina.studio.core.merge.MergeError
import com.lumina.studio.core.merge.MergeFrames
import com.lumina.studio.core.merge.MergeKind
import com.lumina.studio.core.merge.MergeLimits
import com.lumina.studio.core.merge.MergeResearch
import com.lumina.studio.core.merge.MergedRecipe
import com.lumina.studio.core.merge.PanoramaMath
import com.lumina.studio.core.merge.use
import com.lumina.studio.core.merge.validateFrameList
import com.lumina.studio.core.render.Dims
import com.lumina.studio.core.render.PixelRect
import java.util.Random
import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.abs

/**
 * M17 reliability gate (pure JVM, no android.*).
 *
 * Per prompt-4: invalid count, incompatible dims, cancellation,
 * alignment failure, malformed input, successful merge, output dims,
 * decoder validation (byte-level header contract), resource cleanup
 * (closed flags), persistence/project integration (default recipe), plus
 * the self-enforced gate — alignment recovers known shifts, fusion
 * dims/validity, panorama seam continuity, determinism. Bitmap-touching
 * loops stay on-device-only; they delegate to the pure math pinned here.
 */
class M17MergeTest {

    private fun noiseLuma(w: Int, h: Int, seed: Long): FloatArray {
        val rnd = Random(seed)
        return FloatArray(w * h) { rnd.nextFloat() }
    }

    private fun grayRgb(luma: FloatArray): FloatArray {
        val out = FloatArray(luma.size * 3)
        for (i in luma.indices) {
            out[i * 3] = luma[i]
            out[i * 3 + 1] = luma[i]
            out[i * 3 + 2] = luma[i]
        }
        return out
    }

    private fun colorRgb(luma: FloatArray): FloatArray {
        val out = FloatArray(luma.size * 3)
        for (i in luma.indices) {
            val l = luma[i]
            out[i * 3] = l
            out[i * 3 + 1] = l
            out[i * 3 + 2] = (l * 0.5f).coerceIn(0f, 1f)
        }
        return out
    }

    private fun scaled(rgb: FloatArray, gain: Float): FloatArray =
        FloatArray(rgb.size) { (rgb[it] * gain).coerceIn(0f, 1f) }

    // ---------- invalid count ----------

    @Test
    fun `empty and single frame rejected as too few`() {
        val one = validateFrameList(listOf(Dims(8, 8)))
        assertTrue(one is MergeError.TooFew)
        assertTrue((one as MergeError.TooFew).message.contains("at least 2"))
        assertTrue(validateFrameList(emptyList()) is MergeError.TooFew)
    }

    @Test
    fun `seven frames rejected as too many`() {
        val seven = List(7) { Dims(8, 8) }
        val err = validateFrameList(seven)
        assertTrue(err is MergeError.TooMany)
        assertTrue((err as MergeError.TooMany).message.contains("max 6"))
    }

    @Test
    fun `two equal frames pass structural validation`() {
        assertNull(validateFrameList(listOf(Dims(8, 8), Dims(8, 8))))
    }

    // ---------- incompatible dims ----------

    @Test
    fun `mismatched dims rejected with reason`() {
        val err = validateFrameList(listOf(Dims(8, 8), Dims(8, 6)))
        assertTrue(err is MergeError.DimMismatch)
        val mismatch = err as MergeError.DimMismatch
        assertEquals(1, mismatch.index)
        assertTrue(mismatch.message.contains("8x6"))
        assertTrue(mismatch.message.contains("8x8"))
    }

    @Test
    fun `empty frame rejected`() {
        assertTrue(validateFrameList(listOf(Dims(8, 8), Dims(0, 0))) is MergeError.EmptyFrame)
    }

    // ---------- decoder validation (byte headers) ----------

    @Test
    fun `jpeg png tiff headers accepted`() {
        assertEquals("jpeg", MergeBytes.kindOf(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())))
        assertEquals("png", MergeBytes.kindOf(byteArrayOf(0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte())))
        assertEquals("tiff", MergeBytes.kindOf(byteArrayOf(0x49.toByte(), 0x49.toByte(), 0x2A.toByte(), 0x00.toByte())))
        assertEquals("tiff", MergeBytes.kindOf(byteArrayOf(0x4D.toByte(), 0x4D.toByte(), 0x00.toByte(), 0x2A.toByte())))
        assertNull(
            MergeBytes.validateHeader(
                byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()), 0, 100L
            )
        )
    }

    @Test
    fun `malformed inputs rejected with reasons`() {
        assertTrue(MergeBytes.validateHeader(ByteArray(0), 1, 0L) is MergeError.Malformed)
        assertTrue(MergeBytes.validateHeader(ByteArray(2), 0, 2L) is MergeError.Malformed)
        val garbage = MergeBytes.validateHeader("hello world!".toByteArray(), 2, 12L)
        assertTrue(garbage is MergeError.Malformed)
        assertTrue((garbage as MergeError.Malformed).message.contains("Photo 3"))
        assertNull(MergeBytes.kindOf("GIF8".toByteArray()))
    }

    // ---------- alignment: known shifts ----------

    @Test
    fun `alignment recovers known integer shift`() {
        val w = 64
        val h = 48
        val base = noiseLuma(w, h, 7L)
        val moved = AlignMath.rgbToLuma(
            AlignMath.applyShiftRgb(grayRgb(base), w, h, 5, -3), w, h
        )
        val s = AlignMath.estimateShift(base, moved, w, h, 8, 8)
        // Convention: estimateShift returns the ALIGNING shift, i.e. what to
        // apply to `moved` to bring it back onto `ref` (negation of the motion
        // that created it). Callers use it directly in applyShiftRgb.
        assertTrue("dx=${s.dx} dy=${s.dy} score=${s.score}", abs(s.dx + 5) <= 1 && abs(s.dy - 3) <= 1)
        assertTrue(s.score > MergeLimits.MIN_ALIGN_NCC)
        assertNull(AlignMath.checkConfidence(s.score))
    }

    @Test
    fun `alignment is deterministic`() {
        val w = 48
        val h = 32
        val base = noiseLuma(w, h, 11L)
        val moved = AlignMath.rgbToLuma(
            AlignMath.applyShiftRgb(grayRgb(base), w, h, -4, 2), w, h
        )
        val a = AlignMath.estimateShift(base, moved, w, h, 8, 8)
        val b = AlignMath.estimateShift(base, moved, w, h, 8, 8)
        assertEquals(a, b)
    }

    @Test
    fun `unrelated content fails confidence with reason`() {
        val w = 48
        val h = 32
        val a = noiseLuma(w, h, 21L)
        val b = noiseLuma(w, h, 22L)
        val s = AlignMath.estimateShift(a, b, w, h, 6, 6)
        assertTrue("unexpected high score ${s.score}", s.score < MergeLimits.MIN_OVERLAP_NCC)
        val err = AlignMath.checkConfidence(s.score, MergeLimits.MIN_OVERLAP_NCC)
        assertNotNull(err)
        assertTrue((err as MergeError.AlignmentFailed).message.contains("NCC"))
    }

    @Test
    fun `self alignment scores one`() {
        val w = 32
        val h = 24
        val a = noiseLuma(w, h, 5L)
        assertEquals(1f, AlignMath.ncc(a, a, w, h, 0, 0), 1e-5f)
    }

    @Test
    fun `tiny overlap is invalid`() {
        val a = FloatArray(4) { 0.5f }
        assertEquals(
            AlignMath.INVALID_SCORE,
            AlignMath.ncc(a, a, 2, 2, 0, 0),
            0f
        )
    }

    @Test
    fun `applyShift moves content right by dx`() {
        val rgb = floatArrayOf(
            0.1f, 0.1f, 0.1f, 0.2f, 0.2f, 0.2f,
            0.3f, 0.3f, 0.3f, 0.4f, 0.4f, 0.4f
        )
        val out = AlignMath.applyShiftRgb(rgb, 4, 1, 1, 0)
        assertEquals(0.1f, out[0], 0f)
        assertEquals(0.1f, out[3], 0f)
        assertEquals(0.2f, out[6], 0f)
        assertEquals(0.3f, out[9], 0f)
    }

    @Test
    fun `thumbnail keeps uniform fields uniform`() {
        val luma = FloatArray(8 * 4) { 0.7f }
        val thumb = AlignMath.toThumb(luma, 8, 4, 4)
        assertEquals(4, thumb.width)
        assertEquals(2, thumb.height)
        for (v in thumb.data) assertEquals(0.7f, v, 1e-6f)
    }

    // ---------- cancellation ----------

    @Test(expected = CancellationException::class)
    fun `estimateShift aborts promptly on cancel`() {
        val w = 64
        val h = 48
        val a = noiseLuma(w, h, 31L)
        val b = noiseLuma(w, h, 32L)
        var calls = 0
        AlignMath.estimateShift(a, b, w, h, 10, 10) {
            if (++calls >= 2) throw CancellationException("test-cancel")
        }
    }

    @Test(expected = CancellationException::class)
    fun `fuse aborts promptly on cancel`() {
        val w = 48
        val h = 32
        val frames = MergeFrames(
            w, h,
            mutableListOf(grayRgb(noiseLuma(w, h, 41L)), grayRgb(noiseLuma(w, h, 42L))),
            listOf(0f, 1f)
        )
        var calls = 0
        frames.use {
            HdrMath.fuse(it, checkCancel = {
                if (++calls >= 3) throw CancellationException("test-cancel")
            })
        }
    }

    // ---------- exposure EVs ----------

    @Test
    fun `exif strings parse`() {
        assertEquals(0.004, ExposureEv.parseExposureTime("1/250")!!, 1e-9)
        assertEquals(0.04, ExposureEv.parseExposureTime("0.04")!!, 1e-9)
        assertNull(ExposureEv.parseExposureTime("abc"))
        assertNull(ExposureEv.parseExposureTime("1/0"))
        assertNull(ExposureEv.parseExposureTime(null))
        assertEquals(100.0, ExposureEv.parseIso("100")!!, 1e-9)
        assertEquals(2.8, ExposureEv.parseFNumber("2.8")!!, 1e-9)
        assertEquals(2.0, ExposureEv.parseFNumber("f/2.0")!!, 1e-9)
        assertNull(ExposureEv.evIndex(null, 100.0, 8.0))
    }

    @Test
    fun `ev index orders brackets`() {
        val dark = ExposureEv.evIndex(1.0 / 250.0, 100.0, 8.0)!!
        val bright = ExposureEv.evIndex(1.0 / 60.0, 100.0, 8.0)!!
        assertTrue(bright > dark)
        val highIso = ExposureEv.evIndex(1.0 / 250.0, 400.0, 8.0)!!
        assertTrue(highIso > dark)
    }

    @Test
    fun `luma ev spread gates brackets`() {
        val evs = ExposureEv.relativeEvsFromLuma(listOf(0.25f, 0.5f))
        assertEquals(0f, evs[0], 1e-6f)
        assertEquals(1f, evs[1], 1e-6f)
        assertEquals(1f, ExposureEv.spreadF(evs), 1e-6f)
        assertNull(ExposureEv.validateSpread(1f))
        assertNull(ExposureEv.validateSpread(MergeLimits.MIN_EV_SPREAD))
        val flat = ExposureEv.validateSpread(
            ExposureEv.spreadF(ExposureEv.relativeEvsFromLuma(listOf(0.4f, 0.4f)))
        )
        assertTrue(flat is MergeError.NoExposureSpread)
        assertTrue((flat as MergeError.NoExposureSpread).message.contains("bracketed"))
    }

    // ---------- HDR fusion ----------

    @Test
    fun `successful merge pipeline on synthetic bracket`() {
        val w = 48
        val h = 32
        val base = noiseLuma(w, h, 51L)
        val dark = scaled(colorRgb(base), 0.6f)
        val brightShifted = AlignMath.applyShiftRgb(scaled(colorRgb(base), 1.4f), w, h, 3, 1)
        val lumaDark = AlignMath.rgbToLuma(dark, w, h)
        val lumaBright = AlignMath.rgbToLuma(brightShifted, w, h)

        val evs = ExposureEv.relativeEvsFromLuma(
            listOf(AlignMath.medianOf(lumaDark), AlignMath.medianOf(lumaBright))
        )
        assertNull(ExposureEv.validateSpread(ExposureEv.spreadF(evs)))

        val shift = AlignMath.estimateShift(lumaDark, lumaBright, w, h, 8, 8)
        // Aligning convention (see test above): applied motion was (3, 1).
        assertTrue(abs(shift.dx + 3) <= 1 && abs(shift.dy - 1) <= 1)

        val alignedBright = AlignMath.applyShiftRgb(brightShifted, w, h, shift.dx, shift.dy)
        val progress = ArrayList<Float>()
        val out = MergeFrames(w, h, mutableListOf(dark, alignedBright), evs).use {
            HdrMath.fuse(it, onProgress = { f -> progress.add(f) })
        }
        assertEquals(w * h * 3, out.size)
        for (v in out) {
            assertTrue("non-finite $v", v.isFinite())
            assertTrue("out of range $v", v in 0f..1f)
        }
        assertEquals(1f, progress.last(), 0f)
        var mean = 0.0
        for (v in out) mean += v
        mean /= out.size
        var meanDark = 0.0
        for (v in dark) meanDark += v
        meanDark /= dark.size
        var meanBright = 0.0
        for (v in alignedBright) meanBright += v
        meanBright /= alignedBright.size
        val lo = minOf(meanDark, meanBright) - 1e-3
        val hi = maxOf(meanDark, meanBright) + 1e-3
        assertTrue("mean $mean not in [$lo, $hi]", mean in lo..hi)
        assertEquals(Dims(w, h), HdrMath.outputDims(w, h))
    }

    @Test
    fun `fusion is deterministic`() {
        val w = 32
        val h = 24
        val f0 = colorRgb(noiseLuma(w, h, 61L))
        val f1 = scaled(colorRgb(noiseLuma(w, h, 62L)), 1.3f)
        fun run(): FloatArray = MergeFrames(w, h, mutableListOf(f0, f1), listOf(0f, 1f)).use {
            HdrMath.fuse(it)
        }
        val a = run()
        val b = run()
        assertTrue(a.contentEquals(b))
    }

    @Test
    fun `flat frames fall back to uniform weights`() {
        val w = 16
        val h = 12
        val f0 = FloatArray(w * h * 3) { 0.5f }
        val f1 = FloatArray(w * h * 3) { 0.5f }
        val lumas = listOf(AlignMath.rgbToLuma(f0, w, h), AlignMath.rgbToLuma(f1, w, h))
        val weights = HdrMath.frameWeights(listOf(f0, f1), lumas, w, h)
        for (i in 0 until w * h) {
            assertEquals(0.5f, weights[0][i], 1e-6f)
            assertEquals(0.5f, weights[1][i], 1e-6f)
        }
        val out = MergeFrames(w, h, mutableListOf(f0, f1), listOf(0f, 0f)).use { HdrMath.fuse(it) }
        for (v in out) assertEquals(0.5f, v, 1e-5f)
    }

    @Test
    fun `deghost penalizes the odd frame`() {
        // Hand-verified 5x5 checkerboard (P/Q) with f2's center replaced by
        // bright R. Center lumas: P=0.49619 (x2, the median), R=0.88175
        // (|R-med|=0.386 > 0.25 -> f2 penalized x0.25, f0/f1 untouched).
        // Raw weights: w0=w1=0.11404*0.10801*0.99982=0.012316,
        // w2=0.4996*0.20139*0.16177*0.25=0.004069 -> normalized
        // w2=0.1418 (below uniform 1/3 and below w0=w1=0.4291). Without the
        // penalty w2 would normalize to ~0.40, so this pins the mechanism.
        val w = 5
        val h = 5
        fun checker(x: Int, y: Int): FloatArray =
            if ((x + y) % 2 == 0) floatArrayOf(0.55f, 0.5f, 0.3f)
            else floatArrayOf(0.35f, 0.4f, 0.3f)
        fun build(replaceCenter: FloatArray?): FloatArray {
            val out = FloatArray(w * h * 3)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val rgb = if (replaceCenter != null && x == 2 && y == 2) replaceCenter
                    else checker(x, y)
                    val d = (y * w + x) * 3
                    out[d] = rgb[0]
                    out[d + 1] = rgb[1]
                    out[d + 2] = rgb[2]
                }
            }
            return out
        }
        val f0 = build(null)
        val f1 = build(null)
        val f2 = build(floatArrayOf(0.95f, 0.9f, 0.5f))
        val frames = listOf(f0, f1, f2)
        val lumas = frames.map { AlignMath.rgbToLuma(it, w, h) }
        val weights = HdrMath.frameWeights(frames, lumas, w, h)
        val c = 2 * w + 2
        assertEquals(weights[0][c], weights[1][c], 0f)
        assertTrue("w2=${weights[2][c]} w0=${weights[0][c]}", weights[2][c] < weights[0][c])
        assertTrue("w2=${weights[2][c]} not suppressed", weights[2][c] < 1f / 3f)
        var sum = 0f
        for (k in 0..2) sum += weights[k][c]
        assertEquals(1f, sum, 1e-6f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `fuse rejects a single frame`() {
        val w = 8
        val h = 8
        MergeFrames(w, h, mutableListOf(FloatArray(w * h * 3)), listOf(0f)).use {
            HdrMath.fuse(it)
        }
    }

    // ---------- resource cleanup ----------

    @Test
    fun `frames release on success and on failure`() {
        val w = 8
        val h = 8
        val ok = MergeFrames(w, h, mutableListOf(FloatArray(w * h * 3)), listOf(0f))
        ok.use { assertTrue(!it.released) }
        assertTrue(ok.released)
        val failing = MergeFrames(w, h, mutableListOf(FloatArray(w * h * 3)), listOf(0f))
        try {
            failing.use { throw IllegalStateException("boom") }
            fail("expected boom")
        } catch (_: IllegalStateException) {
        }
        assertTrue(failing.released)
        failing.release()
        assertTrue(failing.released)
    }

    // ---------- panorama ----------

    @Test
    fun `overlap fraction math`() {
        assertEquals(0.72f, PanoramaMath.overlapFraction(100, 50, 20, 5), 1e-6f)
        assertEquals(1f, PanoramaMath.overlapFraction(100, 50, 0, 0), 1e-6f)
        assertEquals(0f, PanoramaMath.overlapFraction(100, 50, 200, 0), 0f)
    }

    @Test
    fun `pairwise validation messages are specific`() {
        assertNull(PanoramaMath.validatePairwise(0, PanoramaMath.Pairwise(10, 2, 0.9f, 0.8f)))
        val lowScore = PanoramaMath.validatePairwise(1, PanoramaMath.Pairwise(10, 2, 0.1f, 0.8f))
        assertTrue(lowScore is MergeError.InsufficientOverlap)
        assertTrue((lowScore as MergeError.InsufficientOverlap).message.contains("Photos 2 and 3"))
        val lowOverlap = PanoramaMath.validatePairwise(0, PanoramaMath.Pairwise(90, 0, 0.9f, 0.05f))
        assertTrue(lowOverlap is MergeError.InsufficientOverlap)
    }

    @Test
    fun `gain factor matches and clamps`() {
        assertEquals(2f, PanoramaMath.gainFactor(0.5f, 0.25f), 1e-6f)
        assertEquals(4f, PanoramaMath.gainFactor(1f, 0.01f), 0f)
        assertEquals(0.25f, PanoramaMath.gainFactor(0.01f, 1f), 0f)
    }

    @Test
    fun `overlap median reads the shared interior`() {
        val w = 10
        val h = 4
        val luma = FloatArray(w * h) { i -> (i % w) / 10f }
        assertEquals(0.65f, PanoramaMath.overlapMedian(luma, w, h, 4, 0, true), 1e-6f)
        assertEquals(0.25f, PanoramaMath.overlapMedian(luma, w, h, 4, 0, false), 1e-6f)
    }

    @Test
    fun `cylindrical warp keeps the center fixed`() {
        // Odd in/out widths (21 -> 19) so both centers are integer pixels
        // and the center maps exactly (theta = 0 -> bilinear identity).
        val w = 21
        val h = 11
        val rgb = colorRgb(noiseLuma(w, h, 71L))
        val warped = PanoramaMath.cylindricalWarp(rgb, w, h, PanoramaMath.cylindricalFocal(w))
        assertEquals(19, warped.width)
        assertEquals(h, warped.height)
        val cu = (warped.width - 1) / 2
        val cy = (h - 1) / 2
        val s = (cy * w + (w - 1) / 2) * 3
        val d = (cy * warped.width + cu) * 3
        for (c in 0..2) assertEquals(rgb[s + c], warped.data[d + c], 1e-5f)
        assertTrue(warped.valid[d / 3])
        val again = PanoramaMath.cylindricalWarp(rgb, w, h, PanoramaMath.cylindricalFocal(w))
        assertTrue(warped.data.contentEquals(again.data))
    }

    private fun sliceRgb(rgb: FloatArray, srcW: Int, h: Int, x0: Int, w: Int): FloatArray {
        val out = FloatArray(w * h * 3)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val s = (y * srcW + x0 + x) * 3
                val d = (y * w + x) * 3
                out[d] = rgb[s]
                out[d + 1] = rgb[s + 1]
                out[d + 2] = rgb[s + 2]
            }
        }
        return out
    }

    private fun canvasLuma(canvas: FloatArray, cw: Int, x: Int, y: Int): Float {
        val s = (y * cw + x) * 3
        return AlignMath.lumaOf(canvas[s], canvas[s + 1], canvas[s + 2])
    }

    @Test
    fun `panorama seam stays continuous on synthetic overlap`() {
        // Genuine pair construction: frames share an identical 16px overlap
        // strip O (A+O | O+B), placed at offsets 0 and 48. Blending must
        // reproduce O bit-near-exactly — that IS seam continuity.
        val w = 64
        val h = 32
        val full = grayRgb(noiseLuma(112, h, 81L))
        val f0 = sliceRgb(full, 112, h, 0, w)
        val f1 = sliceRgb(full, 112, h, 48, w)
        val offsets = listOf(0 to 0, 48 to 0)
        val feather = PanoramaMath.featherFor(w, 16)
        assertEquals(8, feather)
        val (canvas, cw, ch) = PanoramaMath.assembleCanvas(
            listOf(f0, f1), listOf(null, null), w, h, offsets,
            listOf(1f, 1f), feather
        )
        assertEquals(112, cw)
        assertEquals(32, ch)
        var worst = 0f
        for (y in 0 until ch) {
            for (x in 0 until cw) {
                val s = (y * 112 + x) * 3
                val d = (y * cw + x) * 3
                for (c in 0..2) worst = maxOf(worst, abs(canvas[d + c] - full[s + c]))
            }
        }
        assertTrue("blend alters identical content: $worst", worst < 1e-4f)
        val again = PanoramaMath.assembleCanvas(
            listOf(f0, f1), listOf(null, null), w, h, offsets,
            listOf(1f, 1f), feather
        )
        assertTrue(canvas.contentEquals(again.first))
    }

    @Test
    fun `gain match restores continuity across exposures`() {
        val w = 64
        val h = 32
        val full = grayRgb(noiseLuma(112, h, 91L))
        val f0 = sliceRgb(full, 112, h, 0, w)
        val f1 = scaled(sliceRgb(full, 112, h, 48, w), 0.5f)
        val l0 = AlignMath.rgbToLuma(f0, w, h)
        val l1 = AlignMath.rgbToLuma(f1, w, h)
        val gain = PanoramaMath.gainFactor(
            PanoramaMath.overlapMedian(l0, w, h, 48, 0, true),
            PanoramaMath.overlapMedian(l1, w, h, 48, 0, false)
        )
        assertEquals(2f, gain, 0.05f)
        val (canvas, cw, ch) = PanoramaMath.assembleCanvas(
            listOf(f0, f1), listOf(null, null), w, h, listOf(0 to 0, 48 to 0),
            listOf(1f, gain), 8
        )
        var worst = 0f
        for (y in 0 until ch) {
            for (x in 48 until 64) {
                worst = maxOf(worst, abs(canvasLuma(canvas, cw, x, y) - canvasLuma(f0, w, x, y)))
            }
        }
        assertTrue("gain seam step $worst", worst < 0.02f)
    }

    @Test
    fun `inscribed rect insets masked edges`() {
        val w = 10
        val h = 8
        val full = BooleanArray(w * h) { true }
        assertEquals(PixelRect(0, 0, w, h), PanoramaMath.inscribedRect(full, w, h))
        val masked = BooleanArray(w * h) { i -> i / w >= 2 }
        val rect = PanoramaMath.inscribedRect(masked, w, h)
        assertEquals(2, rect.top)
        assertEquals(h, rect.bottom)
        assertEquals(w, PanoramaMath.outputDims(rect).width)
        val cropped = PanoramaMath.cropToRect(FloatArray(w * h * 3) { 0.25f }, w, h, rect)
        assertEquals(w * (h - 2) * 3, cropped.size)
    }

    @Test
    fun `feather width clamps`() {
        assertEquals(8, PanoramaMath.featherFor(64, 4))
        assertEquals(250, PanoramaMath.featherFor(1000, 800))
    }

    // ---------- memory caps ----------

    @Test
    fun `resolution ladder caps absurd merges`() {
        assertEquals(2048, MergeLimits.hdrFinalEdge(64, 48, 2))
        assertEquals(2048, MergeLimits.panoFinalEdge(64, 48, 2))
        // Full-res working set never fits huge captures, so the ladder
        // steps down instead of OOMing; degenerate inputs refuse outright.
        assertEquals(1600, MergeLimits.hdrFinalEdge(4000, 3000, 3))
        assertEquals(1280, MergeLimits.hdrFinalEdge(12000, 8000, 6))
        assertTrue(!MergeLimits.hdrFitsBudget(12000, 8000, 2))
        assertNull(MergeLimits.hdrFinalEdge(0, 0, 2))
        assertNull(MergeLimits.hdrFinalEdge(64, 48, 0))
        assertTrue(MergeLimits.canvasFitsBudget(112, 32))
        assertTrue(!MergeLimits.canvasFitsBudget(100000, 100000))
    }

    // ---------- persistence / project integration ----------

    @Test
    fun `merged projects start from the default recipe`() {
        assertTrue(MergedRecipe.recipeFor(MergeKind.HDR).isDefault())
        assertTrue(MergedRecipe.recipeFor(MergeKind.PANORAMA).isDefault())
        val now = 123456789L
        val hdr = MergedRecipe.mergedProject("id-hdr", "/tmp/hdr_merge.jpg", 60, 40, MergeKind.HDR, now)
        assertTrue(MergedRecipe.recipeOf(hdr).isDefault())
        assertEquals(EditParams.DEFAULT, MergedRecipe.recipeOf(hdr))
        assertEquals("HDR", hdr.fileType)
        assertEquals("hdr_merge.jpg", hdr.name)
        val pano = MergedRecipe.mergedProject("id-pano", "/tmp/panorama.jpg", 112, 32, MergeKind.PANORAMA, now)
        assertEquals("PANO", pano.fileType)
        assertEquals("panorama.jpg", pano.name)
        assertTrue(MergedRecipe.recipeOf(pano).isDefault())
    }

    @Test
    fun `research pins native backends with no new deps`() {
        assertEquals("native-mertens", MergeResearch.HDR_BACKEND)
        assertEquals("native-feather", MergeResearch.PANORAMA_BACKEND)
        assertTrue(MergeResearch.DEPENDENCY_NOTE.contains("0 new dependencies"))
    }
}
