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
import com.lumina.studio.core.edit.DustCandidate
import com.lumina.studio.core.edit.DustMath
import com.lumina.studio.core.edit.EditMask
import com.lumina.studio.core.edit.LensBlurMath
import com.lumina.studio.core.edit.LensBlurParams
import com.lumina.studio.core.edit.RetouchMath
import com.lumina.studio.core.edit.RetouchOp
import com.lumina.studio.core.edit.EditParams
import com.lumina.studio.core.edit.GeometryMath
import com.lumina.studio.core.edit.GradeHsl
import com.lumina.studio.core.edit.GradeMath
import com.lumina.studio.core.edit.HslColor
import com.lumina.studio.core.edit.MaskOp
import com.lumina.studio.core.edit.MaskRangeMath
import com.lumina.studio.core.edit.MaskTool
import com.lumina.studio.core.edit.OpticsMath
import com.lumina.studio.core.edit.OpticsParams
import com.lumina.studio.core.edit.PointColorMath
import com.lumina.studio.core.edit.StepKey
import com.lumina.studio.core.ai.AiMaskFieldStore
import com.lumina.studio.core.lut.LutCube
import com.lumina.studio.core.lut.LutRenderer
import com.lumina.studio.core.util.ImageOrientation
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

object PreviewRenderer {
    // M5 color-management + precision notes (§14 input assumption, §10 path):
    // every bitmap entering render() is assumed sRGB-encoded (BitmapFactory
    // does not apply embedded ICC profiles; wide-gamut/HDR input profiles are
    // NOT supported — see ColorMatrices). Grade math stays sRGB-math on both
    // qualities; FINAL differs only by intermediate store (RGBA_F16) so the
    // same recipe renders the same look with less quantization. Stage order
    // LUT -> adjusts -> HSL -> curves -> pointColor -> grading -> details ->
    // masks -> crop is identical for preview and export;
    // Exporter.renderForExport funnels through
    // CpuRenderBackend with an Export target, which maps to FINAL.
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

    // M5: intermediate-store selector. RGBA_F16 needs API 26+; minSdk is 26
    // so no version gate, but OOM/edge devices still fall back per call site.
    // PREVIEW returns ARGB_8888 (golden behavior, byte-identical to M4).
    internal fun workingConfig(quality: RenderQuality): Bitmap.Config =
        if (quality == RenderQuality.FINAL) Bitmap.Config.RGBA_F16
        else Bitmap.Config.ARGB_8888

    internal fun createWorkingBitmap(w: Int, h: Int, quality: RenderQuality): Bitmap =
        try {
            Bitmap.createBitmap(w, h, workingConfig(quality))
        } catch (_: OutOfMemoryError) {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        } catch (_: Exception) {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        }

    fun isIdentity(params: EditParams, hasLut: Boolean = false): Boolean =
        params.isDefault() && !hasLut

    // Render order: LUT -> adjusts -> HSL -> curves -> pointColor -> grading ->
    // optics -> details -> masks -> geometry(crop/transform).
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
    //
    // M6 (§18 point color, §19 grading, §85 order):
    // - Point color runs AFTER HSL+curves (it keys off the tone-mapped hue,
    //   like the HSL stage, but targets one picked hue with its own falloff)
    //   and BEFORE grading, so wheels grade the point-corrected pixels.
    // - Grading runs AFTER curves (zone weights read tone-mapped luma) and
    //   BEFORE details/masks. Both stages honor StepKey.COLOR and early-out
    //   when default/disabled (point: disabled or no S/L adjust; grading:
    //   all wheels neutral), preserving the <100ms budget.
    // - Grade lift is gamma-domain sRGB math (display-referred, same choice
    //   as exposure/contrast above); the pure weight/tint/falloff functions
    //   live in core.edit.GradeMath/PointColorMath so JVM tests pin them
    //   without Bitmaps — this file only loops pixels.
    //
    // M5 gamma/luminance audit (§86) — domain choice per operation. Nothing
    // below changes pixel math (zero-risk rule); entries marked M16 are known
    // approximations kept deliberately for golden stability:
    // - Exposure (buildMatrix, 2^EV gain + mask expScale): applied to
    //   gamma-encoded sRGB, i.e. a linear-ish gain in the wrong domain.
    //   Physically it belongs in linear light; in gamma it over-brightens
    //   midtones slightly. Kept: every golden/preset depends on it (M16).
    // - Contrast pivot 128 + tone offsets: gamma-domain contrast around
    //   mid-grey. Correct domain for a display-referred "punch" control.
    // - Saturation/vibrance (setSaturation) + HSL hue/sat/lum edits: operate
    //   on gamma-encoded values via RGB<->HSL. HSL is defined on gamma RGB,
    //   so this is the correct domain for selective-color UX (M16 could move
    //   global-sat to linear for physical purity; kept for stability).
    // - Temperature/tint channel gains: gamma-domain white-balance approx.
    //   True WB belongs in linear/raw; gamma gains are the standard
    //   display-referred approximation. Kept (M16 with real RAW demosaic).
    // - Mask blend out = base*(1-a) + graded*a in gamma: matches overlay UX;
    //   linear blend would look more physical but change every mask (M16).
    // - Curves: display-referred tone map on gamma values by design.
    // - LUT trilinear: interpolates in the LUT's native (gamma) domain.
    // M5 precision (§10): [quality] selects the intermediate store only.
    // PREVIEW (default) keeps M4 ARGB_8888 behavior so all existing callers
    // compile and render byte-identically; FINAL uses RGBA_F16 intermediates
    // for the LUT/curve/HSL/matrix chain (export + fullscreen/zoom-tile final).
    // M7 (§22-24, §85 order): optics runs AFTER grading (operates on graded
    // pixels) and BEFORE details; masks run BEFORE geometry; geometry
    // (perspective-warp -> crop cut, honoring StepKey.CROP) runs LAST.
    // Coordinate order: masks key off pre-geometry (post-details) pixels in
    // the pre-warp frame; the perspective warp then resamples the graded
    // frame and the crop cut applies in the post-warp frame (same frame the
    // CropOverlay maps to screen pixels).
    //
    // M8 (§26-28, §79 honest local scope, §85 order): retouch runs AFTER
    // masks (spot edits on masked/graded pixels) and BEFORE geometry/crop;
    // lens blur runs AFTER retouch and BEFORE geometry. Both early-out when
    // default (empty op list / amount 0) and carry no StepKey toggle — they
    // always render when non-default. All three features are local,
    // heuristic approximations (no AI, no depth sensor); see applyRetouch /
    // applyLensBlur / detectDustCandidates. Retouch/lens coordinates are
    // fractions of the pre-geometry frame, same frame as masks.
    fun render(
        src: Bitmap,
        params: EditParams,
        lut: LutCube? = null,
        quality: RenderQuality = RenderQuality.PREVIEW,
        fullLut: Boolean = false
    ): Bitmap {
        val steps = params.steps
        val useLut = lut != null && params.presetId != null && params.presetIntensity > 0f &&
            steps.get(StepKey.PRESET) && steps.get(StepKey.LUT)
        val graded = if (useLut) {
            if (fullLut) {
                LutRenderer.applyLutFull(src, lut, params.presetIntensity, workingConfig(quality))
            } else {
                LutRenderer.applyLut(src, lut, params.presetIntensity, workingConfig(quality))
            }
        } else {
            src
        }
        if (params.isDefault()) return graded
        var current = graded
        if (!params.isAdjustsDefault() && steps.get(StepKey.ADJUSTS)) {
            val combined = buildMatrix(params)
            val out = applyMatrix(current, combined, quality)
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
                    val out = applyMatrix(current, cm, quality)
                    if (out !== current) {
                        if (current !== src) current.recycle()
                        current = out
                    }
                }
            }
            if (params.hasPerColorHsl()) {
                val out = applyHslPerColor(current, params, quality)
                if (out !== current) {
                    if (current !== src) current.recycle()
                    current = out
                }
            }
        }
        if (!params.isCurvesDefault() && steps.get(StepKey.CURVES)) {
            val out = applyCurves(current, params, quality)
            if (out !== current) {
                if (current !== src) current.recycle()
                current = out
            }
        }
        if (!params.isPointColorDefault() && steps.get(StepKey.COLOR)) {
            val out = applyPointColor(current, params, quality)
            if (out !== current) {
                if (current !== src) current.recycle()
                current = out
            }
        }
        if (!params.isGradeDefault() && steps.get(StepKey.COLOR)) {
            val out = applyGrading(current, params, quality)
            if (out !== current) {
                if (current !== src) current.recycle()
                current = out
            }
        }
        if (!params.optics.isDefault() && steps.get(StepKey.CROP)) {
            val out = applyOptics(current, params.optics, quality)
            if (out !== current) {
                if (current !== src) current.recycle()
                current = out
            }
        }
        if (!params.isDetailsDefault() && steps.get(StepKey.DETAILS)) {
            val out = applyDetails(current, src, params, quality)
            if (out !== current) {
                if (current !== src) current.recycle()
                current = out
            }
        }
        if (params.masks.isNotEmpty() && steps.get(StepKey.MASKS)) {
            val out = applyMasks(current, params, quality)
            if (out !== current) {
                if (current !== src) current.recycle()
                current = out
            }
        }
        // M8: retouch AFTER masks, BEFORE geometry. No step gate (always
        // renders when non-default); early-out inside applyRetouch.
        if (params.retouch.isNotEmpty()) {
            val out = applyRetouch(current, params.retouch, quality)
            if (out !== current) {
                if (current !== src) current.recycle()
                current = out
            }
        }
        // M8: lens blur AFTER retouch, BEFORE geometry. Heuristic depth
        // blur (NOT AI); early-out inside applyLensBlur at amount 0.
        if (!params.lensBlur.isDefault()) {
            val out = applyLensBlur(current, params.lensBlur, quality)
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

    private fun applyMatrix(
        src: Bitmap,
        matrix: ColorMatrix,
        quality: RenderQuality = RenderQuality.PREVIEW
    ): Bitmap {
        val out = createWorkingBitmap(src.width, src.height, quality)
        val paint = Paint().apply {
            colorFilter = ColorMatrixColorFilter(matrix)
            isFilterBitmap = true
        }
        Canvas(out).drawBitmap(src, 0f, 0f, paint)
        return out
    }

    fun applyHslPerColor(
        src: Bitmap,
        params: EditParams,
        quality: RenderQuality = RenderQuality.PREVIEW
    ): Bitmap {
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
        val out = createWorkingBitmap(w, h, quality)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }

    fun applyCurves(
        src: Bitmap,
        params: EditParams,
        quality: RenderQuality = RenderQuality.PREVIEW
    ): Bitmap {
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
        val out = createWorkingBitmap(w, h, quality)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }

    fun applyPointColor(
        src: Bitmap,
        params: EditParams,
        quality: RenderQuality = RenderQuality.PREVIEW
    ): Bitmap {
        val point = params.pointColor
        if (point.isDefault()) return src
        val w = src.width
        val h = src.height
        if (w <= 0 || h <= 0) return src
        val total = w * h
        val pixels = IntArray(total)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        var touched = false
        for (i in pixels.indices) {
            val p = pixels[i]
            val r0 = ((p shr 16) and 0xFF) / 255f
            val g0 = ((p shr 8) and 0xFF) / 255f
            val b0 = (p and 0xFF) / 255f
            val out = PointColorMath.applyPoint(r0, g0, b0, point)
            val r = (out[0] * 255f + 0.5f).toInt().coerceIn(0, 255)
            val g = (out[1] * 255f + 0.5f).toInt().coerceIn(0, 255)
            val b = (out[2] * 255f + 0.5f).toInt().coerceIn(0, 255)
            if (r != ((p shr 16) and 0xFF) || g != ((p shr 8) and 0xFF) || b != (p and 0xFF)) {
                touched = true
                val a = p ushr 24
                pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        if (!touched) return src
        val out = createWorkingBitmap(w, h, quality)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }

    fun applyGrading(
        src: Bitmap,
        params: EditParams,
        quality: RenderQuality = RenderQuality.PREVIEW
    ): Bitmap {
        val grade = params.grade
        if (grade.isDefault()) return src
        val w = src.width
        val h = src.height
        if (w <= 0 || h <= 0) return src
        val total = w * h
        val pixels = IntArray(total)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        var touched = false
        for (i in pixels.indices) {
            val p = pixels[i]
            val r0 = ((p shr 16) and 0xFF) / 255f
            val g0 = ((p shr 8) and 0xFF) / 255f
            val b0 = (p and 0xFF) / 255f
            val out = GradeMath.applyGrade(r0, g0, b0, grade)
            val r = (out[0] * 255f + 0.5f).toInt().coerceIn(0, 255)
            val g = (out[1] * 255f + 0.5f).toInt().coerceIn(0, 255)
            val b = (out[2] * 255f + 0.5f).toInt().coerceIn(0, 255)
            if (r != ((p shr 16) and 0xFF) || g != ((p shr 8) and 0xFF) || b != (p and 0xFF)) {
                touched = true
                val a = p ushr 24
                pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        if (!touched) return src
        val out = createWorkingBitmap(w, h, quality)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }

    /**
     * M6 point-color affected-area overlay (§18, cheap approx). Downscales to
     * [maxDim] and paints a red tint with alpha = hue-falloff weight, so the
     * editor can show which pixels the current hue/range selects. Transparent
     * where unselected. Returns null when point color is default. The caller
     * owns the returned bitmap (overlay display recycles on replace).
     */
    fun buildPointColorMask(
        src: Bitmap,
        params: EditParams,
        maxDim: Int = 192
    ): Bitmap? {
        val point = params.pointColor
        if (point.isDefault()) return null
        val w = src.width
        val h = src.height
        if (w <= 0 || h <= 0) return null
        return try {
            val longest = maxOf(w, h)
            val scale = if (longest <= maxDim) 1f else maxDim.toFloat() / longest.toFloat()
            val sw = (w * scale + 0.5f).toInt().coerceIn(1, w)
            val sh = (h * scale + 0.5f).toInt().coerceIn(1, h)
            val small = Bitmap.createScaledBitmap(src, sw, sh, true)
            val total = sw * sh
            val pixels = IntArray(total)
            small.getPixels(pixels, 0, sw, 0, 0, sw, sh)
            if (!small.isRecycled) small.recycle()
            for (i in pixels.indices) {
                val p = pixels[i]
                val r0 = ((p shr 16) and 0xFF) / 255f
                val g0 = ((p shr 8) and 0xFF) / 255f
                val b0 = (p and 0xFF) / 255f
                val hue = GradeHsl.rgbToHsl(r0, g0, b0)[0]
                val weight = PointColorMath.falloffWeight(hue, point.hueCenter, point.hueRange)
                val alpha = (weight * 160f + 0.5f).toInt().coerceIn(0, 160)
                pixels[i] = (alpha shl 24) or (0xFF shl 16) or (0x40 shl 8) or 0x40
            }
            val out = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
            out.setPixels(pixels, 0, sw, 0, 0, sw, sh)
            out
        } catch (_: Exception) {
            null
        }
    }

    fun applyDetails(
        src: Bitmap,
        originalSrc: Bitmap,
        params: EditParams,
        quality: RenderQuality = RenderQuality.PREVIEW
    ): Bitmap {
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
            val out = applyMatrix(current, result, quality)
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
            // M7 geometry order: rotate -> straighten -> flip ->
            // perspective-warp -> crop cut. The warp resamples the graded
            // frame (Matrix.setPolyToPoly 4-point mapping, bilinear filter;
            // transparent gutters possible at extremes); the cut below runs
            // in the post-warp frame, matching CropOverlay.
            if (crop.perspectiveV != 0f || crop.perspectiveH != 0f) {
                val warped = perspectiveWarp(
                    current, crop.perspectiveV, crop.perspectiveH
                )
                if (warped !== current) replace(warped)
            }
            // M7: Custom ratio uses crop.effectiveAspect() (customW/customH);
            // invalid custom aspect falls back to no cut (free behavior).
            val aspect = crop.effectiveAspect()
            // Crop order: rotate -> straighten -> flip -> perspective ->
            // custom-rect-crop for FREE (fractions are relative to the
            // post-warp frame, the same frame CropOverlay maps to screen
            // pixels, so the overlay box matches this cut) else centered
            // aspect-crop. Export reuses this exact path via
            // Exporter.renderForExport -> render(), so preview and export
            // stay identical.
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

    /**
     * M7 perspective warp (§23, documented approximation): 4-point projective
     * mapping via android.graphics.Matrix.setPolyToPoly on preview-size
     * bitmaps, bilinear filter. Early-out (returns [src]) at 0/0.
     * Destination corners come from [GeometryMath.perspectiveDst].
     */
    fun perspectiveWarp(src: Bitmap, vAmt: Float, hAmt: Float): Bitmap {
        if (vAmt == 0f && hAmt == 0f) return src
        val w = src.width
        val h = src.height
        if (w <= 0 || h <= 0) return src
        return try {
            val v = vAmt.coerceIn(-100f, 100f)
            val hz = hAmt.coerceIn(-100f, 100f)
            if (v == 0f && hz == 0f) return src
            val wf = w.toFloat()
            val hf = h.toFloat()
            val srcPts = floatArrayOf(0f, 0f, wf, 0f, wf, hf, 0f, hf)
            val dstPts = GeometryMath.perspectiveDst(w, h, v, hz)
            val matrix = Matrix()
            if (!matrix.setPolyToPoly(srcPts, 0, dstPts, 0, 4)) return src
            Bitmap.createBitmap(src, 0, 0, w, h, matrix, true)
        } catch (_: Exception) {
            src
        }
    }

    /**
     * M7 manual optics (§22, manual-only approximations on preview-size
     * bitmaps, early-out when default). Order: distortion (inverse radial
     * remap, nearest-neighbor) -> CA (radial red/blue shift,
     * nearest-neighbor) -> vignette gain. Honors StepKey.CROP (transform
     * group); called from render() between grading and details.
     */
    fun applyOptics(
        src: Bitmap,
        optics: OpticsParams,
        quality: RenderQuality = RenderQuality.PREVIEW
    ): Bitmap {
        if (optics.isDefault()) return src
        val w = src.width
        val h = src.height
        if (w <= 0 || h <= 0) return src
        return try {
            val total = w * h
            val pixels = IntArray(total)
            src.getPixels(pixels, 0, w, 0, 0, w, h)
            val needsRemap = optics.distortion != 0f || optics.caShift != 0f
            var working = pixels
            if (needsRemap) {
                working = applyOpticsRemap(pixels, w, h, optics)
            }
            if (optics.vignetteCorr != 0f) {
                applyOpticsVignetteInPlace(working, w, h, optics.vignetteCorr)
            }
            if (working === pixels && optics.vignetteCorr == 0f) return src
            val out = createWorkingBitmap(w, h, quality)
            out.setPixels(working, 0, w, 0, 0, w, h)
            out
        } catch (_: Exception) {
            src
        }
    }

    private fun applyOpticsVignetteInPlace(
        pixels: IntArray, w: Int, h: Int, amount: Float
    ) {
        val cx = w / 2f
        val cy = h / 2f
        val halfDiag = sqrt(cx * cx + cy * cy).coerceAtLeast(1f)
        var idx = 0
        for (y in 0 until h) {
            val dy = y - cy
            for (x in 0 until w) {
                val dx = x - cx
                val dist = sqrt(dx * dx + dy * dy) / halfDiag
                val gain = OpticsMath.vignetteGain(dist, amount)
                if (gain != 1f) {
                    val p = pixels[idx]
                    val a = p ushr 24
                    val r = (((p shr 16) and 0xFF) * gain + 0.5f).toInt().coerceIn(0, 255)
                    val g = (((p shr 8) and 0xFF) * gain + 0.5f).toInt().coerceIn(0, 255)
                    val b = ((p and 0xFF) * gain + 0.5f).toInt().coerceIn(0, 255)
                    pixels[idx] = (a shl 24) or (r shl 16) or (g shl 8) or b
                }
                idx++
            }
        }
    }

    private fun applyOpticsRemap(
        srcPixels: IntArray, w: Int, h: Int, optics: OpticsParams
    ): IntArray {
        val total = w * h
        val out = IntArray(total)
        val cx = w / 2f
        val cy = h / 2f
        val halfDiag = sqrt(cx * cx + cy * cy).coerceAtLeast(1f)
        val minDim = minOf(w, h).coerceAtLeast(1)
        val k1 = OpticsMath.distortionK1(optics.distortion)
        val hasDist = optics.distortion != 0f && k1 != 0f
        val hasCa = optics.caShift != 0f
        for (y in 0 until h) {
            for (x in 0 until w) {
                val dx = x - cx
                val dy = y - cy
                val distPx = sqrt(dx * dx + dy * dy)
                val distNorm = (distPx / halfDiag).coerceIn(0f, 1f)
                var sx = x.toFloat()
                var sy = y.toFloat()
                if (hasDist && distNorm > 1e-6f) {
                    val rSrc = OpticsMath.inverseRemapRadius(distNorm, k1)
                    val s = if (distNorm > 1e-6f) rSrc / distNorm else 1f
                    sx = cx + dx * s
                    sy = cy + dy * s
                }
                if (!hasCa) {
                    out[y * w + x] = sampleNearest(srcPixels, w, h, sx, sy)
                } else {
                    val shift = OpticsMath.caShiftPx(distNorm, optics.caShift, minDim)
                    if (shift == 0f || distPx < 1e-6f) {
                        out[y * w + x] = sampleNearest(srcPixels, w, h, sx, sy)
                    } else {
                        val ux = dx / distPx
                        val uy = dy / distPx
                        // Convention: positive shift = red outward, blue
                        // inward. Red samples inward (pos - dir*shift/2),
                        // blue outward (pos + dir*shift/2), green center.
                        val rPx = sampleNearest(srcPixels, w, h, sx - ux * shift / 2f, sy - uy * shift / 2f)
                        val gPx = sampleNearest(srcPixels, w, h, sx, sy)
                        val bPx = sampleNearest(srcPixels, w, h, sx + ux * shift / 2f, sy + uy * shift / 2f)
                        val a = gPx ushr 24
                        out[y * w + x] = (a shl 24) or
                            ((rPx shr 16) and 0xFF shl 16) or
                            ((gPx shr 8) and 0xFF shl 8) or
                            (bPx and 0xFF)
                    }
                }
            }
        }
        return out
    }

    private fun sampleNearest(pixels: IntArray, w: Int, h: Int, fx: Float, fy: Float): Int {
        val x = (fx + 0.5f).toInt().coerceIn(0, w - 1)
        val y = (fy + 0.5f).toInt().coerceIn(0, h - 1)
        return pixels[y * w + x]
    }

    /**
     * M8 retouch (§26, honest local scope): HEAL blends the surrounding
     * annulus median color with the target's luminance detail (approx, best
     * on smooth areas); CLONE copies the source disc with a feathered edge;
     * ERASE is bounded onion-peel inpaint (beta, preview-size only) with an
     * annulus-median fallback for unfilled interiors. Runs AFTER masks and
     * BEFORE geometry. Early-out (returns [src]) when [ops] is empty or no
     * op touches pixels.
     */
    fun applyRetouch(
        src: Bitmap,
        ops: List<RetouchOp>,
        quality: RenderQuality = RenderQuality.PREVIEW
    ): Bitmap {
        if (ops.isEmpty()) return src
        val w = src.width
        val h = src.height
        if (w <= 0 || h <= 0) return src
        return try {
            val total = w * h
            val pixels = IntArray(total)
            src.getPixels(pixels, 0, w, 0, 0, w, h)
            val minDim = minOf(w, h).toFloat().coerceAtLeast(1f)
            var touched = false
            for (op in ops) {
                if (op.opacity <= 0.001f) continue
                val rPx = (op.radius * minDim).coerceAtLeast(1f)
                if (rPx < 1f) continue
                val applied = when (op.kind) {
                    com.lumina.studio.core.edit.RetouchKind.HEAL ->
                        retouchHealInPlace(pixels, w, h, op, rPx)
                    com.lumina.studio.core.edit.RetouchKind.CLONE ->
                        retouchCloneInPlace(pixels, w, h, op, rPx)
                    com.lumina.studio.core.edit.RetouchKind.ERASE ->
                        retouchEraseInPlace(pixels, w, h, op, rPx)
                }
                touched = touched || applied
            }
            if (!touched) return src
            val out = createWorkingBitmap(w, h, quality)
            out.setPixels(pixels, 0, w, 0, 0, w, h)
            out
        } catch (_: Exception) {
            src
        }
    }

    private fun retouchBounds(
        cx: Float, cy: Float, rPx: Float, w: Int, h: Int
    ): IntArray {
        val px = (cx.coerceIn(0f, 1f) * w)
        val py = (cy.coerceIn(0f, 1f) * h)
        val left = (px - rPx - 1f).toInt().coerceIn(0, w - 1)
        val right = (px + rPx + 1f).toInt().coerceIn(0, w - 1)
        val top = (py - rPx - 1f).toInt().coerceIn(0, h - 1)
        val bottom = (py + rPx + 1f).toInt().coerceIn(0, h - 1)
        return intArrayOf(left, top, right, bottom)
    }

    private fun retouchAnnulusMedian(
        pixels: IntArray, w: Int, h: Int,
        cx: Float, cy: Float, rPx: Float
    ): FloatArray {
        val px = cx.coerceIn(0f, 1f) * w
        val py = cy.coerceIn(0f, 1f) * h
        val inner = rPx * 1.15f
        val outer = rPx * 1.9f + 1f
        val left = (px - outer).toInt().coerceIn(0, w - 1)
        val right = (px + outer).toInt().coerceIn(0, w - 1)
        val top = (py - outer).toInt().coerceIn(0, h - 1)
        val bottom = (py + outer).toInt().coerceIn(0, h - 1)
        val bw = (right - left + 1).coerceAtLeast(1)
        val bh = (bottom - top + 1).coerceAtLeast(1)
        // Stride the ring so the median stays bounded (~1500 samples max).
        val stride = maxOf(1, ((bw * bh) + 1499) / 1500)
        val rs = ArrayList<Float>(1024)
        val gs = ArrayList<Float>(1024)
        val bs = ArrayList<Float>(1024)
        var y = top
        while (y <= bottom) {
            var x = left
            while (x <= right) {
                val dx = x - px
                val dy = y - py
                val dist = sqrt(dx * dx + dy * dy)
                if (dist >= inner && dist <= outer) {
                    val p = pixels[y * w + x]
                    rs.add(((p shr 16) and 0xFF) / 255f)
                    gs.add(((p shr 8) and 0xFF) / 255f)
                    bs.add((p and 0xFF) / 255f)
                }
                x += stride
            }
            y += stride
        }
        if (rs.isEmpty()) {
            val p = pixels[(py.toInt().coerceIn(0, h - 1)) * w + px.toInt().coerceIn(0, w - 1)]
            return floatArrayOf(
                ((p shr 16) and 0xFF) / 255f,
                ((p shr 8) and 0xFF) / 255f,
                (p and 0xFF) / 255f
            )
        }
        return floatArrayOf(
            RetouchMath.median(rs.toFloatArray()),
            RetouchMath.median(gs.toFloatArray()),
            RetouchMath.median(bs.toFloatArray())
        )
    }

    private fun retouchHealInPlace(
        pixels: IntArray, w: Int, h: Int, op: RetouchOp, rPx: Float
    ): Boolean {
        val bounds = retouchBounds(op.cx, op.cy, rPx, w, h)
        val median = retouchAnnulusMedian(pixels, w, h, op.cx, op.cy, rPx)
        val px = op.cx.coerceIn(0f, 1f) * w
        val py = op.cy.coerceIn(0f, 1f) * h
        var touched = false
        for (y in bounds[1]..bounds[3]) {
            for (x in bounds[0]..bounds[2]) {
                val dx = x - px
                val dy = y - py
                val dist = sqrt(dx * dx + dy * dy)
                if (dist > rPx) continue
                val alpha = RetouchMath.featherAlpha(dist, rPx, op.feather) *
                    op.opacity.coerceIn(0f, 1f)
                if (alpha <= 0.001f) continue
                val i = y * w + x
                val p = pixels[i]
                val healed = RetouchMath.healPixel(
                    ((p shr 16) and 0xFF) / 255f,
                    ((p shr 8) and 0xFF) / 255f,
                    (p and 0xFF) / 255f,
                    median[0], median[1], median[2]
                )
                val a = p ushr 24
                val r = RetouchMath.mix(((p shr 16) and 0xFF) / 255f, healed[0], alpha)
                val g = RetouchMath.mix(((p shr 8) and 0xFF) / 255f, healed[1], alpha)
                val b = RetouchMath.mix((p and 0xFF) / 255f, healed[2], alpha)
                pixels[i] = (a shl 24) or
                    ((r * 255f + 0.5f).toInt().coerceIn(0, 255) shl 16) or
                    ((g * 255f + 0.5f).toInt().coerceIn(0, 255) shl 8) or
                    (b * 255f + 0.5f).toInt().coerceIn(0, 255)
                touched = true
            }
        }
        return touched
    }

    private fun retouchCloneInPlace(
        pixels: IntArray, w: Int, h: Int, op: RetouchOp, rPx: Float
    ): Boolean {
        val snapshot = pixels.clone()
        val bounds = retouchBounds(op.cx, op.cy, rPx, w, h)
        val tx = op.cx.coerceIn(0f, 1f) * w
        val ty = op.cy.coerceIn(0f, 1f) * h
        val sx = op.sx.coerceIn(0f, 1f) * w
        val sy = op.sy.coerceIn(0f, 1f) * h
        var touched = false
        for (y in bounds[1]..bounds[3]) {
            for (x in bounds[0]..bounds[2]) {
                val dx = x - tx
                val dy = y - ty
                val dist = sqrt(dx * dx + dy * dy)
                if (dist > rPx) continue
                val alpha = RetouchMath.featherAlpha(dist, rPx, op.feather) *
                    op.opacity.coerceIn(0f, 1f)
                if (alpha <= 0.001f) continue
                val sPx = sampleNearest(snapshot, w, h, sx + dx, sy + dy)
                val i = y * w + x
                val dPx = pixels[i]
                val a = dPx ushr 24
                val r = RetouchMath.mix(
                    ((dPx shr 16) and 0xFF) / 255f,
                    ((sPx shr 16) and 0xFF) / 255f, alpha
                )
                val g = RetouchMath.mix(
                    ((dPx shr 8) and 0xFF) / 255f,
                    ((sPx shr 8) and 0xFF) / 255f, alpha
                )
                val b = RetouchMath.mix(
                    (dPx and 0xFF) / 255f, (sPx and 0xFF) / 255f, alpha
                )
                pixels[i] = (a shl 24) or
                    ((r * 255f + 0.5f).toInt().coerceIn(0, 255) shl 16) or
                    ((g * 255f + 0.5f).toInt().coerceIn(0, 255) shl 8) or
                    (b * 255f + 0.5f).toInt().coerceIn(0, 255)
                touched = true
            }
        }
        return touched
    }

    /**
     * ERASE (beta): bounded onion-peel diffusion over the target disc, then
     * feather-blend like the other kinds. Peel passes are capped at
     * min(radiusPx, 64); any still-masked interior falls back to the annulus
     * median so large regions always complete. Preview-size only.
     */
    private fun retouchEraseInPlace(
        pixels: IntArray, w: Int, h: Int, op: RetouchOp, rPx: Float
    ): Boolean {
        val bounds = retouchBounds(op.cx, op.cy, rPx, w, h)
        val bw = bounds[2] - bounds[0] + 1
        val bh = bounds[3] - bounds[1] + 1
        if (bw <= 0 || bh <= 0) return false
        val px = op.cx.coerceIn(0f, 1f) * w
        val py = op.cy.coerceIn(0f, 1f) * h
        val r = FloatArray(bw * bh)
        val g = FloatArray(bw * bh)
        val b = FloatArray(bw * bh)
        val mask = BooleanArray(bw * bh)
        for (y in 0 until bh) {
            for (x in 0 until bw) {
                val gx = bounds[0] + x
                val gy = bounds[1] + y
                val dx = gx - px
                val dy = gy - py
                val i = y * bw + x
                val p = pixels[gy * w + gx]
                r[i] = ((p shr 16) and 0xFF) / 255f
                g[i] = ((p shr 8) and 0xFF) / 255f
                b[i] = (p and 0xFF) / 255f
                mask[i] = sqrt(dx * dx + dy * dy) <= rPx
            }
        }
        if (!mask.any { it }) return false
        val passes = rPx.toInt().coerceIn(8, 64)
        RetouchMath.onionPeelInpaint(r, g, b, mask, bw, bh, passes)
        // Documented fallback: unfilled interior takes the annulus median.
        var remainder = false
        for (m in mask) {
            if (m) {
                remainder = true
                break
            }
        }
        var median = floatArrayOf(0f, 0f, 0f)
        if (remainder) {
            median = retouchAnnulusMedian(pixels, w, h, op.cx, op.cy, rPx)
        }
        var touched = false
        for (y in 0 until bh) {
            for (x in 0 until bw) {
                val gx = bounds[0] + x
                val gy = bounds[1] + y
                val dx = gx - px
                val dy = gy - py
                val dist = sqrt(dx * dx + dy * dy)
                if (dist > rPx) continue
                val alpha = RetouchMath.featherAlpha(dist, rPx, op.feather) *
                    op.opacity.coerceIn(0f, 1f)
                if (alpha <= 0.001f) continue
                val i = y * bw + x
                val fr: Float
                val fg: Float
                val fb: Float
                if (mask[i]) {
                    fr = median[0]; fg = median[1]; fb = median[2]
                } else {
                    fr = r[i]; fg = g[i]; fb = b[i]
                }
                val gi = gy * w + gx
                val dPx = pixels[gi]
                val a = dPx ushr 24
                val rr = RetouchMath.mix(((dPx shr 16) and 0xFF) / 255f, fr, alpha)
                val gg = RetouchMath.mix(((dPx shr 8) and 0xFF) / 255f, fg, alpha)
                val bb = RetouchMath.mix((dPx and 0xFF) / 255f, fb, alpha)
                pixels[gi] = (a shl 24) or
                    ((rr * 255f + 0.5f).toInt().coerceIn(0, 255) shl 16) or
                    ((gg * 255f + 0.5f).toInt().coerceIn(0, 255) shl 8) or
                    (bb * 255f + 0.5f).toInt().coerceIn(0, 255)
                touched = true
            }
        }
        return touched
    }

    /**
     * M8 lens blur (§27, honest heuristic): depth = distance-from-focus-point
     * field with transition control (NOT AI, no depth sensor); render blends
     * 3 box-blur levels (base/mild/strong via downscale-upscale, same cheap
     * approx family as the luma-NR stage) by smooth depth-band weights. The
     * focus ellipse (radius [LensBlurParams.focusRadius]) stays sharp.
     * Early-out (returns [src]) when [blur] is default.
     */
    fun applyLensBlur(
        src: Bitmap,
        blur: LensBlurParams,
        quality: RenderQuality = RenderQuality.PREVIEW
    ): Bitmap {
        if (blur.isDefault()) return src
        val w = src.width
        val h = src.height
        if (w <= 0 || h <= 0) return src
        return try {
            val mild = scaledBlurBitmap(src, LensBlurMath.downscaleFactor(blur.amount, 1))
            val strong = scaledBlurBitmap(src, LensBlurMath.downscaleFactor(blur.amount, 2))
            val total = w * h
            val basePx = IntArray(total)
            val mildPx = IntArray(total)
            val strongPx = IntArray(total)
            src.getPixels(basePx, 0, w, 0, 0, w, h)
            mild.getPixels(mildPx, 0, w, 0, 0, w, h)
            strong.getPixels(strongPx, 0, w, 0, 0, w, h)
            val aspect = (w.toFloat() / h.toFloat().coerceAtLeast(1f))
                .let { if (it.isFinite() && it > 0f) it else 1f }
            val out = IntArray(total)
            for (y in 0 until h) {
                val ny = if (h > 1) y.toFloat() / (h - 1).toFloat() else 0.5f
                for (x in 0 until w) {
                    val nx = if (w > 1) x.toFloat() / (w - 1).toFloat() else 0.5f
                    val depth = LensBlurMath.depthAt(
                        nx, ny, blur.focusX, blur.focusY,
                        blur.focusRadius, blur.transition, aspect
                    )
                    val i = y * w + x
                    if (depth <= 0.001f) {
                        out[i] = basePx[i]
                        continue
                    }
                    val weights = LensBlurMath.bandWeights(depth)
                    val bp = basePx[i]
                    val mp = mildPx[i]
                    val sp = strongPx[i]
                    val a = bp ushr 24
                    val r = (((bp shr 16) and 0xFF) * weights[0] +
                        ((mp shr 16) and 0xFF) * weights[1] +
                        ((sp shr 16) and 0xFF) * weights[2] + 0.5f)
                        .toInt().coerceIn(0, 255)
                    val g = (((bp shr 8) and 0xFF) * weights[0] +
                        ((mp shr 8) and 0xFF) * weights[1] +
                        ((sp shr 8) and 0xFF) * weights[2] + 0.5f)
                        .toInt().coerceIn(0, 255)
                    val bl = ((bp and 0xFF) * weights[0] +
                        (mp and 0xFF) * weights[1] +
                        (sp and 0xFF) * weights[2] + 0.5f)
                        .toInt().coerceIn(0, 255)
                    out[i] = (a shl 24) or (r shl 16) or (g shl 8) or bl
                }
            }
            if (mild !== src) {
                try {
                    if (!mild.isRecycled) mild.recycle()
                } catch (_: Exception) {
                }
            }
            if (strong !== src && strong !== mild) {
                try {
                    if (!strong.isRecycled) strong.recycle()
                } catch (_: Exception) {
                }
            }
            val result = createWorkingBitmap(w, h, quality)
            result.setPixels(out, 0, w, 0, 0, w, h)
            result
        } catch (_: Exception) {
            src
        }
    }

    private fun scaledBlurBitmap(src: Bitmap, factor: Float): Bitmap {
        val w = src.width
        val h = src.height
        if (factor >= 0.999f) return src
        val f = factor.coerceIn(0.05f, 1f)
        val sw = (w * f + 0.5f).toInt().coerceIn(1, w)
        val sh = (h * f + 0.5f).toInt().coerceIn(1, h)
        if (sw >= w && sh >= h) return src
        return try {
            val small = Bitmap.createScaledBitmap(src, sw, sh, true)
            val up = Bitmap.createScaledBitmap(small, w, h, true)
            try {
                if (!small.isRecycled) small.recycle()
            } catch (_: Exception) {
            }
            up
        } catch (_: Exception) {
            src
        }
    }

    /**
     * M8 depth-map preview (§27): grayscale rendering of the heuristic depth
     * field (black = protected focus, white = fully blurred) on a downscaled
     * copy for the show-depth-map toggle. Reuses the overlay pattern (caller
     * owns the bitmap). Returns null when blur is default.
     */
    fun buildLensDepthPreview(
        src: Bitmap,
        blur: LensBlurParams,
        maxDim: Int = 192
    ): Bitmap? {
        if (blur.isDefault()) return null
        val w = src.width
        val h = src.height
        if (w <= 0 || h <= 0) return null
        return try {
            val longest = maxOf(w, h)
            val scale = if (longest <= maxDim) 1f else maxDim.toFloat() / longest.toFloat()
            val sw = (w * scale + 0.5f).toInt().coerceIn(1, w)
            val sh = (h * scale + 0.5f).toInt().coerceIn(1, h)
            val aspect = (sw.toFloat() / sh.toFloat().coerceAtLeast(1f))
                .let { if (it.isFinite() && it > 0f) it else 1f }
            val pixels = IntArray(sw * sh)
            for (y in 0 until sh) {
                val ny = if (sh > 1) y.toFloat() / (sh - 1).toFloat() else 0.5f
                for (x in 0 until sw) {
                    val nx = if (sw > 1) x.toFloat() / (sw - 1).toFloat() else 0.5f
                    val depth = LensBlurMath.depthAt(
                        nx, ny, blur.focusX, blur.focusY,
                        blur.focusRadius, blur.transition, aspect
                    )
                    val v = (depth * 255f + 0.5f).toInt().coerceIn(0, 255)
                    pixels[y * sw + x] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
                }
            }
            val out = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
            out.setPixels(pixels, 0, sw, 0, 0, sw, sh)
            out
        } catch (_: Exception) {
            null
        }
    }

    /**
     * M8 dust scan (§28, honest subset): luminance-outlier detection on a
     * downsampled copy (longest side [maxDim]). Returns transient "dust
     * candidate" spots for INSPECTION — nothing is healed until the user
     * confirms (confirmed candidates become HEAL ops). No fake claims.
     */
    fun detectDustCandidates(
        src: Bitmap,
        sensitivity: Float,
        maxDim: Int = 256
    ): List<DustCandidate> {
        val w = src.width
        val h = src.height
        if (w <= 0 || h <= 0) return emptyList()
        return try {
            val longest = maxOf(w, h)
            val scale = if (longest <= maxDim) 1f else maxDim.toFloat() / longest.toFloat()
            val sw = (w * scale + 0.5f).toInt().coerceIn(1, w)
            val sh = (h * scale + 0.5f).toInt().coerceIn(1, h)
            val small = if (sw == w && sh == h) src
            else Bitmap.createScaledBitmap(src, sw, sh, true)
            try {
                val total = sw * sh
                val pixels = IntArray(total)
                small.getPixels(pixels, 0, sw, 0, 0, sw, sh)
                val luma = FloatArray(total)
                for (i in 0 until total) {
                    val p = pixels[i]
                    luma[i] = RetouchMath.luma(
                        ((p shr 16) and 0xFF) / 255f,
                        ((p shr 8) and 0xFF) / 255f,
                        (p and 0xFF) / 255f
                    )
                }
                DustMath.detectCandidates(luma, sw, sh, sensitivity)
            } finally {
                if (small !== src) {
                    try {
                        if (!small.isRecycled) small.recycle()
                    } catch (_: Exception) {
                    }
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun applyMasks(
        src: Bitmap,
        params: EditParams,
        quality: RenderQuality = RenderQuality.PREVIEW
    ): Bitmap {
        val masks = params.masks
        if (masks.isEmpty()) return src
        val w = src.width
        val h = src.height
        if (w <= 0 || h <= 0) return src
        val visible = masks.filter { it.visible && it.opacity > 0.001f }
        if (visible.isEmpty()) return src
        // M7: SUBTRACT erases prior grades (active even with a default grade);
        // eraser always active; otherwise the mask needs a local grade.
        val active = visible.filter {
            if (it.tool == MaskTool.ERASER) true
            else if (it.op == MaskOp.SUBTRACT) true
            else it.hasLocalGrade()
        }
        if (active.isEmpty()) return src
        return try {
            val total = w * h
            val pixels = IntArray(total)
            src.getPixels(pixels, 0, w, 0, 0, w, h)
            val baseCopy = pixels.clone()
            // Range weights read the pre-mask (post-details) frame so stacked
            // local grades never shift another mask's selection.
            val needHues = active.any { it.tool == MaskTool.COLOR }
            val needLumas = active.any { it.tool == MaskTool.LUMINANCE }
            val baseHues = if (needHues) FloatArray(total) else null
            val baseLumas = if (needLumas) FloatArray(total) else null
            if (baseHues != null || baseLumas != null) {
                for (i in 0 until total) {
                    val p = baseCopy[i]
                    val r = ((p shr 16) and 0xFF) / 255f
                    val g = ((p shr 8) and 0xFF) / 255f
                    val b = (p and 0xFF) / 255f
                    if (baseHues != null) baseHues[i] = rgbToHsl(r, g, b)[0]
                    if (baseLumas != null) {
                        baseLumas[i] = (0.2126f * r + 0.7152f * g + 0.0722f * b).coerceIn(0f, 1f)
                    }
                }
            }
            // Accumulated selection alpha (list order); ops compose against it.
            val acc = FloatArray(total)
            for (mask in active) {
                var raw = computeRawAlpha(mask, w, h, baseHues, baseLumas)
                if (mask.blur > 0.001f) raw = blurAlphaDownUp(raw, w, h, mask.blur)
                applyMaskWithOp(pixels, baseCopy, acc, raw, w, h, mask)
            }
            val out = createWorkingBitmap(w, h, quality)
            out.setPixels(pixels, 0, w, 0, 0, w, h)
            out
        } catch (_: Exception) {
            src
        }
    }

    private fun computeRawAlpha(
        mask: EditMask, w: Int, h: Int, baseHues: FloatArray?, baseLumas: FloatArray?
    ): FloatArray {
        val total = w * h
        val out = FloatArray(total)
        val opacity = mask.opacity.coerceIn(0f, 1f)
        if (opacity <= 0.001f) return out
        val inverted = mask.inverted
        when (mask.tool) {
            MaskTool.COLOR -> {
                val hues = baseHues ?: return out
                for (i in 0 until total) {
                    val weight = MaskRangeMath.colorWeight(hues[i], mask.hueCenter, mask.hueRange)
                    out[i] = (if (inverted) 1f - weight else weight) * opacity
                }
            }
            MaskTool.LUMINANCE -> {
                val lumas = baseLumas ?: return out
                for (i in 0 until total) {
                    val weight = MaskRangeMath.lumaWeight(lumas[i], mask.lumaLo, mask.lumaHi, mask.lumaFeather)
                    out[i] = (if (inverted) 1f - weight else weight) * opacity
                }
            }
            MaskTool.RADIAL -> fillRadialAlpha(out, w, h, mask, opacity, inverted)
            MaskTool.LINEAR -> fillLinearAlpha(out, w, h, mask, opacity, inverted)
            MaskTool.BRUSH, MaskTool.ERASER -> fillStrokeAlpha(out, w, h, mask, opacity, inverted)
            // M12 heuristic select: alpha resolves from the cached analysis
            // field (primed from files/<id>/metadata/ on project open, so
            // preview and export share it). Missing field = honest no-op
            // (zeros); the same feather/blur/opacity/invert/op path below
            // applies, so local exposure/color/grade/blur grades integrate
            // with zero extra render code.
            MaskTool.AI_SUBJECT, MaskTool.AI_SKY -> {
                val field = AiMaskFieldStore.resampledAlpha(mask.cacheKey, w, h)
                if (field != null && field.size == total) {
                    for (i in 0 until total) {
                        val weight = field[i].coerceIn(0f, 1f)
                        out[i] = (if (inverted) 1f - weight else weight) * opacity
                    }
                }
            }
        }
        return out
    }

    /**
     * M7 mask blur: cheap box approx on the alpha field via
     * downsample-then-upsample (block-average down, nearest-neighbor up).
     * Radius maps blur 0..50 to a downsample factor 1..8.
     */
    internal fun blurAlphaDownUp(alpha: FloatArray, w: Int, h: Int, blur: Float): FloatArray {
        if (blur <= 0.001f) return alpha
        val total = w * h
        if (total <= 0) return alpha
        val factor = (1f + (blur.coerceIn(0f, EditMask.MAX_BLUR) / EditMask.MAX_BLUR) * 7f)
            .coerceIn(1f, 8f)
        if (factor <= 1.001f) return alpha
        val sw = (w / factor + 0.5f).toInt().coerceIn(1, w)
        val sh = (h / factor + 0.5f).toInt().coerceIn(1, h)
        if (sw >= w && sh >= h) return alpha
        return try {
            val small = FloatArray(sw * sh)
            for (sy in 0 until sh) {
                val y0 = ((sy * h) / sh).coerceIn(0, h - 1)
                val y1 = (((sy + 1) * h) / sh).coerceIn(y0 + 1, h)
                for (sx in 0 until sw) {
                    val x0 = ((sx * w) / sw).coerceIn(0, w - 1)
                    val x1 = (((sx + 1) * w) / sw).coerceIn(x0 + 1, w)
                    var sum = 0f
                    var n = 0
                    for (y in y0 until y1) {
                        var idx = y * w + x0
                        for (x in x0 until x1) {
                            sum += alpha[idx]
                            n++
                            idx++
                        }
                    }
                    small[sy * sw + sx] = if (n > 0) sum / n else 0f
                }
            }
            val out = FloatArray(total)
            for (y in 0 until h) {
                val sy = ((y * sh) / h).coerceIn(0, sh - 1)
                for (x in 0 until w) {
                    val sx = ((x * sw) / w).coerceIn(0, sw - 1)
                    out[y * w + x] = small[sy * sw + sx]
                }
            }
            out
        } catch (_: Exception) {
            alpha
        }
    }

    private fun applyMaskWithOp(
        pixels: IntArray, baseCopy: IntArray, acc: FloatArray,
        raw: FloatArray, w: Int, h: Int, mask: EditMask
    ) {
        val total = w * h
        if (raw.size != total || acc.size != total) return
        val isEraser = mask.tool == MaskTool.ERASER
        val expScale = if (isEraser) 1f else 2f.pow(mask.exposure).coerceIn(0.1f, 8f)
        val rGain = if (isEraser) 1f else (1f + mask.temperature / 150f).coerceIn(0.4f, 2f)
        val bGain = if (isEraser) 1f else (1f - mask.temperature / 150f).coerceIn(0.4f, 2f)
        val sat = if (isEraser) 0f else mask.saturation.coerceIn(-100f, 100f)
        val micro = if (isEraser) 0f else mask.clarity.coerceIn(-100f, 100f)
        val op = mask.op
        val isErase = isEraser || op == MaskOp.SUBTRACT
        for (i in 0 until total) {
            val a = raw[i].coerceIn(0f, 1f)
            val prev = acc[i].coerceIn(0f, 1f)
            val e = MaskRangeMath.effectiveAlpha(prev, a, op)
            acc[i] = MaskRangeMath.combineAcc(prev, a, op)
            if (e <= 0.001f) continue
            pixels[i] = if (isErase) {
                blendTowardBase(pixels[i], baseCopy[i], e)
            } else {
                blendPixelExtended(pixels[i], expScale, rGain, bGain, sat, micro, e)
            }
        }
    }

    private fun blendTowardBase(current: Int, base: Int, a: Float): Int {
        if (a <= 0.001f) return current
        val alpha = a.coerceIn(0f, 1f)
        if (alpha >= 0.999f) return (current and -0x1000000) or (base and 0x00FFFFFF)
        val r0 = ((current shr 16) and 0xFF)
        val g0 = ((current shr 8) and 0xFF)
        val b0 = (current and 0xFF)
        val r1 = ((base shr 16) and 0xFF)
        val g1 = ((base shr 8) and 0xFF)
        val b1 = (base and 0xFF)
        val r = (r0 + (r1 - r0) * alpha + 0.5f).toInt().coerceIn(0, 255)
        val g = (g0 + (g1 - g0) * alpha + 0.5f).toInt().coerceIn(0, 255)
        val b = (b0 + (b1 - b0) * alpha + 0.5f).toInt().coerceIn(0, 255)
        val al = current ushr 24
        return (al shl 24) or (r shl 16) or (g shl 8) or b
    }

    /**
     * M7 local grade blend: exposure + temperature gains (same gamma-domain
     * sRGB approximations as the global buildMatrix stage), then saturation
     * (luma-anchored interpolation, same scale as the global sat stage) and
     * clarity-micro (gentle pivot contrast, same family as the details
     * micro-contrast approx). Gamma-domain by design — matches overlay UX;
     * linear blend would change every existing mask (see M5 audit).
     */
    private fun blendPixelExtended(
        current: Int, expScale: Float, rGain: Float, bGain: Float,
        satAdjust: Float, clarityAdjust: Float, a: Float
    ): Int {
        if (a <= 0.001f) return current
        val alpha = a.coerceIn(0f, 1f)
        val r0 = ((current shr 16) and 0xFF) / 255f
        val g0 = ((current shr 8) and 0xFF) / 255f
        val b0 = (current and 0xFF) / 255f
        var tr = (r0 * expScale * rGain).coerceIn(0f, 1f)
        var tg = (g0 * expScale).coerceIn(0f, 1f)
        var tb = (b0 * expScale * bGain).coerceIn(0f, 1f)
        if (satAdjust != 0f) {
            val satF = (1f + satAdjust / 100f).coerceIn(0f, 3f)
            if (satF != 1f) {
                val luma = 0.2126f * tr + 0.7152f * tg + 0.0722f * tb
                tr = (luma + (tr - luma) * satF).coerceIn(0f, 1f)
                tg = (luma + (tg - luma) * satF).coerceIn(0f, 1f)
                tb = (luma + (tb - luma) * satF).coerceIn(0f, 1f)
            }
        }
        if (clarityAdjust != 0f) {
            val k = (1f + clarityAdjust / 100f * 0.3f).coerceIn(0f, 2f)
            if (k != 1f) {
                tr = (0.5f + (tr - 0.5f) * k).coerceIn(0f, 1f)
                tg = (0.5f + (tg - 0.5f) * k).coerceIn(0f, 1f)
                tb = (0.5f + (tb - 0.5f) * k).coerceIn(0f, 1f)
            }
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

    private fun fillRadialAlpha(
        out: FloatArray, w: Int, h: Int, mask: EditMask, opacity: Float, inverted: Boolean
    ) {
        val cx = mask.centerX.coerceIn(0f, 1f) * w
        val cy = mask.centerY.coerceIn(0f, 1f) * h
        val radiusPx = (mask.radius.coerceIn(0.01f, 1f) * minOf(w, h).toFloat()).coerceAtLeast(1f)
        val feather = mask.feather.coerceIn(0f, 1f)
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
                out[idx] = (if (inverted) 1f - raw else raw) * opacity
                idx++
            }
        }
    }

    private fun fillLinearAlpha(
        out: FloatArray, w: Int, h: Int, mask: EditMask, opacity: Float, inverted: Boolean
    ) {
        val rad = mask.angleDeg * Math.PI.toFloat() / 180f
        val cosA = cos(rad)
        val sinA = sin(rad)
        val position = mask.position.coerceIn(0f, 1f)
        val feather = mask.feather.coerceIn(0f, 1f)
        val fw = 0.02f + feather * 0.3f
        val wf = w.toFloat()
        val hf = h.toFloat()
        for (y in 0 until h) {
            val ny = if (hf > 0f) y / hf else 0.5f
            var idx = y * w
            for (x in 0 until w) {
                val nx = if (wf > 0f) x / wf else 0.5f
                val p = (nx - 0.5f) * cosA + (ny - 0.5f) * sinA + 0.5f
                val raw = 1f - smoothstep(position - fw / 2f, position + fw / 2f, p)
                out[idx] = (if (inverted) 1f - raw else raw) * opacity
                idx++
            }
        }
    }

    private fun fillStrokeAlpha(
        out: FloatArray, w: Int, h: Int, mask: EditMask, opacity: Float, inverted: Boolean
    ) {
        val pts = mask.points
        if (pts.isEmpty()) return
        val minDim = minOf(w, h).toFloat().coerceAtLeast(1f)
        val radiusPx = ((mask.sizePx.coerceIn(EditMask.MIN_SIZE_PX, EditMask.MAX_SIZE_PX) * 0.5f) * (minDim / 1000f)).coerceAtLeast(1f)
        val feather = mask.feather.coerceIn(0f, 1f)
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
                    out[idx] = raw * opacity
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
                    out[idx] = a
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
