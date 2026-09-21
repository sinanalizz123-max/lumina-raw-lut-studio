package com.lumina.studio.ui.screens

import android.app.Application
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Grain
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import coil.compose.AsyncImage
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.Image
import com.lumina.studio.core.data.datastore.SettingsRepository
import com.lumina.studio.core.edit.CropRatio
import com.lumina.studio.core.edit.CropRects
import com.lumina.studio.core.design.components.EmptyState
import com.lumina.studio.core.design.components.EmptyStateIllustration
import com.lumina.studio.core.export.Exporter
import com.lumina.studio.core.util.ExifReader
import com.lumina.studio.core.design.components.ErrorState
import com.lumina.studio.core.design.components.LoadingShimmer
import com.lumina.studio.core.design.components.LoadingShimmerStyle
import com.lumina.studio.core.design.theme.LuminaAmber
import com.lumina.studio.core.design.theme.LuminaCaptionTextStyle
import com.lumina.studio.core.design.theme.LuminaMotion
import com.lumina.studio.core.design.theme.LuminaMuted
import com.lumina.studio.core.design.theme.LuminaOnSurface
import com.lumina.studio.core.design.theme.LuminaSectionHeaderTextStyle
import com.lumina.studio.core.design.theme.LuminaSurfaceContainerHigh
import com.lumina.studio.core.design.theme.LuminaSurfaceContainerLow
import com.lumina.studio.core.render.PreviewRenderer
import com.lumina.studio.core.util.ImageFiles
import com.lumina.studio.navigation.Routes
import com.lumina.studio.ui.editor.ColorToolPanel
import com.lumina.studio.ui.editor.CropToolPanel
import com.lumina.studio.ui.editor.CurvesToolPanel
import com.lumina.studio.ui.editor.DetailsToolPanel
import com.lumina.studio.ui.editor.EditorTool
import com.lumina.studio.ui.editor.EditorToolPanel
import com.lumina.studio.ui.editor.GradeToolPanel
import com.lumina.studio.ui.editor.MaskToolPanel
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

private const val HOLD_COMPARE_MS = 250L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(navController: NavController, projectId: String? = null) {
    val context = LocalContext.current
    val app = context.applicationContext as Application
    val vm: EditorViewModel = viewModel(
        key = "editor_${projectId ?: "none"}",
        factory = EditorViewModelFactory(app, projectId)
    )

    val project by vm.project.collectAsState()
    val params by vm.params.collectAsState()
    val canUndo by vm.canUndo.collectAsState()
    val canRedo by vm.canRedo.collectAsState()
    val preview by vm.preview.collectAsState()
    val histogram by vm.histogram.collectAsState()
    val loading by vm.loading.collectAsState()
    val pressingOriginal by vm.pressingOriginal.collectAsState()
    val fullscreen by vm.fullscreen.collectAsState()
    val fullscreenPreview by vm.fullscreenPreview.collectAsState()
    val showHistogram by vm.showHistogram.collectAsState()
    val activeTool by vm.activeTool.collectAsState()
    val selectedHsl by vm.selectedHsl.collectAsState()
    val eyedropperArmed by vm.eyedropperArmed.collectAsState()
    val selectedGradeZone by vm.selectedGradeZone.collectAsState()
    val pointEyedropperArmed by vm.pointEyedropperArmed.collectAsState()
    val showPointAffected by vm.showPointAffected.collectAsState()
    val pointColorMask by vm.pointColorMask.collectAsState()
    val selectedCurve by vm.selectedCurve.collectAsState()
    val selectedMaskId by vm.selectedMaskId.collectAsState()
    val showMaskOverlay by vm.showMaskOverlay.collectAsState()
    val maskSampleArmedId by vm.maskSampleArmedId.collectAsState()
    val zoomTile by vm.zoomTile.collectAsState()
    val zoomWarning by vm.zoomWarning.collectAsState()
    val zoomEnhancing by vm.zoomEnhancing.collectAsState()
    val paramsRevision by vm.paramsRevision.collectAsState()

    var showRename by remember { mutableStateOf(false) }
    var renameText by remember(project?.name) { mutableStateOf(project?.name ?: "") }
    var curvesDragging by remember { mutableStateOf(false) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    val settingsRepo = remember(context) { SettingsRepository(context) }
    val hapticEnabled by settingsRepo.hapticEnabled.collectAsState(initial = true)
    // Phase 4B polish wiring: GPU + preview-quality gate the render path
    // (EditorViewModel.setPerformanceFlags), animations gates editor tool transitions.
    val gpuSetting by settingsRepo.gpuAcceleration.collectAsState(initial = true)
    val previewQualitySetting by settingsRepo.previewQuality.collectAsState(initial = "High")
    val animationsEnabled by settingsRepo.animationsEnabled.collectAsState(initial = true)
    val largeDecoding by vm.largeDecoding.collectAsState()
    val loadCancelled by vm.loadCancelled.collectAsState()
    val transformBitmap = if (fullscreen) fullscreenPreview ?: preview else preview
    val viewportSizeRef = rememberUpdatedState(viewportSize)
    val transformBitmapRef = rememberUpdatedState(transformBitmap)
    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        val newScale = (scale * zoomChange).coerceIn(1f, 5f)
        scale = newScale
        val vs = viewportSizeRef.value
        val bmp = transformBitmapRef.value
        offset = if (newScale <= 1.01f) {
            Offset.Zero
        } else {
            clampEditorOffset(offset + panChange, newScale, vs, bmp?.width ?: 0, bmp?.height ?: 0)
        }
        vm.setZoom(scale)
    }

    LaunchedEffect(fullscreen, viewportSize, transformBitmap?.width, transformBitmap?.height) {
        offset = if (scale <= 1.01f) {
            Offset.Zero
        } else {
            clampEditorOffset(
                offset, scale, viewportSize,
                transformBitmap?.width ?: 0, transformBitmap?.height ?: 0
            )
        }
    }

    LaunchedEffect(activeTool) {
        if (activeTool == EditorTool.CURVES) vm.requestHistogram()
    }

    LaunchedEffect(gpuSetting, previewQualitySetting) {
        vm.setPerformanceFlags(
            gpuSetting,
            PreviewRenderer.effectivePreviewMaxDim(previewQualitySetting, gpuSetting)
        )
    }

    val viewportBitmap = if (fullscreen) fullscreenPreview ?: preview else preview
    LaunchedEffect(
        scale, offset, viewportSize,
        viewportBitmap?.width, viewportBitmap?.height
    ) {
        if (scale > 1.25f) {
            val bw = viewportBitmap?.width ?: 0
            val bh = viewportBitmap?.height ?: 0
            val rect = zoomVisibleFractions(
                viewportSize.width, viewportSize.height, bw, bh, scale, offset
            )
            if (rect != null) {
                vm.onViewportChanged(rect.left, rect.top, rect.right, rect.bottom, scale)
            } else {
                vm.onViewportChanged(0f, 0f, 0f, 0f, scale)
            }
        } else {
            vm.onViewportChanged(0f, 0f, 0f, 0f, scale)
        }
    }

    LaunchedEffect(projectId) {
        scale = 1f
        offset = Offset.Zero
        val jump = try {
            navController.previousBackStackEntry
                ?.savedStateHandle
                ?.get<String>("history_jump_tool")
        } catch (_: Exception) {
            null
        }
        if (!jump.isNullOrBlank()) {
            try {
                val tool = EditorTool.valueOf(jump)
                vm.setActiveTool(tool)
            } catch (_: Exception) {
            }
            try {
                navController.previousBackStackEntry
                    ?.savedStateHandle
                    ?.remove<String>("history_jump_tool")
            } catch (_: Exception) {
            }
        }
    }

    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val effectiveOriginal = pressingOriginal

    Scaffold(
        topBar = {
            if (!fullscreen) {
                Surface(
                    color = LuminaSurfaceContainerLow,
                    contentColor = LuminaOnSurface
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .height(52.dp)
                            .padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { navController.popBackStack() },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                Icons.Filled.ArrowBack,
                                contentDescription = "Back",
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        TextButton(
                            onClick = {
                                renameText = project?.name ?: ""
                                showRename = true
                            },
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.Start
                            ) {
                                Text(
                                    text = project?.name ?: "Editor",
                                    style = LuminaSectionHeaderTextStyle,
                                    color = LuminaOnSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                project?.let {
                                    Text(
                                        text = "● Saved • ${timeFormat.format(Date(it.updatedAt))}",
                                        style = LuminaCaptionTextStyle,
                                        color = LuminaMuted,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                        IconButton(
                            onClick = { vm.undo() },
                            enabled = canUndo,
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                Icons.Filled.Undo,
                                contentDescription = "Undo",
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        IconButton(
                            onClick = { vm.toggleHistogram() },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                Icons.Filled.BarChart,
                                contentDescription = "Histogram",
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        IconButton(
                            onClick = { vm.redo() },
                            enabled = canRedo,
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                Icons.Filled.Redo,
                                contentDescription = "Redo",
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        IconButton(
                            onClick = {
                                try {
                                    navController.currentBackStackEntry?.savedStateHandle?.set(
                                        "history_project_id", project?.id ?: projectId.orEmpty()
                                    )
                                } catch (_: Exception) {
                                }
                                navController.navigate(Routes.HISTORY)
                            },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                Icons.Filled.History,
                                contentDescription = "History",
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        IconButton(
                            onClick = {
                                try {
                                    navController.currentBackStackEntry?.savedStateHandle?.set(
                                        "export_project_id", project?.id ?: projectId.orEmpty()
                                    )
                                } catch (_: Exception) {
                                }
                                navController.navigate(Routes.EXPORT)
                            },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                Icons.Filled.Share,
                                contentDescription = "Export",
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }
    ) { padding ->
        if (projectId.isNullOrBlank()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp)
            ) {
                EmptyState(
                    title = "No photo loaded",
                    message = "Pick a photo to start editing.",
                    actionLabel = "Import photo",
                    onAction = { navController.navigate(Routes.IMPORT) },
                    illustration = EmptyStateIllustration.Photo
                )
            }
            return@Scaffold
        }
        if (loading && project == null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                LoadingShimmer(style = LoadingShimmerStyle.Card)
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Phase 4B: large-image decode surfaces a cancellable indicator tied to
                    // the actual decode job (EditorViewModel.largeDecoding).
                    if (largeDecoding) {
                        Text(
                            "Processing large image…",
                            style = LuminaCaptionTextStyle,
                            color = LuminaMuted
                        )
                        TextButton(
                            onClick = { vm.cancelLoad() },
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) { Text("Cancel") }
                    }
                }
            }
            return@Scaffold
        }
        if (loadCancelled && project == null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp)
            ) {
                ErrorState(
                    title = "Loading cancelled",
                    message = "Image decode was cancelled.",
                    actionLabel = "Retry",
                    onAction = { vm.retryLoad() }
                )
            }
            return@Scaffold
        }
        val current = project
        if (current == null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                EmptyState(
                    title = "Project not found",
                    message = "It may have been deleted.",
                    illustration = EmptyStateIllustration.Folder
                )
                TextButton(
                    onClick = { navController.popBackStack() },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) { Text("Back") }
            }
            return@Scaffold
        }

        val imageModel: Any? = current.photoUri?.let { path ->
            val file = File(path)
            if (file.exists()) file else path
        }
        val typeBadgeLabel = remember(current) {
            val extSource = current.fileType.ifBlank {
                current.photoUri?.substringAfterLast('.', "")?.substringBefore('?').orEmpty()
            }
            ImageFiles.typeBadge(extSource, current.mimeType)
        }
        var typeBadgeVisible by remember(current.id) { mutableStateOf(true) }
        var showImageDetails by remember(current.id) { mutableStateOf(false) }
        var imagePressStartMs by remember(current.id) { mutableLongStateOf(0L) }
        val detailsFile = remember(current) {
            current.photoUri?.let { java.io.File(it) }?.takeIf { it.exists() }
        }
        val detailsExif = remember(current) {
            detailsFile?.let { ExifReader.read(it) }
        }
        val detailsDateFmt = remember { java.text.SimpleDateFormat("dd MMM yyyy, HH:mm", java.util.Locale.getDefault()) }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                val displayBitmap = if (fullscreen) fullscreenPreview ?: preview else preview
                val showPreviewBitmap = !effectiveOriginal && displayBitmap != null
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .onSizeChanged { viewportSize = it }
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offset.x
                            translationY = offset.y
                        }
                        .transformable(transformState, enabled = !eyedropperArmed && !pointEyedropperArmed && maskSampleArmedId == null)
                        .pointerInput(eyedropperArmed, pointEyedropperArmed, maskSampleArmedId, displayBitmap, viewportSize) {
                            detectTapGestures(
                                onDoubleTap = {
                                    vm.toggleFullscreen()
                                },
                                onPress = {
                                    imagePressStartMs = System.currentTimeMillis()
                                    val quickRelease = try {
                                        withTimeout(HOLD_COMPARE_MS) { awaitRelease() }
                                        true
                                    } catch (_: TimeoutCancellationException) {
                                        false
                                    }
                                    if (!quickRelease) {
                                        vm.setPressingOriginal(true)
                                        try {
                                            tryAwaitRelease()
                                        } finally {
                                            vm.setPressingOriginal(false)
                                        }
                                    }
                                },
                                onTap = { tap ->
                                    if (maskSampleArmedId != null) {
                                        val bmp = displayBitmap ?: return@detectTapGestures
                                        val vw = viewportSize.width.toFloat()
                                        val vh = viewportSize.height.toFloat()
                                        if (vw <= 0f || vh <= 0f) return@detectTapGestures
                                        val bw = bmp.width.toFloat()
                                        val bh = bmp.height.toFloat()
                                        if (bw <= 0f || bh <= 0f) return@detectTapGestures
                                        val fitScale = minOf(vw / bw, vh / bh)
                                        if (fitScale <= 0f) return@detectTapGestures
                                        val drawnW = bw * fitScale
                                        val drawnH = bh * fitScale
                                        val left = (vw - drawnW) / 2f
                                        val top = (vh - drawnH) / 2f
                                        val x = tap.x
                                        val y = tap.y
                                        if (x < left || x > left + drawnW || y < top || y > top + drawnH) return@detectTapGestures
                                        val bx = ((x - left) / fitScale).toInt().coerceIn(0, bmp.width - 1)
                                        val by = ((y - top) / fitScale).toInt().coerceIn(0, bmp.height - 1)
                                        try {
                                            if (bmp.isRecycled) return@detectTapGestures
                                            vm.maskSamplePick(bmp.getPixel(bx, by))
                                        } catch (_: Exception) {
                                        }
                                        return@detectTapGestures
                                    }
                                    if (pointEyedropperArmed) {
                                        val bmp = displayBitmap ?: return@detectTapGestures
                                        val vw = viewportSize.width.toFloat()
                                        val vh = viewportSize.height.toFloat()
                                        if (vw <= 0f || vh <= 0f) return@detectTapGestures
                                        val bw = bmp.width.toFloat()
                                        val bh = bmp.height.toFloat()
                                        if (bw <= 0f || bh <= 0f) return@detectTapGestures
                                        val fitScale = minOf(vw / bw, vh / bh)
                                        if (fitScale <= 0f) return@detectTapGestures
                                        val drawnW = bw * fitScale
                                        val drawnH = bh * fitScale
                                        val left = (vw - drawnW) / 2f
                                        val top = (vh - drawnH) / 2f
                                        val x = tap.x
                                        val y = tap.y
                                        if (x < left || x > left + drawnW || y < top || y > top + drawnH) return@detectTapGestures
                                        val bx = ((x - left) / fitScale).toInt().coerceIn(0, bmp.width - 1)
                                        val by = ((y - top) / fitScale).toInt().coerceIn(0, bmp.height - 1)
                                        try {
                                            if (bmp.isRecycled) return@detectTapGestures
                                            vm.pointEyedropperPick(bmp.getPixel(bx, by))
                                        } catch (_: Exception) {
                                        }
                                        return@detectTapGestures
                                    }
                                    if (!eyedropperArmed) {
                                        if (!showImageDetails && System.currentTimeMillis() - imagePressStartMs < HOLD_COMPARE_MS) {
                                            typeBadgeVisible = !typeBadgeVisible
                                        }
                                        return@detectTapGestures
                                    }
                                    val bmp = displayBitmap ?: return@detectTapGestures
                                    val vw = viewportSize.width.toFloat()
                                    val vh = viewportSize.height.toFloat()
                                    if (vw <= 0f || vh <= 0f) return@detectTapGestures
                                    val bw = bmp.width.toFloat()
                                    val bh = bmp.height.toFloat()
                                    if (bw <= 0f || bh <= 0f) return@detectTapGestures
                                    val fitScale = minOf(vw / bw, vh / bh)
                                    if (fitScale <= 0f) return@detectTapGestures
                                    val drawnW = bw * fitScale
                                    val drawnH = bh * fitScale
                                    val left = (vw - drawnW) / 2f
                                    val top = (vh - drawnH) / 2f
                                    val x = tap.x
                                    val y = tap.y
                                    if (x < left || x > left + drawnW || y < top || y > top + drawnH) return@detectTapGestures
                                    val bx = ((x - left) / fitScale).toInt().coerceIn(0, bmp.width - 1)
                                    val by = ((y - top) / fitScale).toInt().coerceIn(0, bmp.height - 1)
                                    try {
                                        if (bmp.isRecycled) return@detectTapGestures
                                        vm.eyedropperPick(bmp.getPixel(bx, by))
                                    } catch (_: Exception) {
                                    }
                                }
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    val previewBitmap = displayBitmap
                    if (showPreviewBitmap && previewBitmap != null) {
                        Image(
                            bitmap = previewBitmap.asImageBitmap(),
                            contentDescription = current.name,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )
                    } else {
                        AsyncImage(
                            model = imageModel,
                            contentDescription = current.name,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )
                    }
                    val zt = zoomTile
                    if (zt != null && zt.revision == paramsRevision &&
                        !effectiveOriginal && scale > 1.25f
                    ) {
                        val tile = zt
                        val vw = viewportSize.width
                        val vh = viewportSize.height
                        val bw = previewBitmap?.width ?: 0
                        val bh = previewBitmap?.height ?: 0
                        if (vw > 0 && vh > 0 && bw > 0 && bh > 0) {
                            val fitScale = minOf(vw / bw.toFloat(), vh / bh.toFloat())
                            if (fitScale > 0f && fitScale.isFinite()) {
                                val drawnW = bw * fitScale
                                val drawnH = bh * fitScale
                                val imgLeft = (vw - drawnW) / 2f
                                val imgTop = (vh - drawnH) / 2f
                                val tLeft = imgLeft + tile.left * drawnW
                                val tTop = imgTop + tile.top * drawnH
                                val tW = (tile.right - tile.left).coerceAtLeast(0f) * drawnW
                                val tH = (tile.bottom - tile.top).coerceAtLeast(0f) * drawnH
                                if (tW >= 1f && tH >= 1f) {
                                    Box(
                                        modifier = Modifier.fillMaxSize(),
                                        contentAlignment = Alignment.TopStart
                                    ) {
                                        val density = LocalDensity.current
                                        val tileWdp = with(density) { tW.toDp() }
                                        val tileHdp = with(density) { tH.toDp() }
                                        Image(
                                            bitmap = tile.bitmap.asImageBitmap(),
                                            contentDescription = null,
                                            modifier = Modifier
                                                .offset {
                                                    IntOffset(
                                                        tLeft.roundToInt(),
                                                        tTop.roundToInt()
                                                    )
                                                }
                                                .width(tileWdp)
                                                .height(tileHdp),
                                            contentScale = ContentScale.FillBounds
                                        )
                                    }
                                }
                            }
                        }
                    }
                    if (activeTool == EditorTool.CROP && !effectiveOriginal) {
                        val bmp = previewBitmap
                        if (bmp != null) {
                            CropOverlay(
                                bitmapW = bmp.width,
                                bitmapH = bmp.height,
                                ratioAspect = params.crop.ratio.aspect,
                                customLeft = params.crop.customLeft,
                                customTop = params.crop.customTop,
                                customRight = params.crop.customRight,
                                customBottom = params.crop.customBottom,
                                freeEditable = params.crop.ratio == CropRatio.FREE,
                                onBeginEdit = { vm.beginCustomCropEdit() },
                                onRectChange = { l, t, r, b -> vm.moveCustomCropLive(l, t, r, b) },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                    if (activeTool == EditorTool.MASK && !effectiveOriginal &&
                        showMaskOverlay && params.masks.isNotEmpty()
                    ) {
                        val bmp = previewBitmap
                        if (bmp != null) {
                            MaskOverlay(
                                bitmapW = bmp.width,
                                bitmapH = bmp.height,
                                masks = params.masks,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                    if (!effectiveOriginal && showPointAffected) {
                        // Read + recycle-check hoisted out: try/catch is illegal
                        // around @Composable invocations (M6 CI fix).
                        val pcm = pointColorMask
                        val pcmReady = pcm != null && runCatching { !pcm.isRecycled }.getOrDefault(false)
                        if (pcmReady && pcm != null) {
                            Image(
                                bitmap = pcm.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Fit,
                                alpha = 0.6f
                            )
                        }
                    }
                }

                // Phase 4B: cancellable large-image indicator over the preview area,
                // shown while the large-source decode job runs after project load.
                if (largeDecoding) {
                    Card(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(8.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = LuminaSurfaceContainerLow
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            Text(
                                "Processing large image…",
                                style = LuminaCaptionTextStyle,
                                color = LuminaMuted
                            )
                            TextButton(
                                onClick = { vm.cancelLoad() },
                                modifier = Modifier.heightIn(min = 48.dp)
                            ) { Text("Cancel") }
                        }
                    }
                }

                if (showHistogram && histogram != null) {
                    Card(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .size(width = 140.dp, height = 104.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = LuminaSurfaceContainerHigh
                        )
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Text(
                                text = "Histogram",
                                style = LuminaCaptionTextStyle,
                                color = LuminaMuted
                            )
                            HistogramChart(
                                bins = histogram!!,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                            )
                            Text(
                                text = "R G B",
                                style = LuminaCaptionTextStyle,
                                color = LuminaMuted
                            )
                        }
                    }
                }

                if (zoomWarning != null && scale > 1.25f) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "Full detail unavailable on this device — showing preview quality.",
                                style = LuminaCaptionTextStyle,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            IconButton(
                                onClick = { vm.dismissZoomWarning() },
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = "Dismiss",
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }

                val zt2 = zoomTile
                val showEnhancing = zoomEnhancing && zoomWarning == null && scale > 1.25f &&
                    !effectiveOriginal && (zt2 == null || zt2.revision != paramsRevision)
                if (showEnhancing) {
                    Surface(
                        onClick = {},
                        enabled = false,
                        shape = RoundedCornerShape(50),
                        color = LuminaSurfaceContainerLow,
                        contentColor = LuminaOnSurface,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(8.dp)
                    ) {
                        Box(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Enhancing…",
                                style = LuminaCaptionTextStyle,
                                color = LuminaMuted
                            )
                        }
                    }
                }

                if (typeBadgeVisible) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(8.dp)
                            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                            .border(
                                width = 1.dp,
                                color = Color.White,
                                shape = RoundedCornerShape(8.dp)
                            )
                            .background(
                                color = Color.Black.copy(alpha = 0.45f),
                                shape = RoundedCornerShape(8.dp)
                            )
                            .clickable {
                                typeBadgeVisible = true
                                showImageDetails = true
                            }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = typeBadgeLabel,
                            style = LuminaCaptionTextStyle.copy(
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold
                            )
                        )
                    }
                }
                if (showImageDetails) {
                    Card(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(start = 16.dp, end = 16.dp, top = 64.dp)
                            .fillMaxWidth()
                            .heightIn(max = 420.dp),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = LuminaSurfaceContainerHigh
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .verticalScroll(rememberScrollState())
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Photo details",
                                    style = LuminaSectionHeaderTextStyle,
                                    color = LuminaOnSurface,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(
                                    onClick = { showImageDetails = false },
                                    modifier = Modifier.size(48.dp)
                                ) {
                                    Icon(
                                        Icons.Filled.Close,
                                        contentDescription = "Close details",
                                        tint = LuminaOnSurface,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                            HorizontalDivider(color = LuminaMuted.copy(alpha = 0.35f))
                            DetailRow("Name", current.name)
                            DetailRow(
                                "Type",
                                buildString {
                                    append(typeBadgeLabel)
                                    current.mimeType?.takeIf { it.isNotBlank() }?.let { append(" • $it") }
                                }
                            )
                            DetailRow(
                                "Resolution",
                                if (current.width > 0 && current.height > 0) {
                                    "${current.width} × ${current.height}"
                                } else {
                                    "–"
                                }
                            )
                            DetailRow(
                                "Color space",
                                "sRGB (assumed input; P3 converted on export when selected)"
                            )
                            DetailRow(
                                "File size",
                                detailsFile?.let { Exporter.formatBytes(it.length()) } ?: "–"
                            )
                            DetailRow(
                                "Modified",
                                detailsFile?.let { detailsDateFmt.format(java.util.Date(it.lastModified())) } ?: "–"
                            )
                            val exif = detailsExif
                            if (exif != null && !exif.isEmpty) {
                                exif.cameraModel?.let { DetailRow("Camera", it) }
                                exif.lensModel?.let { DetailRow("Lens", it) }
                                exif.iso?.let { DetailRow("ISO", it) }
                                exif.shutter?.let { DetailRow("Shutter", it) }
                                exif.aperture?.let { DetailRow("Aperture", it) }
                                exif.focalLength?.let { DetailRow("Focal length", it) }
                                exif.bitsPerSample?.let { DetailRow("Bit depth", it) }
                                if (exif.hasGps) {
                                    DetailRow("Location", "Present in source")
                                }
                            }
                            DetailRow("Preset", current.presetName ?: "No preset")
                            DetailRow(
                                "Last edited",
                                detailsDateFmt.format(java.util.Date(current.updatedAt))
                            )
                        }
                    }
                }

                Surface(
                    onClick = {},
                    enabled = false,
                    shape = RoundedCornerShape(50),
                    color = LuminaSurfaceContainerLow,
                    contentColor = LuminaOnSurface,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(8.dp)
                        .heightIn(min = 48.dp)
                ) {                    Box(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "${(scale * 100).toInt()}%",
                            style = LuminaCaptionTextStyle,
                            color = LuminaOnSurface
                        )
                    }
                }

            }

            if (!fullscreen) {
                Surface(
                    color = LuminaSurfaceContainerLow.copy(alpha = 0.60f),
                    contentColor = LuminaOnSurface,
                    shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 420.dp)
                                .verticalScroll(
                                    rememberScrollState(),
                                    enabled = !curvesDragging && activeTool != EditorTool.CURVES
                                )
                        ) {
                    // Phase 4B: editor tool transitions honor the animations toggle —
                    // a short fade when ON, an instant switch when OFF (audit found no
                    // other animate* call sites in the app; this is the gated site).
                    val toolPanels: @Composable (EditorTool) -> Unit = { tool ->
                    if (tool == EditorTool.ADJUST) {
                        EditorToolPanel(
                            tool = EditorTool.ADJUST,
                            params = params,
                            onControl = { control, value -> vm.updateControl(control, value) },
                            onResetControl = { vm.resetControl(it) },
                            onAuto = { vm.autoLight() }
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(
                                onClick = { vm.resetAll() },
                                modifier = Modifier.heightIn(min = 48.dp)
                            ) { Text("Reset all") }
                        }
                    } else if (tool == EditorTool.PRESETS) {
                        EditorToolPanel(
                            tool = EditorTool.PRESETS,
                            params = params,
                            onControl = { control, value -> vm.updateControl(control, value) },
                            onResetControl = { vm.resetControl(it) },
                            presetSource = preview,
                            onApplyPreset = { presetId -> vm.applyPreset(presetId) },
                            onPresetIntensity = { vm.setPresetIntensity(it) },
                            onClearPreset = { vm.clearPreset() },
                            onOpenLibrary = { navController.navigate(Routes.PRESETS) }
                        )
                    } else if (tool == EditorTool.COLOR) {
                        ColorToolPanel(
                            params = params,
                            selected = selectedHsl,
                            eyedropperArmed = eyedropperArmed,
                            onSelectColor = { vm.selectHslColor(it) },
                            onToggleEyedropper = { vm.setEyedropperArmed(it) },
                            onHue = { color, v -> vm.updateHslHue(color, v) },
                            onSat = { color, v -> vm.updateHslSat(color, v) },
                            onLum = { color, v -> vm.updateHslLum(color, v) },
                            onResetColor = { vm.resetHslColor(it) },
                            onGlobalSat = { vm.updateGlobalSat(it) },
                            onGlobalVib = { vm.updateGlobalVib(it) },
                            onResetGlobalSat = { vm.updateGlobalSat(0f) },
                            onResetGlobalVib = { vm.updateGlobalVib(0f) },
                            onResetAll = { vm.resetHslAll() },
                            point = params.pointColor,
                            pointEyedropperArmed = pointEyedropperArmed,
                            onPointEnabled = { vm.setPointEnabled(it) },
                            onPointPickToggle = { vm.setPointEyedropperArmed(it) },
                            onPointHue = { vm.updatePointHueCenter(it) },
                            onPointRange = { vm.updatePointHueRange(it) },
                            onPointSat = { vm.updatePointSat(it) },
                            onPointLum = { vm.updatePointLum(it) },
                            onResetPoint = { vm.resetPointColor() },
                            showPointAffected = showPointAffected,
                            onTogglePointAffected = { vm.setShowPointAffected(it) }
                        )
                    } else if (tool == EditorTool.GRADE) {
                        GradeToolPanel(
                            params = params,
                            selected = selectedGradeZone,
                            onSelectZone = { vm.selectGradeZone(it) },
                            onBeginDrag = { vm.beginGradeEdit() },
                            onLiveZone = { zone, adj -> vm.moveGradeZoneLive(zone, adj) },
                            onSetZone = { zone, adj -> vm.updateGradeZone(zone, adj) },
                            onResetZone = { vm.resetGradeZone(it) },
                            onBlending = { vm.updateGradeBlending(it) },
                            onBalance = { vm.updateGradeBalance(it) },
                            onResetAll = { vm.resetGradeAll() }
                        )
                    } else if (tool == EditorTool.CURVES) {
                        CurvesToolPanel(
                            params = params,
                            histogram = histogram,
                            selectedChannel = selectedCurve,
                            hapticEnabled = hapticEnabled,
                            onSelectChannel = { vm.selectCurveChannel(it) },
                            onBeginDrag = { vm.beginCurveEdit() },
                            onLivePoints = { channel, pts -> vm.moveCurvePointLive(channel, pts) },
                            onSetPoints = { channel, pts -> vm.setCurvePoints(channel, pts, true) },
                            onResetChannel = { vm.resetCurve(it) },
                            onResetAll = { vm.resetCurvesAll() },
                            onDraggingChange = { curvesDragging = it }
                        )
                    } else if (tool == EditorTool.DETAILS) {
                        DetailsToolPanel(
                            params = params,
                            onDetail = { control, v -> vm.updateDetail(control, v) },
                            onResetDetail = { vm.resetDetail(it) },
                            onResetAll = { vm.resetDetailsAll() }
                        )
                    } else if (tool == EditorTool.CROP) {
                        CropToolPanel(
                            params = params,
                            onRatio = { vm.setCropRatio(it) },
                            onRotate = { vm.rotateCrop90() },
                            onStraighten = { vm.setStraighten(it) },
                            onFlipH = { vm.toggleFlipH() },
                            onFlipV = { vm.toggleFlipV() },
                            onReset = { vm.resetCrop() },
                            onPerspectiveV = { vm.setPerspectiveV(it) },
                            onPerspectiveH = { vm.setPerspectiveH(it) },
                            onCustomAspect = { w, h -> vm.setCustomAspect(w, h) },
                            onVignette = { vm.setVignetteCorr(it) },
                            onCa = { vm.setCaShift(it) },
                            onDistortion = { vm.setDistortion(it) },
                            onResetOptics = { vm.resetOptics() }
                        )
                    } else if (tool == EditorTool.MASK) {
                        MaskToolPanel(
                            params = params,
                            selectedMaskId = selectedMaskId,
                            showOverlay = showMaskOverlay,
                            onAddMask = { vm.addMask(it) },
                            onSelectMask = { vm.selectMask(it) },
                            onRemoveMask = { vm.removeMask(it) },
                            onToggleVisible = { vm.toggleMaskVisible(it) },
                            onSize = { id, v -> vm.setMaskSize(id, v) },
                            onFeather = { id, v -> vm.setMaskFeather(id, v) },
                            onOpacity = { id, v -> vm.setMaskOpacity(id, v) },
                            onInvert = { id, v -> vm.setMaskInverted(id, v) },
                            onCenter = { id, x, y -> vm.setMaskCenter(id, x, y) },
                            onRadius = { id, v -> vm.setMaskRadius(id, v) },
                            onAngle = { id, v -> vm.setMaskAngle(id, v) },
                            onPosition = { id, v -> vm.setMaskPosition(id, v) },
                            onExposure = { id, v -> vm.setMaskExposure(id, v) },
                            onTemperature = { id, v -> vm.setMaskTemperature(id, v) },
                            onToggleOverlay = { vm.setShowMaskOverlay(it) },
                            onResetAll = { vm.resetMasks() },
                            onOp = { id, op -> vm.setMaskOp(id, op) },
                            onSaturation = { id, v -> vm.setMaskSaturation(id, v) },
                            onClarity = { id, v -> vm.setMaskClarity(id, v) },
                            onBlur = { id, v -> vm.setMaskBlur(id, v) },
                            onHueCenter = { id, v -> vm.setMaskHueCenter(id, v) },
                            onHueRange = { id, v -> vm.setMaskHueRange(id, v) },
                            onLumaLo = { id, v -> vm.setMaskLumaLo(id, v) },
                            onLumaHi = { id, v -> vm.setMaskLumaHi(id, v) },
                            onLumaFeather = { id, v -> vm.setMaskLumaFeather(id, v) },
                            maskSampleArmedId = maskSampleArmedId,
                            onArmSample = { vm.armMaskSample(it) }
                        )
                    } else {
                        EditorToolPanel(tool = tool)
                    }
                    }
                    if (animationsEnabled) {
                        AnimatedContent(
                            targetState = activeTool,
                            transitionSpec = {
                                LuminaMotion.shortFadeIn() togetherWith
                                    LuminaMotion.shortFadeOut()
                            },
                            label = "editorTool"
                        ) { tool ->
                            toolPanels(tool)
                        }
                    } else {
                        toolPanels(activeTool)
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                        }
                        HorizontalDivider(color = Color.White.copy(alpha = 0.12f))
                        EditorBottomToolbar(
                            activeTool = activeTool,
                            onSelect = { vm.setActiveTool(it) }
                        )
                    }
                }
            }
        }
    }

    if (showRename) {
        AlertDialog(
            onDismissRequest = { showRename = false },
            title = {
                Text(
                    "Rename project",
                    style = LuminaCaptionTextStyle,
                    color = LuminaOnSurface
                )
            },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.rename(renameText)
                        showRename = false
                    },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(
                    onClick = { showRename = false },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) { Text("Cancel") }
            },
            shape = RoundedCornerShape(28.dp),
            containerColor = LuminaSurfaceContainerLow,
            titleContentColor = LuminaOnSurface,
            textContentColor = LuminaMuted
        )
    }
}

@Composable
private fun EditorBottomToolbar(
    activeTool: com.lumina.studio.ui.editor.EditorTool,
    onSelect: (com.lumina.studio.ui.editor.EditorTool) -> Unit,
    modifier: Modifier = Modifier
) {
    val pillShape = RoundedCornerShape(50)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        com.lumina.studio.ui.editor.EditorTool.entries.forEach { tool ->
            val selected = tool == activeTool
            val pillAlpha by animateFloatAsState(
                targetValue = if (selected) 1f else 0f,
                animationSpec = LuminaMotion.shortTween(),
                label = "toolPill_$tool"
            )
            Surface(
                onClick = { onSelect(tool) },
                shape = pillShape,
                color = LuminaSurfaceContainerHigh.copy(
                    alpha = 0.6f + 0.4f * (1f - pillAlpha)
                ),
                contentColor = if (selected) LuminaOnSurface else LuminaMuted,
                tonalElevation = 0.dp,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .background(
                        color = LuminaAmber.copy(alpha = 0.16f * pillAlpha),
                        shape = pillShape
                    )
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Icon(
                        imageVector = editorToolIcon(tool),
                        contentDescription = null,
                        tint = if (selected) LuminaOnSurface else LuminaMuted,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = tool.title,
                        style = LuminaCaptionTextStyle,
                        color = if (selected) LuminaOnSurface else LuminaMuted,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
private fun editorToolIcon(tool: com.lumina.studio.ui.editor.EditorTool): androidx.compose.ui.graphics.vector.ImageVector {
    return when (tool) {
        com.lumina.studio.ui.editor.EditorTool.PRESETS -> Icons.Filled.AutoAwesome
        com.lumina.studio.ui.editor.EditorTool.ADJUST -> Icons.Filled.Tune
        com.lumina.studio.ui.editor.EditorTool.COLOR -> Icons.Filled.Palette
        com.lumina.studio.ui.editor.EditorTool.GRADE -> Icons.Filled.ColorLens
        com.lumina.studio.ui.editor.EditorTool.CURVES -> Icons.Filled.ShowChart
        com.lumina.studio.ui.editor.EditorTool.DETAILS -> Icons.Filled.Grain
        com.lumina.studio.ui.editor.EditorTool.CROP -> Icons.Filled.Crop
        com.lumina.studio.ui.editor.EditorTool.MASK -> Icons.Filled.Brush
    }
}

private enum class CropDragMode {
    NONE, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT,
    LEFT, RIGHT, TOP, BOTTOM, BODY
}

@Composable
private fun CropOverlay(
    bitmapW: Int,
    bitmapH: Int,
    ratioAspect: Float?,
    customLeft: Float = 0f,
    customTop: Float = 0f,
    customRight: Float = 1f,
    customBottom: Float = 1f,
    freeEditable: Boolean = false,
    onBeginEdit: () -> Unit = {},
    onRectChange: (Float, Float, Float, Float) -> Unit = { _, _, _, _ -> },
    modifier: Modifier = Modifier
) {
    // Fixed ratios keep the centered-rect display as a read-only guide (no
    // handles, no drag); only FREE is interactive. A drag starting on the
    // rect wins over pan/zoom (down is consumed); drags starting outside
    // fall through untouched, and a second finger aborts the crop drag so
    // pinch-zoom keeps working. Deltas are applied incrementally in rect
    // fractions (dx / drawnW), so handles track the finger 1:1 and the VM
    // live-update (state + overlay immediately, persist debounced) mirrors
    // the curve-drag mutators: one undo push on drag start, none per move.
    val latestRect = rememberUpdatedState(floatArrayOf(customLeft, customTop, customRight, customBottom))
    val latestChange = rememberUpdatedState(onRectChange)
    val latestBegin = rememberUpdatedState(onBeginEdit)
    val touchHalfPx = with(LocalDensity.current) { 14.dp.toPx() }
    val dragModifier = if (freeEditable) {
        Modifier.pointerInput(bitmapW, bitmapH) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val vw = size.width.toFloat()
                val vh = size.height.toFloat()
                if (bitmapW <= 0 || bitmapH <= 0 || vw <= 0f || vh <= 0f) {
                    return@awaitEachGesture
                }
                val fit = minOf(vw / bitmapW.toFloat(), vh / bitmapH.toFloat())
                if (fit <= 0f || !fit.isFinite()) return@awaitEachGesture
                val drawnW = bitmapW * fit
                val drawnH = bitmapH * fit
                val imgLeft = (vw - drawnW) / 2f
                val imgTop = (vh - drawnH) / 2f
                val cur = latestRect.value
                val boxL = imgLeft + cur[0] * drawnW
                val boxT = imgTop + cur[1] * drawnH
                val boxR = imgLeft + cur[2] * drawnW
                val boxB = imgTop + cur[3] * drawnH
                val mode = cropHitTest(down.position, boxL, boxT, boxR, boxB, touchHalfPx)
                if (mode == CropDragMode.NONE) return@awaitEachGesture
                down.consume()
                latestBegin.value.invoke()
                var live = cur.copyOf()
                val slop = awaitTouchSlopOrCancellation(down.id) { change, over ->
                    if (change.id != down.id) return@awaitTouchSlopOrCancellation
                    change.consume()
                    live = cropApplyDelta(
                        live, over.x / drawnW, over.y / drawnH, mode, drawnW, drawnH
                    )
                    latestChange.value.invoke(live[0], live[1], live[2], live[3])
                } ?: return@awaitEachGesture
                if (slop.id != down.id) return@awaitEachGesture
                while (true) {
                    val event = awaitPointerEvent()
                    if (event.changes.any { it.id != down.id && it.pressed }) {
                        return@awaitEachGesture
                    }
                    val change = event.changes.firstOrNull { it.id == down.id }
                        ?: return@awaitEachGesture
                    if (!change.pressed) return@awaitEachGesture
                    val delta = change.positionChange()
                    if (delta != Offset.Zero) {
                        change.consume()
                        live = cropApplyDelta(
                            live, delta.x / drawnW, delta.y / drawnH, mode, drawnW, drawnH
                        )
                        latestChange.value.invoke(live[0], live[1], live[2], live[3])
                    }
                    if (event.changes.all { !it.pressed }) return@awaitEachGesture
                }
            }
        }
    } else {
        Modifier
    }
    Canvas(modifier = modifier.then(dragModifier)) {
        if (bitmapW <= 0 || bitmapH <= 0) return@Canvas
        if (size.width <= 0f || size.height <= 0f) return@Canvas
        val fitScale = minOf(size.width / bitmapW.toFloat(), size.height / bitmapH.toFloat())
        if (fitScale <= 0f || !fitScale.isFinite()) return@Canvas
        val drawnW = bitmapW * fitScale
        val drawnH = bitmapH * fitScale
        val left = (size.width - drawnW) / 2f
        val top = (size.height - drawnH) / 2f
        var rectLeft = left
        var rectTop = top
        var rectRight = left + drawnW
        var rectBottom = top + drawnH
        if (freeEditable) {
            // Same fraction->pixel formula cropBitmap uses (CropRects),
            // evaluated in float screen pixels.
            rectLeft = left + customLeft.coerceIn(0f, 1f) * drawnW
            rectTop = top + customTop.coerceIn(0f, 1f) * drawnH
            rectRight = left + customRight.coerceIn(0f, 1f) * drawnW
            rectBottom = top + customBottom.coerceIn(0f, 1f) * drawnH
        } else if (ratioAspect != null && ratioAspect > 0f) {
            val drawnAspect = drawnW / drawnH.coerceAtLeast(1f)
            if (kotlin.math.abs(drawnAspect - ratioAspect) > 0.001f) {
                if (drawnAspect > ratioAspect) {
                    val cropW = drawnH * ratioAspect
                    val cx = left + drawnW / 2f
                    rectLeft = cx - cropW / 2f
                    rectRight = cx + cropW / 2f
                } else {
                    val cropH = drawnW / ratioAspect
                    val cy = top + drawnH / 2f
                    rectTop = cy - cropH / 2f
                    rectBottom = cy + cropH / 2f
                }
            }
        }
        val dim = Color.Black.copy(alpha = 0.55f)
        if (rectTop > 0f) drawRect(dim, topLeft = Offset(0f, 0f), size = androidx.compose.ui.geometry.Size(size.width, rectTop))
        val bottomTop = rectBottom.coerceAtMost(size.height)
        if (bottomTop < size.height) {
            drawRect(
                dim,
                topLeft = Offset(0f, bottomTop),
                size = androidx.compose.ui.geometry.Size(size.width, size.height - bottomTop)
            )
        }
        val midTop = rectTop.coerceAtLeast(0f)
        val midBottom = rectBottom.coerceAtMost(size.height)
        if (midBottom > midTop) {
            if (rectLeft > 0f) {
                drawRect(
                    dim,
                    topLeft = Offset(0f, midTop),
                    size = androidx.compose.ui.geometry.Size(rectLeft.coerceAtMost(size.width), midBottom - midTop)
                )
            }
            if (rectRight < size.width) {
                drawRect(
                    dim,
                    topLeft = Offset(rectRight.coerceIn(0f, size.width), midTop),
                    size = androidx.compose.ui.geometry.Size((size.width - rectRight).coerceAtLeast(0f), midBottom - midTop)
                )
            }
        }
        drawRect(
            color = Color.White.copy(alpha = 0.9f),
            topLeft = Offset(rectLeft, rectTop),
            size = androidx.compose.ui.geometry.Size((rectRight - rectLeft).coerceAtLeast(0f), (rectBottom - rectTop).coerceAtLeast(0f)),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f)
        )
        if (freeEditable) {
            val outline = Color.Black.copy(alpha = 0.6f)
            val cornerR = 8.dp.toPx()
            val edgeR = 6.dp.toPx()
            val corners = arrayOf(
                Offset(rectLeft, rectTop),
                Offset(rectRight, rectTop),
                Offset(rectLeft, rectBottom),
                Offset(rectRight, rectBottom)
            )
            for (c in corners) {
                drawCircle(outline, radius = cornerR + 1.5f, center = c)
                drawCircle(Color.White, radius = cornerR, center = c)
            }
            val edges = arrayOf(
                Offset((rectLeft + rectRight) / 2f, rectTop),
                Offset((rectLeft + rectRight) / 2f, rectBottom),
                Offset(rectLeft, (rectTop + rectBottom) / 2f),
                Offset(rectRight, (rectTop + rectBottom) / 2f)
            )
            for (e in edges) {
                drawCircle(outline, radius = edgeR + 1.5f, center = e)
                drawCircle(Color.White, radius = edgeR, center = e)
            }
        }
    }
}

private fun cropHitTest(
    pos: Offset,
    boxL: Float,
    boxT: Float,
    boxR: Float,
    boxB: Float,
    half: Float
): CropDragMode {
    fun nearCorner(cx: Float, cy: Float): Boolean {
        val dx = pos.x - cx
        val dy = pos.y - cy
        return dx * dx + dy * dy <= half * half
    }
    if (nearCorner(boxL, boxT)) return CropDragMode.TOP_LEFT
    if (nearCorner(boxR, boxT)) return CropDragMode.TOP_RIGHT
    if (nearCorner(boxL, boxB)) return CropDragMode.BOTTOM_LEFT
    if (nearCorner(boxR, boxB)) return CropDragMode.BOTTOM_RIGHT
    val inY = pos.y >= boxT - half && pos.y <= boxB + half
    val inX = pos.x >= boxL - half && pos.x <= boxR + half
    if (kotlin.math.abs(pos.x - boxL) <= half && inY) return CropDragMode.LEFT
    if (kotlin.math.abs(pos.x - boxR) <= half && inY) return CropDragMode.RIGHT
    if (kotlin.math.abs(pos.y - boxT) <= half && inX) return CropDragMode.TOP
    if (kotlin.math.abs(pos.y - boxB) <= half && inX) return CropDragMode.BOTTOM
    if (pos.x >= boxL && pos.x <= boxR && pos.y >= boxT && pos.y <= boxB) return CropDragMode.BODY
    return CropDragMode.NONE
}

private fun cropApplyDelta(
    rect: FloatArray,
    dx: Float,
    dy: Float,
    mode: CropDragMode,
    drawnW: Float,
    drawnH: Float
): FloatArray {
    if (drawnW <= 0f || drawnH <= 0f) return rect
    if (!dx.isFinite() || !dy.isFinite()) return rect
    val min = CropRects.MIN_SIZE
    var l = rect[0]
    var t = rect[1]
    var r = rect[2]
    var b = rect[3]
    when (mode) {
        CropDragMode.TOP_LEFT -> {
            l = (l + dx).coerceIn(0f, r - min)
            t = (t + dy).coerceIn(0f, b - min)
        }
        CropDragMode.TOP_RIGHT -> {
            r = (r + dx).coerceIn(l + min, 1f)
            t = (t + dy).coerceIn(0f, b - min)
        }
        CropDragMode.BOTTOM_LEFT -> {
            l = (l + dx).coerceIn(0f, r - min)
            b = (b + dy).coerceIn(t + min, 1f)
        }
        CropDragMode.BOTTOM_RIGHT -> {
            r = (r + dx).coerceIn(l + min, 1f)
            b = (b + dy).coerceIn(t + min, 1f)
        }
        CropDragMode.LEFT -> l = (l + dx).coerceIn(0f, r - min)
        CropDragMode.RIGHT -> r = (r + dx).coerceIn(l + min, 1f)
        CropDragMode.TOP -> t = (t + dy).coerceIn(0f, b - min)
        CropDragMode.BOTTOM -> b = (b + dy).coerceIn(t + min, 1f)
        CropDragMode.BODY -> {
            val w = r - l
            val h = b - t
            l = (l + dx).coerceIn(0f, 1f - w)
            t = (t + dy).coerceIn(0f, 1f - h)
            r = l + w
            b = t + h
        }
        CropDragMode.NONE -> return rect
    }
    return floatArrayOf(l, t, r, b)
}

@Composable
private fun MaskOverlay(
    bitmapW: Int,
    bitmapH: Int,
    masks: List<com.lumina.studio.core.edit.EditMask>,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        if (bitmapW <= 0 || bitmapH <= 0) return@Canvas
        if (size.width <= 0f || size.height <= 0f) return@Canvas
        val fitScale = minOf(size.width / bitmapW.toFloat(), size.height / bitmapH.toFloat())
        if (fitScale <= 0f || !fitScale.isFinite()) return@Canvas
        val drawnW = bitmapW * fitScale
        val drawnH = bitmapH * fitScale
        val left = (size.width - drawnW) / 2f
        val top = (size.height - drawnH) / 2f
        val red = Color.Red.copy(alpha = 0.35f)
        val redEdge = Color.Red.copy(alpha = 0.8f)
        for (mask in masks) {
            if (!mask.visible || mask.opacity <= 0.001f) continue
            when (mask.tool) {
                com.lumina.studio.core.edit.MaskTool.RADIAL -> {
                    val cx = left + mask.centerX.coerceIn(0f, 1f) * drawnW
                    val cy = top + mask.centerY.coerceIn(0f, 1f) * drawnH
                    val r = mask.radius.coerceIn(0.01f, 1f) * minOf(drawnW, drawnH)
                    drawCircle(red, radius = r, center = Offset(cx, cy))
                    drawCircle(redEdge, radius = r, center = Offset(cx, cy), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f))
                }
                com.lumina.studio.core.edit.MaskTool.LINEAR -> {
                    val rad = mask.angleDeg * Math.PI.toFloat() / 180f
                    val cosA = kotlin.math.cos(rad)
                    val sinA = kotlin.math.sin(rad)
                    val px = left + drawnW / 2f + cosA * (mask.position - 0.5f) * drawnW
                    val py = top + drawnH / 2f + sinA * (mask.position - 0.5f) * drawnH
                    val nx = -sinA
                    val ny = cosA
                    val half = maxOf(drawnW, drawnH)
                    drawLine(
                        redEdge,
                        Offset(px - nx * half, py - ny * half),
                        Offset(px + nx * half, py + ny * half),
                        strokeWidth = 3f
                    )
                    drawRect(red, topLeft = Offset(left, top), size = androidx.compose.ui.geometry.Size(drawnW, drawnH))
                }
                com.lumina.studio.core.edit.MaskTool.BRUSH, com.lumina.studio.core.edit.MaskTool.ERASER -> {
                    val radius = (mask.sizePx * 0.5f * (minOf(bitmapW, bitmapH).toFloat() / 1000f) * fitScale).coerceAtLeast(4f)
                    val pts = mask.points
                    if (pts.isEmpty()) continue
                    if (pts.size == 1) {
                        val cx = left + pts[0].x * drawnW
                        val cy = top + pts[0].y * drawnH
                        drawCircle(red, radius = radius, center = Offset(cx, cy))
                    } else {
                        for (i in 0 until pts.size - 1) {
                            val ax = left + pts[i].x * drawnW
                            val ay = top + pts[i].y * drawnH
                            val bx = left + pts[i + 1].x * drawnW
                            val by = top + pts[i + 1].y * drawnH
                            drawLine(red, Offset(ax, ay), Offset(bx, by), strokeWidth = radius * 2f)
                            drawCircle(red, radius = radius, center = Offset(ax, ay))
                            if (i == pts.size - 2) drawCircle(red, radius = radius, center = Offset(bx, by))
                        }
                    }
                }
                com.lumina.studio.core.edit.MaskTool.COLOR, com.lumina.studio.core.edit.MaskTool.LUMINANCE -> {
                    // Range masks select by color/luma across the frame: show a
                    // full-frame tint (per-pixel weights are not drawn here).
                    drawRect(red, topLeft = Offset(left, top), size = androidx.compose.ui.geometry.Size(drawnW, drawnH))
                    drawRect(
                        redEdge,
                        topLeft = Offset(left, top),
                        size = androidx.compose.ui.geometry.Size(drawnW, drawnH),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f)
                    )
                }
            }
        }
    }
}

@Composable
private fun HistogramChart(bins: Array<IntArray>, modifier: Modifier = Modifier) {
    val colors = listOf(Color.Red, Color.Green, Color.Blue)
    Canvas(modifier = modifier.fillMaxSize().padding(4.dp)) {
        val count = bins.firstOrNull()?.size ?: 0
        if (count == 0) return@Canvas
        val peak = bins.flatMap { it.asList() }.maxOrNull()?.coerceAtLeast(1) ?: 1
        val stepX = size.width / count.toFloat()
        for (channel in 0..2) {
            val data = bins[channel]
            var prev: Offset? = null
            for (i in data.indices) {
                val x = i * stepX
                val y = size.height - (data[i] / peak.toFloat()) * size.height
                val point = Offset(x, y)
                prev?.let { drawLine(colors[channel], it, point, strokeWidth = 2f) }
                prev = point
            }
        }
    }
}

private fun clampEditorOffset(
    offset: Offset,
    scale: Float,
    viewportSize: IntSize,
    bitmapW: Int,
    bitmapH: Int
): Offset {
    if (scale <= 1.01f) return Offset.Zero
    val vw = viewportSize.width.toFloat()
    val vh = viewportSize.height.toFloat()
    if (vw <= 0f || vh <= 0f || bitmapW <= 0 || bitmapH <= 0) return Offset.Zero
    val fitScale = minOf(vw / bitmapW.toFloat(), vh / bitmapH.toFloat())
    if (fitScale <= 0f || !fitScale.isFinite()) return Offset.Zero
    val drawnW = bitmapW * fitScale
    val drawnH = bitmapH * fitScale
    val maxX = maxOf(0f, (drawnW * scale - vw) / 2f)
    val maxY = maxOf(0f, (drawnH * scale - vh) / 2f)
    return Offset(
        offset.x.coerceIn(-maxX, maxX),
        offset.y.coerceIn(-maxY, maxY)
    )
}

private fun zoomVisibleFractions(
    viewportW: Int,
    viewportH: Int,
    bitmapW: Int,
    bitmapH: Int,
    scale: Float,
    offset: Offset
): androidx.compose.ui.geometry.Rect? {
    if (viewportW <= 0 || viewportH <= 0 || bitmapW <= 0 || bitmapH <= 0) return null
    if (scale <= 0f || !scale.isFinite()) return null
    val vw = viewportW.toFloat()
    val vh = viewportH.toFloat()
    val fitScale = minOf(vw / bitmapW.toFloat(), vh / bitmapH.toFloat())
    if (fitScale <= 0f || !fitScale.isFinite()) return null
    val drawnW = bitmapW * fitScale
    val drawnH = bitmapH * fitScale
    val imgLeft = (vw - drawnW) / 2f
    val imgTop = (vh - drawnH) / 2f
    val cx = vw / 2f
    val cy = vh / 2f
    val innerLeft = cx + (0f - cx - offset.x) / scale
    val innerTop = cy + (0f - cy - offset.y) / scale
    val innerRight = cx + (vw - cx - offset.x) / scale
    val innerBottom = cy + (vh - cy - offset.y) / scale
    val visLeft = maxOf(innerLeft, imgLeft)
    val visTop = maxOf(innerTop, imgTop)
    val visRight = minOf(innerRight, imgLeft + drawnW)
    val visBottom = minOf(innerBottom, imgTop + drawnH)
    if (visRight <= visLeft || visBottom <= visTop) return null
    val l = ((visLeft - imgLeft) / drawnW).coerceIn(0f, 1f)
    val t = ((visTop - imgTop) / drawnH).coerceIn(0f, 1f)
    val r = ((visRight - imgLeft) / drawnW).coerceIn(0f, 1f)
    val b = ((visBottom - imgTop) / drawnH).coerceIn(0f, 1f)
    if (r <= l || b <= t) return null
    return androidx.compose.ui.geometry.Rect(l, t, r, b)
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = label,
            style = LuminaCaptionTextStyle,
            color = LuminaMuted,
            modifier = Modifier.width(96.dp)
        )
        Text(
            text = value,
            style = LuminaCaptionTextStyle.copy(fontWeight = FontWeight.SemiBold),
            color = LuminaOnSurface,
            modifier = Modifier.weight(1f)
        )
    }
}
