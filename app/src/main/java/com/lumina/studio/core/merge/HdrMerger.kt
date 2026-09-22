package com.lumina.studio.core.merge

import android.graphics.Bitmap
import com.lumina.studio.core.render.Dims
import com.lumina.studio.core.render.RenderResult
import com.lumina.studio.core.util.ExifReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/**
 * M17 HDR merge service contract. Results reuse [RenderResult] (the same
 * Ok/Unavailable/OomBudget vocabulary as [com.lumina.studio.core.render.RenderBackend]),
 * so merge plugs into the existing render/error architecture instead of
 * inventing its own.
 */
interface HdrMerger {
    suspend fun merge(
        inputs: List<MergeInput>,
        progress: MergeProgress = MergeProgress { }
    ): RenderResult<Bitmap>
}

/**
 * Native Mertens HDR merge (Android edge; math in [HdrMath]/[AlignMath]/
 * [ExposureEv], all JVM-pinned).
 *
 * Flow: header/dims validation -> EXIF EVs (else median-luma fallback,
 * uniformly) -> capped decode (resolution ladder) -> 256px-thumbnail
 * translation align vs frame 0 -> deghost-lite Mertens fusion w/ true
 * 2-level Laplacian pyramid -> RGBA_F16 base. Cooperative cancellation
 * (CancellationException propagates); OOM anywhere maps to OomBudget;
 * validation failures map to Unavailable with the specific [MergeError]
 * message; a project is NEVER created here (the ViewModel stores the
 * Ok bitmap via ProjectStore, like a camera capture).
 */
object AndroidHdrMerger : HdrMerger {
    override suspend fun merge(
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
            val edge = MergeLimits.hdrFinalEdge(ref.width, ref.height, frames)
                ?: return@withContext RenderResult.OomBudget
            progress.emit(0.08f)

            val exifEvs = inputs.mapIndexed { i, input ->
                input.exposureEv?.toDouble()
                    ?: runCatching {
                        val info = ExifReader.read(files[i])
                        ExposureEv.evIndexFromExifStrings(info.shutter, info.iso, info.aperture)
                    }.getOrNull()
            }
            val useExif = exifEvs.all { it != null }

            val target = MergeDecode.scaledDims(ref.width, ref.height, edge)
            val decoded = ArrayList<Bitmap>(frames)
            try {
                for (i in files.indices) {
                    ensureActive()
                    var bmp = MergeDecode.decodeAtEdge(files[i], edge)
                        ?: return@withContext RenderResult.Unavailable(
                            MergeError.DecodeFailed(i, "decoder returned no pixels.").message
                        )
                    bmp = MergeDecode.exactSize(bmp, target.width, target.height)
                    if (bmp.width != target.width || bmp.height != target.height) {
                        MergeDecode.recycleQuietly(bmp)
                        return@withContext RenderResult.Unavailable(
                            MergeError.DecodeFailed(i, "decoded size mismatch.").message
                        )
                    }
                    decoded.add(bmp)
                    progress.emit(0.08f + 0.22f * (i + 1).toFloat() / frames.toFloat())
                }

                val w = target.width
                val h = target.height
                val rgbFrames = ArrayList<FloatArray>(frames)
                val lumas = ArrayList<FloatArray>(frames)
                for (i in decoded.indices) {
                        ensureActive()
                        val rgb = MergeDecode.floatRgb(decoded[i])
                        MergeDecode.recycleQuietly(decoded[i])
                        rgbFrames.add(rgb)
                        lumas.add(AlignMath.rgbToLuma(rgb, w, h))
                        progress.emit(0.30f + 0.10f * (i + 1).toFloat() / frames.toFloat())
                    }

                    val evs: List<Float> = if (useExif) {
                        exifEvs.map { it!!.toFloat() }
                    } else {
                        ExposureEv.relativeEvsFromLuma(lumas.map { AlignMath.medianOf(it) })
                    }
                    ExposureEv.validateSpread(ExposureEv.spreadF(evs))?.let {
                        return@withContext RenderResult.Unavailable(it.message)
                    }

                    val thumbs = lumas.map { AlignMath.toThumb(it, w, h, MergeLimits.ALIGN_THUMB_EDGE) }
                    val tw = thumbs[0].width
                    val th = thumbs[0].height
                    val mx = AlignMath.shiftRangeFor(MergeLimits.HDR_SHIFT_FRACTION, maxOf(tw, th))
                    val my = AlignMath.shiftRangeFor(MergeLimits.HDR_SHIFT_FRACTION, maxOf(tw, th))
                    val scaleX = w.toFloat() / tw.toFloat()
                    val scaleY = h.toFloat() / th.toFloat()
                    val shifts = ArrayList<AlignMath.Shift>(frames)
                    shifts.add(AlignMath.Shift(0, 0, 1f))
                    for (i in 1 until frames) {
                        ensureActive()
                        val s = AlignMath.estimateShift(
                            thumbs[0].data, thumbs[i].data, tw, th, mx, my
                        ) { ensureActive() }
                        AlignMath.checkConfidence(s.score)?.let {
                            return@withContext RenderResult.Unavailable(it.message)
                        }
                        shifts.add(
                            AlignMath.Shift(
                                (s.dx * scaleX).roundToInt(),
                                (s.dy * scaleY).roundToInt(),
                                s.score
                            )
                        )
                        progress.emit(0.40f + 0.10f * i.toFloat() / (frames - 1).toFloat())
                    }

                    val aligned = ArrayList<FloatArray>(frames)
                    for (i in 0 until frames) {
                        val s = shifts[i]
                        aligned.add(
                            if (s.dx == 0 && s.dy == 0) rgbFrames[i]
                            else AlignMath.applyShiftRgb(rgbFrames[i], w, h, s.dx, s.dy)
                        )
                    }
                    rgbFrames.clear()

                    val fused = MergeFrames(w, h, aligned, evs).use { owned ->
                        HdrMath.fuse(
                            owned,
                            checkCancel = { ensureActive() },
                            onProgress = { f -> progress.emit(0.50f + 0.45f * f) }
                        )
                    }
                    ensureActive()
                    val pixels = MergeDecode.argbFromFloat(fused)
                    val out = MergeDecode.storeMerged(w, h, pixels)
                    progress.emit(1f)
                    RenderResult.Ok(out)
            } finally {
                decoded.forEach { MergeDecode.recycleQuietly(it) }
            }
        } catch (e: OutOfMemoryError) {
            RenderResult.OomBudget
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            RenderResult.Unavailable(e.message ?: "HDR merge failed.")
        }
    }
}
