package com.lumina.studio.core.render

interface ImageDecoder<B : Any> {
    val name: String
    fun bounds(source: RenderSource): Dims?
    fun decode(source: RenderSource, maxDim: Int): B?
    fun region(source: RenderSource, rectPx: PixelRect, sample: Int): B?
}
