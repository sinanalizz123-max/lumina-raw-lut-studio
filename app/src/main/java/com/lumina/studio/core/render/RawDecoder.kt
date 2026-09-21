package com.lumina.studio.core.render

data class RawRecipe(
    val exposureEv: Float = 0f,
    val tempGain: Float = 1f,
    val tintGain: Float = 1f
)

data class RawCapability(
    val format: String,
    val editable: Boolean,
    val note: String
)

object RawCapabilities {
    const val DNG_PREVIEW_NOTE =
        "Embedded preview only — sensor data preserved untouched; full demosaic lands in M9"
    const val PROPRIETARY_NOTE =
        "Embedded preview only — proprietary RAW is not fully decoded"

    val TABLE: List<RawCapability> = listOf(
        RawCapability("dng", editable = false, note = DNG_PREVIEW_NOTE),
        RawCapability("cr2", editable = false, note = PROPRIETARY_NOTE),
        RawCapability("cr3", editable = false, note = PROPRIETARY_NOTE),
        RawCapability("nef", editable = false, note = PROPRIETARY_NOTE),
        RawCapability("nrw", editable = false, note = PROPRIETARY_NOTE),
        RawCapability("arw", editable = false, note = PROPRIETARY_NOTE),
        RawCapability("raf", editable = false, note = PROPRIETARY_NOTE),
        RawCapability("rw2", editable = false, note = PROPRIETARY_NOTE),
        RawCapability("orf", editable = false, note = PROPRIETARY_NOTE),
        RawCapability("pef", editable = false, note = PROPRIETARY_NOTE),
        RawCapability("srw", editable = false, note = PROPRIETARY_NOTE)
    )

    val PROPRIETARY_FORMATS: Set<String> =
        TABLE.map { it.format }.filter { it != "dng" }.toSet()

    fun capabilityOf(extension: String): RawCapability? {
        val ext = extension.trim().lowercase(java.util.Locale.US)
        if (ext.isEmpty()) return null
        return TABLE.firstOrNull { it.format == ext }
    }
}

interface RawDecoder<B : Any> {
    fun capabilities(): List<RawCapability>
    fun develop(source: RenderSource, maxDim: Int, recipe: RawRecipe? = null): B?
}
