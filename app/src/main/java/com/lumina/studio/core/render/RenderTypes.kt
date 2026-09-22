package com.lumina.studio.core.render

import java.util.concurrent.atomic.AtomicLong

data class Dims(val width: Int, val height: Int) {
    val pixels: Long get() = width.toLong() * height.toLong()
    fun isEmpty(): Boolean = width <= 0 || height <= 0
}

data class PixelRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    fun isEmpty(): Boolean = right <= left || bottom <= top
}

sealed interface RenderSource {
    data class File(val path: String) : RenderSource
    data class Content(val uri: String) : RenderSource

    companion object {
        fun of(pathOrUri: String): RenderSource {
            return try {
                if (java.io.File(pathOrUri).exists()) File(pathOrUri) else Content(pathOrUri)
            } catch (_: Exception) {
                Content(pathOrUri)
            }
        }
    }
}

sealed interface RenderTarget {
    data class Preview(val maxDim: Int) : RenderTarget
    data class Fullscreen(val maxDim: Int) : RenderTarget
    data class Tile(val maxPixels: Long) : RenderTarget
    data class Export(val targetW: Int, val targetH: Int) : RenderTarget
    data object Thumb : RenderTarget
}

enum class RenderColorSpace {
    SRGB,
    DISPLAY_P3
}

/**
 * M5 precision selector for the CPU pipeline (§10, §14).
 *
 * - PREVIEW: interactive path. sRGB-math in [Bitmap.Config.ARGB_8888]
 *   intermediates (8-bit store, float compute). Fast; rounds fractional stage
 *   output to 8-bit between stages.
 * - FINAL: high-quality path (export + fullscreen/zoom-tile final). Same
 *   sRGB-math recipe, but intermediates use [Bitmap.Config.RGBA_F16]
 *   (minSdk 26, so no version gate needed; OOM still falls back to 8888).
 *   Half-float stores 8-bit integers exactly and keeps fractional matrix-stage
 *   output that PREVIEW would round away, reducing banding in LUT trilinear +
 *   curves + HSL chains. Final store is still 8-bit for encode/display.
 *
 * Both qualities run the identical stage order and recipe
 * ([PreviewRenderer.render]); FINAL is not a different look, only less
 * quantization. [ColorPipeline.FINAL_PREVIEW_MAX_DELTA] bounds the visible
 * difference on test ramps.
 */
enum class RenderQuality {
    PREVIEW,
    FINAL
}

// M5: target -> quality mapping. Preview/Thumb stay interactive (PREVIEW);
// Fullscreen/Tile-final/Export take the high-precision path (FINAL).
// Pure function (no android.*) so JVM tests can pin the mapping.
fun qualityForTarget(target: RenderTarget): RenderQuality = when (target) {
    is RenderTarget.Preview -> RenderQuality.PREVIEW
    is RenderTarget.Thumb -> RenderQuality.PREVIEW
    is RenderTarget.Fullscreen -> RenderQuality.FINAL
    is RenderTarget.Tile -> RenderQuality.FINAL
    is RenderTarget.Export -> RenderQuality.FINAL
}

object MemoryBudget {
    const val BYTES_PER_PIXEL_ARGB_8888 = 4L
    const val BYTES_PER_PIXEL_RGB_TIFF = 3L
    const val MAX_RENDER_PIXELS = 120_000_000L

    // M16 large-image policy (§3): tile-first above TILE_FIRST_PIXELS, decode
    // caps per surface, graceful refusal above MAX_RENDER_PIXELS. Values are
    // pure pixel math (no android.*) so JVM tests pin them; see
    // PERFORMANCE.md for the on-device byte budgets. All multiplications
    // below saturate (never overflow) so hostile dimensions fail safe.
    const val PIXELS_12MP = 12_000_000L
    const val PIXELS_24MP = 24_000_000L
    const val PIXELS_48MP = 48_000_000L

    /** Above this, zoom/fullscreen prefer region-decode tiles over full frames. */
    const val TILE_FIRST_PIXELS = PIXELS_12MP

    /**
     * M16 TIFF triple-copy budget (§3): bitmapToRgb holds IntArray(4B/px) +
     * rgb ByteArray(3B/px) while encodeTiff allocates the file image
     * (3B/px + overhead). Pre-flight refusal before ANY giant alloc, so a
     * 48MP TIFF (192MB + 144MB + 144MB) surfaces "image too large" instead
     * of an OOM crash. Strip-export was assessed and rejected: TiffWriter's
     * single-strip bytes are golden-pinned (offset 180, exact length), so a
     * multi-strip rewrite would break parity for no on-device gain without
     * a streaming file writer (see PERFORMANCE.md).
     */
    fun tiffWorkingBytes(width: Int, height: Int): Long {
        if (width <= 0 || height <= 0) return 0L
        val pixels = saturatingMultiply(width.toLong(), height.toLong())
        val bytesPerPixel = BYTES_PER_PIXEL_ARGB_8888 + 2L * BYTES_PER_PIXEL_RGB_TIFF
        return saturatingAdd(
            saturatingMultiply(pixels, bytesPerPixel),
            com.lumina.studio.core.export.TiffWriter.TIFF_FILE_OVERHEAD_BYTES
        )
    }

    /** True when the TIFF path would exceed [capBytes] working memory. */
    fun exceedsTiffBudget(width: Int, height: Int, capBytes: Long): Boolean {
        if (width <= 0 || height <= 0 || capBytes <= 0L) return false
        return tiffWorkingBytes(width, height) > capBytes
    }

    /**
     * M16 strip math (pure): row count per horizontal band so each band's
     * pixel buffer stays under [maxStripBytes]. Returns at least 1 row and
     * at most [height]. Clamped in Long BEFORE toInt (huge caps like
     * Long.MAX_VALUE would otherwise overflow Int). Used by
     * documentation/tests; the single-strip TIFF writer stays authoritative
     * for output bytes.
     */
    fun stripRowsFor(
        width: Int,
        height: Int,
        maxStripBytes: Long,
        bytesPerPixel: Long = BYTES_PER_PIXEL_ARGB_8888
    ): Int {
        if (width <= 0 || height <= 0 || maxStripBytes <= 0L || bytesPerPixel <= 0L) {
            return height.coerceAtLeast(1)
        }
        val rowBytes = width.toLong() * bytesPerPixel
        if (rowBytes <= 0L) return height
        return (maxStripBytes / rowBytes).coerceIn(1L, height.toLong()).toInt()
    }

    /**
     * M16 decode-sample math (pure): power-of-two inSampleSize so the
     * longest edge fits in [maxDim]. Mirrors BitmapFactoryDecoder behavior
     * for tests without android.*.
     *
     * Returns a positive power-of-two sample. The largest representable
     * inSampleSize is 2^30; for the theoretical Int.MAX_VALUE edge case,
     * that is the safe ceiling without overflowing an Int.
     */
    fun sampleFor(longestEdge: Int, maxDim: Int): Int {
        if (longestEdge <= 0 || maxDim <= 0) return 1
        var sample = 1
        while (longestEdge.toLong() / sample.toLong() > maxDim.toLong()) {
            if (sample >= (1 shl 30)) return (1 shl 30)
            sample = sample shl 1
        }
        return sample
    }

    fun bytesFor(width: Int, height: Int, bytesPerPixel: Long = BYTES_PER_PIXEL_ARGB_8888): Long {
        if (width <= 0 || height <= 0 || bytesPerPixel <= 0L) return 0L
        return saturatingMultiply(
            saturatingMultiply(width.toLong(), height.toLong()),
            bytesPerPixel
        )
    }

    fun exceeds(width: Int, height: Int, capPixels: Long = MAX_RENDER_PIXELS): Boolean {
        if (width <= 0 || height <= 0) return false
        return saturatingMultiply(width.toLong(), height.toLong()) > capPixels
    }

    private fun saturatingMultiply(a: Long, b: Long): Long {
        if (a <= 0L || b <= 0L) return 0L
        return if (a > Long.MAX_VALUE / b) Long.MAX_VALUE else a * b
    }

    private fun saturatingAdd(a: Long, b: Long): Long {
        if (a >= Long.MAX_VALUE - b) return Long.MAX_VALUE
        return a + b
    }

    fun exceeds(dims: Dims?, capPixels: Long = MAX_RENDER_PIXELS): Boolean {
        if (dims == null || dims.isEmpty()) return false
        return dims.pixels > capPixels
    }
}

class GenerationTracker {
    private val counter = AtomicLong(0L)

    fun next(): Long = counter.incrementAndGet()
    fun current(): Long = counter.get()
    fun isCurrent(generation: Long): Boolean = generation == counter.get()
    fun isStale(generation: Long): Boolean = generation != counter.get()
}
