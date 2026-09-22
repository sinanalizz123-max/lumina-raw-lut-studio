package com.lumina.studio.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.lumina.studio.core.design.components.CategoryChip
import com.lumina.studio.core.design.theme.LuminaCaptionTextStyle
import com.lumina.studio.core.design.theme.LuminaMuted
import com.lumina.studio.core.design.theme.LuminaOnSurface
import com.lumina.studio.core.design.theme.LuminaSectionHeaderTextStyle
import com.lumina.studio.core.design.theme.LuminaSurfaceContainerHigh
import com.lumina.studio.core.edit.CurveChannel
import com.lumina.studio.core.edit.CurvePoint
import com.lumina.studio.core.edit.Curves
import com.lumina.studio.core.edit.EditParams

private fun channelColor(channel: CurveChannel): Color = when (channel) {
    CurveChannel.MASTER -> Color.White
    CurveChannel.RED -> Color(0xFFE53935)
    CurveChannel.GREEN -> Color(0xFF43A047)
    CurveChannel.BLUE -> Color(0xFF1E88E5)
}

@Composable
fun CurvesPanel(
    params: EditParams,
    histogram: Array<IntArray>?,
    selectedChannel: CurveChannel,
    hapticEnabled: Boolean,
    onSelectChannel: (CurveChannel) -> Unit,
    onBeginDrag: () -> Unit,
    onLivePoints: (CurveChannel, List<CurvePoint>) -> Unit,
    onSetPoints: (CurveChannel, List<CurvePoint>) -> Unit,
    onResetChannel: (CurveChannel) -> Unit,
    onResetAll: () -> Unit,
    modifier: Modifier = Modifier,
    onDraggingChange: (Boolean) -> Unit = {}
) {
    val points = params.getCurve(selectedChannel)
    // Live ref so in-progress gestures survive recomposition:
    // the gesture block is keyed on selectedChannel only and always reads
    // the latest list through this ref instead of a stale closure.
    val pointsRef = rememberUpdatedState(points)
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val hitRadiusPx = remember(density) { with(density) { 28.dp.toPx() } }
    val lineGrabPx = remember(density) { with(density) { 20.dp.toPx() } }
    // Tiny move threshold: the curve responds the instant the finger moves.
    val moveSlopPx = remember(density) { with(density) { 4.dp.toPx() } }
    var draggingIndex by remember(selectedChannel) { mutableIntStateOf(-1) }
    var selectedIndex by remember(selectedChannel) { mutableIntStateOf(-1) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val sampled = remember(points) { Curves.sampleCurve(points) }
    val selectedPoint = points.getOrNull(selectedIndex)
    var xField by remember(selectedChannel, selectedIndex, points) {
        mutableStateOf(selectedPoint?.let { (it.x * 255f + 0.5f).toInt().coerceIn(0, 255).toString() } ?: "")
    }
    var yField by remember(selectedChannel, selectedIndex, points) {
        mutableStateOf(selectedPoint?.let { (it.y * 255f + 0.5f).toInt().coerceIn(0, 255).toString() } ?: "")
    }

    fun toCurve(offset: Offset, w: Float, h: Float): CurvePoint {
        val x = (offset.x / w).coerceIn(0f, 1f)
        val y = (1f - offset.y / h).coerceIn(0f, 1f)
        return CurvePoint(x, y)
    }

    fun nearestAny(offset: Offset, w: Float, h: Float, pts: List<CurvePoint>): Int {
        var best = -1
        var bestDist = Float.MAX_VALUE
        for ((index, p) in pts.withIndex()) {
            val dx = p.x * w - offset.x
            val dy = (1f - p.y) * h - offset.y
            val d = dx * dx + dy * dy
            if (d < bestDist) {
                bestDist = d
                best = index
            }
        }
        return best
    }

    fun nearestIndex(offset: Offset, w: Float, h: Float, pts: List<CurvePoint>): Int {
        val best = nearestAny(offset, w, h, pts)
        if (best < 0) return -1
        val p = pts[best]
        val dx = p.x * w - offset.x
        val dy = (1f - p.y) * h - offset.y
        return if (kotlin.math.sqrt(dx * dx + dy * dy) <= hitRadiusPx) best else -1
    }

    fun distToLine(offset: Offset, w: Float, h: Float, lut: FloatArray): Float {
        var best = Float.MAX_VALUE
        var px = 0f
        var py = (1f - lut[0].coerceIn(0f, 1f)) * h
        for (i in 1 until lut.size) {
            val x = i / 255f * w
            val y = (1f - lut[i].coerceIn(0f, 1f)) * h
            val dx = x - px
            val dy = y - py
            val len2 = dx * dx + dy * dy
            val t = if (len2 > 0f) {
                (((offset.x - px) * dx + (offset.y - py) * dy) / len2).coerceIn(0f, 1f)
            } else {
                0f
            }
            val cx = px + t * dx
            val cy = py + t * dy
            val ddx = offset.x - cx
            val ddy = offset.y - cy
            val d = ddx * ddx + ddy * ddy
            if (d < best) best = d
            px = x
            py = y
        }
        return kotlin.math.sqrt(best)
    }

    fun buzz() {
        if (!hapticEnabled) return
        try {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        } catch (_: Exception) {
        }
    }

    fun showPoint(idx: Int, cur: List<CurvePoint>) {
        selectedIndex = idx
        val p = cur[idx]
        xField = (p.x * 255f + 0.5f).toInt().coerceIn(0, 255).toString()
        yField = (p.y * 255f + 0.5f).toInt().coerceIn(0, 255).toString()
    }

    fun setDragging(index: Int) {
        val wasDragging = draggingIndex >= 0
        draggingIndex = index
        val isDragging = index >= 0
        if (wasDragging != isDragging) onDraggingChange(isDragging)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Curves",
                style = LuminaSectionHeaderTextStyle,
                color = LuminaOnSurface,
                modifier = Modifier.weight(1f)
            )
            TextButton(
                onClick = {
                    setDragging(-1)
                    selectedIndex = -1
                    onResetChannel(selectedChannel)
                },
                modifier = Modifier.heightIn(min = 48.dp)
            ) { Text("Reset") }
            TextButton(
                onClick = {
                    setDragging(-1)
                    selectedIndex = -1
                    onResetAll()
                },
                modifier = Modifier.heightIn(min = 48.dp)
            ) { Text("All") }
        }
        HorizontalDivider(color = LuminaSurfaceContainerHigh)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CurveChannel.entries.forEach { channel ->
                CategoryChip(
                    label = channel.label,
                    selected = channel == selectedChannel,
                    onClick = {
                        setDragging(-1)
                        selectedIndex = -1
                        onSelectChannel(channel)
                    }
                )
            }
        }
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp)
                .semantics { contentDescription = "Curve editor for ${selectedChannel.label}" }
                .onSizeChanged { canvasSize = it }
                .pointerInput(selectedChannel) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val downTime = System.currentTimeMillis()
                        val w = size.width.toFloat()
                        val h = size.height.toFloat()
                        if (w <= 0f || h <= 0f) return@awaitEachGesture
                        var cur = pointsRef.value
                        // Instant hit-test on DOWN: no touch-slop wait, the curve
                        // grabs the finger the moment it touches.
                        var idx = nearestIndex(down.position, w, h, cur)
                        var planted = false
                        if (idx < 0 && distToLine(down.position, w, h, Curves.sampleCurve(cur)) <= lineGrabPx) {
                            if (cur.size >= 16) {
                                idx = nearestAny(down.position, w, h, cur)
                            } else {
                                var p = toCurve(down.position, w, h)
                                val dup = cur.indexOfFirst { kotlin.math.abs(it.x - p.x) < 0.015f }
                                if (dup >= 0) {
                                    idx = dup
                                } else {
                                    p = p.copy(x = p.x.coerceIn(0.015f, 0.985f))
                                    val next = (cur + p).sortedBy { it.x }
                                    val inserted = next.indexOf(p)
                                    showPoint(inserted, next)
                                    buzz()
                                    onSetPoints(selectedChannel, next)
                                    idx = inserted
                                    planted = true
                                }
                            }
                        }
                        if (idx >= 0) {
                            showPoint(idx, cur)
                            buzz()
                            onBeginDrag()
                            setDragging(idx)
                        }
                        var moved = false
                        var finished = false
                        while (!finished) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id }
                            if (change == null) continue
                            if (!change.pressed) {
                                finished = true
                            } else if (idx >= 0) {
                                val dist = (change.position - down.position).getDistance()
                                if (!moved && dist > moveSlopPx) moved = true
                                if (moved) {
                                    cur = pointsRef.value
                                    if (idx < cur.size) {
                                        val raw = toCurve(change.position, w, h)
                                        val prevX = if (idx > 0) cur[idx - 1].x else -1f
                                        val nextX = if (idx < cur.size - 1) cur[idx + 1].x else 2f
                                        val clampedX = when (idx) {
                                            0 -> 0f
                                            cur.size - 1 -> 1f
                                            else -> raw.x.coerceIn(prevX + 0.01f, nextX - 0.01f)
                                        }
                                        val clamped = CurvePoint(clampedX, raw.y.coerceIn(0f, 1f))
                                        val next = cur.toMutableList()
                                        next[idx] = clamped
                                        onLivePoints(selectedChannel, next)
                                    }
                                    change.consume()
                                }
                            }
                        }
                        val heldMs = System.currentTimeMillis() - downTime
                        if (idx >= 0) {
                            setDragging(-1)
                            if (!moved && !planted && heldMs >= 500) {
                                // Hold without moving = remove point (min 2 kept).
                                val latest = pointsRef.value
                                if (latest.size > 2 && idx < latest.size) {
                                    val next = latest.toMutableList()
                                    next.removeAt(idx)
                                    if (selectedIndex == idx) selectedIndex = -1
                                    else if (selectedIndex > idx) selectedIndex -= 1
                                    onSetPoints(selectedChannel, next)
                                }
                            }
                        } else if (!moved && heldMs < 600) {
                            // Quick tap on empty area = add point.
                            val latest = pointsRef.value
                            val p = toCurve(down.position, w, h)
                            val next = (latest + p).sortedBy { it.x }
                            if (next.size <= 16) {
                                val inserted = next.indexOf(p)
                                showPoint(inserted, next)
                                onSetPoints(selectedChannel, next)
                            }
                        }
                    }
                }
        ) {
            val w = size.width
            val h = size.height
            if (w <= 0f || h <= 0f) return@Canvas
            for (i in 1..3) {
                val fx = w * i / 4f
                drawLine(Color.White.copy(alpha = 0.12f), Offset(fx, 0f), Offset(fx, h), strokeWidth = 1f)
                val fy = h * i / 4f
                drawLine(Color.White.copy(alpha = 0.12f), Offset(0f, fy), Offset(w, fy), strokeWidth = 1f)
            }
            histogram?.let { bins ->
                val chans = listOf(Color.Red.copy(alpha = 0.35f), Color.Green.copy(alpha = 0.35f), Color.Blue.copy(alpha = 0.35f))
                val peak = bins.flatMap { it.asList() }.maxOrNull()?.coerceAtLeast(1) ?: 1
                for (c in 0..2) {
                    val data = bins.getOrNull(c) ?: return@let
                    if (data.isEmpty()) return@let
                    val path = Path()
                    for (i in data.indices) {
                        val x = i / data.size.toFloat() * w
                        val y = h - (data[i] / peak.toFloat()) * h * 0.9f
                        if (i == 0) path.moveTo(x, h)
                        path.lineTo(x, y)
                    }
                    path.lineTo(w, h)
                    path.close()
                    drawPath(path, color = chans[c], alpha = 0.25f)
                }
            }
            drawLine(Color.White.copy(alpha = 0.35f), Offset(0f, h), Offset(w, 0f), strokeWidth = 1f)
            val line = Path()
            for (i in sampled.indices) {
                val x = i / 255f * w
                val y = (1f - sampled[i].coerceIn(0f, 1f)) * h
                if (i == 0) line.moveTo(x, y) else line.lineTo(x, y)
            }
            drawPath(line, color = channelColor(selectedChannel), style = Stroke(width = 3f))
            val activeIndex = if (draggingIndex >= 0) draggingIndex else selectedIndex
            if (activeIndex in points.indices) {
                val ap = points[activeIndex]
                val ax = ap.x * w
                val ay = (1f - ap.y) * h
                drawLine(
                    channelColor(selectedChannel).copy(alpha = 0.45f),
                    Offset(ax, 0f),
                    Offset(ax, h),
                    strokeWidth = 2f
                )
                drawLine(
                    channelColor(selectedChannel).copy(alpha = 0.45f),
                    Offset(0f, ay),
                    Offset(w, ay),
                    strokeWidth = 2f
                )
            }
            for ((index, p) in points.withIndex()) {
                val cx = p.x * w
                val cy = (1f - p.y) * h
                val isActive = index == activeIndex
                val outer = if (isActive) 18f else 14f
                val inner = if (isActive) 14f else 11f
                drawCircle(Color.Black, radius = outer, center = Offset(cx, cy))
                drawCircle(channelColor(selectedChannel), radius = inner, center = Offset(cx, cy))
                drawCircle(Color.White, radius = inner, center = Offset(cx, cy), style = Stroke(width = 2f))
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = xField,
                onValueChange = { v -> xField = v.filter { it.isDigit() }.take(3) },
                label = { Text("In (0-255)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = yField,
                onValueChange = { v -> yField = v.filter { it.isDigit() }.take(3) },
                label = { Text("Out (0-255)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f)
            )
            TextButton(
                onClick = {
                    val xInt = xField.toIntOrNull()?.coerceIn(0, 255) ?: return@TextButton
                    val yInt = yField.toIntOrNull()?.coerceIn(0, 255) ?: return@TextButton
                    val cur = pointsRef.value
                    val selIdx = selectedIndex
                    if (selIdx in cur.indices) {
                        val edited = CurvePoint(xInt / 255f, yInt / 255f)
                        val next = cur.toMutableList()
                        next[selIdx] = edited
                        val sanitized = Curves.sanitize(next)
                        var bestIndex = -1
                        var bestDist = Float.MAX_VALUE
                        for ((i, p) in sanitized.withIndex()) {
                            val dx = p.x - edited.x
                            val dy = p.y - edited.y
                            val d = dx * dx + dy * dy
                            if (d < bestDist) {
                                bestDist = d
                                bestIndex = i
                            }
                        }
                        selectedIndex = bestIndex
                        onSetPoints(selectedChannel, sanitized)
                    } else {
                        if (cur.size >= 16) return@TextButton
                        val added = CurvePoint(xInt / 255f, yInt / 255f)
                        val sanitized = Curves.sanitize(cur + added)
                        if (sanitized.size > 16) return@TextButton
                        var bestIndex = -1
                        var bestDist = Float.MAX_VALUE
                        for ((i, p) in sanitized.withIndex()) {
                            val dx = p.x - added.x
                            val dy = p.y - added.y
                            val d = dx * dx + dy * dy
                            if (d < bestDist) {
                                bestDist = d
                                bestIndex = i
                            }
                        }
                        selectedIndex = bestIndex
                        onSetPoints(selectedChannel, sanitized)
                    }
                },
                modifier = Modifier.heightIn(min = 48.dp)
            ) { Text("Apply") }
        }
        Text(
            text = "Touch line to bend • tap adds • hold removes.",
            style = LuminaCaptionTextStyle,
            color = LuminaMuted
        )
    }
}
