package com.lumina.studio.core.render.cpu

import android.graphics.Bitmap
import com.lumina.studio.core.edit.EditMask
import com.lumina.studio.core.edit.EditParams
import com.lumina.studio.core.render.MaskEngine
import com.lumina.studio.core.render.PreviewRenderer

object CpuMaskEngine : MaskEngine<Bitmap> {

    // M7: op/range/blur compositing lives in PreviewRenderer.applyMasks
    // (accumulated-alpha ops in list order); this engine stays a thin
    // delegate so preview and export share one path.
    override fun composite(base: Bitmap, masks: List<EditMask>): Bitmap {
        if (masks.isEmpty()) return base
        return PreviewRenderer.applyMasks(base, EditParams.DEFAULT.copy(masks = masks))
    }
}
