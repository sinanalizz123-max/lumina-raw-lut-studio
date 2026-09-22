package com.lumina.studio.ui.screens

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.lumina.studio.core.design.components.EmptyState
import com.lumina.studio.core.design.components.EmptyStateIllustration
import com.lumina.studio.core.batch.SettingsClipboard
import com.lumina.studio.core.design.theme.LuminaAmber
import com.lumina.studio.core.design.theme.LuminaCaptionTextStyle
import com.lumina.studio.core.design.theme.LuminaMuted
import com.lumina.studio.core.design.theme.LuminaOnSurface
import com.lumina.studio.core.design.theme.LuminaSectionHeaderTextStyle
import com.lumina.studio.core.design.theme.LuminaSurfaceContainerLow
import com.lumina.studio.core.edit.EditParams
import com.lumina.studio.core.edit.StepKey
import com.lumina.studio.navigation.Routes
import com.lumina.studio.ui.editor.EditorTool
import kotlin.math.roundToInt

private enum class HistoryStep(
    val title: String,
    val tool: EditorTool?,
    val stepKey: StepKey?
) {
    ORIGINAL("Original", null, null),
    PRESET("Preset", EditorTool.PRESETS, StepKey.PRESET),
    LUT("LUT", EditorTool.PRESETS, StepKey.LUT),
    ADJUSTMENTS("Adjustments", EditorTool.ADJUST, StepKey.ADJUSTS),
    COLOR("Color", EditorTool.COLOR, StepKey.COLOR),
    CURVES("Curves", EditorTool.CURVES, StepKey.CURVES),
    DETAILS("Details", EditorTool.DETAILS, StepKey.DETAILS),
    MASKS("Masks", EditorTool.MASK, StepKey.MASKS),
    RETOUCH("Retouch", EditorTool.RETOUCH, null),
    LENS_BLUR("Lens blur", EditorTool.BLUR, null),
    CROP("Crop", EditorTool.CROP, StepKey.CROP);

    fun isActive(params: EditParams): Boolean = when (this) {
        ORIGINAL -> true
        PRESET -> params.presetId != null
        LUT -> params.presetId != null
        ADJUSTMENTS -> !params.isAdjustsDefault()
        COLOR -> !params.isHslDefault() || !params.isGradeDefault() || !params.isPointColorDefault()
        CURVES -> !params.isCurvesDefault()
        DETAILS -> !params.isDetailsDefault()
        MASKS -> params.masks.isNotEmpty()
        RETOUCH -> params.retouch.isNotEmpty()
        LENS_BLUR -> !params.isLensBlurDefault()
        CROP -> !params.isCropDefault()
    }

    fun detail(params: EditParams): String = when (this) {
        ORIGINAL -> "Source photo"
        PRESET -> params.presetId ?: "Empty — no preset"
        LUT -> if (params.presetId == null) "Empty — no LUT" else "Intensity ${(params.presetIntensity * 100f).roundToInt()}%"
        ADJUSTMENTS -> if (params.isAdjustsDefault()) "Empty" else "Exposure ${params.exposure} • Contrast ${params.contrast.roundToInt()}"
        COLOR -> if (params.isHslDefault() && params.isGradeDefault() && params.isPointColorDefault()) "Empty" else "Color edits"
        CURVES -> if (params.isCurvesDefault()) "Empty — diagonal" else "Custom curves"
        DETAILS -> if (params.isDetailsDefault()) "Empty" else "Texture/clarity/NR"
        MASKS -> if (params.masks.isEmpty()) "Empty" else "${params.masks.size} mask(s)"
        RETOUCH -> if (params.retouch.isEmpty()) "Empty" else "${params.retouch.size} spot(s)"
        LENS_BLUR -> if (params.isLensBlurDefault()) "Empty — off" else "Amount ${params.lensBlur.amount.roundToInt()}%"
        CROP -> if (params.isCropDefault()) "Empty — free" else "${params.crop.ratio.label} • ${params.crop.rotationSteps * 90}°"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(navController: NavController) {
    val prevEntry = remember(navController) {
        try {
            navController.previousBackStackEntry
        } catch (_: Exception) {
            null
        }
    }
    val projectId = remember(prevEntry) {
        try {
            prevEntry?.savedStateHandle?.get<String>("history_project_id")?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("History") },
                navigationIcon = {
                    androidx.compose.material3.IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        if (projectId.isNullOrBlank()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                EmptyState(
                    title = "Edit history",
                    message = "Open a project in the editor or detail screen, then tap History to see its timeline.",
                    illustration = EmptyStateIllustration.Sliders
                )
                TextButton(
                    onClick = { navController.popBackStack() },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) { Text("Back") }
            }
            return@Scaffold
        }
        val prevRoute = try {
            prevEntry?.destination?.route
        } catch (_: Exception) {
            null
        }
        val isFromEditor = prevRoute?.startsWith("editor") == true && prevEntry != null
        if (isFromEditor) {
            val context = LocalContext.current
            val app = context.applicationContext as Application
            val sharedVm: EditorViewModel = viewModel(
                viewModelStoreOwner = prevEntry!!,
                key = "editor_$projectId",
                factory = EditorViewModelFactory(app, projectId)
            )
            HistoryContent(
                navController = navController,
                vm = sharedVm,
                projectId = projectId,
                isShared = true,
                modifier = Modifier.padding(padding)
            )
        } else {
            val context = LocalContext.current
            val app = context.applicationContext as Application
            val ownVm: EditorViewModel = viewModel(
                key = "history_$projectId",
                factory = EditorViewModelFactory(app, projectId)
            )
            HistoryContent(
                navController = navController,
                vm = ownVm,
                projectId = projectId,
                isShared = false,
                modifier = Modifier.padding(padding)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistoryContent(
    navController: NavController,
    vm: EditorViewModel,
    projectId: String,
    isShared: Boolean,
    modifier: Modifier = Modifier
) {
    val params by vm.params.collectAsState()
    val project by vm.project.collectAsState()
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = project?.name ?: "Project",
            style = LuminaSectionHeaderTextStyle,
            color = LuminaOnSurface
        )
        Text(
            text = "Tap a row to jump to its tool. Toggle to enable/disable a step (render skips disabled steps). Swipe left to reset a group. Changes auto-save via debounce persist (~300ms).",
            style = LuminaCaptionTextStyle,
            color = LuminaMuted
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(
                onClick = { SettingsClipboard.copy(vm.params.value) },
                modifier = Modifier.heightIn(min = 48.dp)
            ) { Text("Copy settings") }
            TextButton(
                onClick = {
                    SettingsClipboard.paste(vm.params.value)?.let { pasted ->
                        vm.applyExternalParams(pasted)
                    }
                },
                modifier = Modifier.heightIn(min = 48.dp)
            ) { Text("Paste settings") }
        }
        Text(
            text = "Copy saves every group; paste skips masks unless they were included when copying.",
            style = LuminaCaptionTextStyle,
            color = LuminaMuted
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(HistoryStep.entries, key = { it.name }) { step ->
                val active = step.isActive(params)
                val enabled = step.stepKey?.let { params.steps.get(it) } ?: true
                if (step == HistoryStep.ORIGINAL) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = LuminaSurfaceContainerLow
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    step.title,
                                    style = LuminaSectionHeaderTextStyle,
                                    color = LuminaOnSurface
                                )
                                Text(
                                    step.detail(params),
                                    style = LuminaCaptionTextStyle,
                                    color = LuminaMuted
                                )
                            }
                            Text(
                                "active",
                                style = LuminaCaptionTextStyle,
                                color = LuminaAmber
                            )
                        }
                    }
                } else {
                    val dismissState = rememberSwipeToDismissBoxState(
                        confirmValueChange = { value ->
                            if (value == SwipeToDismissBoxValue.EndToStart) {
                                resetStep(vm, step)
                            }
                            false
                        }
                    )
                    SwipeToDismissBox(
                        state = dismissState,
                        enableDismissFromStartToEnd = false,
                        enableDismissFromEndToStart = true,
                        backgroundContent = {
                            Row(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(8.dp),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Filled.Delete, contentDescription = "Reset step")
                                Text("Reset", modifier = Modifier.padding(start = 8.dp))
                            }
                        }
                    ) {
                        Card(
                            onClick = {
                                val tool = step.tool
                                if (tool != null) {
                                    if (isShared) {
                                        vm.setActiveTool(tool)
                                        navController.popBackStack()
                                    } else {
                                        try {
                                            navController.currentBackStackEntry?.savedStateHandle?.set(
                                                "history_jump_tool", tool.name
                                            )
                                        } catch (_: Exception) {
                                        }
                                        navController.navigate(Routes.editor(projectId))
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = LuminaSurfaceContainerLow
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        step.title,
                                        style = LuminaSectionHeaderTextStyle,
                                        color = LuminaOnSurface
                                    )
                                    Text(
                                        step.detail(params),
                                        style = LuminaCaptionTextStyle,
                                        color = LuminaMuted
                                    )
                                    Text(
                                        if (!enabled) "disabled — skipped in render"
                                        else if (active) "active" else "empty",
                                        style = LuminaCaptionTextStyle,
                                        color = if (!enabled) LuminaMuted
                                        else if (active) LuminaAmber
                                        else LuminaMuted
                                    )
                                }
                                val key = step.stepKey
                                if (key != null) {
                                    Switch(
                                        checked = enabled,
                                        onCheckedChange = { vm.setStepEnabled(key, it) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun resetStep(vm: EditorViewModel, step: HistoryStep) {
    when (step) {
        HistoryStep.ORIGINAL -> Unit
        HistoryStep.PRESET -> vm.resetPresetGroup()
        HistoryStep.LUT -> vm.resetLutGroup()
        HistoryStep.ADJUSTMENTS -> vm.resetAdjustsGroup()
        HistoryStep.COLOR -> vm.resetHslAll()
        HistoryStep.CURVES -> vm.resetCurvesAll()
        HistoryStep.DETAILS -> vm.resetDetailsAll()
        HistoryStep.MASKS -> vm.resetMasks()
        HistoryStep.CROP -> vm.resetCrop()
        HistoryStep.RETOUCH -> vm.resetRetouch()
        HistoryStep.LENS_BLUR -> vm.resetLensBlur()
    }
}
