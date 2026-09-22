package com.lumina.studio.core.export

/**
 * M11 output sharpening math (§38). Pure JVM (no android.*) so unit tests
 * pin it without Bitmaps.
 *
 * Applied post-resize pre-encode on the export-size bitmap: a real
 * small-radius unsharp mask (3x3 box blur as the blur approximation, then
 * out = orig + (orig - blurred) * strength). The Bitmap loop lives in
 * [Exporter.applyOutputSharpen]; this file owns the amount mapping and the
 * per-channel blend so the identity case (0 = off, same instance, zero
 * delta) and the formula are JVM-pinned.
 *
 * This is deliberately separate from the creative Details sharpen
 * (EditParams.sharpenAmount, preview-approximated): output sharpening is a
 * print-style post-process applied once at export size.
 */
object OutputSharpen {

    const val MIN_AMOUNT = 0
    const val MAX_AMOUNT = 100

    /** Amount 0..100 maps to unsharp strength 0..1. Clamped. */
    fun strengthFor(amount: Int): Float =
        amount.coerceIn(MIN_AMOUNT, MAX_AMOUNT) / MAX_AMOUNT.toFloat()

    /** True when sharpening is a no-op (early-out, caller returns the input). */
    fun isIdentity(amount: Int): Boolean = amount.coerceIn(MIN_AMOUNT, MAX_AMOUNT) == 0

    /**
     * Unsharp blend of one normalized channel. Amount 0 returns [orig]
     * exactly (identity); otherwise out = orig + (orig - blurred) * strength,
     * clamped to 0..1.
     */
    fun blendChannel(orig: Float, blurred: Float, amount: Int): Float {
        val strength = strengthFor(amount)
        if (strength <= 0f) return orig
        return (orig + (orig - blurred) * strength).coerceIn(0f, 1f)
    }
}
