package com.lumina.studio.core.ai

/**
 * M12 in-memory alpha-field registry (JVM-safe, no android.*).
 *
 * Mirrors the LutRegistry precedent: render code (PreviewRenderer, export)
 * resolves fields by key without IO or Context. Keyed by [EditMask.cacheKey];
 * disk is owned by [AiMaskCache] (files/<id>/metadata/), memory is primed
 * from disk on project open so reopen/export never recompute. Bounded LRU-ish
 * cap; removing a mask row does NOT evict (rows may share keys; cheap).
 */
object AiMaskFieldStore {
    const val MAX_FIELDS = 24

    data class Field(val mask: FloatArray, val width: Int, val height: Int)

    private val lock = Any()
    private val fields = LinkedHashMap<String, Field>()

    fun put(cacheKey: String, mask: FloatArray, width: Int, height: Int) {
        if (cacheKey.isBlank() || width <= 0 || height <= 0) return
        if (mask.size != width * height) return
        synchronized(lock) {
            if (fields.size >= MAX_FIELDS && !fields.containsKey(cacheKey)) {
                fields.remove(fields.keys.firstOrNull())
            }
            fields[cacheKey] = Field(mask.copyOf(), width, height)
        }
    }

    fun get(cacheKey: String?): Field? {
        if (cacheKey.isNullOrBlank()) return null
        synchronized(lock) {
            return fields[cacheKey]
        }
    }

    fun remove(cacheKey: String?) {
        if (cacheKey.isNullOrBlank()) return
        synchronized(lock) {
            fields.remove(cacheKey)
        }
    }

    fun clear() {
        synchronized(lock) {
            fields.clear()
        }
    }

    fun size(): Int {
        synchronized(lock) {
            return fields.size
        }
    }

    /** Cached field resampled to [dstW]x[dstH], or null when unresolvable. */
    fun resampledAlpha(cacheKey: String?, dstW: Int, dstH: Int): FloatArray? {
        val field = get(cacheKey) ?: return null
        if (dstW <= 0 || dstH <= 0) return null
        return AiMaskResample.resample(field.mask, field.width, field.height, dstW, dstH)
    }
}
