package com.lumina.studio.ui.screens

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.lumina.studio.core.design.components.EmptyState
import com.lumina.studio.core.design.components.EmptyStateIllustration
import com.lumina.studio.core.design.components.ErrorState
import com.lumina.studio.core.design.components.LoadingShimmer
import com.lumina.studio.core.design.components.LoadingShimmerStyle
import com.lumina.studio.core.design.components.ProSlider
import com.lumina.studio.core.design.theme.LuminaAmber
import com.lumina.studio.core.design.theme.LuminaBackground
import com.lumina.studio.core.design.theme.LuminaCaptionTextStyle
import com.lumina.studio.core.design.theme.LuminaError
import com.lumina.studio.core.design.theme.LuminaMuted
import com.lumina.studio.core.design.theme.LuminaOnSurface
import com.lumina.studio.core.design.theme.LuminaSectionHeaderTextStyle
import com.lumina.studio.core.design.theme.LuminaSurfaceContainerLow
import com.lumina.studio.core.data.datastore.SettingsRepository
import com.lumina.studio.core.data.local.DatabaseProvider
import com.lumina.studio.core.data.local.EditHistoryLog
import com.lumina.studio.core.export.ExportColorSpace
import com.lumina.studio.core.export.ExportFormat
import com.lumina.studio.core.export.ExportSettings
import com.lumina.studio.core.export.Exporter
import com.lumina.studio.core.export.QualityPreset
import com.lumina.studio.core.export.ResolutionMode
import com.lumina.studio.core.export.Sidecar
import com.lumina.studio.core.util.ExifReader
import com.lumina.studio.core.lut.LutLimits
import com.lumina.studio.core.lut.LutRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportScreen(navController: NavController, projectId: String? = null) {
    val context = LocalContext.current
    val app = context.applicationContext as Application
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    if (projectId.isNullOrBlank()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Export") },
                    navigationIcon = {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp)
            ) {
                EmptyState(
                    title = "No photo selected",
                    message = "Open a photo in the editor, then tap Export.",
                    actionLabel = "Back",
                    onAction = { navController.popBackStack() },
                    illustration = EmptyStateIllustration.Photo
                )
            }
        }
        return
    }

    val vm: EditorViewModel = viewModel(
        key = "export_$projectId",
        factory = EditorViewModelFactory(app, projectId)
    )
    val project by vm.project.collectAsState()
    val params by vm.params.collectAsState()
    val preview by vm.preview.collectAsState()
    val loading by vm.loading.collectAsState()

    var settings by remember { mutableStateOf(ExportSettings()) }
    var estimatedBytes by remember { mutableLongStateOf(0L) }
    var exporting by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var savedUri by remember { mutableStateOf<Uri?>(null) }
    var savedName by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var exportJob by remember { mutableStateOf<Job?>(null) }
    var rawBusy by remember { mutableStateOf(false) }
    var rawUri by remember { mutableStateOf<Uri?>(null) }
    var sidecarUri by remember { mutableStateOf<Uri?>(null) }
    var rawError by remember { mutableStateOf<String?>(null) }
    var sidecarNotice by remember { mutableStateOf<String?>(null) }
    // M11 (§39): RAW/sidecar jobs are cancellable (previously fire-and-forget).
    var rawJob by remember { mutableStateOf<Job?>(null) }
    var sidecarJob by remember { mutableStateOf<Job?>(null) }
    // M11 (§38 formats): encoder-gated availability drives the format rows —
    // HEIC/WebP rows appear only when the device can encode them (no dead UI).
    val webpAvailable = remember { Exporter.hasWebpEncoder() }
    val heicAvailable = remember { Exporter.hasHeicEncoder() }
    val availableFormats = remember(webpAvailable, heicAvailable) {
        Exporter.availableFormats(webpAvailable, heicAvailable)
    }
    // M11 (§36 metadata): source EXIF summary for the export card — focal
    // length display plus GPS presence (ExifReader already parses both).
    val sourceExif = remember(project) {
        project?.photoUri?.let { java.io.File(it) }?.takeIf { it.exists() }?.let {
            runCatching { ExifReader.read(it) }.getOrNull()
        }
    }

    val sidecarPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        sidecarNotice = null
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                try {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        LutLimits.readBounded(input)?.toString(Charsets.UTF_8)
                    }
                } catch (_: Exception) {
                    null
                }
            }
            if (text.isNullOrBlank()) {
                sidecarNotice = "Could not read that sidecar file."
                return@launch
            }
            val parsed = Sidecar.parse(text)
            if (parsed == null) {
                sidecarNotice = "That file is not a Lumina sidecar (.lumina.json)."
                return@launch
            }
            var final = parsed.params
            val warnings = parsed.warnings.toMutableList()
            val lutId = parsed.params.presetId
            if (lutId != null && LutRegistry.resolve(lutId) == null) {
                final = Sidecar.withoutLut(parsed.params)
                warnings.add(Sidecar.missingLutWarning(lutId))
            }
            vm.applyExternalParams(final)
            warnings.add("Recipe applied — undo from the editor if needed.")
            sidecarNotice = warnings.joinToString("\n")
        }
    }

    val wideGamut = remember { Exporter.isWideGamutDisplay(context) }
    val settingsRepository = remember { SettingsRepository(context) }

    LaunchedEffect(Unit) {
        try {
            val stored = settingsRepository.exportColorSpace.first()
            val coerced = ExportColorSpace.coerceForDisplay(
                ExportColorSpace.fromKey(stored),
                Exporter.isWideGamutDisplay(context)
            )
            val includeLocation = try {
                settingsRepository.exportIncludeLocation.first()
            } catch (_: Exception) {
                false
            }
            val preserveExif = try {
                settingsRepository.exportIncludeMetadata.first()
            } catch (_: Exception) {
                true
            }
            settings = settings.copy(colorSpace = coerced, includeLocation = includeLocation, preserveExif = preserveExif)
        } catch (_: Exception) {
        }
    }

    val fullW = project?.width?.takeIf { it > 0 }
        ?: preview?.width ?: 0
    val fullH = project?.height?.takeIf { it > 0 }
        ?: preview?.height ?: 0
    // M11: coerce a persisted/unavailable format back to JPEG (no dead UI).
    LaunchedEffect(availableFormats) {
        if (settings.format !in availableFormats) {
            settings = settings.copy(format = ExportFormat.JPEG)
        }
    }
    val (targetW, targetH) = Exporter.targetDimensions(fullW, fullH, settings)
    val isRaw = remember(project) { Exporter.isRawSource(project) }

    LaunchedEffect(preview, settings, fullW, fullH) {
        if (settings.format == ExportFormat.TIFF) {
            estimatedBytes = Exporter.estimateTiffBytes(targetW, targetH)
            return@LaunchedEffect
        }
        val bmp = preview ?: return@LaunchedEffect
        estimatedBytes = withContext(Dispatchers.Default) {
            try {
                ensureActive()
                if (settings.format == ExportFormat.HEIC) {
                    // M11: no cheap HEIC trial path — scale a JPEG trial.
                    val trial = Exporter.compress(
                        bmp,
                        settings.copy(format = ExportFormat.JPEG)
                    )
                    val trialPixels = (bmp.width * bmp.height).toLong()
                    val fullPixels = (fullW * fullH).toLong().coerceAtLeast(trialPixels)
                    Exporter.estimateHeicBytes(trial.size.toLong(), trialPixels, fullPixels)
                } else {
                    val trial = Exporter.compress(bmp, settings)
                    val trialPixels = (bmp.width * bmp.height).toLong()
                    val fullPixels = (fullW * fullH).toLong().coerceAtLeast(trialPixels)
                    Exporter.estimateBytes(trial.size.toLong(), trialPixels, fullPixels)
                }
            } catch (_: Exception) {
                0L
            }
        }
    }

    fun startExport() {
        if (exporting) return
        error = null
        savedUri = null
        exporting = true
        progress = 0f
        exportJob = scope.launch {
            var full: Bitmap? = null
            var rendered: Bitmap? = null
            var spaced: Bitmap? = null
            var sharpened: Bitmap? = null
            try {
                progress = 0.1f
                full = vm.renderFullBitmap()
                    ?: throw IllegalStateException("Could not decode the full-resolution photo")
                ensureActive()
                progress = 0.45f
                val lut = vm.currentLut()
                val fullFrame = full
                val currentSettings = settings
                rendered = withContext(Dispatchers.Default) {
                    ensureActive()
                    Exporter.renderForExport(fullFrame, vm.params.value, lut, targetW, targetH)
                }
                progress = 0.6f
                // M11 (§38+§36): explicit upscale (renderer never upscales),
                // per-format colorspace (TIFF coerces to sRGB), output
                // sharpen post-resize pre-encode (0 = off, same instance).
                val upscaled = withContext(Dispatchers.Default) {
                    ensureActive()
                    Exporter.upscaleIfAllowed(rendered!!, targetW, targetH, currentSettings.allowUpscale)
                }
                if (upscaled !== rendered && rendered !== full) runCatching { rendered!!.recycle() }
                rendered = upscaled
                progress = 0.65f
                val effectiveSpace =
                    Exporter.colorSpaceForFormat(currentSettings.format, currentSettings.colorSpace)
                val renderedFrame = rendered
                spaced = withContext(Dispatchers.Default) {
                    ensureActive()
                    Exporter.withColorSpace(renderedFrame!!, effectiveSpace)
                }
                if (spaced !== rendered && rendered !== full) {
                    runCatching { rendered!!.recycle() }
                    rendered = null
                }
                sharpened = withContext(Dispatchers.Default) {
                    ensureActive()
                    Exporter.applyOutputSharpen(spaced!!, currentSettings.outputSharpen)
                }
                if (sharpened !== spaced && spaced !== full) {
                    runCatching { spaced!!.recycle() }
                    spaced = null
                }
                val finalFrame = sharpened!!
                val bytes = withContext(Dispatchers.Default) {
                    ensureActive()
                    if (currentSettings.format == ExportFormat.TIFF) Exporter.encodeTiff(finalFrame)
                    else if (currentSettings.format == ExportFormat.HEIC) {
                        Exporter.encodeHeic(
                            finalFrame, currentSettings.effectiveQuality(), context.cacheDir
                        )
                    } else Exporter.compress(finalFrame, currentSettings)
                }
                progress = 0.8f
                // M11: HEIC pads odd dims to even (YUV420) — validate the
                // padded frame the decoder will actually reopen.
                val (expW, expH) = if (currentSettings.format == ExportFormat.HEIC) {
                    Exporter.evenDims(finalFrame.width, finalFrame.height)
                } else {
                    finalFrame.width to finalFrame.height
                }
                val withExif = withContext(Dispatchers.Default) {
                    ensureActive()
                    Exporter.withSourceExif(context, bytes, currentSettings, vm.baseSourcePath())
                }
                progress = 0.9f
                val name = Exporter.displayName(currentSettings.format)
                // M11 (§39+§94): validated publish transaction — temp file,
                // validate, metadata, MediaStore pending, finalize. Failures
                // delete temp + incomplete item; project stays intact.
                val uri = Exporter.publishBytes(
                    context, withExif, currentSettings.format, name, expW, expH
                )
                savedUri = uri
                savedName = name
                progress = 1f
                runCatching {
                    EditHistoryLog.log(
                        DatabaseProvider.get(context.applicationContext),
                        projectId,
                        EditHistoryLog.EXPORT
                    )
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                error = e.message ?: "Export failed"
            } finally {
                try {
                    sharpened?.let { if (it !== full) it.recycle() }
                    spaced?.let { if (it !== full) it.recycle() }
                    rendered?.let { if (it !== full) it.recycle() }
                    full?.recycle()
                } catch (_: Exception) {
                }
                exporting = false
                exportJob = null
            }
        }
    }

    fun startRawExport() {
        if (rawBusy) return
        rawError = null
        rawBusy = true
        rawJob = scope.launch {
            try {
                val source = vm.baseSourcePath()
                val name = Exporter.rawOriginalName(source)
                rawUri = withContext(Dispatchers.IO) {
                    ensureActive()
                    Exporter.exportRawOriginal(context, source, name)
                }
                runCatching {
                    EditHistoryLog.log(
                        DatabaseProvider.get(context.applicationContext),
                        projectId,
                        EditHistoryLog.EXPORT
                    )
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                rawError = e.message ?: "RAW export failed"
            } finally {
                rawBusy = false
                rawJob = null
            }
        }
    }

    fun startSidecarExport() {
        if (rawBusy) return
        rawError = null
        rawBusy = true
        sidecarJob = scope.launch {
            try {
                val source = vm.baseSourcePath()
                val rawName = Exporter.rawOriginalName(source)
                val sidecarName = Exporter.sidecarNameFor(rawName)
                sidecarUri = withContext(Dispatchers.IO) {
                    ensureActive()
                    Exporter.exportSidecar(context, vm.params.value, sidecarName, rawName)
                }
                runCatching {
                    EditHistoryLog.log(
                        DatabaseProvider.get(context.applicationContext),
                        projectId,
                        EditHistoryLog.EXPORT
                    )
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                rawError = e.message ?: "Sidecar export failed"
            } finally {
                rawBusy = false
                sidecarJob = null
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Export") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        if (loading && project == null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                LoadingShimmer(style = LoadingShimmerStyle.Card)
            }
            return@Scaffold
        }
        val current = project
        if (current == null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp)
            ) {
                ErrorState(
                    title = "Project not found",
                    message = "It may have been deleted.",
                    actionLabel = "Back",
                    onAction = { navController.popBackStack() }
                )
            }
            return@Scaffold
        }
        if (error != null && savedUri == null && !exporting) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp)
            ) {
                ErrorState(
                    title = "Export failed",
                    message = error!!,
                    actionLabel = "Try again",
                    onAction = { error = null }
                )
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(current.name, style = LuminaSectionHeaderTextStyle)
            val activePreset = params.presetId
            Text(
                if (activePreset == null) "No preset • adjustments only"
                else "Preset intensity ${(params.presetIntensity * 100f).roundToInt()}%",
                style = LuminaCaptionTextStyle,
                color = LuminaMuted
            )

            if (isRaw) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = LuminaSurfaceContainerLow)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("RAW-compatible workflow", style = LuminaSectionHeaderTextStyle)
                        Text(
                            "Original sensor data preserved untouched — edits stored as re-editable recipe, NOT baked in.",
                            style = LuminaCaptionTextStyle,
                            color = LuminaMuted
                        )
                        OutlinedButton(
                            onClick = { startRawExport() },
                            enabled = !rawBusy,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                        ) { Text(if (rawBusy) "Working…" else "Copy original file") }
                        OutlinedButton(
                            onClick = { startSidecarExport() },
                            enabled = !rawBusy,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                        ) { Text(if (rawBusy) "Working…" else "Save recipe (.json)") }
                        OutlinedButton(
                            onClick = { sidecarPicker.launch(arrayOf("application/json", "*/*")) },
                            enabled = !rawBusy,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                        ) { Text("Import recipe (.json)") }
                        if (rawBusy) {
                            TextButton(
                                onClick = {
                                    rawJob?.cancel()
                                    sidecarJob?.cancel()
                                },
                                modifier = Modifier.heightIn(min = 48.dp)
                            ) { Text("Cancel") }
                        }
                        if (rawUri != null) {
                            Text(
                                rawUri.toString(),
                                style = LuminaCaptionTextStyle,
                                color = LuminaMuted
                            )
                        }
                        if (sidecarUri != null) {
                            Text(
                                sidecarUri.toString(),
                                style = LuminaCaptionTextStyle,
                                color = LuminaMuted
                            )
                        }
                        if (sidecarNotice != null) {
                            Text(
                                sidecarNotice!!,
                                style = LuminaCaptionTextStyle,
                                color = LuminaMuted
                            )
                        }
                        if (rawError != null) {
                            Text(
                                rawError!!,
                                style = LuminaCaptionTextStyle,
                                color = LuminaError
                            )
                        }
                    }
                }
            }

            if (isRaw) {
                Text("Rendered image export", style = LuminaSectionHeaderTextStyle)
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = LuminaSurfaceContainerLow)
            ) {
                Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                    Text("Format", style = LuminaSectionHeaderTextStyle)
                    Spacer(modifier = Modifier.height(4.dp))
                    Column(modifier = Modifier.selectableGroup()) {
                        // M11: rows driven by encoder availability — WebP/HEIC
                        // appear only when the device can encode them.
                        for (format in availableFormats) {
                            FormatRow(
                                label = when (format) {
                                    ExportFormat.JPEG -> "JPEG"
                                    ExportFormat.PNG -> "PNG"
                                    ExportFormat.TIFF -> "TIFF"
                                    ExportFormat.WEBP -> "WebP"
                                    ExportFormat.HEIC -> "HEIC"
                                },
                                selected = settings.format == format,
                                enabled = true,
                                onClick = { settings = settings.copy(format = format) }
                            )
                        }
                    }
                    if (settings.format == ExportFormat.PNG) {
                        Text(
                            "PNG is lossless — quality settings are ignored.",
                            style = LuminaCaptionTextStyle,
                            color = LuminaMuted
                        )
                    }
                    if (settings.format == ExportFormat.TIFF) {
                        Text(
                            "Uncompressed 8-bit RGB — large files, maximum compatibility.",
                            style = LuminaCaptionTextStyle,
                            color = LuminaMuted
                        )
                    }
                    if (settings.format == ExportFormat.WEBP) {
                        Text(
                            "WebP is lossless at quality 100, lossy below.",
                            style = LuminaCaptionTextStyle,
                            color = LuminaMuted
                        )
                    }
                    if (settings.format == ExportFormat.HEIC) {
                        Text(
                            "HEIC needs a device encoder — hidden where unsupported.",
                            style = LuminaCaptionTextStyle,
                            color = LuminaMuted
                        )
                    }
                    if (isRaw) {
                        Text(
                            "Rendered pixels — cannot be converted back to RAW.",
                            style = LuminaCaptionTextStyle,
                            color = LuminaMuted
                        )
                    }
                }
            }

            if (settings.format == ExportFormat.JPEG ||
                settings.format == ExportFormat.WEBP ||
                settings.format == ExportFormat.HEIC
            ) {
                Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = LuminaSurfaceContainerLow)
            ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("Quality", style = LuminaSectionHeaderTextStyle)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            QualityPreset.entries.forEach { preset ->
                                TextButton(
                                    onClick = { settings = settings.copy(qualityPreset = preset) },
                                    modifier = Modifier.heightIn(min = 48.dp)
                                ) {
                                    Text(
                                        (if (settings.qualityPreset == preset) "● " else "") +
                                            preset.label +
                                            if (preset == QualityPreset.MAXIMUM) " (100)"
                                            else if (preset == QualityPreset.HIGH) " (90)" else ""
                                    )
                                }
                            }
                        }
                        if (settings.qualityPreset == QualityPreset.CUSTOM) {
                            ProSlider(
                                label = "Custom quality",
                                value = settings.customQuality.toFloat(),
                                onValueChange = {
                                    settings = settings.copy(customQuality = it.roundToInt())
                                },
                                valueRange = 1f..100f,
                                displayValue = settings.customQuality.toString()
                            )
                        } else {
                            Text(
                                "${settings.format.name} quality ${settings.effectiveQuality()}",
                                style = LuminaCaptionTextStyle,
                                color = LuminaMuted
                            )
                        }
                    }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = LuminaSurfaceContainerLow)
            ) {
                Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                    Text("Resolution", style = LuminaSectionHeaderTextStyle)
                    Column(modifier = Modifier.selectableGroup()) {
                        ResolutionRow(
                            label = "Original (${fullW.takeIf { it > 0 } ?: "–"} × ${fullH.takeIf { it > 0 } ?: "–"})",
                            selected = settings.resolutionMode == ResolutionMode.ORIGINAL,
                            onClick = { settings = settings.copy(resolutionMode = ResolutionMode.ORIGINAL) }
                        )
                        ResolutionRow(
                            label = "Custom (max dimension)",
                            selected = settings.resolutionMode == ResolutionMode.CUSTOM,
                            onClick = { settings = settings.copy(resolutionMode = ResolutionMode.CUSTOM) }
                        )
                    }
                    if (settings.resolutionMode == ResolutionMode.CUSTOM) {
                        ProSlider(
                            label = "Max dimension",
                            value = settings.customMaxDim.toFloat(),
                            onValueChange = {
                                settings = settings.copy(customMaxDim = it.roundToInt())
                            },
                            valueRange = Exporter.MIN_CUSTOM_DIM.toFloat()..Exporter.MAX_CUSTOM_DIM.toFloat(),
                            displayValue = "${settings.customMaxDim} px"
                        )
                        Text(
                            "Fit-scale keeps the aspect ratio and never upscales.",
                            style = LuminaCaptionTextStyle,
                            color = LuminaMuted
                        )
                    }
                    // M11 (§38 size): longest-edge control. When set (>0) it
                    // is the single size control (Custom max-dim ignored);
                    // aspect-locked, no custom width/height by design.
                    ProSlider(
                        label = "Longest edge",
                        value = settings.longestEdge.toFloat(),
                        onValueChange = {
                            settings = settings.copy(longestEdge = it.roundToInt())
                        },
                        valueRange = Exporter.LONGEST_EDGE_OFF.toFloat()..Exporter.MAX_LONGEST_EDGE.toFloat(),
                        displayValue = if (settings.longestEdge <= Exporter.LONGEST_EDGE_OFF) {
                            "Off"
                        } else {
                            "${settings.longestEdge} px"
                        }
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Allow upscale",
                            modifier = Modifier.weight(1f),
                            style = LuminaSectionHeaderTextStyle,
                            color = LuminaOnSurface
                        )
                        Switch(
                            checked = settings.allowUpscale,
                            enabled = settings.longestEdge > Exporter.LONGEST_EDGE_OFF,
                            onCheckedChange = { settings = settings.copy(allowUpscale = it) }
                        )
                    }
                    Text(
                        "Longest-edge fit keeps the aspect ratio and never upscales unless allowed.",
                        style = LuminaCaptionTextStyle,
                        color = LuminaMuted
                    )
                    Text(
                        "Exports at $targetW × $targetH",
                        style = LuminaCaptionTextStyle,
                        color = LuminaMuted
                    )
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = LuminaSurfaceContainerLow)
            ) {
                Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                    Text("Output sharpening", style = LuminaSectionHeaderTextStyle)
                    ProSlider(
                        label = "Sharpen for output",
                        value = settings.outputSharpen.toFloat(),
                        onValueChange = {
                            settings = settings.copy(outputSharpen = it.roundToInt())
                        },
                        valueRange = 0f..100f,
                        displayValue = if (settings.outputSharpen == 0) "Off"
                        else settings.outputSharpen.toString()
                    )
                    Text(
                        "Small-radius unsharp applied once at export size, before encoding. Off at 0.",
                        style = LuminaCaptionTextStyle,
                        color = LuminaMuted
                    )
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = LuminaSurfaceContainerLow)
            ) {
                Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                    Text("Color space", style = LuminaSectionHeaderTextStyle)
                    if (wideGamut) {
                        Column(modifier = Modifier.selectableGroup()) {
                            ResolutionRow(
                                label = "sRGB",
                                selected = settings.colorSpace == ExportColorSpace.SRGB,
                                onClick = {
                                    settings = settings.copy(colorSpace = ExportColorSpace.SRGB)
                                    scope.launch {
                                        runCatching {
                                            settingsRepository.setExportColorSpace(
                                                ExportColorSpace.SRGB.key
                                            )
                                        }
                                    }
                                }
                            )
                            ResolutionRow(
                                label = ExportColorSpace.DISPLAY_P3.label,
                                selected = settings.colorSpace == ExportColorSpace.DISPLAY_P3,
                                onClick = {
                                    settings = settings.copy(colorSpace = ExportColorSpace.DISPLAY_P3)
                                    scope.launch {
                                        runCatching {
                                            settingsRepository.setExportColorSpace(
                                                ExportColorSpace.DISPLAY_P3.key
                                            )
                                        }
                                    }
                                }
                            )
                        }
                        Text(
                            "Render math stays sRGB for correctness; P3 converts the pixels on export.",
                            style = LuminaCaptionTextStyle,
                            color = LuminaMuted
                        )
                        if (settings.format == ExportFormat.TIFF &&
                            settings.colorSpace == ExportColorSpace.DISPLAY_P3
                        ) {
                            Text(
                                "TIFF is an untagged sRGB container — P3 coerces to sRGB for TIFF.",
                                style = LuminaCaptionTextStyle,
                                color = LuminaMuted
                            )
                        }
                    } else {
                        Text(
                            "sRGB",
                            style = LuminaCaptionTextStyle,
                            color = LuminaMuted
                        )
                    }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = LuminaSurfaceContainerLow)
            ) {
                Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Preserve EXIF metadata",
                            modifier = Modifier.weight(1f),
                            style = LuminaSectionHeaderTextStyle,
                            color = LuminaOnSurface
                        )
                        Switch(
                            checked = settings.preserveExif && !settings.stripMetadata,
                            enabled = !settings.stripMetadata,
                            onCheckedChange = { settings = settings.copy(preserveExif = it) }
                        )
                    }
                    Text(
                        "Copies camera metadata from the source into JPEG, WebP and HEIC exports when available. PNG and TIFF stay clean by construction.",
                        style = LuminaCaptionTextStyle,
                        color = LuminaMuted
                    )
                    // M11 (§36): explicit per-export "Remove location" toggle
                    // (inverse of the persisted include-location key) plus
                    // "Strip all metadata" for a clean file with no EXIF.
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Remove location",
                            modifier = Modifier.weight(1f),
                            style = LuminaSectionHeaderTextStyle,
                            color = LuminaOnSurface
                        )
                        Switch(
                            checked = settings.removeLocation || settings.stripMetadata,
                            enabled = !settings.stripMetadata,
                            onCheckedChange = {
                                settings = settings.copy(includeLocation = !it)
                                scope.launch {
                                    runCatching { settingsRepository.setExportIncludeLocation(!it) }
                                }
                            }
                        )
                    }
                    Text(
                        "On by default for privacy — GPS tags are stripped from exports and shares when on.",
                        style = LuminaCaptionTextStyle,
                        color = LuminaMuted
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Strip all metadata",
                            modifier = Modifier.weight(1f),
                            style = LuminaSectionHeaderTextStyle,
                            color = LuminaOnSurface
                        )
                        Switch(
                            checked = settings.stripMetadata,
                            onCheckedChange = { settings = settings.copy(stripMetadata = it) }
                        )
                    }
                    Text(
                        "Writes a clean file with no EXIF. Orientation is always normal on export.",
                        style = LuminaCaptionTextStyle,
                        color = LuminaMuted
                    )
                    // M11 (§36): export summary — focal length + GPS state.
                    sourceExif?.focalLength?.let {
                        Text(
                            "Focal length: $it",
                            style = LuminaCaptionTextStyle,
                            color = LuminaMuted
                        )
                    }
                    if (sourceExif?.hasGps == true) {
                        Text(
                            if (settings.removeLocation || settings.stripMetadata) {
                                "Location: present in source, removed from this export"
                            } else {
                                "Location: present in source, included in this export"
                            },
                            style = LuminaCaptionTextStyle,
                            color = LuminaMuted
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Saves to MediaStore › Pictures › Lumina as ${Exporter.displayName(settings.format)}",
                        style = LuminaCaptionTextStyle,
                        color = LuminaMuted
                    )
                }
            }

            Text(
                "Estimated size: ≈ ${Exporter.formatBytes(estimatedBytes)}",
                style = LuminaCaptionTextStyle,
                color = LuminaMuted
            )

            if (exporting) {
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth()
                )
                TextButton(
                    onClick = { exportJob?.cancel() },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) { Text("Cancel") }
            }

            if (savedUri != null) {
                Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = LuminaSurfaceContainerLow)
            ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Export complete", style = LuminaSectionHeaderTextStyle)
                        Text(
                            savedName,
                            style = LuminaCaptionTextStyle,
                            color = LuminaMuted
                        )
                        Text(
                            savedUri.toString(),
                            style = LuminaCaptionTextStyle,
                            color = LuminaMuted
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    try {
                                        val open = Intent(Intent.ACTION_VIEW).apply {
                                            setDataAndType(savedUri, settings.format.mime)
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        context.startActivity(open)
                                    } catch (_: Exception) {
                                        scope.launch { snackbar.showSnackbar("No app can open this image") }
                                    }
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp)
                            ) { Text("Open") }
                            OutlinedButton(
                                onClick = {
                                    try {
                                        val share = Intent(Intent.ACTION_SEND).apply {
                                            type = settings.format.mime
                                            putExtra(Intent.EXTRA_STREAM, savedUri)
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        context.startActivity(Intent.createChooser(share, "Share export"))
                                    } catch (_: Exception) {
                                        scope.launch { snackbar.showSnackbar("Sharing is not available") }
                                    }
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp)
                            ) { Text("Share") }
                        }
                    }
                }
            }

            Button(
                onClick = { startExport() },
                enabled = !exporting,
                colors = ButtonDefaults.buttonColors(
                    containerColor = LuminaAmber,
                    contentColor = LuminaBackground
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .heightIn(min = 48.dp)
            ) {
                Text(
                    if (exporting) "Exporting…" else "Export",
                    style = LuminaSectionHeaderTextStyle,
                    color = LuminaBackground
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun FormatRow(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    note: String? = null,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onClick
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Text(
            text = label,
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp),
            color = if (enabled) LuminaOnSurface
            else LuminaMuted
        )
        if (note != null) {
            Text(
                text = note,
                style = LuminaCaptionTextStyle,
                color = LuminaMuted
            )
        }
    }
}

@Composable
private fun ResolutionRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(text = label, modifier = Modifier.padding(start = 8.dp))
    }
}
