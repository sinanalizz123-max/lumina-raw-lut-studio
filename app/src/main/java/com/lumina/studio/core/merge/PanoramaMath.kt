package com.lumina.studio.core.merge

import com.lumina.studio.core.render.Dims
import com.lumina.studio.core.render.PixelRect
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.tan

/**
 * M17 panorama math (pure JVM, no android.*).
 *
 * Pipeline: pairwise translation estimate on cylindrical-warped luma
 * thumbnails -> overlap validation -> per-frame gain match on overlaps ->
 * feather-blend canvas assembly -> auto-crop to the max inscribed
 * all-valid rect. This is genuine stitching (shared-content alignment +
 * gain + seam blend + crop), NOT side-by-side placement.
 *
 * Honest limits (documented, not hidden):
 * - Translation-only with vertical-drift correction: rotation is NOT
 *   modeled. Tilts past ~±2° (or parallax from a non-nodal viewpoint)
 *   lower NCC and surface InsufficientOverlap instead of corrupt output.
 * - Cylindrical projection uses focal f = frame width (a moderate phone
 *   wide-lens approx; true focal needs calibration the app does not do).
 *   Narrow-FOV shots barely feel it; ultra-wide shots keep mild residual
 *   curvature that the feather blend absorbs at seams.
 * - Feather blend is a linear ramp across each overlap (documented
 *   approx, NOT multi-band): identical content stays seamless; strong
 *   parallax ghosts softly instead of tearing.
 * - Exposure match is ONE global gain per frame from overlap medians
 *   (documented approx, not spatially varying).
 *
 * Determinism: same conventions as [AlignMath]/[HdrMath]. Cancellation
 * via [checkCancel] (throw to abort).
 */
object PanoramaMath {
    data class Pairwise(val dx: Int, val dy: Int, val score: Float, val overlapFraction: Float)

    data class Warped(val data: FloatArray, val valid: BooleanArray, val width: Int, val height: Int)

    /** Overlap area / frame area for a shift on a w*h frame. */
    fun overlapFraction(width: Int, height: Int, dx: Int, dy: Int): Float {
        if (width <= 0 || height <= 0) return 0f
        val ow = (width - abs(dx)).coerceAtLeast(0)
        val oh = (height - abs(dy)).coerceAtLeast(0)
        return (ow.toLong() * oh.toLong()).toFloat() / (width.toLong() * height.toLong()).toFloat()
    }

    /**
     * Pairwise estimate between consecutive frames. Convention matches
     * [AlignMath]: mov aligns as mov(x - dx, y - dy). [maxShiftX/Y] bound
     * the search (wide in x for side-by-side chains, narrow in y: drift
     * correction, not rotation).
     */
    fun estimatePairwise(
        ref: FloatArray,
        mov: FloatArray,
        width: Int,
        height: Int,
        maxShiftX: Int,
        maxShiftY: Int,
        checkCancel: () -> Unit = {}
    ): Pairwise {
        val s = AlignMath.estimateShift(ref, mov, width, height, maxShiftX, maxShiftY, checkCancel)
        return Pairwise(s.dx, s.dy, s.score, overlapFraction(width, height, s.dx, s.dy))
    }

    /**
     * Overlap gate for pair [index]/[index+1]: null when stitchable, else
     * InsufficientOverlap with a specific, actionable message.
     */
    fun validatePairwise(index: Int, pair: Pairwise): MergeError? {
        if (pair.score < MergeLimits.MIN_OVERLAP_NCC) {
            return MergeError.InsufficientOverlap(
                "Photos ${index + 1} and ${index + 2} don't overlap enough to stitch " +
                    String.format(
                        java.util.Locale.US,
                        "(match score %.2f, need >= %.2f). ",
                        pair.score,
                        MergeLimits.MIN_OVERLAP_NCC
                    ) +
                    "Retake with ~30-50% shared content between neighbors."
            )
        }
        if (pair.overlapFraction < MergeLimits.MIN_OVERLAP_FRACTION) {
            return MergeError.InsufficientOverlap(
                "Photos ${index + 1} and ${index + 2} share too little area " +
                    String.format(
                        java.util.Locale.US,
                        "(overlap %.0f%%, need >= %.0f%%). ",
                        pair.overlapFraction * 100f,
                        MergeLimits.MIN_OVERLAP_FRACTION * 100f
                    ) +
                    "Retake with ~30-50% shared content between neighbors."
            )
        }
        return null
    }

    /**
     * Single global gain for mov so its overlap median matches ref's.
     * Clamped to [0.25, 4] (±2 EV): beyond that the pair is not a matching
     * exposure and blending would posterize — the clamp keeps output sane.
     */
    fun gainFactor(refOverlapMedian: Float, movOverlapMedian: Float): Float {
        val ref = refOverlapMedian.coerceIn(1e-6f, 1f)
        val mov = movOverlapMedian.coerceIn(1e-6f, 1f)
        return (ref / mov).coerceIn(0.25f, 4f)
    }

    /** Median luma of the shared interior for a shift (empty -> 0.5). */
    fun overlapMedian(
        luma: FloatArray,
        width: Int,
        height: Int,
        dx: Int,
        dy: Int,
        self: Boolean
    ): Float {
        if (width <= 0 || height <= 0) return 0.5f
        val x0: Int
        val y0: Int
        val x1: Int
        val y1: Int
        if (self) {
            x0 = maxOf(0, dx)
            x1 = minOf(width, width + dx)
            y0 = maxOf(0, dy)
            y1 = minOf(height, height + dy)
        } else {
            x0 = maxOf(0, -dx)
            x1 = minOf(width, width - dx)
            y0 = maxOf(0, -dy)
            y1 = minOf(height, height - dy)
        }
        val count = (x1 - x0).coerceAtLeast(0) * (y1 - y0).coerceAtLeast(0)
        if (count < MergeLimits.MIN_VALID_OVERLAP_PX) return 0.5f
        val samples = FloatArray(count)
        var n = 0
        for (y in y0 until y1) {
            for (x in x0 until x1) {
                samples[n++] = luma[y * width + x]
            }
        }
        return AlignMath.medianOf(samples)
    }

    /**
     * Cylindrical focal length (documented approx): frame width, i.e. a
     * moderate wide-lens guess without per-device calibration.
     */
    fun cylindricalFocal(width: Int): Float = width.coerceAtLeast(1).toFloat()

    /** Cylindrical output width for focal [focal]. */
    fun cylindricalWidth(width: Int, focal: Float): Int {
        if (width <= 0 || focal <= 0f) return width.coerceAtLeast(1)
        return maxOf(1, (2f * focal * atan(width.toDouble() / (2.0 * focal.toDouble())) + 0.5).toInt())
    }

    /**
     * Perspective -> cylindrical warp (forward-mapped output, bilinear
     * resample, edge-clamped validity). Center column maps to itself;
     * output height == input height; top/bottom edge rows may be invalid
     * where the cylinder curves away (marked in [Warped.valid]).
     */
    fun cylindricalWarp(
        rgb: FloatArray,
        width: Int,
        height: Int,
        focal: Float = cylindricalFocal(width),
        checkCancel: () -> Unit = {}
    ): Warped {
        require(width > 0 && height > 0) { "warp needs non-empty frames" }
        val f = if (focal.isFinite() && focal > 0f) focal.toDouble() else width.toDouble()
        val cx = (width - 1) / 2.0
        val cy = (height - 1) / 2.0
        val nw = cylindricalWidth(width, f.toFloat())
        val ncx = (nw - 1) / 2.0
        val out = FloatArray(nw * height * 3)
        val valid = BooleanArray(nw * height)
        for (y in 0 until height) {
            if (y % 32 == 0) checkCancel()
            val v = y - cy
            for (u in 0 until nw) {
                val theta = (u - ncx) / f
                val sx = cx + f * tan(theta)
                val cos = 1.0 / kotlin.math.sqrt(1.0 + (sx - cx) * (sx - cx) / (f * f))
                val sy = cy + v / cos
                val d = (y * nw + u)
                if (sx < 0.0 || sx > width - 1.0 || sy < 0.0 || sy > height - 1.0) {
                    valid[d] = false
                    continue
                }
                valid[d] = true
                val x0 = sx.toInt().coerceIn(0, width - 1)
                val x1 = (x0 + 1).coerceIn(0, width - 1)
                val y0 = sy.toInt().coerceIn(0, height - 1)
                val y1 = (y0 + 1).coerceIn(0, height - 1)
                val fx = (sx - x0).coerceIn(0.0, 1.0).toFloat()
                val fy = (sy - y0).coerceIn(0.0, 1.0).toFloat()
                for (c in 0..2) {
                    val v00 = rgb[(y0 * width + x0) * 3 + c]
                    val v10 = rgb[(y0 * width + x1) * 3 + c]
                    val v01 = rgb[(y1 * width + x0) * 3 + c]
                    val v11 = rgb[(y1 * width + x1) * 3 + c]
                    out[d * 3 + c] = (v00 * (1f - fx) + v10 * fx) * (1f - fy) +
                        (v01 * (1f - fx) + v11 * fx) * fy
                }
            }
        }
        return Warped(out, valid, nw, height)
    }

    /**
     * Feather ramp across an overlap of [overlapPx] (0..1 left->right).
     * Linear documented approx, NOT multi-band.
     */
    fun featherRamp(x: Int, overlapPx: Int): Float {
        if (overlapPx <= 1) return 1f
        return (x.toFloat() / (overlapPx - 1).toFloat()).coerceIn(0f, 1f)
    }

    /**
     * Feather width: half the smallest pair overlap, clamped to
     * [8, frameWidth/4]. Narrower than the overlap so flat (weight 1)
     * interiors survive; wider than 8px so the seam never steps.
     */
    fun featherFor(frameWidth: Int, minOverlapPx: Int): Int {
        if (frameWidth <= 0) return 8
        val cap = (frameWidth / 4).coerceAtLeast(8)
        return (minOverlapPx / 2).coerceIn(8, cap)
    }

    /**
     * Canvas assembly: places frames at cumulative [offsets] (frame 0 at
     * 0,0), applies per-frame [gains], feather-blends overlaps with a ramp
     * [featherPx] wide at each frame's left/right interior edge. Invalid
     * warp pixels ([valids], null = all valid) contribute zero weight.
     * Returns canvas RGB + canvas width/height via [Dims]; caller crops.
     */
    fun assembleCanvas(
        frames: List<FloatArray>,
        valids: List<BooleanArray?>,
        frameWidth: Int,
        frameHeight: Int,
        offsets: List<Pair<Int, Int>>,
        gains: List<Float>,
        featherPx: Int,
        checkCancel: () -> Unit = {},
        onProgress: (Float) -> Unit = {}
    ): Triple<FloatArray, Int, Int> {
        require(frames.size >= 2) { "panorama needs at least 2 frames" }
        val n = frames.size
        var minX = 0
        var minY = 0
        var maxX = frameWidth
        var maxY = frameHeight
        for (k in 0 until n) {
            val (ox, oy) = offsets[k]
            minX = minOf(minX, ox)
            minY = minOf(minY, oy)
            maxX = maxOf(maxX, ox + frameWidth)
            maxY = maxOf(maxY, oy + frameHeight)
        }
        val cw = maxX - minX
        val ch = maxY - minY
        require(cw > 0 && ch > 0) { "degenerate canvas" }
        if (!MergeLimits.canvasFitsBudget(cw, ch)) {
            throw IllegalStateException("panorama canvas ${cw}x$ch exceeds the device budget")
        }
        fun emit(f: Float) {
            runCatching { onProgress(f.coerceIn(0f, 1f)) }
        }
        val acc = FloatArray(cw * ch * 3)
        val wgt = FloatArray(cw * ch)
        val fpx = featherPx.coerceAtLeast(1)
        for (k in 0 until n) {
            checkCancel()
            val rgb = frames[k]
            val valid = valids[k]
            val gain = gains[k]
            val ox = offsets[k].first - minX
            val oy = offsets[k].second - minY
            for (y in 0 until frameHeight) {
                val cy = y + oy
                if (cy < 0 || cy >= ch) continue
                for (x in 0 until frameWidth) {
                    val cx = x + ox
                    if (cx < 0 || cx >= cw) continue
                    val si = y * frameWidth + x
                    if (valid != null && !valid[si]) continue
                    var a = 1f
                    val leftEdge = x
                    val rightEdge = frameWidth - 1 - x
                    if (k > 0 && leftEdge < fpx) a = minOf(a, featherRamp(leftEdge, fpx))
                    if (k < n - 1 && rightEdge < fpx) a = minOf(a, featherRamp(rightEdge, fpx))
                    if (a <= 0f) continue
                    val di = cy * cw + cx
                    val s3 = si * 3
                    val d3 = di * 3
                    acc[d3] += rgb[s3] * gain * a
                    acc[d3 + 1] += rgb[s3 + 1] * gain * a
                    acc[d3 + 2] += rgb[s3 + 2] * gain * a
                    wgt[di] += a
                }
            }
            emit((k + 1).toFloat() / n.toFloat() * 0.9f)
        }
        checkCancel()
        for (i in 0 until cw * ch) {
            val wsum = wgt[i]
            val d3 = i * 3
            if (wsum > 1e-9f) {
                acc[d3] = (acc[d3] / wsum).coerceIn(0f, 1f)
                acc[d3 + 1] = (acc[d3 + 1] / wsum).coerceIn(0f, 1f)
                acc[d3 + 2] = (acc[d3 + 2] / wsum).coerceIn(0f, 1f)
            }
        }
        emit(1f)
        return Triple(acc, cw, ch)
    }

    /**
     * Max inscribed all-valid rect of the canvas coverage [valid] (union of
     * placed frame masks). Conservative: top = max over columns of first
     * valid y; bottom = min over columns of last valid y; same for rows.
     * Falls back to the full canvas when nothing qualifies (caller then
     * ships the union box — documented, never empty output).
     */
    fun inscribedRect(valid: BooleanArray, width: Int, height: Int): PixelRect {
        if (width <= 0 || height <= 0 || valid.size < width * height) {
            return PixelRect(0, 0, width.coerceAtLeast(1), height.coerceAtLeast(1))
        }
        var top = 0
        var bottom = height
        var left = 0
        var right = width
        var anyValid = false
        for (x in 0 until width) {
            var first = -1
            var last = -1
            for (y in 0 until height) {
                if (valid[y * width + x]) {
                    if (first < 0) first = y
                    last = y
                }
            }
            // Empty columns/rows cannot belong to the rect but must not veto
            // the inset (panorama canvases legitimately have empty borders).
            if (first < 0) continue
            anyValid = true
            if (first > top) top = first
            if (last + 1 < bottom) bottom = last + 1
        }
        for (y in 0 until height) {
            var first = -1
            var last = -1
            for (x in 0 until width) {
                if (valid[y * width + x]) {
                    if (first < 0) first = x
                    last = x
                }
            }
            if (first < 0) continue
            anyValid = true
            if (first > left) left = first
            if (last + 1 < right) right = last + 1
        }
        if (!anyValid) return PixelRect(0, 0, width, height)
        if (right <= left || bottom <= top) return PixelRect(0, 0, width, height)
        return PixelRect(left, top, right, bottom)
    }

    fun cropToRect(canvas: FloatArray, cw: Int, ch: Int, rect: PixelRect): FloatArray {
        val l = rect.left.coerceIn(0, cw)
        val t = rect.top.coerceIn(0, ch)
        val r = rect.right.coerceIn(l, cw)
        val b = rect.bottom.coerceIn(t, ch)
        val ow = (r - l).coerceAtLeast(1)
        val oh = (b - t).coerceAtLeast(1)
        val out = FloatArray(ow * oh * 3)
        for (y in 0 until oh) {
            for (x in 0 until ow) {
                val s = ((y + t) * cw + (x + l)) * 3
                val d = (y * ow + x) * 3
                out[d] = canvas[s]
                out[d + 1] = canvas[s + 1]
                out[d + 2] = canvas[s + 2]
            }
        }
        return out
    }

    fun outputDims(rect: PixelRect): Dims = Dims(rect.width, rect.height)
}
