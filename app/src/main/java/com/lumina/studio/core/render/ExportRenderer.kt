package com.lumina.studio.core.render

import com.lumina.studio.core.edit.EditParams
import com.lumina.studio.core.lut.LutCube

interface ExportRenderer<B : Any> {
    fun renderForExport(src: B, params: EditParams, lut: LutCube?, targetW: Int, targetH: Int): B
}
