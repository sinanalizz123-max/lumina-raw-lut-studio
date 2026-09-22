package com.lumina.studio.core.lut

import android.graphics.Bitmap

enum class PresetCategory(val label: String) {
    CINEMATIC("Cinematic"),
    FILM("Film"),
    PORTRAIT("Portrait"),
    MOODY("Moody"),
    TRAVEL("Travel"),
    NATURE("Nature"),
    BW("BW"),
    VINTAGE("Vintage");

    companion object {
        fun fromLabel(label: String?): PresetCategory =
            entries.firstOrNull { it.label.equals(label, ignoreCase = true) } ?: FILM
    }
}

data class DemoPreset(
    val id: String,
    val name: String,
    val category: PresetCategory,
    val defaultIntensity: Float = 1f,
    val lut: LutCube
)

object BuiltInPresets {
    const val LUT_SIZE = 17

    val list: List<DemoPreset> by lazy {
        listOf(
            DemoPreset("builtin_golden_hour", "Golden Hour", PresetCategory.CINEMATIC, 1f, build(::goldenHour)),
            DemoPreset("builtin_soft_fade", "Soft Fade", PresetCategory.FILM, 0.85f, build(::softFade)),
            DemoPreset("builtin_porcelain", "Porcelain", PresetCategory.PORTRAIT, 1f, build(::porcelain)),
            DemoPreset("builtin_noir", "Noir", PresetCategory.MOODY, 1f, build(::noir)),
            DemoPreset("builtin_teal_voyage", "Teal Voyage", PresetCategory.TRAVEL, 1f, build(::tealVoyage)),
            DemoPreset("builtin_vivid_pop", "Vivid Pop", PresetCategory.NATURE, 0.9f, build(::vividPop)),
            DemoPreset("builtin_mono", "Mono", PresetCategory.BW, 1f, build(::mono)),
            DemoPreset("builtin_sepia", "Sepia", PresetCategory.VINTAGE, 1f, build(::sepia))
        )
    }

    fun byId(id: String?): DemoPreset? = list.firstOrNull { it.id == id }

    private fun build(fn: (Float, Float, Float) -> Triple<Float, Float, Float>): LutCube {
        val size = LUT_SIZE
        val data = FloatArray(size * size * size * 3)
        var k = 0
        for (b in 0 until size) {
            for (g in 0 until size) {
                for (r in 0 until size) {
                    val out = fn(
                        r / (size - 1f),
                        g / (size - 1f),
                        b / (size - 1f)
                    )
                    data[k++] = out.first.coerceIn(0f, 1f)
                    data[k++] = out.second.coerceIn(0f, 1f)
                    data[k++] = out.third.coerceIn(0f, 1f)
                }
            }
        }
        return LutCube(title = null, size = size, is3D = true, data = data)
    }

    private fun luma(r: Float, g: Float, b: Float): Float = 0.2126f * r + 0.7152f * g + 0.0722f * b

    private fun goldenHour(r: Float, g: Float, b: Float): Triple<Float, Float, Float> {
        val l = luma(r, g, b)
        val c = { x: Float -> x + (x - l) * 0.18f }
        return Triple(c(r * 1.07f) + 0.015f, c(g * 1.0f), c(b * 0.90f) - 0.01f)
    }

    private fun tealVoyage(r: Float, g: Float, b: Float): Triple<Float, Float, Float> {
        val l = luma(r, g, b)
        val shadow = 1f - l
        return Triple(
            r * 1.05f - shadow * 0.06f,
            g * 1.01f + shadow * 0.03f,
            b * 1.08f + shadow * 0.05f
        )
    }

    private fun mono(r: Float, g: Float, b: Float): Triple<Float, Float, Float> {
        val l = luma(r, g, b)
        val v = l + (l - 0.5f) * 0.12f
        return Triple(v, v, v)
    }

    private fun softFade(r: Float, g: Float, b: Float): Triple<Float, Float, Float> {
        val l = luma(r, g, b)
        val mix = { x: Float -> x + (l - x) * 0.25f }
        return Triple(mix(r) * 0.86f + 0.075f, mix(g) * 0.86f + 0.075f, mix(b) * 0.86f + 0.075f)
    }

    private fun vividPop(r: Float, g: Float, b: Float): Triple<Float, Float, Float> {
        val l = luma(r, g, b)
        val sat = { x: Float -> l + (x - l) * 1.35f }
        val con = { x: Float -> x + (x - 0.5f) * 0.10f }
        return Triple(con(sat(r)), con(sat(g)), con(sat(b)))
    }

    private fun noir(r: Float, g: Float, b: Float): Triple<Float, Float, Float> {
        val l = luma(r, g, b)
        val v = 0.5f + (l - 0.5f) * 1.6f - 0.02f
        return Triple(v, v, v)
    }

    private fun sepia(r: Float, g: Float, b: Float): Triple<Float, Float, Float> {
        val l = luma(r, g, b)
        return Triple(l * 1.08f + 0.04f, l * 0.92f + 0.02f, l * 0.78f)
    }

    private fun porcelain(r: Float, g: Float, b: Float): Triple<Float, Float, Float> {
        val l = luma(r, g, b)
        val mids = 1f - kotlin.math.abs(l - 0.55f) * 1.6f
        val warmth = (mids.coerceIn(0f, 1f)) * 0.05f
        return Triple(r * 1.03f + 0.012f + warmth, g * 1.0f + 0.008f, b * 0.96f - warmth * 0.5f)
    }
}

object LutRegistry {
    const val MAX_ENTRIES = LutLimits.REGISTRY_MAX_ENTRIES

    private val lock = Any()
    private val cache = object : LinkedHashMap<String, LutCube>(
        LutLimits.REGISTRY_MAX_ENTRIES, 0.75f, true
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, LutCube>?): Boolean {
            if (size <= MAX_ENTRIES) return false
            val key = eldest?.key
            if (key != null && isBuiltinId(key)) return false
            return true
        }
    }

    init {
        for (preset in BuiltInPresets.list) cache[preset.id] = preset.lut
    }

    private fun isBuiltinId(id: String): Boolean = id.startsWith("builtin_")

    fun resolve(id: String?): LutCube? {
        if (id.isNullOrBlank()) return null
        synchronized(lock) { cache[id]?.let { return it } }
        val builtin = BuiltInPresets.byId(id)?.lut
        if (builtin != null) {
            register(id, builtin)
            return builtin
        }
        return null
    }

    fun register(id: String, lut: LutCube) {
        if (id.isBlank()) return
        synchronized(lock) {
            cache[id] = lut
            evictOverflow()
        }
    }

    fun registerParsed(id: String, cubeText: String?): LutCube? {
        if (id.isBlank() || cubeText.isNullOrBlank()) {
            synchronized(lock) { return cache[id] }
        }
        if (LutLimits.isFileRef(cubeText)) {
            synchronized(lock) { return cache[id] }
        }
        synchronized(lock) { cache[id]?.let { return it } }
        return when (val result = CubeParser.parse(cubeText)) {
            is CubeParseResult.Ok -> {
                register(id, result.lut)
                result.lut
            }
            is CubeParseResult.Err -> synchronized(lock) { cache[id] }
        }
    }

    fun unregister(id: String) {
        if (isBuiltinId(id)) return
        synchronized(lock) { cache.remove(id) }
    }

    fun entryCount(): Int = synchronized(lock) { cache.size }

    fun estimatedBytes(): Long = synchronized(lock) {
        cache.values.fold(0L) { acc, lut -> acc + lut.data.size.toLong() * 4L }
    }

    fun clearTransient() {
        synchronized(lock) {
            val transient = cache.keys.filterNot { isBuiltinId(it) }
            for (key in transient) cache.remove(key)
        }
    }

    private fun evictOverflow() {
        if (cache.size <= MAX_ENTRIES) return
        val eldest = cache.keys.firstOrNull { !isBuiltinId(it) } ?: return
        cache.remove(eldest)
        if (cache.size > MAX_ENTRIES) evictOverflow()
    }
}

object SampleImage {
    fun placeholder(width: Int = 320, height: Int = 200): Bitmap {
        val w = width.coerceAtLeast(8)
        val h = height.coerceAtLeast(8)
        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val u = x / (w - 1f)
                val v = y / (h - 1f)
                val shade = 1f - v * 0.25f
                val r: Float
                val g: Float
                val b: Float
                when {
                    v > 0.78f -> {
                        val ramp = u
                        r = ramp; g = ramp; b = ramp
                    }
                    u < 0.34f -> {
                        r = (0.25f + u * 2.2f) * shade
                        g = (0.35f + v * 0.4f) * shade
                        b = (0.55f + (1f - u) * 0.3f) * shade
                    }
                    u < 0.67f -> {
                        r = (0.85f - v * 0.3f) * shade
                        g = (0.62f - v * 0.2f) * shade
                        b = (0.48f - v * 0.15f) * shade
                    }
                    else -> {
                        r = (0.3f + v * 0.35f) * shade
                        g = (0.55f + u * 0.2f) * shade
                        b = (0.3f + v * 0.2f) * shade
                    }
                }
                pixels[y * w + x] = (0xFF shl 24) or
                    (r.coerceIn(0f, 1f) * 255f + 0.5f).toInt().coerceIn(0, 255).shl(16) or
                    (g.coerceIn(0f, 1f) * 255f + 0.5f).toInt().coerceIn(0, 255).shl(8) or
                    (b.coerceIn(0f, 1f) * 255f + 0.5f).toInt().coerceIn(0, 255)
            }
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }
}
