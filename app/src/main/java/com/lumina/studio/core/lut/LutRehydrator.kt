package com.lumina.studio.core.lut

import com.lumina.studio.core.data.local.Preset
import java.io.File

object LutRehydrator {
    data class Report(val registered: Int, val failed: Int)

    fun rehydrate(presets: List<Preset>, lutsDir: File?): Report {
        var registered = 0
        var failed = 0
        for (preset in presets) {
            val cubeText = preset.cubeText
            if (cubeText.isNullOrBlank()) {
                BuiltInPresets.byId(preset.id)?.let {
                    LutRegistry.register(it.id, it.lut)
                    registered++
                }
                continue
            }
            if (LutLimits.isFileRef(cubeText)) {
                val fileName = LutLimits.fileNameFromRef(cubeText)
                val file = if (fileName != null && lutsDir != null) File(lutsDir, fileName) else null
                val text = file?.let { LutLimits.readBoundedFile(it) }
                if (text.isNullOrBlank()) {
                    failed++
                    continue
                }
                when (val result = CubeParser.parse(text, preset.name)) {
                    is CubeParseResult.Ok -> {
                        LutRegistry.register(preset.id, result.lut)
                        registered++
                    }
                    is CubeParseResult.Err -> failed++
                }
            } else {
                val lut = LutRegistry.registerParsed(preset.id, cubeText)
                if (lut != null) registered++ else failed++
            }
        }
        return Report(registered, failed)
    }
}
