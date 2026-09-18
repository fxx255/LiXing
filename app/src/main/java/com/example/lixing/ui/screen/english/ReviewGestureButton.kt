package com.example.lixing.ui.screen.english

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.example.lixing.domain.english.ReviewGrade
import kotlin.math.abs
import kotlin.math.roundToInt

internal fun gestureGrade(dx: Float, dy: Float, threshold: Float): ReviewGrade? = when {
    abs(dy) >= threshold && abs(dy) > abs(dx) * 1.25f -> if (dy < 0) ReviewGrade.GOOD else ReviewGrade.AGAIN
    dx >= threshold && dx > abs(dy) * 1.25f -> ReviewGrade.HARD
    else -> null
}

/** Only this control captures gestures. Scrollable definitions never participate in grading. */
@Composable
internal fun ReviewGestureButton(
    x: Float, y: Float, enabled: Boolean, cardKey: String,
    onMove: (Float, Float) -> Unit, onSpeak: () -> Unit, onGrade: (ReviewGrade) -> Unit,
) {
    val speak by rememberUpdatedState(onSpeak)
    val grade by rememberUpdatedState(onGrade)
    val move by rememberUpdatedState(onMove)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val diameter = 64.dp
        val maxX = with(density) { (maxWidth - diameter).toPx().coerceAtLeast(0f) }
        val maxY = with(density) { (maxHeight - diameter).toPx().coerceAtLeast(0f) }
        val threshold = with(density) { 36.dp.toPx() }
        var position by remember(maxX, maxY) { mutableStateOf(Offset(x.coerceIn(0f, 1f) * maxX, y.coerceIn(0f, 1f) * maxY)) }
        var moving by remember { mutableStateOf(false) }
        var direction by remember { mutableStateOf<ReviewGrade?>(null) }
        LaunchedEffect(x, y, maxX, maxY) { if (!moving) position = Offset(x.coerceIn(0f, 1f) * maxX, y.coerceIn(0f, 1f) * maxY) }
        val color = when (direction) {
            ReviewGrade.AGAIN -> MaterialTheme.colorScheme.error
            ReviewGrade.HARD -> MaterialTheme.colorScheme.tertiary
            else -> MaterialTheme.colorScheme.primary
        }
        Box(
            Modifier.offset { IntOffset(position.x.roundToInt(), position.y.roundToInt()) }
                .size(diameter)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape)
                .border(if (moving) 3.dp else 2.dp, color, CircleShape)
                .semantics {
                    contentDescription = "发音与评分圆钮"
                    stateDescription = "点击发音；上滑认识、右滑模糊、下滑忘记；长按拖动位置"
                    role = Role.Button
                    onClick("播放发音") { if (enabled) speak(); enabled }
                    customActions = listOf(
                        CustomAccessibilityAction("认识") { if (enabled) grade(ReviewGrade.GOOD); enabled },
                        CustomAccessibilityAction("模糊") { if (enabled) grade(ReviewGrade.HARD); enabled },
                        CustomAccessibilityAction("忘记") { if (enabled) grade(ReviewGrade.AGAIN); enabled },
                        CustomAccessibilityAction("重置圆钮位置") { move(.92f, .75f); true },
                    )
                }
                .pointerInput(enabled, cardKey, maxX, maxY) {
                    if (!enabled) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        val origin = position
                        val absoluteDown = down.position + position
                        var dragged = false
                        var cancelled = false
                        var delta = Offset.Zero
                        var lastTime = down.uptimeMillis
                        try {
                            while (true) {
                                val event = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) { awaitPointerEvent() }
                                if (event == null) { if (!dragged) moving = true; continue }
                                if (event.changes.size != 1) { cancelled = true; break }
                                val change = event.changes.first()
                                if (change.id != down.id || change.isConsumed) { cancelled = true; break }
                                delta = change.position + position - absoluteDown
                                lastTime = change.uptimeMillis
                                if (!moving && !dragged && lastTime - down.uptimeMillis >= viewConfiguration.longPressTimeoutMillis) moving = true
                                if (!moving && delta.getDistance() > viewConfiguration.touchSlop) dragged = true
                                if (moving) position = Offset((origin.x + delta.x).coerceIn(0f, maxX), (origin.y + delta.y).coerceIn(0f, maxY))
                                else direction = gestureGrade(delta.x, delta.y, threshold)
                                change.consume()
                                if (!change.pressed) break
                            }
                            if (!cancelled) {
                                if (moving) move(if (maxX > 0) position.x / maxX else 0f, if (maxY > 0) position.y / maxY else 0f)
                                else if (!dragged) speak()
                                else gestureGrade(delta.x, delta.y, threshold)?.let(grade)
                            } else position = origin
                        } finally { moving = false; direction = null }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            if (moving) Text("移动", style = MaterialTheme.typography.labelMedium)
            else if (direction != null) Text(gradeLabel(direction!!), color = color, style = MaterialTheme.typography.labelMedium)
            else Icon(Icons.Default.VolumeUp, contentDescription = null, tint = color, modifier = Modifier.size(30.dp))
        }
    }
}

internal fun gradeLabel(grade: ReviewGrade) = when (grade) {
    ReviewGrade.GOOD -> "认识"; ReviewGrade.HARD -> "模糊"; ReviewGrade.AGAIN -> "忘记"
}
