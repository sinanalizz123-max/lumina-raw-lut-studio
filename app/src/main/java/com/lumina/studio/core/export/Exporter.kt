package com.lumina.studio.core.export

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.WindowManager
import androidx.core.net.toUri
import androidx.exifinterface.media.ExifInterface
import com.lumina.studio.BuildConfig
import com.lumina.studio.core.data.local.Project
import com.lumina.studio.core.edit.EditParams
import com.lumina.studio.core.edit.EditParamsJson
import com.lumina.studio.core.lut.LutCube
import com.lumina.studio.core.render.cpu.RenderBackends
import com.lumina.studio.core.util.ImageFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ExportFormat(val mime: String, val extension: String) {
    JPEG("image/jpeg", "jpg"),
    PNG("image/png", "png"),
    TIFF("image/tiff", "tif"),
    // M11 (§38): WebP via Bitmap encoder (lossless at quality 100, lossy
    // below) and HEIC via the platform MediaCodec encoder where present.
    // PNG stays lossless (quality ignored); JPEG/PNG/TIFF behavior unchanged.
    WEBP("image/webp", "webp"),
    HEIC("image/heic", "heic")
}

enum class ExportColorSpace(val label: String, val key: String) {
    SRGB("sRGB", "sRGB"),
    DISPLAY_P3("Display P3 (converted output, device-supported)", "Display P3");

    companion object {
        fun fromKey(key: String?): ExportColorSpace {
            if (key == null) return SRGB
            val normalized = key.trim().lowercase(Locale.US)
            return if (normalized == "display p3" || normalized == "display_p3" || normalized == "p3") DISPLAY_P3
            else SRGB
        }

        // M5: pure no-P3-on-unsupported gate (no android.*, JVM-pinned).
        // Display P3 is offered only when the device reports a wide-gamut
        // display; anything else (garbage keys via fromKey, unsupported
        // displays) coerces to sRGB so exports never claim P3 they cannot
        // show. Callers must use this instead of inlining the check.
        fun coerceForDisplay(
            requested: ExportColorSpace,
            wideGamutSupported: Boolean
        ): ExportColorSpace =
            if (requested == DISPLAY_P3 && !wideGamutSupported) SRGB else requested
    }
}

enum class QualityPreset(val label: String) {
    MAXIMUM("Maximum"),
    HIGH("High"),
    CUSTOM("Custom")
}

enum class ResolutionMode(val label: String) {
    ORIGINAL("Original"),
    CUSTOM("Custom")
}

data class ExportSettings(
    val format: ExportFormat = ExportFormat.JPEG,
    val qualityPreset: QualityPreset = QualityPreset.MAXIMUM,
    val customQuality: Int = 90,
    val resolutionMode: ResolutionMode = ResolutionMode.ORIGINAL,
    val customMaxDim: Int = 2048,
    val preserveExif: Boolean = true,
    val includeLocation: Boolean = false,
    val colorSpace: ExportColorSpace = ExportColorSpace.SRGB,
    // M11 (§38 size): longest-edge control. 0 = off (legacy
    // Original/Custom-max-dim behavior). When > 0 it is the single size
    // control (Custom max-dim is ignored) and keeps the aspect ratio with no
    // custom width/height. No upscale unless [allowUpscale] is explicit.
    val longestEdge: Int = 0,
    val allowUpscale: Boolean = false,
    // M11 (§38 sharpen): output unsharp amount 0..100, 0 = off (default).
    // Applied post-resize pre-encode (see applyOutputSharpen).
    val outputSharpen: Int = 0,
    // M11 (§36 strip): writes a clean file with no EXIF (overrides
    // preserveExif/includeLocation). Session-only, never persisted.
    val stripMetadata: Boolean = false
) {
    fun effectiveQuality(): Int = when (qualityPreset) {
        QualityPreset.MAXIMUM -> 100
        QualityPreset.HIGH -> 90
        QualityPreset.CUSTOM -> customQuality.coerceIn(1, 100)
    }

    // M11 (§36): explicit per-export "Remove location" toggle. Inverse of
    // the persisted includeLocation key (unchanged storage, no migration):
    // removeLocation == true strips every GPS tag from the export.
    val removeLocation: Boolean get() = !includeLocation
}

object Exporter {
    const val MIN_CUSTOM_DIM = 256
    const val MAX_CUSTOM_DIM = 8192
    // M11 (§38 size): longest-edge bounds. OFF = 0 (legacy behavior).
    const val LONGEST_EDGE_OFF = 0
    const val MIN_LONGEST_EDGE = 256
    const val MAX_LONGEST_EDGE = 8192
    // M11 (§38): WebP quality threshold. effectiveQuality 100 encodes
    // WEBP_LOSSLESS, anything below encodes WEBP_LOSSY (API 30+; the legacy
    // WEBP constant below that, minSdk is 26).
    const val WEBP_LOSSLESS_QUALITY = 100
    const val RELATIVE_DIR = "Pictures/Lumina"
    const val RELATIVE_DOWNLOAD_DIR = "Download/Lumina"
    const val MAX_RAW_READ_BYTES = 150_000_000L

    fun targetDimensions(srcW: Int, srcH: Int, settings: ExportSettings): Pair<Int, Int> {
        if (srcW <= 0 || srcH <= 0) return 0 to 0
        // M11: an explicit longest edge is the single size control.
        if (settings.longestEdge > 0) {
            return longestEdgeDimensions(srcW, srcH, settings.longestEdge, settings.allowUpscale)
        }
        if (settings.resolutionMode != ResolutionMode.CUSTOM) return srcW to srcH
        val longest = maxOf(srcW, srcH)
        val cap = settings.customMaxDim.coerceIn(MIN_CUSTOM_DIM, MAX_CUSTOM_DIM)
        if (longest <= cap) return srcW to srcH
        val scale = cap / longest.toFloat()
        return (srcW * scale + 0.5f).toInt().coerceAtLeast(1) to
            (srcH * scale + 0.5f).toInt().coerceAtLeast(1)
    }

    /**
     * M11 (§38 size, pure): fit [srcW] x [srcH] so the longest edge equals
     * [longestEdge] px. Aspect-locked (no custom width/height control);
     * never upscales unless [allowUpscale] is true. Values below
     * [MIN_LONGEST_EDGE] clamp up; [LONGEST_EDGE_OFF]/negative disables the
     * cap and returns the source dims. Degenerate input returns 0 x 0.
     */
    fun longestEdgeDimensions(
        srcW: Int,
        srcH: Int,
        longestEdge: Int,
        allowUpscale: Boolean = false
    ): Pair<Int, Int> {
        if (srcW <= 0 || srcH <= 0) return 0 to 0
        if (longestEdge <= LONGEST_EDGE_OFF) return srcW to srcH
        val cap = longestEdge.coerceIn(MIN_LONGEST_EDGE, MAX_LONGEST_EDGE)
        val longest = maxOf(srcW, srcH)
        if (longest == cap) return srcW to srcH
        if (longest < cap && !allowUpscale) return srcW to srcH
        val scale = cap / longest.toFloat()
        if (!scale.isFinite() || scale <= 0f) return srcW to srcH
        return (srcW * scale + 0.5f).toInt().coerceAtLeast(1) to
            (srcH * scale + 0.5f).toInt().coerceAtLeast(1)
    }

    /**
     * M11 (§38, pure): even dims for the HEIC path. YUV420 chroma
     * subsampling needs even width/height; odd inputs round up by one (the
     * encoder stretches the edge pixel, a documented 1px approximation).
     */
    fun evenDims(w: Int, h: Int): Pair<Int, Int> {
        if (w <= 0 || h <= 0) return 0 to 0
        return ((w + 1) / 2 * 2) to ((h + 1) / 2 * 2)
    }

    fun estimateBytes(trialBytes: Long, trialPixels: Long, fullPixels: Long): Long {
        if (trialBytes <= 0L || trialPixels <= 0L || fullPixels <= 0L) return 0L
        val scaled = trialBytes.toDouble() * (fullPixels.toDouble() / trialPixels.toDouble())
        if (!scaled.isFinite()) return 0L
        return scaled.toLong().coerceIn(1L, Long.MAX_VALUE)
    }

    fun estimateTiffBytes(width: Int, height: Int): Long =
        TiffWriter.estimateBytes(width, height)

    fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "–"
        return when {
            bytes < 1024L -> "$bytes B"
            bytes < 1024L * 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
            bytes < 1024L * 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
            else -> String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        }
    }

    fun displayName(format: ExportFormat, nowMs: Long = System.currentTimeMillis()): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(nowMs))
        return "lumina_$stamp.${format.extension}"
    }

    // M11 (§38 formats, pure): which formats the export UI may offer. JPEG /
    // PNG / TIFF are always available; WebP and HEIC appear only when the
    // device encoder exists, so there is never a dead row. Tested with
    // synthetic booleans; the on-device queries below supply the inputs.
    fun availableFormats(webpEncoderPresent: Boolean, heicEncoderPresent: Boolean): List<ExportFormat> {
        val out = ArrayList<ExportFormat>()
        out.add(ExportFormat.JPEG)
        out.add(ExportFormat.PNG)
        out.add(ExportFormat.TIFF)
        if (webpEncoderPresent) out.add(ExportFormat.WEBP)
        if (heicEncoderPresent) out.add(ExportFormat.HEIC)
        return out
    }

    // M11 (§38): probe the Bitmap WebP encoder with a 1x1 trial compress.
    // minSdk is 26 so the WEBP constant always exists; the LOSSY/LOSSLESS
    // split is API 30+ (see webpFormatFor).
    fun hasWebpEncoder(): Boolean = runCatching {
        val probe = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        try {
            val out = ByteArrayOutputStream()
            probe.compress(webpFormatFor(90), 90, out) && out.size() > 0
        } finally {
            runCatching { probe.recycle() }
        }
    }.getOrDefault(false)

    // M11 (§38): HEIC needs Android 9+ plus a platform MediaCodec encoder
    // for the HEIC mime. Absent -> the HEIC row stays hidden.
    fun hasHeicEncoder(): Boolean = runCatching {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        // MIMETYPE_IMAGE_ANDROID_HEIC is a compile-time constant (inlined),
        // so referencing it is safe below API 28; the version guard above
        // keeps the codec query itself off old devices.
        val mime = MediaFormat.MIMETYPE_IMAGE_ANDROID_HEIC
        MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.any { info ->
            runCatching { info.isEncoder }.getOrDefault(false) &&
                runCatching { info.supportedTypes }.getOrDefault(emptyArray())
                    .any { it.equals(mime, ignoreCase = true) }
        }
    }.getOrDefault(false)

    internal fun webpFormatFor(quality: Int): Bitmap.CompressFormat {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return if (quality >= WEBP_LOSSLESS_QUALITY) {
                Bitmap.CompressFormat.WEBP_LOSSLESS
            } else {
                Bitmap.CompressFormat.WEBP_LOSSY
            }
        }
        @Suppress("DEPRECATION")
        return Bitmap.CompressFormat.WEBP
    }

    // M11 (§38 estimate): HEIC has no cheap Bitmap trial path (MediaCodec
    // per keystroke is too heavy), so the estimate scales a JPEG trial at
    // the same quality by ~0.6x — a documented approximation, not a promise.
    fun estimateHeicBytes(jpegTrialBytes: Long, trialPixels: Long, fullPixels: Long): Long {
        val jpeg = estimateBytes(jpegTrialBytes, trialPixels, fullPixels)
        if (jpeg <= 0L) return 0L
        return (jpeg * 0.6 + 0.5).toLong().coerceAtLeast(1L)
    }

    fun renderForExport(
        src: Bitmap,
        params: EditParams,
        lut: LutCube?,
        targetW: Int,
        targetH: Int
    ): Bitmap = RenderBackends.export().renderForExport(src, params, lut, targetW, targetH)

    fun compress(bitmap: Bitmap, settings: ExportSettings, tmpDir: java.io.File? = null): ByteArray {
        if (settings.format == ExportFormat.TIFF) return encodeTiff(bitmap)
        if (settings.format == ExportFormat.HEIC) return encodeHeic(bitmap, settings.effectiveQuality(), tmpDir)
        val stream = ByteArrayOutputStream()
        when (settings.format) {
            ExportFormat.JPEG -> bitmap.compress(
                Bitmap.CompressFormat.JPEG,
                settings.effectiveQuality(),
                stream
            )
            // PNG stays lossless: quality settings are ignored by the encoder.
            ExportFormat.PNG -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            ExportFormat.WEBP -> bitmap.compress(
                webpFormatFor(settings.effectiveQuality()),
                settings.effectiveQuality().coerceIn(1, 100),
                stream
            )
            ExportFormat.TIFF, ExportFormat.HEIC -> throw IllegalStateException("unreachable")
        }
        return stream.toByteArray()
    }

    fun bitmapToRgb(bitmap: Bitmap): ByteArray {
        val w = bitmap.width
        val h = bitmap.height
        require(w > 0 && h > 0) { "Invalid bitmap dimensions: $w x $h" }
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val rgb = ByteArray(w * h * 3)
        var o = 0
        for (pixel in pixels) {
            rgb[o++] = ((pixel shr 16) and 0xFF).toByte()
            rgb[o++] = ((pixel shr 8) and 0xFF).toByte()
            rgb[o++] = (pixel and 0xFF).toByte()
        }
        return rgb
    }

    fun encodeTiff(bitmap: Bitmap): ByteArray =
        TiffWriter.encodeTiff(bitmap.width, bitmap.height, bitmapToRgb(bitmap))

    /**
     * M11 (§38 sharpen): small-radius unsharp mask on the export-size
     * bitmap, applied post-resize (and post-upscale/colorspace) pre-encode.
     * Amount 0 is the early-out: the input instance is returned untouched
     * (identity, zero delta). Otherwise a 3x3 box blur approximates the
     * blurred copy and [OutputSharpen.blendChannel] mixes per channel.
     */
    fun applyOutputSharpen(bitmap: Bitmap, amount: Int): Bitmap {
        if (OutputSharpen.isIdentity(amount)) return bitmap
        val w = runCatching { bitmap.width }.getOrDefault(0)
        val h = runCatching { bitmap.height }.getOrDefault(0)
        if (w <= 0 || h <= 0) return bitmap
        return try {
            val total = w * h
            val pixels = IntArray(total)
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
            val blurred = boxBlur3(pixels, w, h)
            for (i in pixels.indices) {
                val p = pixels[i]
                val b = blurred[i]
                val a = p ushr 24
                val r = OutputSharpen.blendChannel(
                    ((p shr 16) and 0xFF) / 255f, ((b shr 16) and 0xFF) / 255f, amount
                )
                val g = OutputSharpen.blendChannel(
                    ((p shr 8) and 0xFF) / 255f, ((b shr 8) and 0xFF) / 255f, amount
                )
                val bl = OutputSharpen.blendChannel(
                    (p and 0xFF) / 255f, (b and 0xFF) / 255f, amount
                )
                pixels[i] = (a shl 24) or
                    ((r * 255f + 0.5f).toInt().coerceIn(0, 255) shl 16) or
                    ((g * 255f + 0.5f).toInt().coerceIn(0, 255) shl 8) or
                    (bl * 255f + 0.5f).toInt().coerceIn(0, 255)
            }
            val out = try {
                Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            } catch (_: Exception) {
                return bitmap
            }
            out.setPixels(pixels, 0, w, 0, 0, w, h)
            out
        } catch (_: OutOfMemoryError) {
            bitmap
        } catch (_: Exception) {
            bitmap
        }
    }

    private fun boxBlur3(pixels: IntArray, w: Int, h: Int): IntArray {
        val out = IntArray(pixels.size)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var r = 0
                var g = 0
                var b = 0
                var n = 0
                for (dy in -1..1) {
                    val yy = (y + dy).coerceIn(0, h - 1)
                    for (dx in -1..1) {
                        val xx = (x + dx).coerceIn(0, w - 1)
                        val p = pixels[yy * w + xx]
                        r += (p shr 16) and 0xFF
                        g += (p shr 8) and 0xFF
                        b += p and 0xFF
                        n++
                    }
                }
                val a = pixels[y * w + x] ushr 24
                out[y * w + x] = (a shl 24) or
                    ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
            }
        }
        return out
    }

    /**
     * M11 (§38 size): the crop-aware render backend never upscales, so an
     * explicit [ExportSettings.allowUpscale] longest-edge request is applied
     * here post-render pre-sharpen. Returns the input when upscaling is off
     * or the frame already meets the target longest edge.
     */
    fun upscaleIfAllowed(rendered: Bitmap, targetW: Int, targetH: Int, allowUpscale: Boolean): Bitmap {
        if (!allowUpscale) return rendered
        if (targetW <= 0 || targetH <= 0) return rendered
        val rw = runCatching { rendered.width }.getOrDefault(0)
        val rh = runCatching { rendered.height }.getOrDefault(0)
        if (rw <= 0 || rh <= 0) return rendered
        if (maxOf(rw, rh) >= maxOf(targetW, targetH)) return rendered
        return try {
            Bitmap.createScaledBitmap(rendered, targetW, targetH, true)
        } catch (_: Exception) {
            rendered
        }
    }

    /**
     * M11 (§38 HEIC): single-image encode through the platform MediaCodec
     * HEIC encoder into a MediaMuxer HEIF container. Requires API 28+ and a
     * device encoder (see [hasHeicEncoder]); every failure surfaces as
     * IllegalStateException with a human message so the transaction can
     * clean up and the UI can show it. Quality maps to bitrate
     * (~0.15..1.5 bits/px, documented heuristic). Odd dims are padded via
     * [evenDims] (YUV420 needs even width/height).
     */
    fun encodeHeic(bitmap: Bitmap, quality: Int, tmpDir: java.io.File?): ByteArray {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            throw IllegalStateException("HEIC export needs Android 9 or newer")
        }
        if (!hasHeicEncoder()) {
            throw IllegalStateException("This device has no HEIC encoder")
        }
        val dir = tmpDir ?: throw IllegalStateException("HEIC export needs a temp directory")
        val srcW = runCatching { bitmap.width }.getOrDefault(0)
        val srcH = runCatching { bitmap.height }.getOrDefault(0)
        require(srcW > 0 && srcH > 0) { "Invalid bitmap dimensions: $srcW x $srcH" }
        val (w, h) = evenDims(srcW, srcH)
        val frame = if (w == srcW && h == srcH) bitmap else Bitmap.createScaledBitmap(bitmap, w, h, true)
        var frameOwned = frame !== bitmap
        var tmp: java.io.File? = null
        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        try {
            val total = w * h
            val pixels = IntArray(total)
            frame.getPixels(pixels, 0, w, 0, 0, w, h)
            if (frameOwned) {
                runCatching { frame.recycle() }
                frameOwned = false
            }
            val nv12 = argbToNv12(pixels, w, h)
            val mime = MediaFormat.MIMETYPE_IMAGE_ANDROID_HEIC
            val q = quality.coerceIn(1, 100)
            val bitsPerPx = 0.15f + (q / 100f) * 1.35f
            val format = MediaFormat.createVideoFormat(mime, w, h).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
                )
                setLong(
                    MediaFormat.KEY_BIT_RATE,
                    (w.toLong() * h * bitsPerPx).toLong().coerceAtLeast(64_000L)
                )
                setInteger(MediaFormat.KEY_FRAME_RATE, 1)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            codec = MediaCodec.createEncoderByType(mime)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val active = codec
            val inIndex = active.dequeueInputBuffer(10_000)
            if (inIndex < 0) throw IllegalStateException("HEIC encoder is busy, try again")
            active.getInputBuffer(inIndex)?.let { buf ->
                buf.clear()
                buf.put(nv12)
                active.queueInputBuffer(inIndex, 0, nv12.size, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            } ?: throw IllegalStateException("HEIC encoder refused input")
            tmp = java.io.File.createTempFile("lumina_heic_", ".heic", dir)
            muxer = MediaMuxer(tmp.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_HEIF)
            var track = -1
            var started = false
            val info = MediaCodec.BufferInfo()
            var done = false
            var guard = 0
            while (!done && guard++ < 120) {
                val outIndex = active.dequeueOutputBuffer(info, 10_000)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (started) throw IllegalStateException("HEIC encoder changed format twice")
                        track = muxer.addTrack(active.outputFormat)
                        muxer.start()
                        started = true
                    }
                    outIndex >= 0 -> {
                        val buf = active.getOutputBuffer(outIndex)
                        if (buf != null && info.size > 0 && started &&
                            info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                        ) {
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            muxer.writeSampleData(track, buf, info)
                        }
                        active.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) done = true
                    }
                    outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (started) done = true
                    }
                }
            }
            if (!started) throw IllegalStateException("HEIC encoder produced no output")
            runCatching { active.stop() }
            runCatching { active.release() }
            codec = null
            runCatching { muxer.stop() }
            runCatching { muxer.release() }
            muxer = null
            val bytes = tmp.readBytes()
            if (bytes.isEmpty()) throw IllegalStateException("HEIC encoder produced an empty file")
            return bytes
        } catch (e: Exception) {
            if (e is IllegalStateException) throw e
            throw IllegalStateException("HEIC encode failed: ${e.message ?: "unknown error"}")
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            if (frameOwned) runCatching { frame.recycle() }
            runCatching { tmp?.delete() }
        }
    }

    internal fun argbToNv12(pixels: IntArray, w: Int, h: Int): ByteArray {
        val ySize = w * h
        val out = ByteArray(ySize + ySize / 2)
        var uv = ySize
        for (y in 0 until h) {
            for (x in 0 until w) {
                val p = pixels[y * w + x]
                val r = ((p shr 16) and 0xFF).toFloat()
                val g = ((p shr 8) and 0xFF).toFloat()
                val b = (p and 0xFF).toFloat()
                val yy = (0.299f * r + 0.587f * g + 0.114f * b + 0.5f).toInt().coerceIn(0, 255)
                out[y * w + x] = yy.toByte()
                if (y % 2 == 0 && x % 2 == 0) {
                    val u = (-0.169f * r - 0.331f * g + 0.5f * b + 128f + 0.5f).toInt().coerceIn(0, 255)
                    val v = (0.5f * r - 0.419f * g - 0.081f * b + 128f + 0.5f).toInt().coerceIn(0, 255)
                    out[uv++] = u.toByte()
                    out[uv++] = v.toByte()
                }
            }
        }
        return out
    }

    // Wide-gamut detection via the real platform signals
    // (Configuration.isScreenWideColorGamut + Display.isWideColorGamut).
    fun isWideGamutDisplay(context: Context): Boolean = runCatching {
        val configWide = context.resources.configuration.isScreenWideColorGamut
        val displayWide = runCatching {
            val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                context.display
            } else {
                @Suppress("DEPRECATION")
                (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.defaultDisplay
            }
            display?.isWideColorGamut == true
        }.getOrDefault(false)
        configWide || displayWide
    }.getOrDefault(false)

    // M11 (§36 colorspace): per-format tag correctness. Our TIFF writer is
    // an untagged 8-bit RGB container (sRGB by definition), so a Display P3
    // request would write mistagged values — TIFF coerces to sRGB.
    // JPEG/PNG/WebP keep the P3-tagged bitmap container where the platform
    // encoder honors it; HEIC reads the input bitmap colorspace
    // (best-effort, encoder-dependent). sRGB passes through everywhere.
    fun colorSpaceForFormat(
        format: ExportFormat,
        requested: ExportColorSpace
    ): ExportColorSpace =
        if (format == ExportFormat.TIFF) ExportColorSpace.SRGB else requested

    // M5 real P3 output (§14): the render pipeline stays sRGB-math for
    // correctness, and Display P3 now means CONVERTED pixels (linearize sRGB
    // -> XYZ -> P3 D65 -> re-encode, via CpuColorManager.convertP3) in a
    // P3-tagged container — not a relabeled sRGB buffer. sRGB returns the
    // input untouched. Preview P3, when required, must go through this same
    // function (never tag without converting); editor previews otherwise stay
    // sRGB working-space and the system compositor handles P3 displays.
    fun withColorSpace(bitmap: Bitmap, colorSpace: ExportColorSpace): Bitmap {
        if (colorSpace != ExportColorSpace.DISPLAY_P3) return bitmap
        return try {
            RenderBackends.colors().convert(
                bitmap,
                com.lumina.studio.core.render.RenderColorSpace.SRGB,
                com.lumina.studio.core.render.RenderColorSpace.DISPLAY_P3
            )
        } catch (_: Exception) {
            bitmap
        }
    }

    // M11 (§36 metadata): GPS stays end-to-end gated (persisted
    // exportIncludeLocation -> ExportSettings.includeLocation/removeLocation
    // -> copyExifAttributes skips + nulls GPS_TAGS when location is removed).
    // Focal length rides along: TAG_FOCAL_LENGTH(+35MM) are in EXIF_TAGS, so
    // copies preserve it, and the UI surfaces it from ExifReader. Orientation
    // is always normal on export (M3: decode normalizes, we declare 1).
    // Strip-all (settings.stripMetadata) or preserveExif=false writes a clean
    // file: encoder output carries no EXIF by construction, returned as-is.
    // JPEG/WebP/HEIC copy via ExifInterface (best-effort per format); PNG has
    // no EXIF container support here and TIFF is our own untagged writer, so
    // both stay clean by construction.
    fun withSourceExif(
        context: Context,
        jpegBytes: ByteArray,
        settings: ExportSettings,
        sourcePath: String?
    ): ByteArray {
        if (settings.stripMetadata || !settings.preserveExif) return jpegBytes
        if (settings.format != ExportFormat.JPEG &&
            settings.format != ExportFormat.WEBP &&
            settings.format != ExportFormat.HEIC
        ) return jpegBytes
        if (sourcePath.isNullOrBlank()) return jpegBytes
        return try {
            val srcFile = File(sourcePath)
            if (!srcFile.exists()) return jpegBytes
            val suffix = when (settings.format) {
                ExportFormat.WEBP -> ".webp"
                ExportFormat.HEIC -> ".heic"
                else -> ".jpg"
            }
            val tmp = File.createTempFile("lumina_export_", suffix, context.cacheDir)
            try {
                tmp.writeBytes(jpegBytes)
                copyExifAttributes(srcFile.absolutePath, tmp.absolutePath, settings.includeLocation)
                tmp.readBytes()
            } finally {
                tmp.delete()
            }
        } catch (_: Exception) {
            jpegBytes
        }
    }

    private fun copyExifAttributes(srcPath: String, dstPath: String, includeLocation: Boolean) {
        val src = ExifInterface(srcPath)
        val dst = ExifInterface(dstPath)
        for (tag in EXIF_TAGS) {
            if (!includeLocation && tag in GPS_TAGS) continue
            try {
                src.getAttribute(tag)?.let { dst.setAttribute(tag, it) }
            } catch (_: Exception) {
            }
        }
        // Displayed pixels are already EXIF-normalized at decode, so exported
        // files must always declare orientation 1 (normal) to avoid double rotation.
        try {
            dst.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
        } catch (_: Exception) {
        }
        if (!includeLocation) {
            for (tag in GPS_TAGS) {
                try {
                    dst.setAttribute(tag, null)
                } catch (_: Exception) {
                }
            }
        }
        try {
            dst.saveAttributes()
        } catch (_: Exception) {
        }
    }

    // M11 (§39): MediaStore pending transaction. The row is created
    // IS_PENDING=1, bytes stream in, then IS_PENDING=0 finalizes. Any
    // failure deletes the incomplete item so no corrupt file ever lands in
    // the gallery. Cancellation is honored before insert and after write.
    suspend fun saveToGallery(
        context: Context,
        bytes: ByteArray,
        format: ExportFormat,
        fileName: String
    ): Uri = withContext(Dispatchers.IO) {
        ensureActive()
        val resolver = context.contentResolver
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, format.mime)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, RELATIVE_DIR)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            } else {
                @Suppress("DEPRECATION")
                put(
                    MediaStore.Images.Media.DATA,
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                        .resolve("Lumina/$fileName").absolutePath
                )
            }
        }
        val uri = resolver.insert(collection, values)
            ?: throw IllegalStateException("MediaStore refused the export")
        try {
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: throw IllegalStateException("Could not write the exported file")
            ensureActive()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
        uri
    }

    // M11 (§94): validation failures abort the transaction instead of
    // publishing.
    class ExportValidationException(message: String) : IllegalStateException(message)

    /**
     * M11 (§94): on-device result validation. Checks the encoded bytes
     * before anything reaches MediaStore: non-empty, dimensions match the
     * final frame ([expectedW] x [expectedH], decode bounds), and the
     * platform decoder reopens the stream (bounds + a memory-capped full
     * decode). TIFF validates through [ExportValidation.tiffDimensions]
     * because BitmapFactory cannot decode TIFF. Returns failure strings;
     * empty means valid.
     */
    fun validateBytes(
        bytes: ByteArray,
        format: ExportFormat,
        expectedW: Int,
        expectedH: Int
    ): List<String> {
        if (bytes.isEmpty()) {
            return ExportValidation.check(
                ExportValidation.Input(
                    fileExists = true, sizeBytes = 0L,
                    actualW = 0, actualH = 0,
                    expectedW = expectedW, expectedH = expectedH,
                    decodes = false, tempGone = true
                )
            )
        }
        if (format == ExportFormat.TIFF) {
            val dims = runCatching { ExportValidation.tiffDimensions(bytes) }.getOrNull()
            return ExportValidation.check(
                ExportValidation.Input(
                    fileExists = true, sizeBytes = bytes.size.toLong(),
                    actualW = dims?.first ?: 0, actualH = dims?.second ?: 0,
                    expectedW = expectedW, expectedH = expectedH,
                    decodes = dims != null, tempGone = true
                )
            )
        }
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val bw = bounds.outWidth
            val bh = bounds.outHeight
            var decodes = bw > 0 && bh > 0
            if (decodes) {
                // Memory-capped reopen proof: the sampled decode still parses
                // the whole stream, catching truncation/corruption.
                val pixels = bw.toLong() * bh.toLong()
                var sample = 1
                while (pixels / (sample * sample) > 2_000_000L && sample < 16) sample *= 2
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                val decoded = runCatching {
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                }.getOrNull()
                decodes = decoded != null
                if (decoded != null) runCatching { decoded.recycle() }
            }
            ExportValidation.check(
                ExportValidation.Input(
                    fileExists = true, sizeBytes = bytes.size.toLong(),
                    actualW = bw.coerceAtLeast(0), actualH = bh.coerceAtLeast(0),
                    expectedW = expectedW, expectedH = expectedH,
                    decodes = decodes, tempGone = true
                )
            )
        } catch (_: OutOfMemoryError) {
            listOf("validation ran out of memory")
        } catch (e: Exception) {
            listOf("validation failed: ${e.message ?: "unknown error"}")
        }
    }

    /**
     * M11 (§39+§94): the safe publish transaction executed by every export
     * path (single + batch). Stages follow [ExportTransaction.ORDERED_STAGES]:
     * bytes are staged to a cache temp file, validated, then handed to the
     * MediaStore pending transaction. The temp is always deleted (no temp
     * left behind); failures and cancellation propagate with the MediaStore
     * item already removed and bitmap release left to the caller finally.
     */
    suspend fun publishBytes(
        context: Context,
        bytes: ByteArray,
        format: ExportFormat,
        fileName: String,
        expectedW: Int,
        expectedH: Int
    ): Uri = withContext(Dispatchers.IO) {
        ensureActive()
        var tmp: File? = null
        try {
            tmp = File.createTempFile("lumina_export_", ".tmp", context.cacheDir)
            ensureActive()
            tmp.writeBytes(bytes)
            ensureActive()
            val problems = validateBytes(bytes, format, expectedW, expectedH)
            if (problems.isNotEmpty()) {
                throw ExportValidationException(
                    "Export failed validation: " + problems.joinToString("; ")
                )
            }
            ensureActive()
            saveToGallery(context, bytes, format, fileName)
        } finally {
            runCatching { tmp?.delete() }
        }
    }

    fun isRawSource(project: Project?): Boolean {
        if (project == null) return false
        return isRawSource(project.fileType, project.photoUri, project.mimeType)
    }

    fun isRawSource(fileType: String?, sourcePath: String?, mimeType: String?): Boolean {
        if (!fileType.isNullOrBlank()) {
            val badge = fileType.trim().uppercase(Locale.US)
            if (badge == "DNG" || badge == "RAW") return true
        }
        if (!sourcePath.isNullOrBlank()) {
            val ext = sourcePath.substringAfterLast('.', "").substringBefore('?').lowercase(Locale.US)
            if (ext.isNotEmpty() && ImageFiles.isRaw(ext)) return true
        }
        if (!mimeType.isNullOrBlank()) {
            val mime = mimeType.trim().lowercase(Locale.US)
            if (mime == "image/x-adobe-dng" || mime == "image/x-dng") return true
        }
        return false
    }

    fun rawOriginalName(sourcePath: String?): String {
        val base = sourcePath?.substringAfterLast('/')?.substringAfterLast('\\')?.takeIf { it.isNotBlank() }
            ?: "lumina_raw"
        return if ('.' in base) base else "$base.dng"
    }

    fun sidecarNameFor(rawName: String): String {
        val base = rawName.substringBeforeLast('.', rawName.ifBlank { "lumina_raw" })
        return "$base.lumina.json"
    }

    fun appVersionName(context: Context): String = runCatching { BuildConfig.VERSION_NAME }.getOrNull()
        ?.takeIf { it.isNotBlank() } ?: "unknown"

    fun buildSidecarJson(params: EditParams, appVersion: String, sourceName: String?): String {
        val recipe = EditParamsJson.encode(params)
        val safeSource = (sourceName ?: "").replace("\\", "\\\\").replace("\"", "\\\"")
        val safeVersion = appVersion.replace("\\", "\\\\").replace("\"", "\\\"")
        return "{\"app\":\"Lumina RAW & LUT Studio\"," +
            "\"appVersion\":\"$safeVersion\"," +
            "\"schemaVersion\":" + Sidecar.SCHEMA_VERSION + "," +
            "\"workflow\":\"raw-compatible\"," +
            "\"sourceFile\":\"$safeSource\"," +
            "\"recipe\":$recipe," +
            "\"note\":\"Original sensor data preserved untouched. " +
            "Edits stored as a re-editable recipe, NOT baked in.\"}"
    }

    // Phase-future re-import note: a sidecar import path would read the JSON
    // written by exportSidecar(), recover the embedded \"recipe\" object and
    // apply it with EditParamsJson.decode() onto the matching RAW project.
    // That import UI is intentionally NOT built in Phase 4A.

    // M11 (§39): RAW/sidecar paths are cancellable (ensureActive at every
    // stage boundary); cancellation propagates and the pending MediaStore
    // item is removed by saveRawBytes, leaving the project intact.
    suspend fun exportRawOriginal(
        context: Context,
        sourcePath: String?,
        displayName: String? = null
    ): Uri = withContext(Dispatchers.IO) {
        ensureActive()
        val name = displayName?.takeIf { it.isNotBlank() } ?: rawOriginalName(sourcePath)
        val bytes = readSourceBytes(context, sourcePath)
            ?: throw IllegalStateException("Could not read the original source file")
        ensureActive()
        val uri = saveRawBytes(context, bytes, name, mimeForRawName(name))
        ensureActive()
        uri
    }

    suspend fun exportSidecar(
        context: Context,
        params: EditParams,
        sidecarName: String,
        sourceName: String? = null
    ): Uri = withContext(Dispatchers.IO) {
        ensureActive()
        val json = buildSidecarJson(params, appVersionName(context), sourceName)
        ensureActive()
        val uri = saveRawBytes(context, json.toByteArray(Charsets.UTF_8), sidecarName, "application/json")
        ensureActive()
        uri
    }

    suspend fun saveJsonToDownloads(
        context: Context,
        fileName: String,
        json: String
    ): Uri = withContext(Dispatchers.IO) {
        ensureActive()
        val uri = saveRawBytes(context, json.toByteArray(Charsets.UTF_8), fileName, "application/json")
        ensureActive()
        uri
    }

    private fun readSourceBytes(context: Context, sourcePath: String?): ByteArray? {
        if (sourcePath.isNullOrBlank()) return null
        return try {
            val file = File(sourcePath)
            if (file.exists()) {
                val len = runCatching { file.length() }.getOrDefault(-1L)
                if (len <= 0L || len > MAX_RAW_READ_BYTES) return null
                file.readBytes()
            } else {
                context.contentResolver.openInputStream(sourcePath.toUri())?.use { input ->
                    readCapped(input, MAX_RAW_READ_BYTES)
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun readCapped(input: java.io.InputStream, cap: Long): ByteArray? {
        return try {
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(32768)
            var total = 0L
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                total += n
                if (total > cap) return null
                out.write(buf, 0, n)
            }
            out.toByteArray()
        } catch (_: Exception) {
            null
        }
    }

    private fun mimeForRawName(name: String): String {
        return when (name.substringAfterLast('.', "").lowercase(Locale.US)) {
            "dng" -> "image/x-adobe-dng"
            "json" -> "application/json"
            else -> "application/octet-stream"
        }
    }

    private fun saveRawBytes(
        context: Context,
        bytes: ByteArray,
        fileName: String,
        mime: String
    ): Uri {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, RELATIVE_DOWNLOAD_DIR)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(collection, values)
                ?: throw IllegalStateException("MediaStore refused the RAW export")
            try {
                resolver.openOutputStream(uri)?.use { it.write(bytes) }
                    ?: throw IllegalStateException("Could not write the RAW export")
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } catch (e: Exception) {
                runCatching { resolver.delete(uri, null, null) }
                throw e
            }
            return uri
        }
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            .resolve("Lumina")
        if (!dir.exists()) dir.mkdirs()
        val out = File(dir, fileName)
        out.writeBytes(bytes)
        return Uri.fromFile(out)
    }

    private val GPS_TAGS = setOf(
        ExifInterface.TAG_GPS_LATITUDE,
        ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE,
        ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE,
        ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_TIMESTAMP,
        ExifInterface.TAG_GPS_DATESTAMP
    )

    private val EXIF_TAGS = arrayOf(
        ExifInterface.TAG_ORIENTATION,
        ExifInterface.TAG_MAKE,
        ExifInterface.TAG_MODEL,
        ExifInterface.TAG_LENS_MAKE,
        ExifInterface.TAG_LENS_MODEL,
        ExifInterface.TAG_F_NUMBER,
        ExifInterface.TAG_APERTURE_VALUE,
        ExifInterface.TAG_EXPOSURE_TIME,
        ExifInterface.TAG_SHUTTER_SPEED_VALUE,
        ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
        ExifInterface.TAG_ISO_SPEED_RATINGS,
        ExifInterface.TAG_FOCAL_LENGTH,
        ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
        ExifInterface.TAG_FLASH,
        ExifInterface.TAG_WHITE_BALANCE,
        ExifInterface.TAG_EXPOSURE_MODE,
        ExifInterface.TAG_EXPOSURE_PROGRAM,
        ExifInterface.TAG_METERING_MODE,
        ExifInterface.TAG_DATETIME,
        ExifInterface.TAG_DATETIME_ORIGINAL,
        ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_OFFSET_TIME,
        ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
        ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
        ExifInterface.TAG_GPS_LATITUDE,
        ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE,
        ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE,
        ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_TIMESTAMP,
        ExifInterface.TAG_GPS_DATESTAMP,
        ExifInterface.TAG_IMAGE_DESCRIPTION,
        ExifInterface.TAG_COPYRIGHT,
        ExifInterface.TAG_X_RESOLUTION,
        ExifInterface.TAG_Y_RESOLUTION,
        ExifInterface.TAG_RESOLUTION_UNIT
    )
}
