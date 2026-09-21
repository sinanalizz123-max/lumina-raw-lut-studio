package com.lumina.studio.core.render

import com.lumina.studio.core.edit.EditParams
import com.lumina.studio.core.lut.LutCube

data class RenderRequest<B : Any>(
    val params: EditParams,
    val lut: LutCube?,
    val source: B,
    val target: RenderTarget,
    val generation: Long
)

sealed interface RenderResult<out B : Any> {
    data class Ok<B : Any>(val bitmap: B) : RenderResult<B>
    data class Unavailable(val reason: String) : RenderResult<Nothing>
    data object OomBudget : RenderResult<Nothing>
}

interface RenderBackend<B : Any> {
    fun render(request: RenderRequest<B>): RenderResult<B>
    fun cancel(generation: Long)
}
