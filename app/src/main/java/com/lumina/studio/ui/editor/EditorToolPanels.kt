package com.lumina.studio.ui.editor

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lumina.studio.core.data.local.Preset
import com.lumina.studio.core.design.components.AppBottomSheet
import com.lumina.studio.core.design.components.EmptyState
import com.lumina.studio.core.design.components.EmptyStateIllustration
import com.lumina.studio.core.design.components.ProSlider
import com.lumina.studio.core.design.theme.LuminaAmber
import com.lumina.studio.core.design.theme.LuminaCaptionTextStyle
import com.lumina.studio.core.design.theme.LuminaMuted
import com.lumina.studio.core.design.theme.LuminaOnSurface
import com.lumina.studio.core.design.theme.LuminaScrim
import com.lumina.studio.core.design.theme.LuminaSectionHeaderTextStyle
import com.lumina.studio.core.design.theme.LuminaSurfaceContainerHigh
import com.lumina.studio.core.design.theme.LuminaSurfaceContainerLow
import com.lumina.studio.core.edit.AdjustControl
import com.lumina.studio.core.edit.CropRatio
import com.lumina.studio.core.edit.CurveChannel
import com.lumina.studio.core.edit.DetailControl
import com.lumina.studio.core.edit.EditParams
import com.lumina.studio.core.edit.GradeAdjust
import com.lumina.studio.core.edit.GradeZone
import com.lumina.studio.core.edit.HslColor
import com.lumina.studio.core.edit.MaskTool
import com.lumina.studio.ui.presets.PresetThumb
import com.lumina.studio.ui.screens.PresetsViewModel
import kotlin.math.roundToInt

@Composable
fun EditorToolPanel(tool: EditorTool) {
    EditorToolPanel(
        tool = tool,
        params = EditParams.DEFAULT,
        onControl = { _, _ -> },
        onResetControl = {}
    )
}

@Composable
fun EditorToolPanel(
    tool: EditorTool,
    params: EditParams,
    onControl: (AdjustControl, Float) -> Unit,
    onResetControl: (AdjustControl) -> Unit,
    modifier: Modifier = Modifier,
    onAuto: () -> Unit = {}
) {
    when (tool) {
        EditorTool.PRESETS -> PresetsToolPanel(
            params = params,
            source = null,
            onApplyPreset = { _, _ -> },
            onPresetIntensity = {},
            onClearPreset = {},
            onOpenLibrary = {},
            modifier = modifier
        )
        EditorTool.ADJUST -> AdjustPanel(
            params = params,
            onControl = onControl,
            onResetControl = onResetControl,
            onAuto = onAuto,
            modifier = modifier
        )
        EditorTool.COLOR -> ColorPanel(
            params = params,
            selected = HslColor.RED,
            eyedropperArmed = false,
            onSelectColor = {},
            onToggleEyedropper = {},
            onHue = { _, _ -> },
            onSat = { _, _ -> },
            onLum = { _, _ -> },
            onResetColor = {},
            onGlobalSat = {},
            onGlobalVib = {},
            onResetGlobalSat = {},
            onResetGlobalVib = {},
            onResetAll = {},
            modifier = modifier
        )
        EditorTool.GRADE -> GradePanel(
            params = params,
            modifier = modifier
        )
        EditorTool.CURVES -> CurvesPanel(
            params = params,
            histogram = null,
            selectedChannel = CurveChannel.MASTER,
            hapticEnabled = true,
            onSelectChannel = {},
            onBeginDrag = {},
            onLivePoints = { _, _ -> },
            onSetPoints = { _, _ -> },
            onResetChannel = {},
            onResetAll = {},
            modifier = modifier
        )
        EditorTool.DETAILS -> DetailsPanel(
            params = params,
            onDetail = { _, _ -> },
            onResetDetail = {},
            onResetAll = {},
            modifier = modifier
        )
        EditorTool.CROP -> CropPanel(
            params = params,
            onRatio = {},
            onRotate = {},
            onStraighten = {},
            onFlipH = {},
            onFlipV = {},
            onReset = {},
            modifier = modifier
        )
        EditorTool.MASK -> MaskPanel(
            params = params,
            selectedMaskId = null,
            showOverlay = false,
            onAddMask = {},
            onSelectMask = {},
            onRemoveMask = {},
            onToggleVisible = {},
            onSize = { _, _ -> },
            onFeather = { _, _ -> },
            onOpacity = { _, _ -> },
            onInvert = { _, _ -> },
            onCenter = { _, _, _ -> },
            onRadius = { _, _ -> },
            onAngle = { _, _ -> },
            onPosition = { _, _ -> },
            onExposure = { _, _ -> },
            onTemperature = { _, _ -> },
            onToggleOverlay = {},
            onResetAll = {},
            modifier = modifier
        )
    }
}

@Composable
fun EditorToolPanel(
    tool: EditorTool,
    params: EditParams,
    onControl: (AdjustControl, Float) -> Unit,
    onResetControl: (AdjustControl) -> Unit,
    presetSource: Bitmap?,
    onApplyPreset: (String?) -> Unit,
    onPresetIntensity: (Float) -> Unit,
    onClearPreset: () -> Unit,
    onOpenLibrary: () -> Unit,
    modifier: Modifier = Modifier,
    onAuto: () -> Unit = {}
) {
    when (tool) {
        EditorTool.PRESETS -> PresetsToolPanel(
            params = params,
            source = presetSource,
            onApplyPreset = { id, _ -> onApplyPreset(id) },
            onPresetIntensity = onPresetIntensity,
            onClearPreset = onClearPreset,
            onOpenLibrary = onOpenLibrary,
            modifier = modifier
        )
        else -> EditorToolPanel(
            tool = tool,
            params = params,
            onControl = onControl,
            onResetControl = onResetControl,
            modifier = modifier,
            onAuto = onAuto
        )
    }
}

@Composable
fun PresetsToolPanel(
    params: EditParams,
    source: Bitmap?,
    onApplyPreset: (String?, Preset) -> Unit,
    onPresetIntensity: (Float) -> Unit,
    onClearPreset: () -> Unit,
    onOpenLibrary: () -> Unit,
    modifier: Modifier = Modifier,
    presetsViewModel: PresetsViewModel = viewModel()
) {
    val presets by presetsViewModel.presets.collectAsState()
    val presetCardShape = RoundedCornerShape(16.dp)
    var selectedPresetId by remember { mutableStateOf<String?>(null) }
    var sheetVisible by remember { mutableStateOf(false) }
    Column(
        modifier = modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Presets for this photo",
                style = LuminaSectionHeaderTextStyle,
                color = LuminaOnSurface,
                modifier = Modifier.weight(1f)
            )
            TextButton(
                onClick = onOpenLibrary,
                modifier = Modifier.heightIn(min = 48.dp)
            ) { Text("Library") }
        }
        HorizontalDivider(color = LuminaSurfaceContainerHigh)
        if (presets.isEmpty()) {
            EmptyState(
                title = "No presets yet",
                message = "Demo presets are loading, or import a .CUBE from the library.",
                illustration = EmptyStateIllustration.Palette
            )
            return@Column
        }
        val rows = presets.chunked(3)
        for (row in rows) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                for (preset in row) {
                    val selected = preset.id == params.presetId
                    Card(
                        onClick = {
                            if (preset.id == params.presetId && preset.id == selectedPresetId) {
                                sheetVisible = !sheetVisible
                            } else if (preset.id == params.presetId) {
                                selectedPresetId = preset.id
                                sheetVisible = true
                            } else {
                                onApplyPreset(preset.id, preset)
                                selectedPresetId = preset.id
                                sheetVisible = false
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .then(
                                if (selected) Modifier.border(2.dp, LuminaAmber, presetCardShape)
                                else Modifier
                            ),
                        shape = presetCardShape,
                        colors = CardDefaults.cardColors(
                            containerColor = LuminaSurfaceContainerLow
                        )
                    ) {
                        Column(modifier = Modifier.padding(6.dp)) {
                            Box(modifier = Modifier.fillMaxWidth()) {
                                PresetThumb(
                                    preset = preset,
                                    source = source,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(1f)
                                        .clip(presetCardShape)
                                )
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(4.dp)
                                        .size(48.dp)
                                        .clip(CircleShape)
                                        .background(LuminaScrim),
                                    contentAlignment = Alignment.Center
                                ) {
                                    IconButton(
                                        onClick = { presetsViewModel.toggleFavorite(preset) },
                                        modifier = Modifier.size(48.dp)
                                    ) {
                                        Icon(
                                            if (preset.isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                                            contentDescription = if (preset.isFavorite) "Unfavorite" else "Favorite",
                                            tint = if (preset.isFavorite) LuminaAmber else LuminaOnSurface
                                        )
                                    }
                                }
                            }
                            Text(
                                text = preset.name,
                                style = LuminaCaptionTextStyle,
                                color = if (selected) LuminaOnSurface else LuminaMuted,
                                maxLines = 1,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }
                repeat(3 - row.size) { Spacer(modifier = Modifier.weight(1f)) }
            }
        }
        if (params.presetId != null && sheetVisible && params.presetId == selectedPresetId) {
            AppBottomSheet(onDismiss = { sheetVisible = false }) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Preset intensity",
                        style = LuminaSectionHeaderTextStyle,
                        color = LuminaOnSurface,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                    ProSlider(
                        label = "Preset intensity",
                        value = params.presetIntensity * 100f,
                        onValueChange = { onPresetIntensity(it / 100f) },
                        valueRange = 0f..100f,
                        displayValue = "${(params.presetIntensity * 100f).roundToInt()}%"
                    )
                    TextButton(
                        onClick = {
                            onClearPreset()
                            sheetVisible = false
                            selectedPresetId = null
                        },
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) { Text("Reset preset") }
                }
            }
        }
    }
}

@Composable
fun ColorToolPanel(
    params: EditParams,
    selected: HslColor,
    eyedropperArmed: Boolean,
    onSelectColor: (HslColor) -> Unit,
    onToggleEyedropper: (Boolean) -> Unit,
    onHue: (HslColor, Float) -> Unit,
    onSat: (HslColor, Float) -> Unit,
    onLum: (HslColor, Float) -> Unit,
    onResetColor: (HslColor) -> Unit,
    onGlobalSat: (Float) -> Unit,
    onGlobalVib: (Float) -> Unit,
    onResetGlobalSat: () -> Unit,
    onResetGlobalVib: () -> Unit,
    onResetAll: () -> Unit,
    modifier: Modifier = Modifier,
    point: com.lumina.studio.core.edit.PointColorParams = params.pointColor,
    pointEyedropperArmed: Boolean = false,
    onPointEnabled: (Boolean) -> Unit = {},
    onPointPickToggle: (Boolean) -> Unit = {},
    onPointHue: (Float) -> Unit = {},
    onPointRange: (Float) -> Unit = {},
    onPointSat: (Float) -> Unit = {},
    onPointLum: (Float) -> Unit = {},
    onResetPoint: () -> Unit = {},
    showPointAffected: Boolean = false,
    onTogglePointAffected: (Boolean) -> Unit = {}
) {
    ColorPanel(
        params = params,
        selected = selected,
        eyedropperArmed = eyedropperArmed,
        onSelectColor = onSelectColor,
        onToggleEyedropper = onToggleEyedropper,
        onHue = onHue,
        onSat = onSat,
        onLum = onLum,
        onResetColor = onResetColor,
        onGlobalSat = onGlobalSat,
        onGlobalVib = onGlobalVib,
        onResetGlobalSat = onResetGlobalSat,
        onResetGlobalVib = onResetGlobalVib,
        onResetAll = onResetAll,
        modifier = modifier,
        point = point,
        pointEyedropperArmed = pointEyedropperArmed,
        onPointEnabled = onPointEnabled,
        onPointPickToggle = onPointPickToggle,
        onPointHue = onPointHue,
        onPointRange = onPointRange,
        onPointSat = onPointSat,
        onPointLum = onPointLum,
        onResetPoint = onResetPoint,
        showPointAffected = showPointAffected,
        onTogglePointAffected = onTogglePointAffected
    )
}

@Composable
fun GradeToolPanel(
    params: EditParams,
    selected: GradeZone = GradeZone.GLOBAL,
    onSelectZone: (GradeZone) -> Unit = {},
    onBeginDrag: () -> Unit = {},
    onLiveZone: (GradeZone, GradeAdjust) -> Unit = { _, _ -> },
    onSetZone: (GradeZone, GradeAdjust) -> Unit = { _, _ -> },
    onResetZone: (GradeZone) -> Unit = {},
    onBlending: (Float) -> Unit = {},
    onBalance: (Float) -> Unit = {},
    onResetAll: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    GradePanel(
        params = params,
        selected = selected,
        onSelectZone = onSelectZone,
        onBeginDrag = onBeginDrag,
        onLiveZone = onLiveZone,
        onSetZone = onSetZone,
        onResetZone = onResetZone,
        onBlending = onBlending,
        onBalance = onBalance,
        onResetAll = onResetAll,
        modifier = modifier
    )
}

@Composable
fun CurvesToolPanel(
    params: EditParams,
    histogram: Array<IntArray>?,
    selectedChannel: CurveChannel,
    hapticEnabled: Boolean,
    onSelectChannel: (CurveChannel) -> Unit,
    onBeginDrag: () -> Unit,
    onLivePoints: (CurveChannel, List<com.lumina.studio.core.edit.CurvePoint>) -> Unit,
    onSetPoints: (CurveChannel, List<com.lumina.studio.core.edit.CurvePoint>) -> Unit,
    onResetChannel: (CurveChannel) -> Unit,
    onResetAll: () -> Unit,
    modifier: Modifier = Modifier,
    onDraggingChange: (Boolean) -> Unit = {}
) {
    CurvesPanel(
        params = params,
        histogram = histogram,
        selectedChannel = selectedChannel,
        hapticEnabled = hapticEnabled,
        onSelectChannel = onSelectChannel,
        onBeginDrag = onBeginDrag,
        onLivePoints = onLivePoints,
        onSetPoints = onSetPoints,
        onResetChannel = onResetChannel,
        onResetAll = onResetAll,
        modifier = modifier,
        onDraggingChange = onDraggingChange
    )
}

@Composable
fun DetailsToolPanel(
    params: EditParams,
    onDetail: (DetailControl, Float) -> Unit,
    onResetDetail: (DetailControl) -> Unit,
    onResetAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    DetailsPanel(
        params = params,
        onDetail = onDetail,
        onResetDetail = onResetDetail,
        onResetAll = onResetAll,
        modifier = modifier
    )
}

@Composable
fun CropToolPanel(
    params: EditParams,
    onRatio: (CropRatio) -> Unit,
    onRotate: () -> Unit,
    onStraighten: (Float) -> Unit,
    onFlipH: () -> Unit,
    onFlipV: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier
) {
    CropPanel(
        params = params,
        onRatio = onRatio,
        onRotate = onRotate,
        onStraighten = onStraighten,
        onFlipH = onFlipH,
        onFlipV = onFlipV,
        onReset = onReset,
        modifier = modifier
    )
}

@Composable
fun MaskToolPanel(
    params: EditParams,
    selectedMaskId: String?,
    showOverlay: Boolean,
    onAddMask: (MaskTool) -> Unit,
    onSelectMask: (String) -> Unit,
    onRemoveMask: (String) -> Unit,
    onToggleVisible: (String) -> Unit,
    onSize: (String, Float) -> Unit,
    onFeather: (String, Float) -> Unit,
    onOpacity: (String, Float) -> Unit,
    onInvert: (String, Boolean) -> Unit,
    onCenter: (String, Float, Float) -> Unit,
    onRadius: (String, Float) -> Unit,
    onAngle: (String, Float) -> Unit,
    onPosition: (String, Float) -> Unit,
    onExposure: (String, Float) -> Unit,
    onTemperature: (String, Float) -> Unit,
    onToggleOverlay: (Boolean) -> Unit,
    onResetAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    MaskPanel(
        params = params,
        selectedMaskId = selectedMaskId,
        showOverlay = showOverlay,
        onAddMask = onAddMask,
        onSelectMask = onSelectMask,
        onRemoveMask = onRemoveMask,
        onToggleVisible = onToggleVisible,
        onSize = onSize,
        onFeather = onFeather,
        onOpacity = onOpacity,
        onInvert = onInvert,
        onCenter = onCenter,
        onRadius = onRadius,
        onAngle = onAngle,
        onPosition = onPosition,
        onExposure = onExposure,
        onTemperature = onTemperature,
        onToggleOverlay = onToggleOverlay,
        onResetAll = onResetAll,
        modifier = modifier
    )
}
