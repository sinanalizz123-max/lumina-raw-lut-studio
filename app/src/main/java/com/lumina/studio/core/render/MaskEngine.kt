package com.lumina.studio.core.render

import com.lumina.studio.core.edit.EditMask

interface MaskEngine<B : Any> {
    fun composite(base: B, masks: List<EditMask>): B
}
