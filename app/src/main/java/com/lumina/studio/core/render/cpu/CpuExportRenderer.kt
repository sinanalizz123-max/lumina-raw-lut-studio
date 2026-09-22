package com.lumina.studio.core.render.cpu

import android.graphics.Bitmap
import com.lumina.studio.core.edit.EditParams
import com.lumina.studio.core.lut.LutCube
import com.lumina.studio.core.render.ExportRenderer
import com.lumina.studio.core.render.RenderRequest
import com.lumina.studio.core.render.RenderResult
import com.lumina.studio.core.render.RenderTarget

object CpuExportRenderer : ExportRenderer<Bitmap> {

    override fun renderForExport(
        src: Bitmap,
        params: EditParams,
        lut: LutCube?,
        targetW: Int,
        targetH: Int
    ): Bitmap {
        val graded = when (
            val result = RenderBackends.cpu().render(
                RenderRequest(
                    params = params,
                    lut = lut,
                    source = src,
                    target = RenderTarget.Export(targetW, targetH),
                    generation = CpuRenderBackend.NO_GENERATION,
                    // M10 parity: exports render the FULL LUT table while
                    // previews keep the downsampled effective table.
                    fullLut = true
                )
            )
        ) {
            is RenderResult.Ok -> result.bitmap
            is RenderResult.Unavailable -> src
            RenderResult.OomBudget -> src
        }
        return fitToTarget(graded, src, targetW, targetH)
    }

    // M15: shared fit-scale used by both the CPU export render above and the
    // optional GPU-first path in Exporter.renderForExport (same code, same
    // behavior for either backend's graded bitmap).
    fun fitToTarget(rendered: Bitmap, src: Bitmap, targetW: Int, targetH: Int): Bitmap {
        if (targetW <= 0 || targetH <= 0) return rendered
        if (rendered.width == targetW && rendered.height == targetH) return rendered
        val rw = rendered.width
        val rh = rendered.height
        if (rw <= 0 || rh <= 0) return rendered
        // Crop-aware: target dims describe the pre-crop source, so a blind
        // scale to targetW x targetH would stretch a cropped/rotated render
        // back to the source frame and silently undo the crop. Fit instead:
        // keep the rendered bitmap when it already fits (allowing swapped
        // orientation for 90-degree rotations), else downscale preserving aspect.
        if ((rw <= targetW && rh <= targetH) || (rw <= targetH && rh <= targetW)) return rendered
        val scale = minOf(targetW.toFloat() / rw, targetH.toFloat() / rh).coerceIn(0f, 1f)
        if (scale <= 0f || !scale.isFinite()) return rendered
        val outW = (rw * scale + 0.5f).toInt().coerceIn(1, rw)
        val outH = (rh * scale + 0.5f).toInt().coerceIn(1, rh)
        if (outW == rw && outH == rh) return rendered
        val scaled = Bitmap.createScaledBitmap(rendered, outW, outH, true)
        if (scaled !== rendered && rendered !== src) rendered.recycle()
        return scaled
    }
}
