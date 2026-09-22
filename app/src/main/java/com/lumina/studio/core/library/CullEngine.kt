package com.lumina.studio.core.library

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.lumina.studio.core.ai.AiMaskCache
import com.lumina.studio.core.data.local.Project
import com.lumina.studio.core.data.store.ProjectStore
import java.io.File
import java.util.UUID

/**
 * M13 assisted-culling engine (Android glue; math lives in [CullScoring]).
 *
 * Scores are computed on the caller's thread — the ViewModel runs the
 * review loop on Dispatchers.Default — from a thumbnail-size decode
 * (longest side <= [ANALYSIS_MAX_DIM], no full-res read). Results are
 * cached in memory (small LRU) and in `files/<id>/metadata/cull.json`
 * (tiny JSON sidecar next to the AiMaskCache-style blobs, keyed by a
 * source hash so edits/reimports recompute). Scores only ever produce
 * badges; deletion stays a user-confirmed action in the UI.
 */
object CullEngine {
    const val ANALYSIS_MAX_DIM = 256
    const val SIDECAR_NAME = "cull.json"
    private const val MEM_CACHE_MAX = 200

    private val memCache = object : LinkedHashMap<String, CullScore>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CullScore>): Boolean =
            size > MEM_CACHE_MAX
    }

    data class ThumbFrame(val argb: IntArray, val width: Int, val height: Int)

    @Synchronized
    fun cached(projectId: String, sourceKey: String): CullScore? {
        val hit = memCache[projectId]
        return if (hit != null && hit.sourceKey == sourceKey) hit else null
    }

    @Synchronized
    fun invalidate(projectId: String) {
        memCache.remove(projectId)
    }

    @Synchronized
    private fun remember(score: CullScore) {
        memCache[score.projectId] = score
    }

    fun sourceKeyOf(file: File): String =
        AiMaskCache.sourceHash(file.absolutePath, file.length(), file.lastModified())

    fun decodeThumbnailArgb(file: File, maxDim: Int = ANALYSIS_MAX_DIM): ThumbFrame? {
        return try {
            if (!file.isFile) return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            val rawW = bounds.outWidth
            val rawH = bounds.outHeight
            if (rawW <= 0 || rawH <= 0) return null
            var sample = 1
            val longest = maxOf(rawW, rawH)
            while (longest / sample > maxDim) sample *= 2
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample.coerceAtLeast(1)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val bitmap = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return null
            try {
                val w = bitmap.width
                val h = bitmap.height
                if (w <= 0 || h <= 0) return null
                val pixels = IntArray(w * h)
                bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
                ThumbFrame(pixels, w, h)
            } finally {
                runCatching { bitmap.recycle() }
            }
        } catch (_: Exception) {
            null
        }
    }

    fun loadSidecar(appContext: Context, projectId: String): CullScore? {
        return try {
            val dir = ProjectStore.pathsFor(appContext, projectId).metadataDir
            val file = File(dir, SIDECAR_NAME)
            if (!file.isFile) return null
            CullScoreJson.decode(file.readText())
        } catch (_: Exception) {
            null
        }
    }

    fun saveSidecar(appContext: Context, projectId: String, score: CullScore): Boolean {
        return try {
            val dir = ProjectStore.pathsFor(appContext, projectId).metadataDir
            dir.mkdirs()
            val file = File(dir, SIDECAR_NAME)
            val tmp = File(dir, "$SIDECAR_NAME.part-" + UUID.randomUUID())
            tmp.writeText(CullScoreJson.encode(score))
            var ok = tmp.renameTo(file)
            if (!ok) {
                tmp.inputStream().use { input ->
                    file.outputStream().use { output -> input.copyTo(output) }
                }
                tmp.delete()
                ok = file.isFile && file.length() > 0L
            }
            ok
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Score one project (blocking thumbnail decode — call on
     * Dispatchers.Default). Null when the source is unreadable; the caller
     * skips that row instead of flagging it.
     */
    fun analyzeProject(appContext: Context, project: Project): CullScore? {
        val path = project.photoUri ?: return null
        val file = File(path)
        if (!file.isFile) return null
        return try {
            val key = sourceKeyOf(file)
            cached(project.id, key)?.let { return it }
            loadSidecar(appContext, project.id)?.takeIf { it.sourceKey == key }?.let {
                remember(it)
                return it
            }
            val frame = decodeThumbnailArgb(file) ?: return null
            val hash = CullSignals.averageHash(frame.argb, frame.width, frame.height)
                ?: return null
            val gray = CullSignals.grayField(frame.argb, frame.width, frame.height)
                ?: return null
            val blur = CullSignals.laplacianVariance(gray, frame.width, frame.height)
                ?: return null
            val clip = CullSignals.clipping(frame.argb)
            val score = CullScore(
                projectId = project.id,
                sourceKey = key,
                hash = hash,
                blurVariance = blur,
                highlightClip = clip.highlight,
                shadowClip = clip.shadow,
                flags = CullScoring.flagsFor(blur, clip)
            )
            remember(score)
            runCatching { saveSidecar(appContext, project.id, score) }
            score
        } catch (_: Exception) {
            null
        }
    }
}
