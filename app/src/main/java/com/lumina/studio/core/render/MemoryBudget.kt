package com.lumina.studio.core.render

/**
 * Conservative memory guard for operations that intentionally create multiple
 * full-frame buffers. This is a refusal check, not a guarantee of allocation
 * success; Android may impose a lower effective heap limit.
 */
object MemoryBudget {
    fun availableHeapBytes(): Long {
        return runCatching {
            Runtime.getRuntime().maxMemory().coerceAtLeast(0L)
        }.getOrDefault(0L)
    }

    fun exceedsTiffBudget(width: Int, height: Int, capBytes: Long): Boolean {
        if (width <= 0 || height <= 0 || capBytes <= 0L) return true
        val pixels = width.toLong() * height.toLong()
        if (pixels <= 0L) return true

        // Bitmap (~4 B/px) + IntArray (~4 B/px) + RGB payload (~3 B/px) +
        // TIFF output (~3 B/px), with a conservative 25% headroom reserve.
        val required = runCatching { Math.multiplyExact(pixels, 14L) }
            .getOrDefault(Long.MAX_VALUE)
        val safeCap = minOf(capBytes, (availableHeapBytes() * 0.75).toLong())
        return required > safeCap
    }
}
