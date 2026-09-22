package com.lumina.studio.core.render.cpu

import android.graphics.Bitmap
import com.lumina.studio.core.render.MemoryBudget
import com.lumina.studio.core.render.PreviewRenderer
import com.lumina.studio.core.render.RenderBackend
import com.lumina.studio.core.render.RenderRequest
import com.lumina.studio.core.render.RenderResult
import com.lumina.studio.core.render.qualityForTarget
import java.util.Collections

class CpuRenderBackend : RenderBackend<Bitmap> {

    private val cancelled: MutableSet<Long> =
        Collections.synchronizedSet(LinkedHashSet())

    override fun render(request: RenderRequest<Bitmap>): RenderResult<Bitmap> {
        if (request.generation != NO_GENERATION && cancelled.remove(request.generation)) {
            return RenderResult.Unavailable("cancelled")
        }
        val src = request.source
        if (runCatching { src.isRecycled }.getOrDefault(true)) {
            return RenderResult.Unavailable("source recycled")
        }
        val w = runCatching { src.width }.getOrDefault(0)
        val h = runCatching { src.height }.getOrDefault(0)
        if (w <= 0 || h <= 0) return RenderResult.Unavailable("empty source")
        if (MemoryBudget.exceeds(w, h)) return RenderResult.Unavailable("too large")
        return try {
            // M5: explicit request quality wins; otherwise the target decides
            // (Preview/Thumb -> PREVIEW sRGB-math/8888, Fullscreen/Tile/Export
            // -> FINAL sRGB-math/F16 intermediates). Export therefore always
            // takes the high-precision path via CpuExportRenderer.
            val quality = request.quality ?: qualityForTarget(request.target)
            RenderResult.Ok(
                PreviewRenderer.render(src, request.params, request.lut, quality, request.fullLut)
            )
        } catch (_: OutOfMemoryError) {
            RenderResult.OomBudget
        } catch (e: Exception) {
            RenderResult.Unavailable(e.message ?: "render failed")
        }
    }

    override fun cancel(generation: Long) {
        if (generation == NO_GENERATION) return
        if (cancelled.size > MAX_TRACKED_CANCELLATIONS) cancelled.clear()
        cancelled.add(generation)
    }

    companion object {
        const val NO_GENERATION = 0L
        private const val MAX_TRACKED_CANCELLATIONS = 512
    }
}
