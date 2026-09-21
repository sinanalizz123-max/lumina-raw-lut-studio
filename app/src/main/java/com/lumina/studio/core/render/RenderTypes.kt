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

object MemoryBudget {
    const val BYTES_PER_PIXEL_ARGB_8888 = 4L
    const val MAX_RENDER_PIXELS = 120_000_000L

    fun bytesFor(width: Int, height: Int, bytesPerPixel: Long = BYTES_PER_PIXEL_ARGB_8888): Long {
        if (width <= 0 || height <= 0 || bytesPerPixel <= 0L) return 0L
        return width.toLong() * height.toLong() * bytesPerPixel
    }

    fun exceeds(width: Int, height: Int, capPixels: Long = MAX_RENDER_PIXELS): Boolean {
        if (width <= 0 || height <= 0) return false
        return width.toLong() * height.toLong() > capPixels
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
