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
import com.lumina.studio.core.lut.BuiltInPresets
import com.lumina.studio.core.lut.LutCube
import com.lumina.studio.core.lut.LutRegistry
import com.lumina.studio.core.lut.LutRenderer
import com.lumina.studio.core.lut.SampleImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun resolvePresetLut(preset: Preset): LutCube? {
    LutRegistry.resolve(preset.id)?.let { return it }
    BuiltInPresets.byId(preset.id)?.let {
        LutRegistry.register(it.id, it.lut)
        return it.lut
    }
    return LutRegistry.registerParsed(preset.id, preset.cubeText)
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
                LutRenderer.applyLut(base, lut, 1f)
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
