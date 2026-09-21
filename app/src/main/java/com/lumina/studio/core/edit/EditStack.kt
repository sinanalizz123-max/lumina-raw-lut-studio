package com.lumina.studio.core.edit

import com.lumina.studio.core.data.local.Project
import kotlin.math.roundToInt

enum class AdjustControl(
    val key: String,
    val label: String,
    val min: Float,
    val max: Float,
    val step: Float,
    val default: Float
) {
    EXPOSURE("exposure", "Exposure", -5f, 5f, 0.1f, 0f),
    CONTRAST("contrast", "Contrast", -100f, 100f, 1f, 0f),
    HIGHLIGHTS("highlights", "Highlights", -100f, 100f, 1f, 0f),
    SHADOWS("shadows", "Shadows", -100f, 100f, 1f, 0f),
    WHITES("whites", "Whites", -100f, 100f, 1f, 0f),
    BLACKS("blacks", "Blacks", -100f, 100f, 1f, 0f),
    TEMPERATURE("temperature", "Temperature", -100f, 100f, 1f, 0f),
    TINT("tint", "Tint", -100f, 100f, 1f, 0f),
    SATURATION("saturation", "Saturation", -100f, 100f, 1f, 0f),
    VIBRANCE("vibrance", "Vibrance", -100f, 100f, 1f, 0f),
    CLARITY("clarity", "Clarity", -100f, 100f, 1f, 0f),
    DEHAZE("dehaze", "Dehaze", -100f, 100f, 1f, 0f),
    SHARPNESS("sharpness", "Sharpness", 0f, 100f, 1f, 0f);

    fun clamp(value: Float): Float = value.coerceIn(min, max)

    fun format(value: Float): String = when (this) {
        EXPOSURE -> {
            val rounded = (value * 10).roundToInt() / 10f
            (if (rounded > 0) "+" else "") + rounded.toString()
        }
        SHARPNESS -> value.roundToInt().toString()
        else -> (if (value > 0) "+" else "") + value.roundToInt().toString()
    }

    companion object {
        fun fromKey(key: String): AdjustControl? = entries.firstOrNull { it.key == key }
    }
}

enum class HslColor(val key: String, val label: String, val centerHueDeg: Float) {
    RED("red", "Red", 0f),
    ORANGE("orange", "Orange", 30f),
    YELLOW("yellow", "Yellow", 60f),
    GREEN("green", "Green", 120f),
    AQUA("aqua", "Aqua", 180f),
    BLUE("blue", "Blue", 240f),
    PURPLE("purple", "Purple", 275f),
    MAGENTA("magenta", "Magenta", 315f);

    companion object {
        fun fromKey(key: String): HslColor? = entries.firstOrNull { it.key == key }

        fun nearestForHue(hueDeg: Float): HslColor {
            val h = ((hueDeg % 360f) + 360f) % 360f
            var best: HslColor = RED
            var bestDist = Float.MAX_VALUE
            for (c in entries) {
                var d = kotlin.math.abs(h - c.centerHueDeg)
                if (d > 180f) d = 360f - d
                if (d < bestDist) {
                    bestDist = d
                    best = c
                }
            }
            return best
        }
    }
}

data class HslAdjust(
    val hue: Float = 0f,
    val sat: Float = 0f,
    val lum: Float = 0f
)

enum class CurveChannel(val key: String, val label: String) {
    MASTER("master", "Master"),
    RED("red", "R"),
    GREEN("green", "G"),
    BLUE("blue", "B");

    companion object {
        fun fromKey(key: String): CurveChannel? = entries.firstOrNull { it.key == key }
    }
}

data class CurvePoint(val x: Float, val y: Float)

object Curves {
    val DEFAULT_POINTS: List<CurvePoint> = listOf(CurvePoint(0f, 0f), CurvePoint(1f, 1f))

    fun sanitize(points: List<CurvePoint>?): List<CurvePoint> {
        if (points.isNullOrEmpty()) return DEFAULT_POINTS
        val clamped = points.map { CurvePoint(it.x.coerceIn(0f, 1f), it.y.coerceIn(0f, 1f)) }
            .sortedWith(compareBy<CurvePoint> { it.x }.thenBy { it.y })
        val deduped = ArrayList<CurvePoint>(clamped.size)
        for (p in clamped) {
            val last = deduped.lastOrNull()
            if (last != null && kotlin.math.abs(last.x - p.x) < 1e-6f) {
                deduped[deduped.lastIndex] = p
            } else {
                deduped.add(p)
            }
        }
        if (deduped.size < 2) return DEFAULT_POINTS
        val first = deduped.first()
        val last = deduped.last()
        if (deduped.size == 2 && first.x == 0f && first.y == 0f && last.x == 1f && last.y == 1f) {
            return DEFAULT_POINTS
        }
        return deduped
    }

    fun isDiagonal(points: List<CurvePoint>?): Boolean {
        if (points == null) return true
        if (points.size == 2) {
            val a = points[0]
            val b = points[1]
            if (a.x <= 0.0001f && a.y <= 0.0001f && b.x >= 0.9999f && b.y >= 0.9999f) return true
        }
        if (points.size < 2) return true
        return false
    }

    fun sampleCurve(points: List<CurvePoint>): FloatArray {
        val pts = sanitize(points)
        if (isDiagonal(pts)) {
            return FloatArray(256) { i -> i / 255f }
        }
        val n = pts.size
        val out = FloatArray(256)
        var seg = 0
        for (i in 0 until 256) {
            val x = i / 255f
            while (seg < n - 2 && x > pts[seg + 1].x) seg++
            val p1 = pts[seg]
            val p2 = pts[seg + 1]
            val p0 = if (seg > 0) pts[seg - 1] else p1
            val p3 = if (seg + 2 < n) pts[seg + 2] else p2
            val dx = (p2.x - p1.x).coerceAtLeast(1e-6f)
            val t = ((x - p1.x) / dx).coerceIn(0f, 1f)
            val t2 = t * t
            val t3 = t2 * t
            val y = 0.5f * (
                2f * p1.y +
                    (-p0.y + p2.y) * t +
                    (2f * p0.y - 5f * p1.y + 4f * p2.y - p3.y) * t2 +
                    (-p0.y + 3f * p1.y - 3f * p2.y + p3.y) * t3
                )
            out[i] = y.coerceIn(0f, 1f)
        }
        out[0] = pts.first().y.coerceIn(0f, 1f).let { if (pts.first().x <= 0.0001f) it else (out[0]) }
        return out
    }
}

enum class DetailControl(
    val key: String,
    val label: String,
    val min: Float,
    val max: Float,
    val default: Float
) {
    TEXTURE("texture", "Texture", -100f, 100f, 0f),
    CLARITY_ADV("clarityAdv", "Clarity", -100f, 100f, 0f),
    DEHAZE_ADV("dehazeAdv", "Dehaze", -100f, 100f, 0f),
    SHARPEN_AMOUNT("sharpenAmount", "Amount", 0f, 100f, 0f),
    SHARPEN_RADIUS("sharpenRadius", "Radius", 0f, 100f, 25f),
    NR_LUMA("nrLuma", "Luminance", 0f, 100f, 0f),
    NR_COLOR("nrColor", "Color", 0f, 100f, 25f);

    fun clamp(value: Float): Float = value.coerceIn(min, max)

    fun format(value: Float): String = value.roundToInt().toString()

    companion object {
        fun fromKey(key: String): DetailControl? = entries.firstOrNull { it.key == key }
    }
}

enum class CropRatio(val key: String, val label: String, val aspect: Float?) {
    FREE("free", "Free", null),
    ORIGINAL("original", "Original", null),
    R1_1("1_1", "1:1", 1f),
    R4_5("4_5", "4:5", 4f / 5f),
    R3_2("3_2", "3:2", 3f / 2f),
    R16_9("16_9", "16:9", 16f / 9f),
    R9_16("9_16", "9:16", 9f / 16f);

    companion object {
        fun fromKey(key: String?): CropRatio =
            entries.firstOrNull { it.key == key || it.name == key } ?: FREE
    }
}

/**
 * Shared free-crop rectangle math (pure JVM, no android.*).
 *
 * The custom rect is stored as fractions 0..1 of the post-rotate /
 * straighten / flip frame. The overlay maps them to screen pixels with
 * [pixelRect]'s float analog (imgLeft + l * drawnW, ...), and
 * PreviewRenderer.cropBitmap cuts with [pixelRect] itself, so the overlay
 * box always matches the cut. Overlay and renderer must both go through
 * this object when the formula changes.
 */
object CropRects {
    const val MIN_SIZE = 0.05f

    fun sanitize(left: Float, top: Float, right: Float, bottom: Float): FloatArray {
        var l = left.coerceIn(0f, 1f)
        var t = top.coerceIn(0f, 1f)
        var r = right.coerceIn(0f, 1f)
        var b = bottom.coerceIn(0f, 1f)
        if (r < l) {
            val tmp = l
            l = r
            r = tmp
        }
        if (b < t) {
            val tmp = t
            t = b
            b = tmp
        }
        if (r - l < MIN_SIZE) {
            r = (l + MIN_SIZE).coerceAtMost(1f)
            l = (r - MIN_SIZE).coerceAtLeast(0f)
        }
        if (b - t < MIN_SIZE) {
            b = (t + MIN_SIZE).coerceAtMost(1f)
            t = (b - MIN_SIZE).coerceAtLeast(0f)
        }
        return floatArrayOf(l, t, r, b)
    }

    fun isFullFrame(left: Float, top: Float, right: Float, bottom: Float): Boolean =
        left == 0f && top == 0f && right == 1f && bottom == 1f

    /**
     * Fraction rect -> pixel cut [left, top, width, height] on a w*h frame.
     * Same proportional formula the overlay uses in float pixels.
     */
    fun pixelRect(left: Float, top: Float, right: Float, bottom: Float, w: Int, h: Int): IntArray {
        if (w <= 0 || h <= 0) return intArrayOf(0, 0, 0, 0)
        val s = sanitize(left, top, right, bottom)
        val l = (s[0] * w + 0.5f).toInt().coerceIn(0, w - 1)
        val t = (s[1] * h + 0.5f).toInt().coerceIn(0, h - 1)
        val r = (s[2] * w + 0.5f).toInt().coerceIn(l + 1, w)
        val b = (s[3] * h + 0.5f).toInt().coerceIn(t + 1, h)
        return intArrayOf(l, t, r - l, b - t)
    }
}

data class CropParams(
    val ratio: CropRatio = CropRatio.FREE,
    val rotationSteps: Int = 0,
    val straightenDeg: Float = 0f,
    val flipH: Boolean = false,
    val flipV: Boolean = false,
    val customLeft: Float = 0f,
    val customTop: Float = 0f,
    val customRight: Float = 1f,
    val customBottom: Float = 1f
) {
    fun isFullFrameRect(): Boolean =
        CropRects.isFullFrame(customLeft, customTop, customRight, customBottom)

    fun isDefault(): Boolean =
        ratio == CropRatio.FREE && rotationSteps == 0 &&
            straightenDeg == 0f && !flipH && !flipV && isFullFrameRect()

    fun withRatio(ratio: CropRatio): CropParams =
        if (this.ratio == ratio) this else copy(ratio = ratio)

    fun withRotationSteps(steps: Int): CropParams {
        val v = ((steps % 4) + 4) % 4
        return if (rotationSteps == v) this else copy(rotationSteps = v)
    }

    fun rotated90(): CropParams = withRotationSteps(rotationSteps + 1)

    fun withStraighten(deg: Float): CropParams {
        val v = deg.coerceIn(-45f, 45f)
        return if (straightenDeg == v) this else copy(straightenDeg = v)
    }

    fun withFlipH(flip: Boolean): CropParams =
        if (flipH == flip) this else copy(flipH = flip)

    fun withFlipV(flip: Boolean): CropParams =
        if (flipV == flip) this else copy(flipV = flip)

    fun withCustomRect(left: Float, top: Float, right: Float, bottom: Float): CropParams {
        val s = CropRects.sanitize(left, top, right, bottom)
        return if (customLeft == s[0] && customTop == s[1] &&
            customRight == s[2] && customBottom == s[3]
        ) {
            this
        } else {
            copy(customLeft = s[0], customTop = s[1], customRight = s[2], customBottom = s[3])
        }
    }
}

enum class MaskTool(val label: String) {
    BRUSH("Brush"),
    ERASER("Eraser"),
    LINEAR("Linear"),
    RADIAL("Radial");

    companion object {
        fun fromKey(key: String?): MaskTool =
            entries.firstOrNull { it.name == key } ?: BRUSH
    }
}

data class MaskPoint(val x: Float, val y: Float)

data class EditMask(
    val id: String,
    val tool: MaskTool = MaskTool.BRUSH,
    val sizePx: Float = 80f,
    val feather: Float = 0.5f,
    val opacity: Float = 1f,
    val inverted: Boolean = false,
    val visible: Boolean = true,
    val centerX: Float = 0.5f,
    val centerY: Float = 0.5f,
    val radius: Float = 0.3f,
    val angleDeg: Float = 0f,
    val position: Float = 0.5f,
    val points: List<MaskPoint> = emptyList(),
    val exposure: Float = 0f,
    val temperature: Float = 0f
) {
    companion object {
        const val MIN_SIZE_PX = 1f
        const val MAX_SIZE_PX = 500f
        const val MAX_POINTS = 64

        fun sanitizePoints(points: List<MaskPoint>?): List<MaskPoint> {
            if (points.isNullOrEmpty()) return emptyList()
            val clamped = points.map {
                MaskPoint(it.x.coerceIn(0f, 1f), it.y.coerceIn(0f, 1f))
            }
            if (clamped.size <= MAX_POINTS) return clamped
            val step = clamped.size.toFloat() / MAX_POINTS.toFloat()
            val out = ArrayList<MaskPoint>(MAX_POINTS)
            for (n in 0 until MAX_POINTS) {
                val idx = (n * step).toInt().coerceIn(0, clamped.size - 1)
                val candidate = clamped[idx]
                if (out.isEmpty() || out.last() != candidate) out.add(candidate)
            }
            return out.take(MAX_POINTS)
        }
    }

    fun withSizePx(v: Float): EditMask {
        val c = v.coerceIn(MIN_SIZE_PX, MAX_SIZE_PX)
        return if (sizePx == c) this else copy(sizePx = c)
    }

    fun withFeather(v: Float): EditMask {
        val c = v.coerceIn(0f, 1f)
        return if (feather == c) this else copy(feather = c)
    }

    fun withOpacity(v: Float): EditMask {
        val c = v.coerceIn(0f, 1f)
        return if (opacity == c) this else copy(opacity = c)
    }

    fun withCenter(x: Float, y: Float): EditMask {
        val cx = x.coerceIn(0f, 1f)
        val cy = y.coerceIn(0f, 1f)
        return if (centerX == cx && centerY == cy) this else copy(centerX = cx, centerY = cy)
    }

    fun withRadius(v: Float): EditMask {
        val c = v.coerceIn(0.01f, 1f)
        return if (radius == c) this else copy(radius = c)
    }

    fun withAngle(v: Float): EditMask {
        var c = v % 360f
        if (c < 0f) c += 360f
        return if (angleDeg == c) this else copy(angleDeg = c)
    }

    fun withPosition(v: Float): EditMask {
        val c = v.coerceIn(0f, 1f)
        return if (position == c) this else copy(position = c)
    }

    fun withExposure(v: Float): EditMask {
        val c = v.coerceIn(-5f, 5f)
        return if (exposure == c) this else copy(exposure = c)
    }

    fun withTemperature(v: Float): EditMask {
        val c = v.coerceIn(-100f, 100f)
        return if (temperature == c) this else copy(temperature = v.coerceIn(-100f, 100f))
    }
}

enum class StepKey(val key: String) {
    PRESET("preset"),
    LUT("lut"),
    ADJUSTS("adjusts"),
    COLOR("color"),
    CURVES("curves"),
    DETAILS("details"),
    MASKS("masks"),
    CROP("crop");

    companion object {
        fun fromKey(key: String?): StepKey? = entries.firstOrNull { it.key == key }
    }
}

data class StepsEnabled(
    val preset: Boolean = true,
    val lut: Boolean = true,
    val adjusts: Boolean = true,
    val color: Boolean = true,
    val curves: Boolean = true,
    val details: Boolean = true,
    val masks: Boolean = true,
    val crop: Boolean = true
) {
    fun get(key: StepKey): Boolean = when (key) {
        StepKey.PRESET -> preset
        StepKey.LUT -> lut
        StepKey.ADJUSTS -> adjusts
        StepKey.COLOR -> color
        StepKey.CURVES -> curves
        StepKey.DETAILS -> details
        StepKey.MASKS -> masks
        StepKey.CROP -> crop
    }

    fun with(key: StepKey, enabled: Boolean): StepsEnabled = when (key) {
        StepKey.PRESET -> if (preset == enabled) this else copy(preset = enabled)
        StepKey.LUT -> if (lut == enabled) this else copy(lut = enabled)
        StepKey.ADJUSTS -> if (adjusts == enabled) this else copy(adjusts = enabled)
        StepKey.COLOR -> if (color == enabled) this else copy(color = enabled)
        StepKey.CURVES -> if (curves == enabled) this else copy(curves = enabled)
        StepKey.DETAILS -> if (details == enabled) this else copy(details = enabled)
        StepKey.MASKS -> if (masks == enabled) this else copy(masks = enabled)
        StepKey.CROP -> if (crop == enabled) this else copy(crop = enabled)
    }

    fun isAllEnabled(): Boolean =
        preset && lut && adjusts && color && curves && details && masks && crop
}

data class EditParams(
    val exposure: Float = 0f,
    val contrast: Float = 0f,
    val highlights: Float = 0f,
    val shadows: Float = 0f,
    val whites: Float = 0f,
    val blacks: Float = 0f,
    val temperature: Float = 0f,
    val tint: Float = 0f,
    val saturation: Float = 0f,
    val vibrance: Float = 0f,
    val clarity: Float = 0f,
    val dehaze: Float = 0f,
    val sharpness: Float = 0f,
    val presetId: String? = null,
    val presetIntensity: Float = 1f,
    val globalSat: Float = 0f,
    val globalVib: Float = 0f,
    val hsl: Map<HslColor, HslAdjust> = defaultHslMap(),
    val curves: Map<CurveChannel, List<CurvePoint>> = defaultCurvesMap(),
    val texture: Float = 0f,
    val clarityAdv: Float = 0f,
    val dehazeAdv: Float = 0f,
    val sharpenAmount: Float = 0f,
    val sharpenRadius: Float = 25f,
    val nrLuma: Float = 0f,
    val nrColor: Float = 25f,
    val crop: CropParams = CropParams(),
    val masks: List<EditMask> = emptyList(),
    val steps: StepsEnabled = StepsEnabled()
) {
    fun get(control: AdjustControl): Float = when (control) {
        AdjustControl.EXPOSURE -> exposure
        AdjustControl.CONTRAST -> contrast
        AdjustControl.HIGHLIGHTS -> highlights
        AdjustControl.SHADOWS -> shadows
        AdjustControl.WHITES -> whites
        AdjustControl.BLACKS -> blacks
        AdjustControl.TEMPERATURE -> temperature
        AdjustControl.TINT -> tint
        AdjustControl.SATURATION -> saturation
        AdjustControl.VIBRANCE -> vibrance
        AdjustControl.CLARITY -> clarity
        AdjustControl.DEHAZE -> dehaze
        AdjustControl.SHARPNESS -> sharpness
    }

    fun with(control: AdjustControl, value: Float): EditParams {
        val v = control.clamp(value)
        return when (control) {
            AdjustControl.EXPOSURE -> copy(exposure = v)
            AdjustControl.CONTRAST -> copy(contrast = v)
            AdjustControl.HIGHLIGHTS -> copy(highlights = v)
            AdjustControl.SHADOWS -> copy(shadows = v)
            AdjustControl.WHITES -> copy(whites = v)
            AdjustControl.BLACKS -> copy(blacks = v)
            AdjustControl.TEMPERATURE -> copy(temperature = v)
            AdjustControl.TINT -> copy(tint = v)
            AdjustControl.SATURATION -> copy(saturation = v)
            AdjustControl.VIBRANCE -> copy(vibrance = v)
            AdjustControl.CLARITY -> copy(clarity = v)
            AdjustControl.DEHAZE -> copy(dehaze = v)
            AdjustControl.SHARPNESS -> copy(sharpness = v)
        }
    }

    fun resetControl(control: AdjustControl): EditParams = with(control, control.default)

    fun resetAll(): EditParams = DEFAULT.copy(presetId = presetId, presetIntensity = presetIntensity)

    fun resetAdjusts(): EditParams {
        if (isAdjustsDefault()) return this
        var next = this
        for (control in AdjustControl.entries) {
            next = next.with(control, control.default)
        }
        return next
    }

    fun clearPreset(): EditParams {
        if (presetId == null) return this
        return copy(presetId = null, presetIntensity = 1f)
    }

    fun resetLutIntensity(): EditParams {
        if (presetIntensity == 1f) return this
        return copy(presetIntensity = 1f)
    }

    fun isAdjustsDefault(): Boolean = AdjustControl.entries.all { get(it) == it.default }

    fun isDefault(): Boolean =
        isAdjustsDefault() && isHslDefault() && isCurvesDefault() &&
            isDetailsDefault() && isCropDefault() && isMasksDefault()

    fun toMap(): Map<String, Float> = AdjustControl.entries.associate { it.key to get(it) }

    fun getHsl(color: HslColor): HslAdjust = hsl[color] ?: HslAdjust()

    fun withHsl(color: HslColor, adjust: HslAdjust): EditParams {
        val clamped = HslAdjust(
            hue = adjust.hue.coerceIn(-100f, 100f),
            sat = adjust.sat.coerceIn(-100f, 100f),
            lum = adjust.lum.coerceIn(-100f, 100f)
        )
        if (getHsl(color) == clamped) return this
        return copy(hsl = hsl + (color to clamped))
    }

    fun withHslHue(color: HslColor, value: Float): EditParams =
        withHsl(color, getHsl(color).copy(hue = value.coerceIn(-100f, 100f)))

    fun withHslSat(color: HslColor, value: Float): EditParams =
        withHsl(color, getHsl(color).copy(sat = value.coerceIn(-100f, 100f)))

    fun withHslLum(color: HslColor, value: Float): EditParams =
        withHsl(color, getHsl(color).copy(lum = value.coerceIn(-100f, 100f)))

    fun resetHslColor(color: HslColor): EditParams {
        if (getHsl(color) == HslAdjust()) return this
        return copy(hsl = hsl + (color to HslAdjust()))
    }

    fun withGlobalSat(value: Float): EditParams {
        val v = value.coerceIn(-100f, 100f)
        return if (globalSat == v) this else copy(globalSat = v)
    }

    fun withGlobalVib(value: Float): EditParams {
        val v = value.coerceIn(-100f, 100f)
        return if (globalVib == v) this else copy(globalVib = v)
    }

    fun resetHslAll(): EditParams {
        if (isHslDefault()) return this
        return copy(globalSat = 0f, globalVib = 0f, hsl = defaultHslMap())
    }

    fun isHslDefault(): Boolean =
        globalSat == 0f && globalVib == 0f && HslColor.entries.all { (hsl[it] ?: HslAdjust()) == HslAdjust() }

    fun hasPerColorHsl(): Boolean =
        HslColor.entries.any { (hsl[it] ?: HslAdjust()) != HslAdjust() }

    fun getCurve(channel: CurveChannel): List<CurvePoint> =
        curves[channel] ?: Curves.DEFAULT_POINTS

    fun withCurve(channel: CurveChannel, points: List<CurvePoint>): EditParams {
        val sanitized = Curves.sanitize(points)
        return copy(curves = curves + (channel to sanitized))
    }

    fun resetCurve(channel: CurveChannel): EditParams {
        if (Curves.isDiagonal(getCurve(channel))) return this
        return copy(curves = curves + (channel to Curves.DEFAULT_POINTS))
    }

    fun resetCurvesAll(): EditParams {
        if (isCurvesDefault()) return this
        return copy(curves = defaultCurvesMap())
    }

    fun isCurveDiagonal(channel: CurveChannel): Boolean = Curves.isDiagonal(getCurve(channel))

    fun isCurvesDefault(): Boolean = CurveChannel.entries.all { Curves.isDiagonal(getCurve(it)) }

    fun getDetail(control: DetailControl): Float = when (control) {
        DetailControl.TEXTURE -> texture
        DetailControl.CLARITY_ADV -> clarityAdv
        DetailControl.DEHAZE_ADV -> dehazeAdv
        DetailControl.SHARPEN_AMOUNT -> sharpenAmount
        DetailControl.SHARPEN_RADIUS -> sharpenRadius
        DetailControl.NR_LUMA -> nrLuma
        DetailControl.NR_COLOR -> nrColor
    }

    fun withDetail(control: DetailControl, value: Float): EditParams {
        val v = control.clamp(value)
        return when (control) {
            DetailControl.TEXTURE -> if (texture == v) this else copy(texture = v)
            DetailControl.CLARITY_ADV -> if (clarityAdv == v) this else copy(clarityAdv = v)
            DetailControl.DEHAZE_ADV -> if (dehazeAdv == v) this else copy(dehazeAdv = v)
            DetailControl.SHARPEN_AMOUNT -> if (sharpenAmount == v) this else copy(sharpenAmount = v)
            DetailControl.SHARPEN_RADIUS -> if (sharpenRadius == v) this else copy(sharpenRadius = v)
            DetailControl.NR_LUMA -> if (nrLuma == v) this else copy(nrLuma = v)
            DetailControl.NR_COLOR -> if (nrColor == v) this else copy(nrColor = v)
        }
    }

    fun resetDetail(control: DetailControl): EditParams = withDetail(control, control.default)

    fun resetDetails(): EditParams {
        if (isDetailsDefault()) return this
        return copy(
            texture = 0f,
            clarityAdv = 0f,
            dehazeAdv = 0f,
            sharpenAmount = 0f,
            sharpenRadius = 25f,
            nrLuma = 0f,
            nrColor = 25f
        )
    }

    fun isDetailsDefault(): Boolean = DetailControl.entries.all { getDetail(it) == it.default }

    fun isCropDefault(): Boolean = crop.isDefault()

    fun isMasksDefault(): Boolean = masks.isEmpty()

    fun withCrop(crop: CropParams): EditParams =
        if (this.crop == crop) this else copy(crop = crop)

    fun withCropRatio(ratio: CropRatio): EditParams = withCrop(crop.withRatio(ratio))

    fun withCustomCropRect(left: Float, top: Float, right: Float, bottom: Float): EditParams =
        withCrop(crop.withCustomRect(left, top, right, bottom))

    fun rotateCrop90(): EditParams = withCrop(crop.rotated90())

    fun withStraighten(deg: Float): EditParams = withCrop(crop.withStraighten(deg))

    fun toggleFlipH(): EditParams = withCrop(crop.withFlipH(!crop.flipH))

    fun toggleFlipV(): EditParams = withCrop(crop.withFlipV(!crop.flipV))

    fun resetCrop(): EditParams {
        if (crop.isDefault()) return this
        return copy(crop = CropParams())
    }

    fun getMask(id: String): EditMask? = masks.firstOrNull { it.id == id }

    fun addMask(tool: MaskTool): EditParams {
        if (masks.size >= MAX_MASKS) return this
        val id = java.util.UUID.randomUUID().toString()
        // v1 masks grade exposure + temperature only. A fresh mask defaults to
        // +1 EV (eraser excluded: it restores base pixels so its grade is unused)
        // so adding a mask has an immediately visible effect. The previous 0 EV
        // default made PreviewRenderer.applyMasks filter every new mask out as
        // inactive, i.e. "add mask does nothing".
        val base = EditMask(
            id = id,
            tool = tool,
            exposure = if (tool == MaskTool.ERASER) 0f else 1f,
            points = if (tool == MaskTool.BRUSH || tool == MaskTool.ERASER) {
                listOf(MaskPoint(0.5f, 0.5f))
            } else {
                emptyList()
            }
        )
        return copy(masks = masks + base)
    }

    fun removeMask(id: String): EditParams {
        if (masks.none { it.id == id }) return this
        return copy(masks = masks.filterNot { it.id == id })
    }

    fun updateMask(id: String, transform: (EditMask) -> EditMask): EditParams {
        val index = masks.indexOfFirst { it.id == id }
        if (index < 0) return this
        val current = masks[index]
        var next = transform(current)
        next = next.copy(
            sizePx = next.sizePx.coerceIn(EditMask.MIN_SIZE_PX, EditMask.MAX_SIZE_PX),
            feather = next.feather.coerceIn(0f, 1f),
            opacity = next.opacity.coerceIn(0f, 1f),
            centerX = next.centerX.coerceIn(0f, 1f),
            centerY = next.centerY.coerceIn(0f, 1f),
            radius = next.radius.coerceIn(0.01f, 1f),
            position = next.position.coerceIn(0f, 1f),
            exposure = next.exposure.coerceIn(-5f, 5f),
            temperature = next.temperature.coerceIn(-100f, 100f),
            points = EditMask.sanitizePoints(next.points)
        )
        var angle = next.angleDeg % 360f
        if (angle < 0f) angle += 360f
        if (angle != next.angleDeg) next = next.copy(angleDeg = angle)
        if (next == current) return this
        val updated = masks.toMutableList()
        updated[index] = next
        return copy(masks = updated)
    }

    fun toggleMaskVisible(id: String): EditParams =
        updateMask(id) { it.copy(visible = !it.visible) }

    fun clearMasks(): EditParams {
        if (masks.isEmpty()) return this
        return copy(masks = emptyList())
    }

    fun isStepEnabled(key: StepKey): Boolean = steps.get(key)

    fun withStep(key: StepKey, enabled: Boolean): EditParams {
        val next = steps.with(key, enabled)
        return if (next == steps) this else copy(steps = next)
    }

    fun resetSteps(): EditParams {
        if (steps.isAllEnabled()) return this
        return copy(steps = StepsEnabled())
    }

    companion object {
        const val MAX_MASKS = 3
        val DEFAULT = EditParams()

        fun defaultHslMap(): Map<HslColor, HslAdjust> =
            HslColor.entries.associateWith { HslAdjust() }

        fun defaultCurvesMap(): Map<CurveChannel, List<CurvePoint>> =
            CurveChannel.entries.associateWith { Curves.DEFAULT_POINTS }

        fun fromMap(map: Map<String, Float>, presetId: String? = null, presetIntensity: Float = 1f): EditParams {
            var params = EditParams(presetId = presetId, presetIntensity = presetIntensity)
            for (control in AdjustControl.entries) {
                map[control.key]?.let { params = params.with(control, it) }
            }
            return params
        }
    }
}

object EditParamsJson {
    fun encode(params: EditParams): String {
        val sb = StringBuilder("{")
        AdjustControl.entries.forEachIndexed { index, control ->
            if (index > 0) sb.append(",")
            sb.append("\"").append(control.key).append("\":").append(params.get(control))
        }
        sb.append(",\"presetId\":")
        if (params.presetId == null) sb.append("null")
        else sb.append("\"").append(params.presetId.replace("\\", "\\\\").replace("\"", "\\\"")).append("\"")
        sb.append(",\"presetIntensity\":").append(params.presetIntensity)
        sb.append(",\"globalSat\":").append(params.globalSat)
        sb.append(",\"globalVib\":").append(params.globalVib)
        for (color in HslColor.entries) {
            val a = params.getHsl(color)
            sb.append(",\"hsl_").append(color.key).append("_h\":").append(a.hue)
            sb.append(",\"hsl_").append(color.key).append("_s\":").append(a.sat)
            sb.append(",\"hsl_").append(color.key).append("_l\":").append(a.lum)
        }
        for (channel in CurveChannel.entries) {
            sb.append(",\"curve_").append(channel.key).append("\":[")
            val pts = params.getCurve(channel)
            pts.forEachIndexed { i, p ->
                if (i > 0) sb.append(",")
                sb.append("[").append(p.x).append(",").append(p.y).append("]")
            }
            sb.append("]")
        }
        for (control in DetailControl.entries) {
            sb.append(",\"").append(control.key).append("\":").append(params.getDetail(control))
        }
        sb.append(",\"crop_ratio\":\"").append(params.crop.ratio.key).append("\"")
        sb.append(",\"crop_rotation\":").append(params.crop.rotationSteps)
        sb.append(",\"crop_straighten\":").append(params.crop.straightenDeg)
        sb.append(",\"crop_flipH\":").append(if (params.crop.flipH) "true" else "false")
        sb.append(",\"crop_flipV\":").append(if (params.crop.flipV) "true" else "false")
        sb.append(",\"crop_cl\":").append(params.crop.customLeft)
        sb.append(",\"crop_ct\":").append(params.crop.customTop)
        sb.append(",\"crop_cr\":").append(params.crop.customRight)
        sb.append(",\"crop_cb\":").append(params.crop.customBottom)
        for (key in StepKey.entries) {
            sb.append(",\"step_").append(key.key).append("\":")
                .append(if (params.steps.get(key)) "true" else "false")
        }
        sb.append(",\"masks\":[")
        params.masks.forEachIndexed { index, mask ->
            if (index > 0) sb.append(",")
            sb.append("{")
            sb.append("\"id\":\"").append(escape(mask.id)).append("\"")
            sb.append(",\"tool\":\"").append(mask.tool.name).append("\"")
            sb.append(",\"sizePx\":").append(mask.sizePx)
            sb.append(",\"feather\":").append(mask.feather)
            sb.append(",\"opacity\":").append(mask.opacity)
            sb.append(",\"inverted\":").append(if (mask.inverted) "true" else "false")
            sb.append(",\"visible\":").append(if (mask.visible) "true" else "false")
            sb.append(",\"centerX\":").append(mask.centerX)
            sb.append(",\"centerY\":").append(mask.centerY)
            sb.append(",\"radius\":").append(mask.radius)
            sb.append(",\"angleDeg\":").append(mask.angleDeg)
            sb.append(",\"position\":").append(mask.position)
            sb.append(",\"exposure\":").append(mask.exposure)
            sb.append(",\"temperature\":").append(mask.temperature)
            sb.append(",\"points\":[")
            mask.points.forEachIndexed { pi, p ->
                if (pi > 0) sb.append(",")
                sb.append("[").append(p.x).append(",").append(p.y).append("]")
            }
            sb.append("]")
            sb.append("}")
        }
        sb.append("]")
        sb.append("}")
        return sb.toString()
    }

    private fun escape(raw: String): String =
        raw.replace("\\", "\\\\").replace("\"", "\\\"")

    fun decode(json: String?): EditParams {
        if (json.isNullOrBlank()) return EditParams.DEFAULT
        return try {
            var params = EditParams.DEFAULT
            for (control in AdjustControl.entries) {
                extractNumber(json, control.key)?.let { params = params.with(control, it) }
            }
            val presetId = extractStringOrNull(json, "presetId")
            val intensity = extractNumber(json, "presetIntensity")
            params = params.copy(presetId = presetId, presetIntensity = intensity ?: 1f)
            extractNumber(json, "globalSat")?.let { params = params.withGlobalSat(it) }
            extractNumber(json, "globalVib")?.let { params = params.withGlobalVib(it) }
            for (color in HslColor.entries) {
                val h = extractNumber(json, "hsl_${color.key}_h")
                val s = extractNumber(json, "hsl_${color.key}_s")
                val l = extractNumber(json, "hsl_${color.key}_l")
                if (h != null || s != null || l != null) {
                    val base = params.getHsl(color)
                    params = params.withHsl(
                        color,
                        HslAdjust(
                            hue = h ?: base.hue,
                            sat = s ?: base.sat,
                            lum = l ?: base.lum
                        )
                    )
                }
            }
            for (channel in CurveChannel.entries) {
                extractCurvePoints(json, "curve_${channel.key}")?.let {
                    params = params.withCurve(channel, it)
                }
            }
            for (control in DetailControl.entries) {
                extractNumber(json, control.key)?.let { params = params.withDetail(control, it) }
            }
            val cropRatio = extractStringOrNull(json, "crop_ratio")?.let { CropRatio.fromKey(it) }
            val cropRotation = extractNumber(json, "crop_rotation")?.toInt()
            val cropStraighten = extractNumber(json, "crop_straighten")
            val cropFlipH = extractBoolean(json, "crop_flipH")
            val cropFlipV = extractBoolean(json, "crop_flipV")
            // Missing crop_cl/ct/cr/cb (pre-free-rect JSON) falls back to full-frame.
            val cropCl = extractNumber(json, "crop_cl")
            val cropCt = extractNumber(json, "crop_ct")
            val cropCr = extractNumber(json, "crop_cr")
            val cropCb = extractNumber(json, "crop_cb")
            if (cropRatio != null || cropRotation != null || cropStraighten != null ||
                cropFlipH != null || cropFlipV != null ||
                cropCl != null || cropCt != null || cropCr != null || cropCb != null
            ) {
                val base = params.crop
                params = params.withCrop(
                    CropParams(
                        ratio = cropRatio ?: base.ratio,
                        rotationSteps = ((cropRotation ?: base.rotationSteps) % 4 + 4) % 4,
                        straightenDeg = (cropStraighten ?: base.straightenDeg).coerceIn(-45f, 45f),
                        flipH = cropFlipH ?: base.flipH,
                        flipV = cropFlipV ?: base.flipV,
                        customLeft = cropCl ?: base.customLeft,
                        customTop = cropCt ?: base.customTop,
                        customRight = cropCr ?: base.customRight,
                        customBottom = cropCb ?: base.customBottom
                    ).withCustomRect(
                        cropCl ?: base.customLeft,
                        cropCt ?: base.customTop,
                        cropCr ?: base.customRight,
                        cropCb ?: base.customBottom
                    )
                )
            }
            var steps = params.steps
            var stepsTouched = false
            for (key in StepKey.entries) {
                extractBoolean(json, "step_${key.key}")?.let {
                    steps = steps.with(key, it)
                    stepsTouched = true
                }
            }
            if (stepsTouched) params = params.copy(steps = steps)
            extractMasks(json)?.let { params = params.copy(masks = it) }
            params
        } catch (_: Exception) {
            EditParams.DEFAULT
        }
    }

    private fun extractMasks(json: String): List<EditMask>? {
        val keyToken = "\"masks\""
        val keyIndex = json.indexOf(keyToken)
        if (keyIndex < 0) return null
        val colon = json.indexOf(':', keyIndex + keyToken.length)
        if (colon < 0) return null
        var i = colon + 1
        while (i < json.length && json[i].isWhitespace()) i++
        if (i >= json.length || json[i] != '[') return null
        var depth = 0
        var end = -1
        var k = i
        while (k < json.length) {
            when (json[k]) {
                '[' -> depth++
                ']' -> {
                    depth--
                    if (depth == 0) {
                        end = k
                        break
                    }
                }
            }
            k++
        }
        if (end < 0) return null
        val inner = json.substring(i + 1, end).trim()
        if (inner.isEmpty()) return emptyList()
        val objects = ArrayList<String>()
        var braceDepth = 0
        var inString = false
        var escaped = false
        var start = -1
        var idx = 0
        while (idx < inner.length) {
            val c = inner[idx]
            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (c == '\\') {
                    escaped = true
                } else if (c == '"') {
                    inString = false
                }
            } else {
                when (c) {
                    '"' -> inString = true
                    '{' -> {
                        if (braceDepth == 0) start = idx
                        braceDepth++
                    }
                    '}' -> {
                        braceDepth--
                        if (braceDepth == 0 && start >= 0) {
                            objects.add(inner.substring(start, idx + 1))
                            start = -1
                        }
                    }
                }
            }
            idx++
        }
        if (objects.size > 16) return objects.take(16).mapNotNull { parseMask(it) }
        return objects.mapNotNull { parseMask(it) }
    }

    private fun parseMask(obj: String): EditMask? {
        val id = extractStringOrNull(obj, "id")?.takeIf { it.isNotBlank() } ?: return null
        val tool = extractStringOrNull(obj, "tool")?.let { MaskTool.fromKey(it) } ?: MaskTool.BRUSH
        val sizePx = extractNumber(obj, "sizePx")?.coerceIn(EditMask.MIN_SIZE_PX, EditMask.MAX_SIZE_PX) ?: 80f
        val feather = extractNumber(obj, "feather")?.coerceIn(0f, 1f) ?: 0.5f
        val opacity = extractNumber(obj, "opacity")?.coerceIn(0f, 1f) ?: 1f
        val inverted = extractBoolean(obj, "inverted") ?: false
        val visible = extractBoolean(obj, "visible") ?: true
        val centerX = extractNumber(obj, "centerX")?.coerceIn(0f, 1f) ?: 0.5f
        val centerY = extractNumber(obj, "centerY")?.coerceIn(0f, 1f) ?: 0.5f
        val radius = extractNumber(obj, "radius")?.coerceIn(0.01f, 1f) ?: 0.3f
        val angleRaw = extractNumber(obj, "angleDeg") ?: 0f
        var angle = angleRaw % 360f
        if (angle < 0f) angle += 360f
        val position = extractNumber(obj, "position")?.coerceIn(0f, 1f) ?: 0.5f
        val exposure = extractNumber(obj, "exposure")?.coerceIn(-5f, 5f) ?: 0f
        val temperature = extractNumber(obj, "temperature")?.coerceIn(-100f, 100f) ?: 0f
        val points = extractMaskPoints(obj) ?: emptyList()
        return EditMask(
            id = id,
            tool = tool,
            sizePx = sizePx,
            feather = feather,
            opacity = opacity,
            inverted = inverted,
            visible = visible,
            centerX = centerX,
            centerY = centerY,
            radius = radius,
            angleDeg = angle,
            position = position,
            points = EditMask.sanitizePoints(points),
            exposure = exposure,
            temperature = temperature
        )
    }

    private fun extractMaskPoints(obj: String): List<MaskPoint>? {
        val keyToken = "\"points\""
        val keyIndex = obj.indexOf(keyToken)
        if (keyIndex < 0) return null
        val colon = obj.indexOf(':', keyIndex + keyToken.length)
        if (colon < 0) return null
        var i = colon + 1
        while (i < obj.length && obj[i].isWhitespace()) i++
        if (i >= obj.length || obj[i] != '[') return null
        var depth = 0
        var end = -1
        var k = i
        while (k < obj.length) {
            when (obj[k]) {
                '[' -> depth++
                ']' -> {
                    depth--
                    if (depth == 0) {
                        end = k
                        break
                    }
                }
            }
            k++
        }
        if (end < 0) return null
        val inner = obj.substring(i, end + 1)
        val numberRegex = Regex("-?\\d+(\\.\\d+)?([eE][+-]?\\d+)?")
        val numbers = numberRegex.findAll(inner).mapNotNull { it.value.toFloatOrNull() }.toList()
        if (numbers.isEmpty()) return emptyList()
        if (numbers.size % 2 != 0) return null
        if (numbers.size > EditMask.MAX_POINTS * 2) return null
        val pts = ArrayList<MaskPoint>(numbers.size / 2)
        var j = 0
        while (j + 1 < numbers.size) {
            pts.add(MaskPoint(numbers[j].coerceIn(0f, 1f), numbers[j + 1].coerceIn(0f, 1f)))
            j += 2
        }
        return EditMask.sanitizePoints(pts)
    }

    private fun extractBoolean(json: String, key: String): Boolean? {
        val regex = Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*(true|false)")
        val raw = regex.find(json)?.groupValues?.get(1) ?: return null
        return raw == "true"
    }

    private fun extractCurvePoints(json: String, key: String): List<CurvePoint>? {
        val keyToken = "\"$key\""
        val keyIndex = json.indexOf(keyToken)
        if (keyIndex < 0) return null
        val colon = json.indexOf(':', keyIndex + keyToken.length)
        if (colon < 0) return null
        var i = colon + 1
        while (i < json.length && json[i].isWhitespace()) i++
        if (i >= json.length || json[i] != '[') return null
        var depth = 0
        var end = -1
        var k = i
        while (k < json.length) {
            when (json[k]) {
                '[' -> depth++
                ']' -> {
                    depth--
                    if (depth == 0) {
                        end = k
                        break
                    }
                }
            }
            k++
        }
        if (end < 0) return null
        val inner = json.substring(i, end + 1)
        val numberRegex = Regex("-?\\d+(\\.\\d+)?([eE][+-]?\\d+)?")
        val numbers = numberRegex.findAll(inner).mapNotNull { it.value.toFloatOrNull() }.toList()
        if (numbers.size < 4 || numbers.size % 2 != 0) return null
        if (numbers.size > 64) return null
        val pts = ArrayList<CurvePoint>(numbers.size / 2)
        var j = 0
        while (j + 1 < numbers.size) {
            pts.add(CurvePoint(numbers[j], numbers[j + 1]))
            j += 2
        }
        if (pts.size < 2) return null
        return Curves.sanitize(pts)
    }

    private fun extractNumber(json: String, key: String): Float? {
        val regex = Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*(-?\\d+(\\.\\d+)?([eE][+-]?\\d+)?)")
        return regex.find(json)?.groupValues?.get(1)?.toFloatOrNull()
    }

    private fun extractStringOrNull(json: String, key: String): String? {
        val nullRegex = Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*null")
        if (nullRegex.containsMatchIn(json)) return null
        val regex = Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
        val raw = regex.find(json)?.groupValues?.get(1) ?: return null
        return raw.replace("\\\"", "\"").replace("\\\\", "\\")
    }
}

fun Project.toEditParams(): EditParams = EditParamsJson.decode(editParamsJson)

fun Project.withEditParams(params: EditParams): Project =
    copy(editParamsJson = EditParamsJson.encode(params))
