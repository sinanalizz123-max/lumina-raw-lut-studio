package com.lumina.studio.core.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import com.lumina.studio.core.edit.CropParams
import com.lumina.studio.core.edit.CurveChannel
import com.lumina.studio.core.edit.Curves
import com.lumina.studio.core.edit.EditMask
import com.lumina.studio.core.edit.EditParams
import com.lumina.studio.core.edit.HslColor
import com.lumina.studio.core.edit.MaskTool
import com.lumina.studio.core.edit.StepKey
import com.lumina.studio.core.lut.LutCube
import com.lumina.studio.core.lut.LutRenderer
import com.lumina.studio.core.util.ImageOrientation
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

object PreviewRenderer {
    const val MAX_PREVIEW_DIM = 1600
    const val HISTOGRAM_BINS = 64
    // Phase 4B: performance-gated preview decode. The GPU-acceleration toggle
    // (SettingsRepository.gpuAcceleration) chooses the decode path:
    // - ON (default): the preview-quality setting maps directly to decodePreview
    //   maxDim (High 1600 / Medium 1200 / Low 800) and histogram auto-recompute
    //   on every render stays enabled.
    // - OFF: decode is capped at GPU_OFF_MAX_PREVIEW_DIM (1200px) regardless of
    //   the quality setting, and callers skip histogram auto-compute via
    //   shouldAutoHistogram() (explicit user requests still compute).
    const val PREVIEW_MAX_HIGH = 1600
    const val PREVIEW_MAX_MEDIUM = 1200
    const val PREVIEW_MAX_LOW = 800
    const val GPU_OFF_MAX_PREVIEW_DIM = 1200
    const val LARGE_IMAGE_PIXELS = 8_000_000
    private const val HISTOGRAM_SAMPLE_CAP = 60_000
    private const val HSL_HALF_WIDTH_DEG = 35f
    private const val HSL_HUE_DEG_PER_UNIT = 0.3f
    private const val HSL_LUM_DELTA_PER_UNIT = 0.0025f

    // Zoom-tile perf: per-params-revision cache of the sampled 256-entry curve
    // LUTs. Tile renders reuse the preview render's tables instead of
    // re-sampling Catmull-Rom per tile; keyed on the curves map instance
    // content (EditParams data-class equality). Guarded by lock because
    // preview + tile + fullscreen renders run on concurrent Default workers.
    private val curvesCacheLock = Any()
    private var cachedCurvesKey: Map<CurveChannel, List<com.lumina.studio.core.edit.CurvePoint>>? = null
    private var cachedCurveLuts: Map<CurveChannel, FloatArray?> = emptyMap()

    internal fun curveLutsFor(params: EditParams): Map<CurveChannel, FloatArray?> {
        val key = params.curves
        synchronized(curvesCacheLock) {
            if (key == cachedCurvesKey && cachedCurveLuts.isNotEmpty()) return cachedCurveLuts
        }
        val computed = CurveChannel.entries.associateWith { channel ->
            if (params.isCurveDiagonal(channel)) null
            else Curves.sampleCurve(params.getCurve(channel))
        }
        synchronized(curvesCacheLock) {
            cachedCurvesKey = key
            cachedCurveLuts = computed
        }
        return computed
    }

    fun isIdentity(params: EditParams, hasLut: Boolean = false): Boolean =
        params.isDefault() && !hasLut

    // Render order: LUT -> adjusts -> HSL -> curves -> details -> masks -> crop.
    // Export uses this same function via Exporter.renderForExport so preview matches export.
    // Perf budget: <100ms on <=1600px previews via early-outs per stage; per-pixel
    // HSL/curves passes run only when their groups are non-default. Tradeoff: HSL
    // per-pixel RGB<->HSL on Kotlin is slower than a GPU matrix but stays accurate
    // for selective color on preview sizes; global sat/vib stays on a fast matrix.
    // Details preview: texture/clarityAdv/dehazeAdv via fast matrix; sharpen is
    // skipped in preview (contrast-on-edges needs full-res Phase 4 export path);
    // luma NR is a cheap native downscale-upscale blur; color NR is preview no-op
    // (subtle chroma smoothing, full quality in Phase 4 export).
    // Masks run after curves/details and before crop on preview-size bitmaps:
    // radial/linear use analytic formulae per pixel; brush/eraser rasterize circles
    // along the polyline with feather falloff and a bounding-box fast path so the
    // common non-inverted case touches only the stroke area. Local exposure/temp
    // blend as out = base*(1-a) + graded*a. Crop runs last as a pure function.
    // Each stage is skipped when its group is default or its history step is
    // disabled, keeping the <100ms budget.
    fun render(src: Bitmap, params: EditParams, lut: LutCube? = null): Bitmap {
        val steps = params.steps
        val useLut = lut != null && params.presetId != null && params.presetIntensity > 0f &&
            steps.get(StepKey.PRESET) && steps.get(StepKey.LUT)
        val graded = if (useLut) {
            LutRenderer.applyLut(src, lut, params.presetIntensity)
        } else {
            src
        }
        if (params.isDefault()) return graded
        var current = graded
        if (!params.isAdjustsDefault() && steps.get(StepKey.ADJUSTS)) {
            val combined = buildMatrix(params)
            val out = applyMatrix(current, combined)
            if (out !== current) {
                if (current !== src) current.recycle()
                current = out
            }
        }
        if (!params.isHslDefault() && steps.get(StepKey.COLOR)) {
            if (params.globalSat != 0f || params.globalVib != 0f) {
                val saturation = (1f + params.globalSat / 100f + params.globalVib / 200f)
                    .coerceIn(0f, 3f)
                if (saturation != 1f) {
                    val cm = ColorMatrix().apply { setSaturation(saturation) }
                    val out = applyMatrix(current, cm)
                    if (out !== current) {
                        if (current !== src) current.recycle()
                        current = out
                    }
                }
            }
            if (params.hasPerColorHsl()) {
                val out = applyHslPerColor(current, params)
                if (out !== current) {
                    if (current !== src) current.recycle()
                    current = out
                }
            }
        }
        if (!params.isCurvesDefault() && steps.get(StepKey.CURVES)) {
            val out = applyCurves(current, params)
            if (out !== current) {
                if (current !== src) current.recycle()
                current = out
            }
        }
        if (!params.isDetailsDefault() && steps.get(StepKey.DETAILS)) {
            val out = applyDetails(current, src, params)
            if (out !== current) {
                if (current !== src) current.recycle()
                current = out
            }
        }
        if (params.masks.isNotEmpty() && steps.get(StepKey.MASKS)) {
            val out = applyMasks(current, params)
            if (out !== current) {
                if (current !== src) current.recycle()
                current = out
            }
        }
        if (!params.crop.isDefault() && steps.get(StepKey.CROP)) {
            val out = cropBitmap(current, params.crop)
            if (out !== current) {
                if (current !== src) current.recycle()
                current = out
            }
        }
        return current
    }

    private fun applyMatrix(src: Bitmap, matrix: ColorMatrix): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val paint = Paint().apply {
            colorFilter = ColorMatrixColorFilter(matrix)
            isFilterBitmap = true
        }
        Canvas(out).drawBitmap(src, 0f, 0f, paint)
        return out
    }

    fun applyHslPerColor(src: Bitmap, params: EditParams): Bitmap {
        val w = src.width
        val h = src.height
        if (w <= 0 || h <= 0) return src
        val active = HslColor.entries.filter { params.getHsl(it) != com.lumina.studio.core.edit.HslAdjust() }
        if (active.isEmpty()) return src
        val hues = FloatArray(active.size) { params.getHsl(active[it]).hue }
        val sats = FloatArray(active.size) { params.getHsl(active[it]).sat }
        val lums = FloatArray(active.size) { params.getHsl(active[it]).lum }
        val centers = FloatArray(active.size) { active[it].centerHueDeg }
        val hasLumAdjust = lums.any { it != 0f }
        val total = w * h
        val pixels = IntArray(total)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r0 = ((p shr 16) and 0xFF) / 255f
            val g0 = ((p shr 8) and 0xFF) / 255f
            val b0 = (p and 0xFF) / 255f
            val hsl = rgbToHsl(r0, g0, b0)
            var hDeg = hsl[0]
            var s = hsl[1]
            var l = hsl[2]
            if (s > 0.001f) {
                var hueShift = 0f
                var satFactor = 0f
                var lumDelta = 0f
                for (k in active.indices) {
                    var d = kotlin.math.abs(hDeg - centers[k])
                    if (d > 180f) d = 360f - d
                    val weight = (1f - d / HSL_HALF_WIDTH_DEG).coerceIn(0f, 1f)
                    if (weight <= 0f) continue
                    hueShift += weight * hues[k] * HSL_HUE_DEG_PER_UNIT
                    satFactor += weight * sats[k] / 100f
                    lumDelta += weight * lums[k] * HSL_LUM_DELTA_PER_UNIT
                }
                if (hueShift != 0f || satFactor != 0f || lumDelta != 0f) {
                    hDeg = ((hDeg + hueShift) % 360f + 360f) % 360f
                    s = (s * (1f + satFactor)).coerceIn(0f, 1f)
                    l = (l + lumDelta).coerceIn(0f, 1f)
                    val rgb = hslToRgb(hDeg, s, l)
                    val a = p ushr 24
                    pixels[i] = (a shl 24) or
                        ((rgb[0] * 255f + 0.5f).toInt().coerceIn(0, 255) shl 16) or
                        ((rgb[1] * 255f + 0.5f).toInt().coerceIn(0, 255) shl 8) or
                        (rgb[2] * 255f + 0.5f).toInt().coerceIn(0, 255)
                }
            } else if (hasLumAdjust) {
                var lumDelta = 0f
                for (k in active.indices) {
                    var d = kotlin.math.abs(hDeg - centers[k])
                    if (d > 180f) d = 360f - d
                    val weight = (1f - d / HSL_HALF_WIDTH_DEG).coerceIn(0f, 1f)
                    if (weight <= 0f) continue
                    lumDelta += weight * lums[k] * HSL_LUM_DELTA_PER_UNIT
                    if (sats[k] > 0f) {
                        s = (s + weight * sats[k] / 100f * 0.1f).coerceIn(0f, 1f)
                    }
                }
                if (lumDelta != 0f || s != hsl[1]) {
                    l = (l + lumDelta).coerceIn(0f, 1f)
                    val rgb = hslToRgb(hDeg, s, l)
                    val a = p ushr 24
                    pixels[i] = (a shl 24) or
                        ((rgb[0] * 255f + 0.5f).toInt().coerceIn(0, 255) shl 16) or
                        ((rgb[1] * 255f + 0.5f).toInt().coerceIn(0, 255) shl 8) or
                        (rgb[2] * 255f + 0.5f).toInt().coerceIn(0, 255)
                }
            }
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }

    fun applyCurves(src: Bitmap, params: EditParams): Bitmap {
        val w = src.width
        val h = src.height
        if (w <= 0 || h <= 0) return src
        val masterDiag = params.isCurveDiagonal(CurveChannel.MASTER)
        val redDiag = params.isCurveDiagonal(CurveChannel.RED)
        val greenDiag = params.isCurveDiagonal(CurveChannel.GREEN)
        val blueDiag = params.isCurveDiagonal(CurveChannel.BLUE)
        if (masterDiag && redDiag && greenDiag && blueDiag) return src
        val cached = curveLutsFor(params)
        val masterLut = cached[CurveChannel.MASTER]
        val redLut = cached[CurveChannel.RED]
        val greenLut = cached[CurveChannel.GREEN]
        val blueLut = cached[CurveChannel.BLUE]
        val total = w * h
        val pixels = IntArray(total)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        for (i in pixels.indices) {
            val p = pixels[i]
            val a = p ushr 24
            var r = (p shr 16) and 0xFF
            var g = (p shr 8) and 0xFF
            var b = p and 0xFF
            if (masterLut != null) {
                r = (masterLut[r] * 255f + 0.5f).toInt().coerceIn(0, 255)
                g = (masterLut[g] * 255f + 0.5f).toInt().coerceIn(0, 255)
                b = (masterLut[b] * 255f + 0.5f).toInt().coerceIn(0, 255)
            }
            if (redLut != null) r = (redLut[r] * 255f + 0.5f).toInt().coerceIn(0, 255)
            if (greenLut != null) g = (greenLut[g] * 255f + 0.5f).toInt().coerceIn(0, 255)
            if (blueLut != null) b = (blueLut[b] * 255f + 0.5f).toInt().coerceIn(0, 255)
            pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }

    fun applyDetails(src: Bitmap, originalSrc: Bitmap, params: EditParams): Bitmap {
        var current = src
        val hasMicro = params.texture != 0f || params.clarityAdv != 0f || params.dehazeAdv != 0f
        if (hasMicro) {
            val contrastFactor = (1f + (params.texture * 0.15f + params.clarityAdv * 0.25f + params.dehazeAdv * 0.35f) / 100f)
                .coerceIn(0f, 4f)
            val saturation = (1f + params.dehazeAdv / 300f).coerceIn(0f, 3f)
            val result = ColorMatrix()
            val pivot = (1f - contrastFactor) * 128f
            result.postConcat(
                ColorMatrix(
                    floatArrayOf(
                        contrastFactor, 0f, 0f, 0f, pivot,
                        0f, contrastFactor, 0f, 0f, pivot,
                        0f, 0f, contrastFactor, 0f, pivot,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
            )
            if (saturation != 1f) {
                result.postConcat(ColorMatrix().apply { setSaturation(saturation) })
            }
            val out = applyMatrix(current, result)
            if (out !== current) {
                if (current !== originalSrc) current.recycle()
                current = out
            }
        }
        if (params.nrLuma > 0f) {
            val strength = (params.nrLuma / 100f).coerceIn(0f, 1f)
            if (strength > 0f) {
                val w = current.width
                val h = current.height
                if (w > 2 && h > 2) {
                    val factor = (1f - strength * 0.5f).coerceIn(0.5f, 1f)
                    val smallW = (w * factor + 0.5f).toInt().coerceIn(1, w)
                    val smallH = (h * factor + 0.5f).toInt().coerceIn(1, h)
                    if (smallW < w || smallH < h) {
                        try {
                            val small = Bitmap.createScaledBitmap(current, smallW, smallH, true)
                            val blurred = Bitmap.createScaledBitmap(small, w, h, true)
                            if (!small.isRecycled) small.recycle()
                            if (current !== originalSrc) current.recycle()
                            current = blurred
                        } catch (_: Exception) {
                        }
                    }
                }
            }
        }
        return current
    }

    fun cropBitmap(src: Bitmap, crop: CropParams): Bitmap {
        if (crop.isDefault()) return src
        val w0 = src.width
        val h0 = src.height
        if (w0 <= 0 || h0 <= 0) return src
        return try {
            var current = src
            fun replace(next: Bitmap) {
                if (next !== current && current !== src) {
                    try {
                        current.recycle()
                    } catch (_: Exception) {
                    }
                }
                current = next
            }
            if (crop.rotationSteps != 0) {
                val steps = ((crop.rotationSteps % 4) + 4) % 4
                if (steps != 0) {
                    val matrix = Matrix()
                    matrix.postRotate(steps * 90f)
                    val rotated = Bitmap.createBitmap(current, 0, 0, current.width, current.height, matrix, true)
                    replace(rotated)
                }
            }
            if (crop.straightenDeg != 0f) {
                val deg = crop.straightenDeg.coerceIn(-45f, 45f)
                if (deg != 0f) {
                    val bw = current.width
                    val bh = current.height
                    if (bw > 0 && bh > 0) {
                        val matrix = Matrix()
                        matrix.postRotate(deg, bw / 2f, bh / 2f)
                        val rotated = Bitmap.createBitmap(current, 0, 0, bw, bh, matrix, true)
                        val rad = kotlin.math.abs(deg) * Math.PI.toFloat() / 180f
                        val c = cos(rad).coerceIn(0.5f, 1f)
                        val validW = (bw * c + 0.5f).toInt().coerceIn(1, rotated.width)
                        val validH = (bh * c + 0.5f).toInt().coerceIn(1, rotated.height)
                        val left = ((rotated.width - validW) / 2).coerceIn(0, (rotated.width - validW).coerceAtLeast(0))
                        val top = ((rotated.height - validH) / 2).coerceIn(0, (rotated.height - validH).coerceAtLeast(0))
                        val cropped = Bitmap.createBitmap(rotated, left, top, validW, validH)
                        if (rotated !== cropped) {
                            try {
                                rotated.recycle()
                            } catch (_: Exception) {
                            }
                        }
                        replace(cropped)
                    }
                }
            }
            if (crop.flipH || crop.flipV) {
                val bw = current.width
                val bh = current.height
                if (bw > 0 && bh > 0) {
                    val matrix = Matrix()
                    if (crop.flipH && crop.flipV) {
                        matrix.postRotate(180f, bw / 2f, bh / 2f)
                    } else if (crop.flipH) {
                        matrix.postScale(-1f, 1f)
                        matrix.postTranslate(bw.toFloat(), 0f)
                    } else {
                        matrix.postScale(1f, -1f)
                        matrix.postTranslate(0f, bh.toFloat())
                    }
                    val flipped = Bitmap.createBitmap(current, 0, 0, bw, bh, matrix, true)
                    replace(flipped)
                }
            }
            val aspect = crop.ratio.aspect
            // Crop order: rotate -> straighten -> flip -> custom-rect-crop for
            // FREE (fractions are relative to the post-flip frame, the same
            // frame CropOverlay maps to screen pixels, so the overlay box
            // matches this cut) else centered aspect-crop. Export reuses this
            // exact path via Exporter.renderForExport -> render(), so preview
            // and export stay identical.
            if (crop.ratio == com.lumina.studio.core.edit.CropRatio.FREE) {
                if (!crop.isFullFrameRect()) {
                    val bw = current.width
                    val bh = current.height
                    if (bw > 0 && bh > 0) {
                        val r = com.lumina.studio.core.edit.CropRects.pixelRect(
                            crop.customLeft, crop.customTop,
                            crop.customRight, crop.customBottom, bw, bh
                        )
                        if (r[2] < bw || r[3] < bh || r[0] > 0 || r[1] > 0) {
                            replace(Bitmap.createBitmap(current, r[0], r[1], r[2], r[3]))
                        }
                    }
                }
            } else if (aspect != null && aspect > 0f) {
                val bw = current.width
                val bh = current.height
                if (bw > 0 && bh > 0) {
                    val currentAspect = bw.toFloat() / bh.toFloat()
                    if (kotlin.math.abs(currentAspect - aspect) > 0.001f) {
                        if (currentAspect > aspect) {
                            val newW = (bh * aspect + 0.5f).toInt().coerceIn(1, bw)
                            val left = ((bw - newW) / 2).coerceIn(0, (bw - newW).coerceAtLeast(0))
                            replace(Bitmap.createBitmap(current, left, 0, newW, bh))
                        } else {
                            val newH = (bw / aspect + 0.5f).toInt().coerceIn(1, bh)
                            val top = ((bh - newH) / 2).coerceIn(0, (bh - newH).coerceAtLeast(0))
                            replace(Bitmap.createBitmap(current, 0, top, bw, newH))
                        }
                    }
                }
            }
            current
        } catch (_: Exception) {
            src
        }
    }

    fun applyMasks(src: Bitmap, params: EditParams): Bitmap {
        val masks = params.masks
        if (masks.isEmpty()) return src
        val w = src.width
        val h = src.height
        if (w <= 0 || h <= 0) return src
        val visible = masks.filter { it.visible && it.opacity > 0.001f }
        if (visible.isEmpty()) return src
        val active = visible.filter {
            if (it.tool == MaskTool.ERASER) true else (it.exposure != 0f || it.temperature != 0f)
        }
        if (active.isEmpty()) return src
        return try {
            val total = w * h
            val pixels = IntArray(total)
            src.getPixels(pixels, 0, w, 0, 0, w, h)
            val baseCopy = pixels.clone()
            for (mask in active) {
                applySingleMask(pixels, baseCopy, w, h, mask)
            }
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            out.setPixels(pixels, 0, w, 0, 0, w, h)
            out
        } catch (_: Exception) {
            src
        }
    }

    private fun applySingleMask(pixels: IntArray, baseCopy: IntArray, w: Int, h: Int, mask: EditMask) {
        val opacity = mask.opacity.coerceIn(0f, 1f)
        if (opacity <= 0.001f) return
        val isEraser = mask.tool == MaskTool.ERASER
        val expScale = if (isEraser) 1f else 2f.pow(mask.exposure).coerceIn(0.1f, 8f)
        val rGain = if (isEraser) 1f else (1f + mask.temperature / 150f).coerceIn(0.4f, 2f)
        val bGain = if (isEraser) 1f else (1f - mask.temperature / 150f).coerceIn(0.4f, 2f)
        when (mask.tool) {
            MaskTool.RADIAL -> applyRadial(pixels, baseCopy, w, h, mask, opacity, expScale, rGain, bGain, isEraser)
            MaskTool.LINEAR -> applyLinear(pixels, baseCopy, w, h, mask, opacity, expScale, rGain, bGain, isEraser)
            MaskTool.BRUSH, MaskTool.ERASER -> applyStroke(pixels, baseCopy, w, h, mask, opacity, expScale, rGain, bGain, isEraser)
        }
    }

    private fun blendPixel(current: Int, base: Int, expScale: Float, rGain: Float, bGain: Float, a: Float, isEraser: Boolean): Int {
        if (a <= 0.001f) return current
        val alpha = a.coerceIn(0f, 1f)
        val r0 = ((current shr 16) and 0xFF) / 255f
        val g0 = ((current shr 8) and 0xFF) / 255f
        val b0 = (current and 0xFF) / 255f
        val tr: Float
        val tg: Float
        val tb: Float
        if (isEraser) {
            tr = ((base shr 16) and 0xFF) / 255f
            tg = ((base shr 8) and 0xFF) / 255f
            tb = (base and 0xFF) / 255f
        } else {
            tr = (r0 * expScale * rGain).coerceIn(0f, 1f)
            tg = (g0 * expScale).coerceIn(0f, 1f)
            tb = (b0 * expScale * bGain).coerceIn(0f, 1f)
        }
        val r = (r0 + (tr - r0) * alpha)
        val g = (g0 + (tg - g0) * alpha)
        val b = (b0 + (tb - b0) * alpha)
        val al = current ushr 24
        return (al shl 24) or
            ((r * 255f + 0.5f).toInt().coerceIn(0, 255) shl 16) or
            ((g * 255f + 0.5f).toInt().coerceIn(0, 255) shl 8) or
            (b * 255f + 0.5f).toInt().coerceIn(0, 255)
    }

    private fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        if (e0 >= e1) return if (x < e0) 0f else 1f
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun applyRadial(
        pixels: IntArray, baseCopy: IntArray, w: Int, h: Int, mask: EditMask,
        opacity: Float, expScale: Float, rGain: Float, bGain: Float, isEraser: Boolean
    ) {
        val cx = mask.centerX.coerceIn(0f, 1f) * w
        val cy = mask.centerY.coerceIn(0f, 1f) * h
        val radiusPx = (mask.radius.coerceIn(0.01f, 1f) * minOf(w, h).toFloat()).coerceAtLeast(1f)
        val feather = mask.feather.coerceIn(0f, 1f)
        val inverted = mask.inverted
        val inner = if (feather <= 0.001f) radiusPx else radiusPx * (1f - feather)
        for (y in 0 until h) {
            val dy = y - cy
            var idx = y * w
            for (x in 0 until w) {
                val dx = x - cx
                val dist = sqrt(dx * dx + dy * dy)
                val raw = if (feather <= 0.001f) {
                    if (dist <= radiusPx) 1f else 0f
                } else {
                    if (dist <= inner) 1f
                    else if (dist >= radiusPx) 0f
                    else 1f - smoothstep(inner, radiusPx, dist)
                }
                val a = (if (inverted) 1f - raw else raw) * opacity
                if (a > 0.001f) {
                    pixels[idx] = blendPixel(pixels[idx], baseCopy[idx], expScale, rGain, bGain, a, isEraser)
                }
                idx++
            }
        }
    }

    private fun applyLinear(
        pixels: IntArray, baseCopy: IntArray, w: Int, h: Int, mask: EditMask,
        opacity: Float, expScale: Float, rGain: Float, bGain: Float, isEraser: Boolean
    ) {
        val rad = mask.angleDeg * Math.PI.toFloat() / 180f
        val cosA = cos(rad)
        val sinA = sin(rad)
        val position = mask.position.coerceIn(0f, 1f)
        val feather = mask.feather.coerceIn(0f, 1f)
        val fw = 0.02f + feather * 0.3f
        val inverted = mask.inverted
        val wf = w.toFloat()
        val hf = h.toFloat()
        for (y in 0 until h) {
            val ny = if (hf > 0f) y / hf else 0.5f
            var idx = y * w
            for (x in 0 until w) {
                val nx = if (wf > 0f) x / wf else 0.5f
                val p = (nx - 0.5f) * cosA + (ny - 0.5f) * sinA + 0.5f
                val raw = 1f - smoothstep(position - fw / 2f, position + fw / 2f, p)
                val a = (if (inverted) 1f - raw else raw) * opacity
                if (a > 0.001f) {
                    pixels[idx] = blendPixel(pixels[idx], baseCopy[idx], expScale, rGain, bGain, a, isEraser)
                }
                idx++
            }
        }
    }

    private fun applyStroke(
        pixels: IntArray, baseCopy: IntArray, w: Int, h: Int, mask: EditMask,
        opacity: Float, expScale: Float, rGain: Float, bGain: Float, isEraser: Boolean
    ) {
        val pts = mask.points
        if (pts.isEmpty()) return
        val minDim = minOf(w, h).toFloat().coerceAtLeast(1f)
        val radiusPx = ((mask.sizePx.coerceIn(EditMask.MIN_SIZE_PX, EditMask.MAX_SIZE_PX) * 0.5f) * (minDim / 1000f)).coerceAtLeast(1f)
        val feather = mask.feather.coerceIn(0f, 1f)
        val inverted = mask.inverted
        val inner = if (feather <= 0.001f) radiusPx else radiusPx * (1f - feather)
        val xs = FloatArray(pts.size) { pts[it].x.coerceIn(0f, 1f) * w }
        val ys = FloatArray(pts.size) { pts[it].y.coerceIn(0f, 1f) * h }
        var minX = xs[0]
        var maxX = xs[0]
        var minY = ys[0]
        var maxY = ys[0]
        for (i in 1 until xs.size) {
            if (xs[i] < minX) minX = xs[i]
            if (xs[i] > maxX) maxX = xs[i]
            if (ys[i] < minY) minY = ys[i]
            if (ys[i] > maxY) maxY = ys[i]
        }
        val left = (minX - radiusPx - 1f).toInt().coerceIn(0, w - 1)
        val right = (maxX + radiusPx + 1f).toInt().coerceIn(0, w - 1)
        val top = (minY - radiusPx - 1f).toInt().coerceIn(0, h - 1)
        val bottom = (maxY + radiusPx + 1f).toInt().coerceIn(0, h - 1)
        if (!inverted) {
            for (y in top..bottom) {
                var idx = y * w + left
                for (x in left..right) {
                    val dist = distToPolyline(x.toFloat(), y.toFloat(), xs, ys)
                    val raw = if (feather <= 0.001f) {
                        if (dist <= radiusPx) 1f else 0f
                    } else {
                        if (dist <= inner) 1f
                        else if (dist >= radiusPx) 0f
                        else 1f - smoothstep(inner, radiusPx, dist)
                    }
                    val a = raw * opacity
                    if (a > 0.001f) {
                        pixels[idx] = blendPixel(pixels[idx], baseCopy[idx], expScale, rGain, bGain, a, isEraser)
                    }
                    idx++
                }
            }
        } else {
            for (y in 0 until h) {
                var idx = y * w
                val insideY = y in top..bottom
                for (x in 0 until w) {
                    val a: Float
                    if (insideY && x in left..right) {
                        val dist = distToPolyline(x.toFloat(), y.toFloat(), xs, ys)
                        val raw = if (feather <= 0.001f) {
                            if (dist <= radiusPx) 1f else 0f
                        } else {
                            if (dist <= inner) 1f
                            else if (dist >= radiusPx) 0f
                            else 1f - smoothstep(inner, radiusPx, dist)
                        }
                        a = (1f - raw) * opacity
                    } else {
                        a = opacity
                    }
                    if (a > 0.001f) {
                        pixels[idx] = blendPixel(pixels[idx], baseCopy[idx], expScale, rGain, bGain, a, isEraser)
                    }
                    idx++
                }
            }
        }
    }

    private fun distToPolyline(px: Float, py: Float, xs: FloatArray, ys: FloatArray): Float {
        if (xs.size == 1) {
            val dx = px - xs[0]
            val dy = py - ys[0]
            return sqrt(dx * dx + dy * dy)
        }
        var best = Float.MAX_VALUE
        for (i in 0 until xs.size - 1) {
            val ax = xs[i]
            val ay = ys[i]
            val bx = xs[i + 1]
            val by = ys[i + 1]
            val abx = bx - ax
            val aby = by - ay
            val denom = abx * abx + aby * aby
            val t = if (denom <= 1e-9f) 0f else (((px - ax) * abx + (py - ay) * aby) / denom).coerceIn(0f, 1f)
            val cx = ax + abx * t
            val cy = ay + aby * t
            val dx = px - cx
            val dy = py - cy
            val d = sqrt(dx * dx + dy * dy)
            if (d < best) best = d
        }
        return best
    }

    fun rgbToHsl(r: Float, g: Float, b: Float): FloatArray {
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val l = (max + min) / 2f
        if (max == min) return floatArrayOf(0f, 0f, l.coerceIn(0f, 1f))
        val d = max - min
        val s = if (l > 0.5f) d / (2f - max - min) else d / (max + min)
        var h = when (max) {
            r -> (g - b) / d + (if (g < b) 6f else 0f)
            g -> (b - r) / d + 2f
            else -> (r - g) / d + 4f
        }
        h *= 60f
        if (h < 0f) h += 360f
        return floatArrayOf(h, s.coerceIn(0f, 1f), l.coerceIn(0f, 1f))
    }

    fun hslToRgb(hDeg: Float, s: Float, l: Float): FloatArray {
        val h = (((hDeg % 360f) + 360f) % 360f) / 360f
        val sat = s.coerceIn(0f, 1f)
        val light = l.coerceIn(0f, 1f)
        if (sat == 0f) return floatArrayOf(light, light, light)
        val q = if (light < 0.5f) light * (1f + sat) else light + sat - light * sat
        val p = 2f * light - q
        return floatArrayOf(hueToRgb(p, q, h + 1f / 3f), hueToRgb(p, q, h), hueToRgb(p, q, h - 1f / 3f))
    }

    private fun hueToRgb(p: Float, q: Float, t: Float): Float {
        var tt = t
        if (tt < 0f) tt += 1f
        if (tt > 1f) tt -= 1f
        return when {
            tt < 1f / 6f -> p + (q - p) * 6f * tt
            tt < 1f / 2f -> q
            tt < 2f / 3f -> p + (q - p) * (2f / 3f - tt) * 6f
            else -> p
        }.coerceIn(0f, 1f)
    }

    fun buildMatrix(params: EditParams): ColorMatrix {
        val exposureScale = 2f.pow(params.exposure).coerceIn(0.1f, 8f)
        val contrastBase = (1f + params.contrast / 100f).coerceIn(0f, 3f)
        val micro = 1f + (params.clarity * 0.25f + params.dehaze * 0.35f + params.sharpness * 0.1f) / 100f
        val contrastFactor = (contrastBase * micro).coerceIn(0f, 4f)
        val saturation = (1f + params.saturation / 100f + params.vibrance / 200f + params.dehaze / 300f)
            .coerceIn(0f, 3f)
        val rGain = (1f + params.temperature / 150f).coerceIn(0.4f, 2f)
        val bGain = (1f - params.temperature / 150f).coerceIn(0.4f, 2f)
        val gGain = (1f - params.tint / 300f).coerceIn(0.5f, 1.8f)
        val tone = ((params.whites * 0.4f + params.highlights * 0.3f +
            params.blacks * 0.4f + params.shadows * 0.3f) / 100f * 32f)
            .coerceIn(-64f, 64f)

        val result = ColorMatrix()

        val exposure = ColorMatrix(
            floatArrayOf(
                exposureScale, 0f, 0f, 0f, 0f,
                0f, exposureScale, 0f, 0f, 0f,
                0f, 0f, exposureScale, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            )
        )
        result.postConcat(exposure)

        val pivot = (1f - contrastFactor) * 128f
        val contrast = ColorMatrix(
            floatArrayOf(
                contrastFactor, 0f, 0f, 0f, pivot,
                0f, contrastFactor, 0f, 0f, pivot,
                0f, 0f, contrastFactor, 0f, pivot,
                0f, 0f, 0f, 1f, 0f
            )
        )
        result.postConcat(contrast)

        val sat = ColorMatrix().apply { setSaturation(saturation) }
        result.postConcat(sat)

        val gains = ColorMatrix(
            floatArrayOf(
                rGain, 0f, 0f, 0f, 0f,
                0f, gGain, 0f, 0f, 0f,
                0f, 0f, bGain, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            )
        )
        result.postConcat(gains)

        if (tone != 0f) {
            val toneMatrix = ColorMatrix(
                floatArrayOf(
                    1f, 0f, 0f, 0f, tone,
                    0f, 1f, 0f, 0f, tone,
                    0f, 0f, 1f, 0f, tone,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            result.postConcat(toneMatrix)
        }
        return result
    }

    fun computeHistogram(src: Bitmap, bins: Int = HISTOGRAM_BINS): Array<IntArray> {
        val out = Array(3) { IntArray(bins) }
        val w = src.width
        val h = src.height
        if (w <= 0 || h <= 0) return out
        val total = w * h
        val step = maxOf(1, total / HISTOGRAM_SAMPLE_CAP)
        val pixels = IntArray(total)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        var i = 0
        while (i < total) {
            val pixel = pixels[i]
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            out[0][(r * bins) ushr 8]++
            out[1][(g * bins) ushr 8]++
            out[2][(b * bins) ushr 8]++
            i += step
        }
        return out
    }

    fun decodePreview(filePath: String, maxDim: Int = MAX_PREVIEW_DIM): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(filePath, bounds)
            val w = bounds.outWidth
            val h = bounds.outHeight
            if (w <= 0 || h <= 0) return null
            val longest = maxOf(w, h)
            var sample = 1
            while (longest / sample > maxDim) sample *= 2
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decoded = BitmapFactory.decodeFile(filePath, opts) ?: return null
            // EXIF orientation chokepoint: pixels are normalized to orientation 1
            // here so masks/crop/zoom-tile/export all operate on DISPLAYED pixels.
            // The stored original file is never rewritten.
            try {
                ImageOrientation.normalizeBitmap(decoded, ImageOrientation.orientationOfPath(filePath))
            } catch (_: Exception) {
                decoded
            }
        } catch (_: Exception) {
            null
        }
    }

    fun previewMaxDimFor(quality: String): Int = when (quality.trim().lowercase(java.util.Locale.US)) {
        "low" -> PREVIEW_MAX_LOW
        "medium" -> PREVIEW_MAX_MEDIUM
        else -> PREVIEW_MAX_HIGH
    }

    fun effectivePreviewMaxDim(quality: String, gpuEnabled: Boolean): Int {
        val qualityDim = previewMaxDimFor(quality)
        return if (gpuEnabled) qualityDim else minOf(qualityDim, GPU_OFF_MAX_PREVIEW_DIM)
    }

    fun shouldAutoHistogram(gpuEnabled: Boolean): Boolean = gpuEnabled
}
