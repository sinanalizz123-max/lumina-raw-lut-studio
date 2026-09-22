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

enum class RenderQuality {
    PREVIEW,
    FINAL
}

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
    const val PIXELS_12MP = 12_000_000L
    const val PIXELS_24MP = 24_000_000L
    const val PIXELS_48MP = 48_000_000L
    const val TILE_FIRST_PIXELS = PIXELS_12MP

    fun tiffWorkingBytes(width: Int, height: Int): Long {
        if (width <= 0 || height <= 0) return 0L
        val pixels = saturatingMultiply(width.toLong(), height.toLong())
        val bytesPerPixel = BYTES_PER_PIXEL_ARGB_8888 + 2L * BYTES_PER_PIXEL_RGB_TIFF
        return saturatingAdd(
            saturatingMultiply(pixels, bytesPerPixel),
            com.lumina.studio.core.export.TiffWriter.TIFF_FILE_OVERHEAD_BYTES
        )
    }

    fun exceedsTiffBudget(width: Int, height: Int, capBytes: Long): Boolean {
        if (width <= 0 || height <= 0 || capBytes <= 0L) return false
        return tiffWorkingBytes(width, height) > capBytes
    }

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
