package com.lumina.studio.core.merge

import com.lumina.studio.core.render.Dims
import com.lumina.studio.core.render.MemoryBudget

/**
 * M17 merge shared types (§HDR/panorama, pure JVM, no android.*).
 *
 * HDR and panorama stay processing SERVICES behind [HdrMerger]/[PanoStitcher]
 * interfaces (see HdrMerger.kt / PanoramaStitcher.kt); everything in this
 * file is Bitmap-free so JVM tests pin the contracts. The Android edge owns
 * Bitmap decode/recycle and maps [MergeError.message] into
 * RenderResult.Unavailable, OutOfMemory into RenderResult.OomBudget.
 */
enum class MergeKind { HDR, PANORAMA }

/** One merge source file. [exposureEv] is an explicit relative-EV override. */
data class MergeInput(val path: String, val exposureEv: Float? = null)

/** Progress 0..1. Implementations must tolerate exceptions (see [emit]). */
fun interface MergeProgress {
    fun onProgress(fraction: Float)
}

fun MergeProgress?.emit(fraction: Float) {
    if (this == null) return
    runCatching { onProgress(fraction.coerceIn(0f, 1f)) }
}

/**
 * Typed validation/algorithm failures. Every variant carries a user-facing
 * [message]; the UI surfaces it verbatim and NEVER creates a project.
 */
sealed interface MergeError {
    val message: String

    data class TooFew(val count: Int, val min: Int) : MergeError {
        override val message: String =
            "Select at least $min photos to merge (selected $count)."
    }

    data class TooMany(val count: Int, val max: Int) : MergeError {
        override val message: String =
            "Too many photos selected ($count, max $max). Merge a smaller set."
    }

    data class EmptyFrame(val index: Int) : MergeError {
        override val message: String =
            "Photo ${index + 1} is empty (0x0). Re-import it and try again."
    }

    data class DimMismatch(
        val index: Int,
        val width: Int,
        val height: Int,
        val refWidth: Int,
        val refHeight: Int
    ) : MergeError {
        override val message: String =
            "Photo ${index + 1} is ${width}x$height but photo 1 is " +
                "${refWidth}x$refHeight. Merge needs identical dimensions " +
                "(same camera, no crops)."
    }

    data class NoExposureSpread(val spreadEv: Float, val requiredEv: Float) : MergeError {
        override val message: String =
            String.format(
                java.util.Locale.US,
                "Exposures are too similar (spread %.1f EV, need >= %.1f EV). ",
                spreadEv,
                requiredEv
            ) +
                "HDR merge needs bracketed shots (e.g. -1/0/+1 EV)."
    }

    data class AlignmentFailed(val detail: String) : MergeError {
        override val message: String = "Could not align the photos: $detail"
    }

    data class InsufficientOverlap(val detail: String) : MergeError {
        override val message: String = detail
    }

    data class Malformed(val index: Int, val detail: String) : MergeError {
        override val message: String = "Photo ${index + 1} looks corrupt: $detail"
    }

    data class DecodeFailed(val index: Int, val detail: String) : MergeError {
        override val message: String =
            "Could not decode photo ${index + 1}: $detail"
    }

    data class OutOfMemory(val detail: String) : MergeError {
        override val message: String = detail
    }
}

/**
 * M17 memory/frame limits (pure, JVM-pinned).
 *
 * Resolution ladder rationale: the 2-level Laplacian fusion needs all
 * frames resident as float RGB. A per-strip pyramid was assessed and
 * REJECTED — strip-local pyramids tear the Laplacian reconstruction at
 * strip borders. The ladder (2048 -> 1600 -> 1280 -> 1024 longest edge) plus
 * the [MAX_WORKING_BYTES] preflight is the memory control instead, same
 * honest trade as M16's single-strip TIFF verdict. Alignment always runs on
 * 256px thumbnails regardless of the final rung.
 */
object MergeLimits {
    const val MIN_FRAMES = 2
    const val MAX_FRAMES = 6

    /** Alignment thumbnail longest edge (HDR + panorama pairwise). */
    const val ALIGN_THUMB_EDGE = 256

    /** HDR alignment search range as a fraction of the thumbnail edge. */
    const val HDR_SHIFT_FRACTION = 0.10f

    /** Panorama pairwise search range (wide in x, narrow in y). */
    const val PANO_SHIFT_X_FRACTION = 0.45f
    const val PANO_SHIFT_Y_FRACTION = 0.10f

    /** Final-decode longest edge ladder (first rung that fits wins). */
    val FINAL_EDGE_LADDER = intArrayOf(2048, 1600, 1280, 1024)

    /** Minimum relative-EV spread for an HDR bracket. */
    const val MIN_EV_SPREAD = 0.5f

    /** Minimum thumbnail NCC to accept HDR alignment. */
    const val MIN_ALIGN_NCC = 0.20f

    /** Minimum thumbnail NCC to accept a panorama pair. */
    const val MIN_OVERLAP_NCC = 0.35f

    /** Minimum pair overlap (overlap area / frame area). */
    const val MIN_OVERLAP_FRACTION = 0.15f

    /** Minimum overlap pixels for an NCC score to be meaningful. */
    const val MIN_VALID_OVERLAP_PX = 16

    /** Working-set preflight cap (float buffers + bitmaps + output). */
    const val MAX_WORKING_BYTES = 256L * 1024L * 1024L

    /**
     * Conservative HDR working set: per frame 20 B/px (float RGB 12 +
     * luma 4 + weight 4) plus 40 B/px fixed (pyramid temps, F16 output,
     * ARGB intermediates). Over-estimates on purpose: refusal is a clean
     * error, OOM is a crash.
     */
    fun hdrWorkingBytes(width: Int, height: Int, frames: Int): Long {
        if (width <= 0 || height <= 0 || frames <= 0) return 0L
        val px = width.toLong() * height.toLong()
        return px * (frames.toLong() * 20L + 40L)
    }

    fun hdrFitsBudget(width: Int, height: Int, frames: Int): Boolean {
        if (width <= 0 || height <= 0 || frames <= 0) return false
        return hdrWorkingBytes(width, height, frames) <= MAX_WORKING_BYTES
    }

    /**
     * Largest ladder rung whose working set fits. Null = refuse with
     * [MergeError.OutOfMemory] before ANY giant alloc. Deterministic.
     */
    fun hdrFinalEdge(srcWidth: Int, srcHeight: Int, frames: Int): Int? {
        if (srcWidth <= 0 || srcHeight <= 0 || frames <= 0) return null
        for (edge in FINAL_EDGE_LADDER) {
            val longest = maxOf(srcWidth, srcHeight)
            val scale = if (longest <= edge) 1.0 else edge.toDouble() / longest.toDouble()
            val w = ((srcWidth * scale) + 0.5).toInt().coerceAtLeast(1)
            val h = ((srcHeight * scale) + 0.5).toInt().coerceAtLeast(1)
            if (hdrFitsBudget(w, h, frames)) return edge
        }
        return null
    }

    /**
     * Panorama working-set estimate. Canvas is estimated conservatively as
     * framePixels * (1 + 0.5 * (n - 1)) (side-by-side chain at 50% overlap);
     * real canvases are usually smaller, so this over-refuses rather than
     * OOMs. Frames stay resident as float RGB (12 B/px), canvas holds float
     * RGB + weight (16 B/px) plus output slack.
     */
    fun panoWorkingBytes(framePixels: Long, frames: Int): Long {
        if (framePixels <= 0L || frames <= 0) return 0L
        val canvasPx = (framePixels * (2L + frames - 1L)) / 2L
        return framePixels * frames.toLong() * 12L + canvasPx * 24L
    }

    fun panoFitsBudget(framePixels: Long, frames: Int): Boolean {
        if (framePixels <= 0L || frames <= 0) return false
        return panoWorkingBytes(framePixels, frames) <= MAX_WORKING_BYTES
    }

    fun panoFinalEdge(srcWidth: Int, srcHeight: Int, frames: Int): Int? {
        if (srcWidth <= 0 || srcHeight <= 0 || frames <= 0) return null
        for (edge in FINAL_EDGE_LADDER) {
            val longest = maxOf(srcWidth, srcHeight)
            val scale = if (longest <= edge) 1.0 else edge.toDouble() / longest.toDouble()
            val w = ((srcWidth * scale) + 0.5).toInt().coerceAtLeast(1)
            val h = ((srcHeight * scale) + 0.5).toInt().coerceAtLeast(1)
            if (panoFitsBudget(w.toLong() * h.toLong(), frames)) return edge
        }
        return null
    }

    /** Canvas pixel preflight (mirrors MemoryBudget.MAX_RENDER_PIXELS). */
    fun canvasFitsBudget(canvasWidth: Int, canvasHeight: Int): Boolean {
        if (canvasWidth <= 0 || canvasHeight <= 0) return false
        return !MemoryBudget.exceeds(canvasWidth, canvasHeight)
    }
}

/**
 * Count + dims validation shared by HDR and panorama. Returns the first
 * failure, or null when the frame list is structurally mergeable.
 */
fun validateFrameList(sizes: List<Dims>): MergeError? {
    if (sizes.size < MergeLimits.MIN_FRAMES) {
        return MergeError.TooFew(sizes.size, MergeLimits.MIN_FRAMES)
    }
    if (sizes.size > MergeLimits.MAX_FRAMES) {
        return MergeError.TooMany(sizes.size, MergeLimits.MAX_FRAMES)
    }
    val ref = sizes[0]
    if (ref.isEmpty()) return MergeError.EmptyFrame(0)
    for (i in 1 until sizes.size) {
        val s = sizes[i]
        if (s.isEmpty()) return MergeError.EmptyFrame(i)
        if (s.width != ref.width || s.height != ref.height) {
            return MergeError.DimMismatch(i, s.width, s.height, ref.width, ref.height)
        }
    }
    return null
}

/**
 * Byte-level source contract (pure JVM): the on-device decoder only opens
 * JPEG/PNG/TIFF-family bytes (DNG is TIFF-based). Checking the magic here
 * gives JVM tests a real decoder-validation gate without BitmapFactory.
 */
object MergeBytes {
    fun kindOf(prefix: ByteArray): String? {
        if (prefix.size < 4) return null
        val b0 = prefix[0].toInt() and 0xFF
        val b1 = prefix[1].toInt() and 0xFF
        val b2 = prefix[2].toInt() and 0xFF
        val b3 = prefix[3].toInt() and 0xFF
        if (b0 == 0xFF && b1 == 0xD8 && b2 == 0xFF) return "jpeg"
        if (b0 == 0x89 && b1 == 0x50 && b2 == 0x4E && b3 == 0x47) return "png"
        if (b0 == 0x49 && b1 == 0x49 && b2 == 0x2A && b3 == 0x00) return "tiff"
        if (b0 == 0x4D && b1 == 0x4D && b2 == 0x00 && b3 == 0x2A) return "tiff"
        return null
    }

    fun validateHeader(prefix: ByteArray, index: Int, length: Long): MergeError? {
        if (length <= 0L) return MergeError.Malformed(index, "empty file (0 bytes).")
        if (prefix.size < 4) return MergeError.Malformed(index, "file too small to hold an image header.")
        if (kindOf(prefix) == null) {
            return MergeError.Malformed(
                index,
                "unsupported image header (need JPEG, PNG or TIFF bytes)."
            )
        }
        return null
    }
}

/**
 * Owned float-RGB frame set (values 0..1, [frames][k] size w*h*3).
 * Ownership: whoever creates it must [release] it (see [use]); merge
 * functions never release their input. The [released] flag is the JVM
 * no-leak gate: tests assert it flips on success AND on failure/cancel.
 */
class MergeFrames(
    val width: Int,
    val height: Int,
    val frames: MutableList<FloatArray>,
    val exposureEvs: List<Float>
) {
    var released: Boolean = false
        private set

    fun release() {
        if (!released) {
            released = true
            frames.clear()
        }
    }
}

inline fun <T> MergeFrames.use(block: (MergeFrames) -> T): T {
    try {
        return block(this)
    } finally {
        release()
    }
}
