package com.lumina.studio.core.merge

import android.graphics.Bitmap
import com.lumina.studio.core.render.Dims
import com.lumina.studio.core.render.RenderResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/**
 * M17 panorama stitch service contract. Results reuse [RenderResult] like
 * [HdrMerger], so both merge services share the render/error vocabulary.
 */
interface PanoramaStitcher {
    suspend fun stitch(
        inputs: List<MergeInput>,
        progress: MergeProgress = MergeProgress { }
    ): RenderResult<Bitmap>
}

/**
 * Native panorama stitch (Android edge; math in [PanoramaMath]/[AlignMath],
 * all JVM-pinned).
 *
 * Flow: header/dims validation -> capped decode (resolution ladder) ->
 * cylindrical warp per frame -> pairwise translation estimate on warped
 * luma thumbnails (wide-x/narrow-y search) -> overlap gate per pair ->
 * chained exposure-gain match on overlaps -> feather-blend canvas ->
 * auto-crop to the max inscribed all-valid rect -> RGBA_F16 base.
 * Same cancellation/OOM/validation contract as [AndroidHdrMerger]; a
 * project is NEVER created here.
 */
object AndroidPanoramaStitcher : PanoramaStitcher {
    override suspend fun stitch(
        inputs: List<MergeInput>,
        progress: MergeProgress
    ): RenderResult<Bitmap> = withContext(Dispatchers.Default) {
        if (inputs.size < MergeLimits.MIN_FRAMES) {
            return@withContext RenderResult.Unavailable(
                MergeError.TooFew(inputs.size, MergeLimits.MIN_FRAMES).message
            )
        }
        if (inputs.size > MergeLimits.MAX_FRAMES) {
            return@withContext RenderResult.Unavailable(
                MergeError.TooMany(inputs.size, MergeLimits.MAX_FRAMES).message
            )
        }
        try {
            ensureActive()
            progress.emit(0.02f)
            val files = inputs.map { File(it.path) }
            for (i in files.indices) {
                val headerError = MergeBytes.validateHeader(
                    MergeDecode.headerOf(files[i]), i, files[i].length()
                )
                if (headerError != null) {
                    return@withContext RenderResult.Unavailable(headerError.message)
                }
            }
            val dims = ArrayList<Dims>(files.size)
            for (i in files.indices) {
                ensureActive()
                val d = MergeDecode.orientedDims(files[i])
                    ?: return@withContext RenderResult.Unavailable(
                        MergeError.DecodeFailed(i, "no image dimensions found.").message
                    )
                dims.add(d)
            }
            validateFrameList(dims)?.let {
                return@withContext RenderResult.Unavailable(it.message)
            }
            val ref = dims[0]
            val frames = inputs.size
            val edge = MergeLimits.panoFinalEdge(ref.width, ref.height, frames)
                ?: return@withContext RenderResult.OomBudget
            if (!MergeLimits.panoFitsBudget(
                    MergeDecode.scaledDims(ref.width, ref.height, edge).let {
                        it.width.toLong() * it.height.toLong()
                    },
                    frames
                )
            ) {
                return@withContext RenderResult.OomBudget
            }
            progress.emit(0.06f)

            val target = MergeDecode.scaledDims(ref.width, ref.height, edge)
            val warped = ArrayList<PanoramaMath.Warped>(frames)
            val decoded = ArrayList<Bitmap>(frames)
            try {
                for (i in files.indices) {
                    ensureActive()
                    var bmp = MergeDecode.decodeAtEdge(files[i], edge)
                        ?: return@withContext RenderResult.Unavailable(
                            MergeError.DecodeFailed(i, "decoder returned no pixels.").message
                        )
                    bmp = MergeDecode.exactSize(bmp, target.width, target.height)
                    decoded.add(bmp)
                    progress.emit(0.06f + 0.18f * (i + 1).toFloat() / frames.toFloat())
                }
                val w = target.width
                val h = target.height
                val focal = PanoramaMath.cylindricalFocal(w)
                for (i in decoded.indices) {
                    ensureActive()
                    val rgb = MergeDecode.floatRgb(decoded[i])
                    MergeDecode.recycleQuietly(decoded[i])
                    warped.add(
                        PanoramaMath.cylindricalWarp(rgb, w, h, focal) { ensureActive() }
                    )
                    progress.emit(0.24f + 0.12f * (i + 1).toFloat() / frames.toFloat())
                }
                decoded.clear()

                val ww = warped[0].width
                val wh = warped[0].height
                val lumas = warped.map { AlignMath.rgbToLuma(it.data, ww, wh) }
                val thumbs = lumas.map { AlignMath.toThumb(it, ww, wh, MergeLimits.ALIGN_THUMB_EDGE) }
                val tw = thumbs[0].width
                val th = thumbs[0].height
                val mx = AlignMath.shiftRangeFor(MergeLimits.PANO_SHIFT_X_FRACTION, tw)
                val my = AlignMath.shiftRangeFor(MergeLimits.PANO_SHIFT_Y_FRACTION, th)
                val scaleX = ww.toFloat() / tw.toFloat()
                val scaleY = wh.toFloat() / th.toFloat()

                val pairs = ArrayList<PanoramaMath.Pairwise>(frames - 1)
                for (i in 0 until frames - 1) {
                    ensureActive()
                    val s = PanoramaMath.estimatePairwise(
                        thumbs[i].data, thumbs[i + 1].data, tw, th, mx, my
                    ) { ensureActive() }
                    val full = PanoramaMath.Pairwise(
                        (s.dx * scaleX).roundToInt(),
                        (s.dy * scaleY).roundToInt(),
                        s.score,
                        PanoramaMath.overlapFraction(ww, wh, (s.dx * scaleX).roundToInt(), (s.dy * scaleY).roundToInt())
                    )
                    PanoramaMath.validatePairwise(i, full)?.let {
                        return@withContext RenderResult.Unavailable(it.message)
                    }
                    pairs.add(full)
                    progress.emit(0.36f + 0.12f * (i + 1).toFloat() / (frames - 1).toFloat())
                }

                val gains = ArrayList<Float>(frames)
                gains.add(1f)
                for (i in 1 until frames) {
                    ensureActive()
                    val d = pairs[i - 1]
                    val refMed = PanoramaMath.overlapMedian(lumas[i - 1], ww, wh, d.dx, d.dy, true)
                    val movMed = PanoramaMath.overlapMedian(lumas[i], ww, wh, d.dx, d.dy, false)
                    val step = PanoramaMath.gainFactor(refMed, movMed)
                    gains.add((gains[i - 1] * step).coerceIn(0.25f, 4f))
                }

                val offsets = ArrayList<Pair<Int, Int>>(frames)
                offsets.add(0 to 0)
                for (i in 1 until frames) {
                    val prev = offsets[i - 1]
                    val d = pairs[i - 1]
                    offsets.add(prev.first + d.dx to prev.second + d.dy)
                }
                var minX = 0
                var minY = 0
                var maxX = ww
                var maxY = wh
                var minOverlapW = ww
                for (i in 0 until frames) {
                    val (ox, oy) = offsets[i]
                    minX = minOf(minX, ox)
                    minY = minOf(minY, oy)
                    maxX = maxOf(maxX, ox + ww)
                    maxY = maxOf(maxY, oy + wh)
                    if (i > 0) {
                        minOverlapW = minOf(minOverlapW, ww - kotlin.math.abs(pairs[i - 1].dx))
                    }
                }
                if (!MergeLimits.canvasFitsBudget(maxX - minX, maxY - minY)) {
                    return@withContext RenderResult.OomBudget
                }
                val feather = PanoramaMath.featherFor(ww, minOverlapW)
                progress.emit(0.50f)

                val framesData = warped.map { it.data }
                val valids: List<BooleanArray?> = warped.map { it.valid }
                val (canvas, cw, ch) = try {
                    PanoramaMath.assembleCanvas(
                        framesData, valids, ww, wh, offsets, gains, feather,
                        checkCancel = { ensureActive() },
                        onProgress = { f -> progress.emit(0.50f + 0.35f * f) }
                    )
                } catch (e: IllegalStateException) {
                    if (e is CancellationException) throw e
                    return@withContext RenderResult.OomBudget
                }
                ensureActive()
                progress.emit(0.88f)

                val union = BooleanArray(cw * ch)
                for (i in 0 until frames) {
                    val valid = warped[i].valid
                    val ox = offsets[i].first - minX
                    val oy = offsets[i].second - minY
                    for (y in 0 until wh) {
                        val cy = y + oy
                        if (cy < 0 || cy >= ch) continue
                        for (x in 0 until ww) {
                            if (!valid[y * ww + x]) continue
                            val cx = x + ox
                            if (cx < 0 || cx >= cw) continue
                            union[cy * cw + cx] = true
                        }
                    }
                }
                val rect = PanoramaMath.inscribedRect(union, cw, ch)
                val cropped = PanoramaMath.cropToRect(canvas, cw, ch, rect)
                val ow = rect.width.coerceAtLeast(1)
                val oh = rect.height.coerceAtLeast(1)
                ensureActive()
                val pixels = MergeDecode.argbFromFloat(cropped)
                val out = MergeDecode.storeMerged(ow, oh, pixels)
                progress.emit(1f)
                RenderResult.Ok(out)
            } finally {
                decoded.forEach { MergeDecode.recycleQuietly(it) }
            }
        } catch (e: OutOfMemoryError) {
            RenderResult.OomBudget
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            RenderResult.Unavailable(e.message ?: "Panorama stitch failed.")
        }
    }
}
