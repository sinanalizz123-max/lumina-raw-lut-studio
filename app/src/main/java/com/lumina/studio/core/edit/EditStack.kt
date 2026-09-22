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

/**
 * M6 color grading model (§19). Four wheels — Global + Shadows + Midtones +
 * Highlights — each {hue 0..360, sat 0..100, lum -100..100} plus overall
 * [blending] strength 0..100 (default 50) and [balance] -100..100 (default 0,
 * shifts the shadow/highlight boundary; positive favors shadows).
 *
 * Render position (§85): AFTER curves (zone weights read tone-mapped luma),
 * BEFORE details/masks. Honors StepKey.COLOR. Pure weight/tint math lives in
 * [GradeMath] (android-free, JVM-tested); PreviewRenderer only loops pixels.
 */
enum class GradeZone(val key: String, val label: String) {
    GLOBAL("global", "Global"),
    SHADOWS("shadows", "Shadows"),
    MIDTONES("midtones", "Midtones"),
    HIGHLIGHTS("highlights", "Highlights");

    companion object {
        fun fromKey(key: String?): GradeZone? = entries.firstOrNull { it.key == key }
    }
}

data class GradeAdjust(
    val hue: Float = 0f,
    val sat: Float = 0f,
    val lum: Float = 0f
) {
    companion object {
        fun wrapHue(value: Float): Float {
            var c = value % 360f
            if (c < 0f) c += 360f
            if (c >= 360f) c -= 360f
            return c
        }
    }
}

data class GradeParams(
    val global: GradeAdjust = GradeAdjust(),
    val shadows: GradeAdjust = GradeAdjust(),
    val midtones: GradeAdjust = GradeAdjust(),
    val highlights: GradeAdjust = GradeAdjust(),
    val blending: Float = DEFAULT_BLENDING,
    val balance: Float = 0f
) {
    fun get(zone: GradeZone): GradeAdjust = when (zone) {
        GradeZone.GLOBAL -> global
        GradeZone.SHADOWS -> shadows
        GradeZone.MIDTONES -> midtones
        GradeZone.HIGHLIGHTS -> highlights
    }

    fun with(zone: GradeZone, adjust: GradeAdjust): GradeParams {
        val clamped = GradeAdjust(
            hue = GradeAdjust.wrapHue(adjust.hue),
            sat = adjust.sat.coerceIn(0f, 100f),
            lum = adjust.lum.coerceIn(-100f, 100f)
        )
        if (get(zone) == clamped) return this
        return when (zone) {
            GradeZone.GLOBAL -> copy(global = clamped)
            GradeZone.SHADOWS -> copy(shadows = clamped)
            GradeZone.MIDTONES -> copy(midtones = clamped)
            GradeZone.HIGHLIGHTS -> copy(highlights = clamped)
        }
    }

    fun withHue(zone: GradeZone, value: Float): GradeParams =
        with(zone, get(zone).copy(hue = GradeAdjust.wrapHue(value)))

    fun withSat(zone: GradeZone, value: Float): GradeParams =
        with(zone, get(zone).copy(sat = value.coerceIn(0f, 100f)))

    fun withLum(zone: GradeZone, value: Float): GradeParams =
        with(zone, get(zone).copy(lum = value.coerceIn(-100f, 100f)))

    fun resetZone(zone: GradeZone): GradeParams {
        if (get(zone) == GradeAdjust()) return this
        return with(zone, GradeAdjust())
    }

    fun withBlending(value: Float): GradeParams {
        val v = value.coerceIn(0f, 100f)
        return if (blending == v) this else copy(blending = v)
    }

    fun withBalance(value: Float): GradeParams {
        val v = value.coerceIn(-100f, 100f)
        return if (balance == v) this else copy(balance = v)
    }

    fun resetAll(): GradeParams {
        if (isDefault()) return this
        return GradeParams()
    }

    fun isDefault(): Boolean =
        global == GradeAdjust() && shadows == GradeAdjust() &&
            midtones == GradeAdjust() && highlights == GradeAdjust() &&
            blending == DEFAULT_BLENDING && balance == 0f

    companion object {
        const val DEFAULT_BLENDING = 50f
    }
}

/**
 * M6 point color model (§18). Selective S/L tweak around a picked hue:
 * [enabled] gate, [sampledRgb] ARGB from the eyedropper tap, [hueCenter]
 * 0..360 (seeded from the sample, fine-tunable), [hueRange] 10..180 falloff
 * half-width, [satAdjust]/[lumAdjust] -100..100.
 *
 * Render position: AFTER HSL/curves (operates on tone-mapped pixels, still
 * keyed to the pre-grade hue), BEFORE grading. Non-destructive: lives in the
 * recipe + JSON. Early-out when [isDefault] (disabled, or enabled with no
 * S/L adjust). Pure falloff math in [PointColorMath].
 */
data class PointColorParams(
    val enabled: Boolean = false,
    val sampledRgb: Int? = null,
    val hueCenter: Float = 0f,
    val hueRange: Float = DEFAULT_RANGE,
    val satAdjust: Float = 0f,
    val lumAdjust: Float = 0f
) {
    fun withEnabled(value: Boolean): PointColorParams =
        if (enabled == value) this else copy(enabled = value)

    fun withSample(argb: Int, hueDeg: Float): PointColorParams {
        val next = copy(enabled = true, sampledRgb = argb, hueCenter = GradeAdjust.wrapHue(hueDeg))
        return if (next == this) this else next
    }

    fun withHueCenter(value: Float): PointColorParams {
        val v = GradeAdjust.wrapHue(value)
        return if (hueCenter == v) this else copy(hueCenter = v)
    }

    fun withHueRange(value: Float): PointColorParams {
        val v = value.coerceIn(MIN_RANGE, MAX_RANGE)
        return if (hueRange == v) this else copy(hueRange = v)
    }

    fun withSat(value: Float): PointColorParams {
        val v = value.coerceIn(-100f, 100f)
        return if (satAdjust == v) this else copy(satAdjust = v)
    }

    fun withLum(value: Float): PointColorParams {
        val v = value.coerceIn(-100f, 100f)
        return if (lumAdjust == v) this else copy(lumAdjust = v)
    }

    fun reset(): PointColorParams {
        if (this == PointColorParams()) return this
        return PointColorParams()
    }

    fun isDefault(): Boolean = !enabled || (satAdjust == 0f && lumAdjust == 0f)

    companion object {
        const val MIN_RANGE = 10f
        const val MAX_RANGE = 180f
        const val DEFAULT_RANGE = 60f
    }
}

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
    R4_3("4_3", "4:3", 4f / 3f),
    R4_5("4_5", "4:5", 4f / 5f),
    R5_4("5_4", "5:4", 5f / 4f),
    R3_2("3_2", "3:2", 3f / 2f),
    R16_9("16_9", "16:9", 16f / 9f),
    R9_16("9_16", "9:16", 9f / 16f),
    CUSTOM("custom", "Custom", null);

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
    val customBottom: Float = 1f,
    // M7 geometry (§23): manual perspective (projective warp, early-out at 0)
    // + custom-ratio w:h floats for CropRatio.CUSTOM.
    val perspectiveV: Float = 0f,
    val perspectiveH: Float = 0f,
    val customW: Float = 4f,
    val customH: Float = 3f
) {
    fun isFullFrameRect(): Boolean =
        CropRects.isFullFrame(customLeft, customTop, customRight, customBottom)

    fun isDefault(): Boolean =
        ratio == CropRatio.FREE && rotationSteps == 0 &&
            straightenDeg == 0f && !flipH && !flipV && isFullFrameRect() &&
            perspectiveV == 0f && perspectiveH == 0f

    /** Effective aspect for the centered aspect-cut, or null for free-form. */
    fun effectiveAspect(): Float? {
        if (ratio == CropRatio.CUSTOM) {
            if (customW <= 0f || customH <= 0f) return null
            val a = customW / customH
            if (!a.isFinite() || a <= 0f) return null
            return a
        }
        return ratio.aspect
    }

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

    fun withPerspectiveV(v: Float): CropParams {
        val c = v.coerceIn(-100f, 100f)
        return if (perspectiveV == c) this else copy(perspectiveV = c)
    }

    fun withPerspectiveH(v: Float): CropParams {
        val c = v.coerceIn(-100f, 100f)
        return if (perspectiveH == c) this else copy(perspectiveH = c)
    }

    fun withCustomAspect(w: Float, h: Float): CropParams {
        val cw = w.coerceIn(0.1f, 99f)
        val ch = h.coerceIn(0.1f, 99f)
        return if (customW == cw && customH == ch) this else copy(customW = cw, customH = ch)
    }
}

enum class MaskTool(val label: String) {
    BRUSH("Brush"),
    ERASER("Eraser"),
    LINEAR("Linear"),
    RADIAL("Radial"),
    COLOR("Color range"),
    LUMINANCE("Luma range");

    companion object {
        fun fromKey(key: String?): MaskTool =
            entries.firstOrNull { it.name == key } ?: BRUSH
    }
}

/**
 * M7 mask combine op (§24). Order = list order, applied against the
 * ACCUMULATED alpha of previous masks (see MaskRangeMath):
 * ADD unions, SUBTRACT cuts out (erases prior grades, ignores its own
 * grade), INTERSECT keeps the overlap (own grade on overlap only).
 * Per-mask [EditMask.inverted] applies before the op.
 */
enum class MaskOp(val label: String) {
    ADD("Add"),
    SUBTRACT("Subtract"),
    INTERSECT("Intersect");

    companion object {
        fun fromKey(key: String?): MaskOp =
            entries.firstOrNull { it.name == key } ?: ADD
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
    val temperature: Float = 0f,
    // M7 (§24): combine op + extended local grades + range specs + blur.
    // Opacity doubles as Density (no duplicate control — see MaskPanel note).
    val op: MaskOp = MaskOp.ADD,
    val saturation: Float = 0f,
    val clarity: Float = 0f,
    // Mask blur radius 0..MAX_BLUR: cheap box approx on the alpha field
    // (downsample-then-upsample); 0 = off.
    val blur: Float = 0f,
    // Color-range selection (MaskTool.COLOR): hue falloff reused from
    // PointColorMath; sampledRgb is the eyedropper seed (UI only).
    val hueCenter: Float = 0f,
    val hueRange: Float = 60f,
    val sampledRgb: Int? = null,
    // Luminance-range selection (MaskTool.LUMINANCE): smooth band.
    val lumaLo: Float = 0f,
    val lumaHi: Float = 1f,
    val lumaFeather: Float = 0.2f
) {
    companion object {
        const val MIN_SIZE_PX = 1f
        const val MAX_SIZE_PX = 500f
        const val MAX_POINTS = 64
        const val MAX_BLUR = 50f

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

    fun withOp(v: MaskOp): EditMask =
        if (op == v) this else copy(op = v)

    fun withSaturation(v: Float): EditMask {
        val c = v.coerceIn(-100f, 100f)
        return if (saturation == c) this else copy(saturation = c)
    }

    fun withClarity(v: Float): EditMask {
        val c = v.coerceIn(-100f, 100f)
        return if (clarity == c) this else copy(clarity = c)
    }

    fun withBlur(v: Float): EditMask {
        val c = v.coerceIn(0f, MAX_BLUR)
        return if (blur == c) this else copy(blur = c)
    }

    fun withHueCenter(v: Float): EditMask {
        val c = GradeAdjust.wrapHue(v)
        return if (hueCenter == c) this else copy(hueCenter = c)
    }

    fun withHueRange(v: Float): EditMask {
        val c = v.coerceIn(PointColorParams.MIN_RANGE, PointColorParams.MAX_RANGE)
        return if (hueRange == c) this else copy(hueRange = c)
    }

    fun withSampledRgb(v: Int?): EditMask =
        if (sampledRgb == v) this else copy(sampledRgb = v)

    fun withLumaLo(v: Float): EditMask {
        val c = v.coerceIn(0f, 1f)
        return if (lumaLo == c) this else copy(lumaLo = c)
    }

    fun withLumaHi(v: Float): EditMask {
        val c = v.coerceIn(0f, 1f)
        return if (lumaHi == c) this else copy(lumaHi = c)
    }

    fun withLumaFeather(v: Float): EditMask {
        val c = v.coerceIn(0f, 1f)
        return if (lumaFeather == c) this else copy(lumaFeather = c)
    }

    fun isRangeTool(): Boolean = tool == MaskTool.COLOR || tool == MaskTool.LUMINANCE

    fun hasLocalGrade(): Boolean =
        exposure != 0f || temperature != 0f || saturation != 0f || clarity != 0f
}

/**
 * M7 manual optics (§22). Manual-only: vignette correction, lateral CA shift
 * and distortion. No lens-profile database (honest manual sliders, early-out
 * at 0). Crop/transform-adjacent: honors StepKey.CROP in render.
 */
data class OpticsParams(
    val vignetteCorr: Float = 0f,
    val caShift: Float = 0f,
    val distortion: Float = 0f
) {
    fun isDefault(): Boolean = vignetteCorr == 0f && caShift == 0f && distortion == 0f

    fun withVignette(v: Float): OpticsParams {
        val c = v.coerceIn(-100f, 100f)
        return if (vignetteCorr == c) this else copy(vignetteCorr = c)
    }

    fun withCa(v: Float): OpticsParams {
        val c = v.coerceIn(-10f, 10f)
        return if (caShift == c) this else copy(caShift = c)
    }

    fun withDistortion(v: Float): OpticsParams {
        val c = v.coerceIn(-100f, 100f)
        return if (distortion == c) this else copy(distortion = c)
    }

    companion object {
        const val CA_MIN = -10f
        const val CA_MAX = 10f
    }
}

/**
 * M8 retouch model (§26). Local spot edits rendered AFTER masks and BEFORE
 * geometry (see PreviewRenderer.render): HEAL blends the surrounding-annulus
 * median color with the target's luminance detail (honest approx, best on
 * smooth areas); CLONE copies the source disc with a feathered edge;
 * ERASE is onion-peel inpaint labelled "beta" in UI (preview-size only).
 *
 * cx/cy 0..1 is the target center; sx/sy 0..1 is the clone source center
 * (unused by HEAL, which auto-samples the surrounding ring, and by ERASE,
 * which needs no source). radius is a fraction of the frame's smaller side.
 */
enum class RetouchKind(val label: String) {
    HEAL("Heal"),
    CLONE("Clone"),
    ERASE("Erase (beta)");

    companion object {
        fun fromKey(key: String?): RetouchKind =
            entries.firstOrNull { it.name == key } ?: HEAL
    }
}

data class RetouchOp(
    val id: String,
    val kind: RetouchKind = RetouchKind.HEAL,
    val cx: Float = 0.5f,
    val cy: Float = 0.5f,
    val sx: Float = 0.5f,
    val sy: Float = 0.5f,
    val radius: Float = DEFAULT_RADIUS,
    val feather: Float = 0.5f,
    val opacity: Float = 1f
) {
    fun withCenter(x: Float, y: Float): RetouchOp {
        val nx = x.coerceIn(0f, 1f)
        val ny = y.coerceIn(0f, 1f)
        return if (cx == nx && cy == ny) this else copy(cx = nx, cy = ny)
    }

    fun withSource(x: Float, y: Float): RetouchOp {
        val nx = x.coerceIn(0f, 1f)
        val ny = y.coerceIn(0f, 1f)
        return if (sx == nx && sy == ny) this else copy(sx = nx, sy = ny)
    }

    fun withRadius(v: Float): RetouchOp {
        val c = v.coerceIn(MIN_RADIUS, MAX_RADIUS)
        return if (radius == c) this else copy(radius = c)
    }

    fun withFeather(v: Float): RetouchOp {
        val c = v.coerceIn(0f, 1f)
        return if (feather == c) this else copy(feather = c)
    }

    fun withOpacity(v: Float): RetouchOp {
        val c = v.coerceIn(0f, 1f)
        return if (opacity == c) this else copy(opacity = c)
    }

    companion object {
        const val MIN_RADIUS = 0.005f
        const val MAX_RADIUS = 0.5f
        const val DEFAULT_RADIUS = 0.06f
        // Each op adds a bounded-disc pass on preview size; 32 small spots
        // stay inside the preview budget (documented perf note in UI).
        const val MAX_OPS = 32
    }
}

/**
 * M8 lens-blur model (§27). Depth is a radial-gradient-from-focus-point
 * heuristic with transition control — NOT AI, no depth sensor is used (UI
 * copy must say so). Rendered AFTER retouch and BEFORE geometry as a
 * spatially-varying 3-level box blur (cheap bokeh approx). [amount] 0 = off.
 */
data class LensBlurParams(
    val focusX: Float = 0.5f,
    val focusY: Float = 0.5f,
    val amount: Float = 0f,
    val transition: Float = DEFAULT_TRANSITION,
    val focusRadius: Float = DEFAULT_FOCUS_RADIUS
) {
    fun isDefault(): Boolean = amount == 0f

    fun withFocus(x: Float, y: Float): LensBlurParams {
        val nx = x.coerceIn(0f, 1f)
        val ny = y.coerceIn(0f, 1f)
        return if (focusX == nx && focusY == ny) this else copy(focusX = nx, focusY = ny)
    }

    fun withAmount(v: Float): LensBlurParams {
        val c = v.coerceIn(0f, 100f)
        return if (amount == c) this else copy(amount = c)
    }

    fun withTransition(v: Float): LensBlurParams {
        val c = v.coerceIn(0f, 1f)
        return if (transition == c) this else copy(transition = c)
    }

    fun withFocusRadius(v: Float): LensBlurParams {
        val c = v.coerceIn(0f, 1f)
        return if (focusRadius == c) this else copy(focusRadius = c)
    }

    companion object {
        const val DEFAULT_TRANSITION = 0.5f
        const val DEFAULT_FOCUS_RADIUS = 0.25f
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
    val grade: GradeParams = GradeParams(),
    val pointColor: PointColorParams = PointColorParams(),
    val optics: OpticsParams = OpticsParams(),
    val retouch: List<RetouchOp> = emptyList(),
    val lensBlur: LensBlurParams = LensBlurParams(),
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
            isDetailsDefault() && isCropDefault() && isMasksDefault() &&
            isGradeDefault() && isPointColorDefault() && isOpticsDefault() &&
            isRetouchDefault() && isLensBlurDefault()

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

    fun getGrade(zone: GradeZone): GradeAdjust = grade.get(zone)

    fun withGrade(zone: GradeZone, adjust: GradeAdjust): EditParams {
        val next = grade.with(zone, adjust)
        return if (next == grade) this else copy(grade = next)
    }

    fun withGradeHue(zone: GradeZone, value: Float): EditParams =
        withGrade(zone, getGrade(zone).copy(hue = GradeAdjust.wrapHue(value)))

    fun withGradeSat(zone: GradeZone, value: Float): EditParams =
        withGrade(zone, getGrade(zone).copy(sat = value.coerceIn(0f, 100f)))

    fun withGradeLum(zone: GradeZone, value: Float): EditParams =
        withGrade(zone, getGrade(zone).copy(lum = value.coerceIn(-100f, 100f)))

    fun resetGradeZone(zone: GradeZone): EditParams {
        val next = grade.resetZone(zone)
        return if (next == grade) this else copy(grade = next)
    }

    fun withGradeBlending(value: Float): EditParams {
        val next = grade.withBlending(value)
        return if (next == grade) this else copy(grade = next)
    }

    fun withGradeBalance(value: Float): EditParams {
        val next = grade.withBalance(value)
        return if (next == grade) this else copy(grade = next)
    }

    fun resetGradeAll(): EditParams {
        if (grade.isDefault()) return this
        return copy(grade = GradeParams())
    }

    fun isGradeDefault(): Boolean = grade.isDefault()

    fun withPointEnabled(enabled: Boolean): EditParams {
        val next = pointColor.withEnabled(enabled)
        return if (next == pointColor) this else copy(pointColor = next)
    }

    fun withPointSample(argb: Int, hueDeg: Float): EditParams {
        val next = pointColor.withSample(argb, hueDeg)
        return if (next == pointColor) this else copy(pointColor = next)
    }

    fun withPointHueCenter(value: Float): EditParams {
        val next = pointColor.withHueCenter(value)
        return if (next == pointColor) this else copy(pointColor = next)
    }

    fun withPointHueRange(value: Float): EditParams {
        val next = pointColor.withHueRange(value)
        return if (next == pointColor) this else copy(pointColor = next)
    }

    fun withPointSat(value: Float): EditParams {
        val next = pointColor.withSat(value)
        return if (next == pointColor) this else copy(pointColor = next)
    }

    fun withPointLum(value: Float): EditParams {
        val next = pointColor.withLum(value)
        return if (next == pointColor) this else copy(pointColor = next)
    }

    fun resetPointColor(): EditParams {
        if (pointColor == PointColorParams()) return this
        return copy(pointColor = PointColorParams())
    }

    fun isPointColorDefault(): Boolean = pointColor.isDefault()

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

    fun isOpticsDefault(): Boolean = optics.isDefault()

    fun withOptics(optics: OpticsParams): EditParams =
        if (this.optics == optics) this else copy(optics = optics)

    fun withVignetteCorr(v: Float): EditParams = withOptics(optics.withVignette(v))

    fun withCaShift(v: Float): EditParams = withOptics(optics.withCa(v))

    fun withDistortion(v: Float): EditParams = withOptics(optics.withDistortion(v))

    fun resetOptics(): EditParams {
        if (optics.isDefault()) return this
        return copy(optics = OpticsParams())
    }

    fun withCrop(crop: CropParams): EditParams =
        if (this.crop == crop) this else copy(crop = crop)

    fun withCropRatio(ratio: CropRatio): EditParams = withCrop(crop.withRatio(ratio))

    fun withCustomCropRect(left: Float, top: Float, right: Float, bottom: Float): EditParams =
        withCrop(crop.withCustomRect(left, top, right, bottom))

    fun rotateCrop90(): EditParams = withCrop(crop.rotated90())

    fun withStraighten(deg: Float): EditParams = withCrop(crop.withStraighten(deg))

    fun withPerspectiveV(v: Float): EditParams = withCrop(crop.withPerspectiveV(v))

    fun withPerspectiveH(v: Float): EditParams = withCrop(crop.withPerspectiveH(v))

    fun withCustomAspect(w: Float, h: Float): EditParams =
        withCrop(crop.withCustomAspect(w, h))

    fun toggleFlipH(): EditParams = withCrop(crop.withFlipH(!crop.flipH))

    fun toggleFlipV(): EditParams = withCrop(crop.withFlipV(!crop.flipV))

    fun resetCrop(): EditParams {
        if (crop.isDefault()) return this
        return copy(crop = CropParams())
    }

    fun isRetouchDefault(): Boolean = retouch.isEmpty()

    fun isLensBlurDefault(): Boolean = lensBlur.isDefault()

    fun getRetouch(id: String): RetouchOp? = retouch.firstOrNull { it.id == id }

    fun addRetouch(kind: RetouchKind): EditParams {
        if (retouch.size >= RetouchOp.MAX_OPS) return this
        val id = java.util.UUID.randomUUID().toString()
        return copy(retouch = retouch + RetouchOp(id = id, kind = kind))
    }

    fun addRetouchAt(kind: RetouchKind, cx: Float, cy: Float): EditParams {
        if (retouch.size >= RetouchOp.MAX_OPS) return this
        val id = java.util.UUID.randomUUID().toString()
        val op = RetouchOp(id = id, kind = kind)
            .withCenter(cx, cy)
            .withSource(cx, cy)
        return copy(retouch = retouch + op)
    }

    fun removeRetouch(id: String): EditParams {
        if (retouch.none { it.id == id }) return this
        return copy(retouch = retouch.filterNot { it.id == id })
    }

    fun updateRetouch(id: String, transform: (RetouchOp) -> RetouchOp): EditParams {
        val index = retouch.indexOfFirst { it.id == id }
        if (index < 0) return this
        val current = retouch[index]
        var next = transform(current)
        next = next.copy(
            cx = next.cx.coerceIn(0f, 1f),
            cy = next.cy.coerceIn(0f, 1f),
            sx = next.sx.coerceIn(0f, 1f),
            sy = next.sy.coerceIn(0f, 1f),
            radius = next.radius.coerceIn(RetouchOp.MIN_RADIUS, RetouchOp.MAX_RADIUS),
            feather = next.feather.coerceIn(0f, 1f),
            opacity = next.opacity.coerceIn(0f, 1f)
        )
        if (next == current) return this
        val updated = retouch.toMutableList()
        updated[index] = next
        return copy(retouch = updated)
    }

    fun clearRetouch(): EditParams {
        if (retouch.isEmpty()) return this
        return copy(retouch = emptyList())
    }

    fun withLensBlur(lensBlur: LensBlurParams): EditParams =
        if (this.lensBlur == lensBlur) this else copy(lensBlur = lensBlur)

    fun withLensFocus(x: Float, y: Float): EditParams =
        withLensBlur(lensBlur.withFocus(x, y))

    fun withLensAmount(v: Float): EditParams =
        withLensBlur(lensBlur.withAmount(v))

    fun withLensTransition(v: Float): EditParams =
        withLensBlur(lensBlur.withTransition(v))

    fun withLensFocusRadius(v: Float): EditParams =
        withLensBlur(lensBlur.withFocusRadius(v))

    fun resetLensBlur(): EditParams {
        if (lensBlur.isDefault()) return this
        return copy(lensBlur = LensBlurParams())
    }

    fun getMask(id: String): EditMask? = masks.firstOrNull { it.id == id }

    fun addMask(tool: MaskTool): EditParams {
        if (masks.size >= MAX_MASKS) return this
        val id = java.util.UUID.randomUUID().toString()
        // v1 masks grade exposure + temperature only. A fresh mask defaults to
        // M7 masks grade exposure + temperature + saturation + clarity (local).
        // A fresh mask defaults to +1 EV (eraser excluded: it restores base
        // pixels so its grade is unused) so adding a mask has an immediately
        // visible effect. The previous 0 EV default made
        // PreviewRenderer.applyMasks filter every new mask out as inactive,
        // i.e. "add mask does nothing".
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
            saturation = next.saturation.coerceIn(-100f, 100f),
            clarity = next.clarity.coerceIn(-100f, 100f),
            blur = next.blur.coerceIn(0f, EditMask.MAX_BLUR),
            hueCenter = GradeAdjust.wrapHue(next.hueCenter),
            hueRange = next.hueRange.coerceIn(PointColorParams.MIN_RANGE, PointColorParams.MAX_RANGE),
            lumaLo = next.lumaLo.coerceIn(0f, 1f),
            lumaHi = next.lumaHi.coerceIn(0f, 1f),
            lumaFeather = next.lumaFeather.coerceIn(0f, 1f),
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
        // M7: cap 3 -> 6. Each mask adds a full-frame alpha pass on preview
        // size; 5-6 masks may exceed the <100ms preview budget on low-end
        // devices (documented perf note, shown in MaskPanel).
        const val MAX_MASKS = 6
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
        sb.append(",\"crop_perspV\":").append(params.crop.perspectiveV)
        sb.append(",\"crop_perspH\":").append(params.crop.perspectiveH)
        sb.append(",\"crop_customW\":").append(params.crop.customW)
        sb.append(",\"crop_customH\":").append(params.crop.customH)
        for (key in StepKey.entries) {
            sb.append(",\"step_").append(key.key).append("\":")
                .append(if (params.steps.get(key)) "true" else "false")
        }
        for (zone in GradeZone.entries) {
            val g = params.getGrade(zone)
            sb.append(",\"grade_").append(zone.key).append("_h\":").append(g.hue)
            sb.append(",\"grade_").append(zone.key).append("_s\":").append(g.sat)
            sb.append(",\"grade_").append(zone.key).append("_l\":").append(g.lum)
        }
        sb.append(",\"grade_blending\":").append(params.grade.blending)
        sb.append(",\"grade_balance\":").append(params.grade.balance)
        sb.append(",\"point_enabled\":").append(if (params.pointColor.enabled) "true" else "false")
        sb.append(",\"point_rgb\":")
        val sampled = params.pointColor.sampledRgb
        if (sampled == null) sb.append("null")
        else sb.append("\"#").append(String.format("%08X", sampled)).append("\"")
        sb.append(",\"point_hue\":").append(params.pointColor.hueCenter)
        sb.append(",\"point_range\":").append(params.pointColor.hueRange)
        sb.append(",\"point_sat\":").append(params.pointColor.satAdjust)
        sb.append(",\"point_lum\":").append(params.pointColor.lumAdjust)
        sb.append(",\"optics_vignette\":").append(params.optics.vignetteCorr)
        sb.append(",\"optics_ca\":").append(params.optics.caShift)
        sb.append(",\"optics_distortion\":").append(params.optics.distortion)
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
            sb.append(",\"op\":\"").append(mask.op.name).append("\"")
            sb.append(",\"saturation\":").append(mask.saturation)
            sb.append(",\"clarity\":").append(mask.clarity)
            sb.append(",\"blur\":").append(mask.blur)
            sb.append(",\"hueCenter\":").append(mask.hueCenter)
            sb.append(",\"hueRange\":").append(mask.hueRange)
            sb.append(",\"sampledRgb\":")
            if (mask.sampledRgb == null) sb.append("null")
            else sb.append("\"#").append(String.format("%08X", mask.sampledRgb)).append("\"")
            sb.append(",\"lumaLo\":").append(mask.lumaLo)
            sb.append(",\"lumaHi\":").append(mask.lumaHi)
            sb.append(",\"lumaFeather\":").append(mask.lumaFeather)
            sb.append(",\"points\":[")
            mask.points.forEachIndexed { pi, p ->
                if (pi > 0) sb.append(",")
                sb.append("[").append(p.x).append(",").append(p.y).append("]")
            }
            sb.append("]")
            sb.append("}")
        }
        sb.append("]")
        // M8 keys only (new keys appended; old JSON simply lacks them).
        sb.append(",\"lensFx\":").append(params.lensBlur.focusX)
        sb.append(",\"lensFy\":").append(params.lensBlur.focusY)
        sb.append(",\"lensAmount\":").append(params.lensBlur.amount)
        sb.append(",\"lensTransition\":").append(params.lensBlur.transition)
        sb.append(",\"lensRadius\":").append(params.lensBlur.focusRadius)
        sb.append(",\"retouch\":[")
        params.retouch.forEachIndexed { index, op ->
            if (index > 0) sb.append(",")
            sb.append("{")
            sb.append("\"id\":\"").append(escape(op.id)).append("\"")
            sb.append(",\"kind\":\"").append(op.kind.name).append("\"")
            sb.append(",\"cx\":").append(op.cx)
            sb.append(",\"cy\":").append(op.cy)
            sb.append(",\"sx\":").append(op.sx)
            sb.append(",\"sy\":").append(op.sy)
            sb.append(",\"radius\":").append(op.radius)
            sb.append(",\"feather\":").append(op.feather)
            sb.append(",\"opacity\":").append(op.opacity)
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
            // M7 keys (missing = pre-M7 JSON defaults).
            val cropPerspV = extractNumber(json, "crop_perspV")
            val cropPerspH = extractNumber(json, "crop_perspH")
            val cropCustomW = extractNumber(json, "crop_customW")
            val cropCustomH = extractNumber(json, "crop_customH")
            if (cropRatio != null || cropRotation != null || cropStraighten != null ||
                cropFlipH != null || cropFlipV != null ||
                cropCl != null || cropCt != null || cropCr != null || cropCb != null ||
                cropPerspV != null || cropPerspH != null ||
                cropCustomW != null || cropCustomH != null
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
                        customBottom = cropCb ?: base.customBottom,
                        perspectiveV = (cropPerspV ?: base.perspectiveV).coerceIn(-100f, 100f),
                        perspectiveH = (cropPerspH ?: base.perspectiveH).coerceIn(-100f, 100f),
                        customW = (cropCustomW ?: base.customW).coerceIn(0.1f, 99f),
                        customH = (cropCustomH ?: base.customH).coerceIn(0.1f, 99f)
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
            var grade = params.grade
            var gradeTouched = false
            for (zone in GradeZone.entries) {
                val h = extractNumber(json, "grade_${zone.key}_h")
                val s = extractNumber(json, "grade_${zone.key}_s")
                val l = extractNumber(json, "grade_${zone.key}_l")
                if (h != null || s != null || l != null) {
                    val base = grade.get(zone)
                    grade = grade.with(
                        zone,
                        GradeAdjust(
                            hue = h ?: base.hue,
                            sat = s ?: base.sat,
                            lum = l ?: base.lum
                        )
                    )
                    gradeTouched = true
                }
            }
            extractNumber(json, "grade_blending")?.let {
                grade = grade.withBlending(it)
                gradeTouched = true
            }
            extractNumber(json, "grade_balance")?.let {
                grade = grade.withBalance(it)
                gradeTouched = true
            }
            if (gradeTouched) params = params.copy(grade = grade)
            // Missing point_* keys (pre-M6 JSON) fall back to disabled defaults.
            val pointEnabled = extractBoolean(json, "point_enabled")
            val pointRgbRaw = extractStringOrNull(json, "point_rgb")
            val pointHue = extractNumber(json, "point_hue")
            val pointRange = extractNumber(json, "point_range")
            val pointSat = extractNumber(json, "point_sat")
            val pointLum = extractNumber(json, "point_lum")
            if (pointEnabled != null || pointRgbRaw != null || pointHue != null ||
                pointRange != null || pointSat != null || pointLum != null
            ) {
                var pc = params.pointColor
                pointRgbRaw?.let { parseHexArgb(it)?.let { argb -> pc = pc.copy(sampledRgb = argb) } }
                pointEnabled?.let { pc = pc.withEnabled(it) }
                pointHue?.let { pc = pc.withHueCenter(it) }
                pointRange?.let { pc = pc.withHueRange(it) }
                pointSat?.let { pc = pc.withSat(it) }
                pointLum?.let { pc = pc.withLum(it) }
                params = params.copy(pointColor = pc)
            }
            // M7 optics keys (missing = pre-M7 JSON defaults at 0).
            val opticsV = extractNumber(json, "optics_vignette")
            val opticsCa = extractNumber(json, "optics_ca")
            val opticsDist = extractNumber(json, "optics_distortion")
            if (opticsV != null || opticsCa != null || opticsDist != null) {
                var o = params.optics
                opticsV?.let { o = o.withVignette(it) }
                opticsCa?.let { o = o.withCa(it) }
                opticsDist?.let { o = o.withDistortion(it) }
                params = params.copy(optics = o)
            }
            extractMasks(json)?.let { params = params.copy(masks = it) }
            // M8 lens keys (missing = pre-M8 JSON defaults: blur off).
            val lensFx = extractNumber(json, "lensFx")
            val lensFy = extractNumber(json, "lensFy")
            val lensAmount = extractNumber(json, "lensAmount")
            val lensTransition = extractNumber(json, "lensTransition")
            val lensRadius = extractNumber(json, "lensRadius")
            if (lensFx != null || lensFy != null || lensAmount != null ||
                lensTransition != null || lensRadius != null
            ) {
                var blur = params.lensBlur
                lensFx?.let { fx -> blur = blur.withFocus(fx, blur.focusY) }
                lensFy?.let { fy -> blur = blur.withFocus(blur.focusX, fy) }
                lensAmount?.let { blur = blur.withAmount(it) }
                lensTransition?.let { blur = blur.withTransition(it) }
                lensRadius?.let { blur = blur.withFocusRadius(it) }
                params = params.copy(lensBlur = blur)
            }
            // M8 retouch list (missing = pre-M8 JSON default: empty).
            extractRetouch(json)?.let { params = params.copy(retouch = it) }
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
        // M7 keys only (missing = pre-M7 JSON defaults).
        val op = extractStringOrNull(obj, "op")?.let { MaskOp.fromKey(it) } ?: MaskOp.ADD
        val saturation = extractNumber(obj, "saturation")?.coerceIn(-100f, 100f) ?: 0f
        val clarity = extractNumber(obj, "clarity")?.coerceIn(-100f, 100f) ?: 0f
        val blur = extractNumber(obj, "blur")?.coerceIn(0f, EditMask.MAX_BLUR) ?: 0f
        val hueCenterRaw = extractNumber(obj, "hueCenter") ?: 0f
        var hueCenter = hueCenterRaw % 360f
        if (hueCenter < 0f) hueCenter += 360f
        val hueRange = extractNumber(obj, "hueRange")
            ?.coerceIn(PointColorParams.MIN_RANGE, PointColorParams.MAX_RANGE) ?: 60f
        val sampledRgb = extractStringOrNull(obj, "sampledRgb")?.let { parseHexArgb(it) }
        val lumaLo = extractNumber(obj, "lumaLo")?.coerceIn(0f, 1f) ?: 0f
        val lumaHi = extractNumber(obj, "lumaHi")?.coerceIn(0f, 1f) ?: 1f
        val lumaFeather = extractNumber(obj, "lumaFeather")?.coerceIn(0f, 1f) ?: 0.2f
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
            temperature = temperature,
            op = op,
            saturation = saturation,
            clarity = clarity,
            blur = blur,
            hueCenter = hueCenter,
            hueRange = hueRange,
            sampledRgb = sampledRgb,
            lumaLo = lumaLo,
            lumaHi = lumaHi,
            lumaFeather = lumaFeather
        )
    }

    private fun extractRetouch(json: String): List<RetouchOp>? {
        val keyToken = "\"retouch\""
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
        if (objects.size > RetouchOp.MAX_OPS + 8) {
            return objects.take(RetouchOp.MAX_OPS + 8).mapNotNull { parseRetouchOp(it) }
        }
        return objects.mapNotNull { parseRetouchOp(it) }.take(RetouchOp.MAX_OPS)
    }

    private fun parseRetouchOp(obj: String): RetouchOp? {
        val id = extractStringOrNull(obj, "id")?.takeIf { it.isNotBlank() } ?: return null
        val kind = extractStringOrNull(obj, "kind")?.let { RetouchKind.fromKey(it) } ?: RetouchKind.HEAL
        val cx = extractNumber(obj, "cx")?.coerceIn(0f, 1f) ?: 0.5f
        val cy = extractNumber(obj, "cy")?.coerceIn(0f, 1f) ?: 0.5f
        val sx = extractNumber(obj, "sx")?.coerceIn(0f, 1f) ?: 0.5f
        val sy = extractNumber(obj, "sy")?.coerceIn(0f, 1f) ?: 0.5f
        val radius = extractNumber(obj, "radius")
            ?.coerceIn(RetouchOp.MIN_RADIUS, RetouchOp.MAX_RADIUS) ?: RetouchOp.DEFAULT_RADIUS
        val feather = extractNumber(obj, "feather")?.coerceIn(0f, 1f) ?: 0.5f
        val opacity = extractNumber(obj, "opacity")?.coerceIn(0f, 1f) ?: 1f
        return RetouchOp(
            id = id, kind = kind, cx = cx, cy = cy,
            sx = sx, sy = sy, radius = radius,
            feather = feather, opacity = opacity
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

    private fun parseHexArgb(raw: String): Int? {
        val s = raw.trim().removePrefix("#")
        return try {
            when (s.length) {
                6 -> (0xFF000000.toInt() or s.toLong(16).toInt())
                8 -> s.toLong(16).toInt()
                else -> null
            }
        } catch (_: Exception) {
            null
        }
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
