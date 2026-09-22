package com.lumina.studio.ui.editor

import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lumina.studio.core.design.components.CategoryChip
import com.lumina.studio.core.design.components.ProSlider
import com.lumina.studio.core.design.theme.LuminaAmber
import com.lumina.studio.core.design.theme.LuminaCaptionTextStyle
import com.lumina.studio.core.design.theme.LuminaMuted
import com.lumina.studio.core.design.theme.LuminaOnSurface
import com.lumina.studio.core.design.theme.LuminaSectionHeaderTextStyle
import com.lumina.studio.core.design.theme.LuminaSurfaceContainerHigh
import com.lumina.studio.core.design.theme.LuminaSurfaceContainerLow
import com.lumina.studio.core.edit.DustCandidate
import com.lumina.studio.core.edit.EditParams
import com.lumina.studio.core.edit.RetouchKind
import com.lumina.studio.core.edit.RetouchOp
import kotlin.math.roundToInt

@Composable
fun RetouchPanel(
    params: EditParams,
    selectedId: String?,
    mode: RetouchKind,
    placeArmed: Boolean,
    cloneSourceArmedId: String?,
    dustSensitivity: Float,
    dustCandidates: List<DustCandidate>,
    onMode: (RetouchKind) -> Unit,
    onArmPlace: (Boolean) -> Unit,
    onSelect: (String) -> Unit,
    onRemove: (String) -> Unit,
    onCenter: (String, Float, Float) -> Unit,
    onRadius: (String, Float) -> Unit,
    onFeather: (String, Float) -> Unit,
    onOpacity: (String, Float) -> Unit,
    onArmCloneSource: (String?) -> Unit,
    onDustSensitivity: (Float) -> Unit,
    onScanDust: () -> Unit,
    onToggleCandidate: (String) -> Unit,
    onRemoveCandidate: (String) -> Unit,
    onHealConfirmed: () -> Unit,
    onClearCandidates: () -> Unit,
    onResetAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    val selected = params.retouch.firstOrNull { it.id == selectedId } ?: params.retouch.lastOrNull()
    val opCardShape = RoundedCornerShape(16.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Retouch",
            style = LuminaSectionHeaderTextStyle,
            color = LuminaOnSurface
        )
        HorizontalDivider(color = LuminaSurfaceContainerHigh)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RetouchKind.entries.forEach { kind ->
                CategoryChip(
                    label = kind.label,
                    selected = mode == kind,
                    onClick = { onMode(kind) }
                )
            }
        }
        Text(
            text = when (mode) {
                RetouchKind.HEAL -> "Heal blends with nearby colors. Best on smooth areas."
                RetouchKind.CLONE -> "Clone copies from a picked source with a feathered edge."
                RetouchKind.ERASE -> "Eraser (beta) fills from nearby pixels; best on small spots."
            },
            style = LuminaCaptionTextStyle,
            color = LuminaMuted
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Tap photo to place",
                modifier = Modifier.weight(1f),
                style = LuminaSectionHeaderTextStyle,
                color = LuminaOnSurface
            )
            Switch(checked = placeArmed, onCheckedChange = onArmPlace)
        }
        Text(
            text = if (params.retouch.size >= RetouchOp.MAX_OPS)
                "Limit reached (${params.retouch.size}/${RetouchOp.MAX_OPS}) — delete a spot to add another."
            else
                "Tap the photo to drop a ${mode.label.lowercase()} spot, then drag it below.",
            style = LuminaCaptionTextStyle,
            color = LuminaMuted
        )
        if (params.retouch.isEmpty()) {
            Text(
                text = "No spots yet. Arm placement and tap the photo.",
                style = LuminaCaptionTextStyle,
                color = LuminaMuted
            )
        } else {
            params.retouch.forEach { op ->
                val isSelected = op.id == selected?.id
                Card(
                    onClick = { onSelect(op.id) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (isSelected) Modifier.border(2.dp, LuminaAmber, opCardShape)
                            else Modifier
                        ),
                    shape = opCardShape,
                    colors = CardDefaults.cardColors(
                        containerColor = LuminaSurfaceContainerLow
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 8.dp)
                        ) {
                            Text(
                                text = "${op.kind.label} • ${(op.radius * 100f).roundToInt()}% • ${(op.opacity * 100f).roundToInt()}%",
                                style = LuminaSectionHeaderTextStyle,
                                color = LuminaOnSurface
                            )
                            Text(
                                text = "at ${(op.cx * 100f).roundToInt()}%, ${(op.cy * 100f).roundToInt()}%" +
                                    (if (op.kind == RetouchKind.CLONE) " ← src ${(op.sx * 100f).roundToInt()}%, ${(op.sy * 100f).roundToInt()}%" else ""),
                                style = LuminaCaptionTextStyle,
                                color = LuminaMuted
                            )
                        }
                        IconButton(
                            onClick = { onRemove(op.id) },
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete spot")
                        }
                    }
                }
            }
        }
        selected?.let { op ->
            val id = op.id
            ProSlider(
                label = "Center X",
                value = op.cx * 100f,
                onValueChange = { onCenter(id, it / 100f, op.cy) },
                valueRange = 0f..100f,
                displayValue = "${(op.cx * 100f).roundToInt()}%"
            )
            ProSlider(
                label = "Center Y",
                value = op.cy * 100f,
                onValueChange = { onCenter(id, op.cx, it / 100f) },
                valueRange = 0f..100f,
                displayValue = "${(op.cy * 100f).roundToInt()}%"
            )
            Text(
                text = "Drag the spot on the photo to move it.",
                style = LuminaCaptionTextStyle,
                color = LuminaMuted
            )
            ProSlider(
                label = "Radius",
                value = op.radius * 100f,
                onValueChange = { onRadius(id, it / 100f) },
                valueRange = RetouchOp.MIN_RADIUS * 100f..RetouchOp.MAX_RADIUS * 100f,
                displayValue = "${(op.radius * 100f).roundToInt()}%"
            )
            ProSlider(
                label = "Feather",
                value = op.feather * 100f,
                onValueChange = { onFeather(id, it / 100f) },
                valueRange = 0f..100f,
                displayValue = "${(op.feather * 100f).roundToInt()}%"
            )
            ProSlider(
                label = "Opacity",
                value = op.opacity * 100f,
                onValueChange = { onOpacity(id, it / 100f) },
                valueRange = 0f..100f,
                displayValue = "${(op.opacity * 100f).roundToInt()}%"
            )
            if (op.kind == RetouchKind.CLONE) {
                TextButton(
                    onClick = {
                        onArmCloneSource(if (cloneSourceArmedId == id) null else id)
                    },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text(
                        if (cloneSourceArmedId == id) "Tap photo for source… (tap to cancel)"
                        else "Pick clone source from photo"
                    )
                }
            }
        }
        HorizontalDivider(color = LuminaSurfaceContainerHigh)
        Text(
            text = "Dust candidates",
            style = LuminaSectionHeaderTextStyle,
            color = LuminaOnSurface
        )
        Text(
            text = "Heuristic scan for dark dots. Only confirmed ones become heal spots.",
            style = LuminaCaptionTextStyle,
            color = LuminaMuted
        )
        ProSlider(
            label = "Sensitivity",
            value = dustSensitivity,
            onValueChange = onDustSensitivity,
            valueRange = 0f..100f,
            displayValue = "${dustSensitivity.roundToInt()}%"
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(
                onClick = onScanDust,
                modifier = Modifier.heightIn(min = 48.dp)
            ) { Text("Scan photo") }
            TextButton(
                onClick = onHealConfirmed,
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                val confirmed = dustCandidates.count { it.confirmed }
                Text("Heal confirmed ($confirmed)")
            }
            TextButton(
                onClick = onClearCandidates,
                modifier = Modifier.heightIn(min = 48.dp)
            ) { Text("Clear") }
        }
        if (dustCandidates.isNotEmpty()) {
            Text(
                text = "${dustCandidates.size} candidate(s) — tap to confirm, ✕ to dismiss.",
                style = LuminaCaptionTextStyle,
                color = LuminaMuted
            )
            dustCandidates.forEach { candidate ->
                Card(
                    onClick = { onToggleCandidate(candidate.id) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (candidate.confirmed) Modifier.border(2.dp, LuminaAmber, opCardShape)
                            else Modifier
                        ),
                    shape = opCardShape,
                    colors = CardDefaults.cardColors(
                        containerColor = LuminaSurfaceContainerLow
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = (if (candidate.confirmed) "✓ " else "○ ") +
                                "at ${(candidate.cx * 100f).roundToInt()}%, ${(candidate.cy * 100f).roundToInt()}%",
                            style = LuminaSectionHeaderTextStyle,
                            color = LuminaOnSurface,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = { onRemoveCandidate(candidate.id) },
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) {
                            Icon(Icons.Filled.Delete, contentDescription = "Dismiss candidate")
                        }
                    }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(
                onClick = onResetAll,
                modifier = Modifier.heightIn(min = 48.dp)
            ) { Text("Reset retouch") }
        }
    }
}
