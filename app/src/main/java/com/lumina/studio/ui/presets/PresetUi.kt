package com.lumina.studio.ui.presets

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.lumina.studio.core.data.local.Preset
import com.lumina.studio.core.edit.EditParams
import com.lumina.studio.core.lut.BuiltInPresets
import com.lumina.studio.core.lut.CubeParseResult
import com.lumina.studio.core.lut.CubeParser
import com.lumina.studio.core.lut.LutCube
import com.lumina.studio.core.lut.LutLimits
import com.lumina.studio.core.lut.LutRegistry
import com.lumina.studio.core.lut.SampleImage
import com.lumina.studio.core.render.RenderRequest
import com.lumina.studio.core.render.RenderResult
import com.lumina.studio.core.render.RenderTarget
import com.lumina.studio.core.render.cpu.RenderBackends
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun resolvePresetLut(preset: Preset): LutCube? = resolvePresetLut(preset, null)

fun resolvePresetLut(preset: Preset, lutsDir: java.io.File?): LutCube? {
    LutRegistry.resolve(preset.id)?.let { return it }
    BuiltInPresets.byId(preset.id)?.let {
        LutRegistry.register(it.id, it.lut)
        return it.lut
    }
    val cubeText = preset.cubeText
    if (LutLimits.isFileRef(cubeText)) {
        val fileName = LutLimits.fileNameFromRef(cubeText) ?: return LutRegistry.resolve(preset.id)
        val dir = lutsDir ?: return LutRegistry.resolve(preset.id)
        val text = LutLimits.readBoundedFile(java.io.File(dir, fileName)) ?: return null
        return when (val result = CubeParser.parse(text, preset.name)) {
            is CubeParseResult.Ok -> {
                LutRegistry.register(preset.id, result.lut)
                result.lut
            }
            is CubeParseResult.Err -> null
        }
    }
    return LutRegistry.registerParsed(preset.id, cubeText)
}

fun downsampleMax(src: Bitmap, maxDim: Int): Bitmap {
    val longest = maxOf(src.width, src.height)
    if (longest <= maxDim) return src
    val scale = maxDim / longest.toFloat()
    return Bitmap.createScaledBitmap(
        src,
        (src.width * scale + 0.5f).toInt().coerceAtLeast(1),
        (src.height * scale + 0.5f).toInt().coerceAtLeast(1),
        true
    )
}

fun decodeSampledFile(path: String, maxDim: Int): Bitmap? {
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxDim) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        BitmapFactory.decodeFile(path, opts)
    } catch (_: Exception) {
        null
    }
}

@Composable
fun PresetThumb(
    preset: Preset,
    source: Bitmap?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop
) {
    val fallback = remember { SampleImage.placeholder() }
    val thumb = produceState<Bitmap?>(
        initialValue = null,
        key1 = preset.id,
        key2 = preset.cubeText?.hashCode() ?: 0,
        key3 = if (source != null) source.generationId else -1
    ) {
        value = withContext(Dispatchers.Default) {
            try {
                val lut = resolvePresetLut(preset) ?: return@withContext source ?: fallback
                val base = if (source != null) downsampleMax(source, 256) else fallback
                // LUT-only grade through the CPU backend: the params carry just
                // the preset identity at full intensity, so the backend applies
                // LutRenderer.applyLut and skips every other stage — pixel parity
                // with the previous direct applyLut call.
                val params = EditParams.DEFAULT.copy(
                    presetId = preset.id,
                    presetIntensity = 1f
                )
                when (
                    val result = RenderBackends.cpu().render(
                        RenderRequest(params, lut, base, RenderTarget.Thumb, 0L)
                    )
                ) {
                    is RenderResult.Ok -> result.bitmap
                    is RenderResult.Unavailable -> base
                    RenderResult.OomBudget -> base
                }
            } catch (_: Exception) {
                source ?: fallback
            }
        }
    }.value
    val bitmap = thumb ?: source ?: fallback
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = preset.name,
        modifier = modifier,
        contentScale = contentScale
    )
}
