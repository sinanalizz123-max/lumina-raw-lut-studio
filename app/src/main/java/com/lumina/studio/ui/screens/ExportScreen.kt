package com.lumina.studio.ui.screens

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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

    val wideGamut = remember { Exporter.isWideGamutDisplay(context) }
    val settingsRepository = remember { SettingsRepository(context) }

    LaunchedEffect(Unit) {
        try {
            val stored = settingsRepository.exportColorSpace.first()
            val mapped = ExportColorSpace.fromKey(stored)
            val coerced = if (mapped == ExportColorSpace.DISPLAY_P3 && !Exporter.isWideGamutDisplay(context)) {
                ExportColorSpace.SRGB
            } else {
                mapped
            }
            settings = settings.copy(colorSpace = coerced)
        } catch (_: Exception) {
        }
    }

    val fullW = project?.width?.takeIf { it > 0 }
        ?: preview?.width ?: 0
    val fullH = project?.height?.takeIf { it > 0 }
        ?: preview?.height ?: 0
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
                val trial = Exporter.compress(bmp, settings)
                val trialPixels = (bmp.width * bmp.height).toLong()
                val fullPixels = (fullW * fullH).toLong().coerceAtLeast(trialPixels)
                Exporter.estimateBytes(trial.size.toLong(), trialPixels, fullPixels)
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
            try {
                progress = 0.1f
                full = vm.renderFullBitmap()
                    ?: throw IllegalStateException("Could not decode the full-resolution photo")
                progress = 0.45f
                val lut = vm.currentLut()
                val rendered = withContext(Dispatchers.Default) {
                    Exporter.renderForExport(full, vm.params.value, lut, targetW, targetH)
                }
                progress = 0.65f
                val currentSettings = settings
                val spaced = withContext(Dispatchers.Default) {
                    Exporter.withColorSpace(rendered, currentSettings.colorSpace)
                }
                if (spaced !== rendered && rendered !== full) rendered.recycle()
                val bytes = withContext(Dispatchers.Default) {
                    if (currentSettings.format == ExportFormat.TIFF) Exporter.encodeTiff(spaced)
                    else Exporter.compress(spaced, currentSettings)
                }
                if (spaced !== full) spaced.recycle()
                progress = 0.8f
                val withExif = withContext(Dispatchers.Default) {
                    Exporter.withSourceExif(context, bytes, currentSettings, vm.baseSourcePath())
                }
                progress = 0.9f
                val name = Exporter.displayName(currentSettings.format)
                val uri = Exporter.saveToGallery(context, withExif, currentSettings.format, name)
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
        scope.launch {
            try {
                val source = vm.baseSourcePath()
                val name = Exporter.rawOriginalName(source)
                rawUri = withContext(Dispatchers.IO) {
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
            }
        }
    }

    fun startSidecarExport() {
        if (rawBusy) return
        rawError = null
        rawBusy = true
        scope.launch {
            try {
                val source = vm.baseSourcePath()
                val rawName = Exporter.rawOriginalName(source)
                val sidecarName = Exporter.sidecarNameFor(rawName)
                sidecarUri = withContext(Dispatchers.IO) {
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
                        FormatRow(
                            label = "JPEG",
                            selected = settings.format == ExportFormat.JPEG,
                            enabled = true,
                            onClick = { settings = settings.copy(format = ExportFormat.JPEG) }
                        )
                        FormatRow(
                            label = "PNG",
                            selected = settings.format == ExportFormat.PNG,
                            enabled = true,
                            onClick = { settings = settings.copy(format = ExportFormat.PNG) }
                        )
                        FormatRow(
                            label = "TIFF",
                            selected = settings.format == ExportFormat.TIFF,
                            enabled = true,
                            onClick = { settings = settings.copy(format = ExportFormat.TIFF) }
                        )
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
                    if (isRaw) {
                        Text(
                            "Rendered pixels — cannot be converted back to RAW.",
                            style = LuminaCaptionTextStyle,
                            color = LuminaMuted
                        )
                    }
                }
            }

            if (settings.format == ExportFormat.JPEG) {
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
                                "JPEG quality ${settings.effectiveQuality()}",
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
                                label = "Display P3 (device-supported output container)",
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
                            "Render math stays sRGB for correctness; P3 is the output container only.",
                            style = LuminaCaptionTextStyle,
                            color = LuminaMuted
                        )
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
                            checked = settings.preserveExif,
                            onCheckedChange = { settings = settings.copy(preserveExif = it) }
                        )
                    }
                    Text(
                        "Copies camera metadata from the source into JPEG exports when available.",
                        style = LuminaCaptionTextStyle,
                        color = LuminaMuted
                    )
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
