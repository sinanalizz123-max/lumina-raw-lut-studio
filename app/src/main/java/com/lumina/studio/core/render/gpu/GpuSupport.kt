package com.lumina.studio.core.render.gpu

import android.app.ActivityManager
import android.content.Context

/**
 * M15 GLES3 capability probe (§11). minSdk 26 exposes the GLES3 API, but the
 * device still has to REPORT it, so every GPU attempt is gated at runtime on
 * [isGles3]. Pure query; the cached value avoids repeated ActivityManager
 * lookups on the render path.
 */
object GpuSupport {
    @Volatile
    private var cached: Int? = null

    fun glesVersion(context: Context): Int {
        cached?.let { return it }
        val v = runCatching {
            val am = context.applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            am?.deviceConfigurationInfo?.reqGlEsVersion ?: 0
        }.getOrDefault(0)
        cached = v
        return v
    }

    fun isGles3(context: Context): Boolean = glesVersion(context) >= GpuRenderPolicy.GLES3_VERSION

    fun invalidateCache() {
        cached = null
    }
}
