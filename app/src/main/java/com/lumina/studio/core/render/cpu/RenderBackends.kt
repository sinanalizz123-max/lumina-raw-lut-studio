package com.lumina.studio.core.render.cpu

import android.content.Context
import android.graphics.Bitmap
import com.lumina.studio.core.render.ColorManager
import com.lumina.studio.core.render.ExportRenderer
import com.lumina.studio.core.render.ImageDecoder
import com.lumina.studio.core.render.MaskEngine
import com.lumina.studio.core.render.RawDecoder
import com.lumina.studio.core.render.RenderBackend

object RenderBackends {
    @Volatile
    private var cpuInstance: CpuRenderBackend? = null

    @Volatile
    private var decoderInstance: BitmapFactoryDecoder? = null

    @Volatile
    private var decoderApp: Context? = null

    @Volatile
    private var rawInstance: DngPreviewDecoder? = null

    @Volatile
    private var rawApp: Context? = null

    fun cpu(): RenderBackend<Bitmap> {
        var current = cpuInstance
        if (current == null) {
            synchronized(this) {
                current = cpuInstance
                if (current == null) {
                    current = CpuRenderBackend()
                    cpuInstance = current
                }
            }
        }
        return current!!
    }

    fun decoder(context: Context): ImageDecoder<Bitmap> {
        val app = context.applicationContext
        var current = decoderInstance
        if (current == null || decoderApp !== app) {
            synchronized(this) {
                current = decoderInstance
                if (current == null || decoderApp !== app) {
                    current = BitmapFactoryDecoder(app)
                    decoderInstance = current
                    decoderApp = app
                }
            }
        }
        return current!!
    }

    fun export(): ExportRenderer<Bitmap> = CpuExportRenderer

    fun masks(): MaskEngine<Bitmap> = CpuMaskEngine

    fun colors(): ColorManager<Bitmap> = CpuColorManager

    fun raw(context: Context): RawDecoder<Bitmap> {
        val app = context.applicationContext
        var current = rawInstance
        if (current == null || rawApp !== app) {
            synchronized(this) {
                current = rawInstance
                if (current == null || rawApp !== app) {
                    current = DngPreviewDecoder(app)
                    rawInstance = current
                    rawApp = app
                }
            }
        }
        return current!!
    }
}
