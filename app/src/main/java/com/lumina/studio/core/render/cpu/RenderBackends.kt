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
    private var gpuInstance: com.lumina.studio.core.render.gpu.GlesBackend? = null

    @Volatile
    private var decoderInstance: BitmapFactoryDecoder? = null

    @Volatile
    private var decoderApp: Context? = null

    @Volatile
    private var rawInstance: DngDevelopDecoder? = null

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

    /**
     * M15 real-GPU backend singleton (§11). Lazy; EGL/GL resources inside are
     * created on first GPU render, reused across renders, and torn down by
     * [releaseGpu] (ViewModel.onCleared + low-memory hook). The backend falls
     * back to [cpu] internally on any GLES failure, so callers never branch.
     */
    fun gpu(): RenderBackend<Bitmap> {
        var current = gpuInstance
        if (current == null) {
            synchronized(this) {
                current = gpuInstance
                if (current == null) {
                    current = com.lumina.studio.core.render.gpu.GlesBackend()
                    gpuInstance = current
                }
            }
        }
        return current!!
    }

    fun releaseGpu() {
        runCatching { gpuInstance?.release() }
    }

    fun attachGpuMemoryHook(context: Context) {
        runCatching {
            val app = context.applicationContext
            var current = gpuInstance
            if (current == null) {
                synchronized(this) {
                    current = gpuInstance
                    if (current == null) {
                        current = com.lumina.studio.core.render.gpu.GlesBackend()
                        gpuInstance = current
                    }
                }
            }
            current?.attachLowMemoryHook(app)
        }
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
                    current = DngDevelopDecoder(app)
                    rawInstance = current
                    rawApp = app
                }
            }
        }
        return current!!
    }
}
