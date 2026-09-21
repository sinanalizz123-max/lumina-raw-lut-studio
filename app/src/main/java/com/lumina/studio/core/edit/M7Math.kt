package com.lumina.studio.core.edit

/**
 * M7 mask-range + op + optics + geometry math (§22-24, pure JVM, no android.*).
 *
 * PreviewRenderer owns the Bitmap loops; everything here is a pure function so
 * JVM tests pin it. All approximations are documented at the call site.
 */
object MaskRangeMath {
    /**
     * Color-range weight: hue-distance falloff reused from [PointColorMath]
     * (full effect inside 40% of [rangeDeg], smoothstep feather to zero).
     */
    fun colorWeight(hueDeg: Float, centerDeg: Float, rangeDeg: Float): Float =
        PointColorMath.falloffWeight(hueDeg, centerDeg, rangeDeg)

    /**
     * Luminance-range weight: smooth band [lo, hi] with [feather] edge width.
     * lo/hi 0..1 (sanitized: swapped if inverted), feather 0..1 scales the
     * smoothstep edge as a fraction of the band (min 1e-3 to avoid div-zero).
     * Returns 1 inside the band, 0 outside, smooth on the edges.
     */
    fun lumaWeight(luma: Float, lo: Float, hi: Float, feather: Float): Float {
        var a = lo.coerceIn(0f, 1f)
        var b = hi.coerceIn(0f, 1f)
        if (b < a) {
            val t = a
            a = b
            b = t
        }
        val l = luma.coerceIn(0f, 1f)
        if (b - a < 1e-6f) return if (l >= a - 1e-6f && l <= b + 1e-6f) 1f else 0f
        val f = feather.coerceIn(0f, 1f)
        if (f <= 0.001f) return if (l >= a && l <= b) 1f else 0f
        val edge = ((b - a) * f * 0.5f).coerceAtLeast(1e-3f)
        val rise = GradeMath.smoothstep(a - edge, a + edge, l)
        val fall = 1f - GradeMath.smoothstep(b - edge, b + edge, l)
        return (rise * fall).coerceIn(0f, 1f)
    }

    /**
     * Mask-op compositing against the ACCUMULATED alpha of previous masks.
     * Order = list order.
     *
     * - ADD (default): union — acc' = acc + a * (1 - acc).
     * - SUBTRACT: cut out — acc' = acc * (1 - a).
     * - INTERSECT: keep overlap — acc' = acc * a.
     */
    fun combineAcc(acc: Float, a: Float, op: MaskOp): Float {
        val c = acc.coerceIn(0f, 1f)
        val w = a.coerceIn(0f, 1f)
        return when (op) {
            MaskOp.ADD -> (c + w * (1f - c)).coerceIn(0f, 1f)
            MaskOp.SUBTRACT -> (c * (1f - w)).coerceIn(0f, 1f)
            MaskOp.INTERSECT -> (c * w).coerceIn(0f, 1f)
        }
    }

    /**
     * Effective per-pixel blend alpha for the current mask given the
     * accumulated alpha BEFORE it (accPrev):
     * - ADD: e = a (blend its local grade additively).
     * - INTERSECT: e = a * accPrev (grade applies on the overlap only).
     * - SUBTRACT: e = a * accPrev (erase amount: blend back toward the
     *   pre-mask base image; the mask's own local grade is ignored).
     */
    fun effectiveAlpha(accPrev: Float, a: Float, op: MaskOp): Float {
        val c = accPrev.coerceIn(0f, 1f)
        val w = a.coerceIn(0f, 1f)
        return when (op) {
            MaskOp.ADD -> w
            MaskOp.SUBTRACT -> (w * c).coerceIn(0f, 1f)
            MaskOp.INTERSECT -> (w * c).coerceIn(0f, 1f)
        }
    }
}

/**
 * M7 manual-optics math (§22, pure JVM). All three controls are manual-only
 * approximations applied on preview-size bitmaps; each early-outs at 0.
 */
object OpticsMath {
    /** Vignette-correction gain. distNorm 0 at center, 1 at corners. Center = 1. */
    fun vignetteGain(distNorm: Float, amount: Float): Float {
        val a = amount.coerceIn(-100f, 100f)
        if (a == 0f) return 1f
        val d = distNorm.coerceIn(0f, 1f)
        return (1f + (a / 100f) * 0.8f * d * d).coerceIn(0.2f, 4f)
    }

    /**
     * Manual CA radial shift (px at render size). Positive [amountPx] shifts
     * RED outward and BLUE inward (documented convention); negative reverses.
     * Scaled by (minDim / 1000) so ±10px is calibrated at 1000px and grows on
     * larger previews; multiplied by distNorm (0 at center, no shift).
     */
    fun caShiftPx(distNorm: Float, amountPx: Float, minDim: Int): Float {
        val a = amountPx.coerceIn(-10f, 10f)
        if (a == 0f) return 0f
        val scale = (minDim.coerceAtLeast(1).toFloat() / 1000f).coerceIn(0.25f, 2f)
        return a * scale * distNorm.coerceIn(0f, 1f)
    }

    /** Distortion k1 from the -100..100 slider (documented inverse-remap approx). */
    fun distortionK1(amount: Float): Float =
        (amount.coerceIn(-100f, 100f) / 100f) * 0.35f

    /**
     * Forward radial model r' = r * (1 + k1 * r^2), r normalized 0..~1.
     * Render uses the one-step inverse approx r_src ~= r_dst / (1 + k1*r_dst^2).
     */
    fun remapRadius(rNorm: Float, k1: Float): Float {
        val r = rNorm.coerceIn(0f, 2f)
        return (r * (1f + k1 * r * r)).coerceIn(0f, 2f)
    }

    /**
     * Inverse radial model via 3 Newton iterations on f(s) = s·(1+k1·s²) − r.
     * Render samples source pixels with this, so forward∘inverse round-trips
     * within ~0.002 (the old one-step approx erred by ~0.18 at full strength).
     */
    fun inverseRemapRadius(rNorm: Float, k1: Float): Float {
        val r = rNorm.coerceIn(0f, 2f)
        if (k1 == 0f) return r
        var s = r
        repeat(3) {
            val d = 1f + 3f * k1 * s * s
            if (d <= 1e-6f) return@repeat
            s -= (s * (1f + k1 * s * s) - r) / d
        }
        return s.coerceIn(0f, 2f)
    }
}

/**
 * M7 geometry math (§23, pure JVM). Perspective is a documented approximation:
 * a 4-point projective warp driven by android.graphics.Matrix.setPolyToPoly
 * on preview-size bitmaps (bilinear filter; transparent gutters possible at
 * extremes). Early-out at 0.
 */
object GeometryMath {
    /**
     * Destination corners for a w*h frame given vertical/horizontal amounts
     * -100..100. Order: TL, TR, BR, BL (matches src [0,0, w,0, w,h, 0,h]).
     * - vertical v > 0 narrows the TOP edge (keystone looking up):
     *   TL.x += dx, TR.x -= dx with dx = v/100 * w * 0.15.
     * - horizontal h > 0 narrows the LEFT edge: TL.y += dy, BL.y -= dy
     *   with dy = h/100 * h * 0.15.
     */
    fun perspectiveDst(w: Int, h: Int, vAmt: Float, hAmt: Float): FloatArray {
        val wf = w.toFloat()
        val hf = h.toFloat()
        val v = vAmt.coerceIn(-100f, 100f)
        val hz = hAmt.coerceIn(-100f, 100f)
        val dx = (v / 100f) * wf * 0.15f
        val dy = (hz / 100f) * hf * 0.15f
        return floatArrayOf(
            dx, dy,
            wf - dx, -dy,
            wf + dx, hf + dy,
            -dx, hf - dy
        )
    }

    fun isPerspectiveDefault(vAmt: Float, hAmt: Float): Boolean =
        vAmt == 0f && hAmt == 0f
}
