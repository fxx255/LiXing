package com.example.lixing.ui.screen.focus

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import android.os.PowerManager
import com.example.lixing.domain.model.FocusMode
import com.example.lixing.ui.theme.LiXingRadius
import com.example.lixing.ui.theme.LocalHeroGradient
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween

/** 专注页：大渐变圆环计时器。统计下方挂「英语积累」与「AI 助手」入口。 */
@Composable
fun FocusScreen(
    onOpenEnglish: () -> Unit = {},
    onOpenAssistant: () -> Unit = {},
    viewModel: FocusViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val gradient = LocalHeroGradient.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val powerManager = LocalContext.current.getSystemService(PowerManager::class.java)

    // Activity 生命周期在切到其它 App 时会收到 PAUSE/STOP；导航到应用内其它页面
    // 不会触发这两个事件，因此只会把真正离开应用的行为计为中断。
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    // 锁屏/熄屏时设备不可交互，不应被算作“切出应用”中断。
                    if (powerManager?.isInteractive != false) viewModel.onAppBackground()
                }
                Lifecycle.Event.ON_RESUME -> viewModel.onAppForeground()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // 大计时器圆环
        val total = if (state.mode == FocusMode.POMODORO) state.pomodoroSeconds else 0
        val ratio = if (state.mode == FocusMode.POMODORO && total > 0) {
            1f - state.remainingSeconds.toFloat() / total
        } else {
            // 正计时：每 25 分钟绕一圈
            (state.elapsedSeconds % (25 * 60)).toFloat() / (25 * 60)
        }
        val animatedRatio by animateFloatAsState(
            targetValue = ratio.coerceIn(0f, 1f),
            animationSpec = tween(400),
            label = "focusRing",
        )

        val display = if (state.mode == FocusMode.POMODORO) {
            formatTime(state.remainingSeconds)
        } else {
            formatTime(state.elapsedSeconds)
        }

        // Canvas 是绘制作用域，不能调 @Composable，颜色在外部先取好
        val trackColor = MaterialTheme.colorScheme.surfaceContainerHighest

        Box(
            modifier = Modifier.size(240.dp).padding(top = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(240.dp)) {
                val stroke = 16.dp.toPx()
                val arcSize = Size(size.minDimension - stroke, size.minDimension - stroke)
                val topLeft = Offset(
                    (size.width - arcSize.width) / 2f,
                    (size.height - arcSize.height) / 2f,
                )
                drawArc(
                    color = trackColor,
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
                if (animatedRatio > 0f) {
                    drawArc(
                        brush = Brush.sweepGradient(gradient),
                        startAngle = -90f,
                        sweepAngle = 360f * animatedRatio,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(stroke, cap = StrokeCap.Round),
                    )
                }
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = display,
                    style = MaterialTheme.typography.displayLarge.copy(fontSize = 54.sp),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = when {
                        !state.isRunning -> if (state.mode == FocusMode.POMODORO) "番茄钟待开始" else "正计时待开始"
                        state.mode == FocusMode.POMODORO -> "剩余"
                        else -> "已专注"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(4.dp))

        if (!state.isRunning) {
            // 番茄钟时长胶囊
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(25, 45, 50).forEach { minutes ->
                    val selected = state.pomodoroSeconds == minutes * 60
                    Surface(
                        shape = LiXingRadius.Pill,
                        color = if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceContainerHigh,
                    ) {
                        Text(
                            text = "$minutes 分",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            color = if (selected) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 9.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = { viewModel.startPomodoro() },
                shape = LiXingRadius.Pill,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Text("开始番茄钟", fontWeight = FontWeight.Bold)
            }

            OutlinedButton(
                onClick = { viewModel.startCountUp() },
                shape = LiXingRadius.Pill,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("正计时") }
        } else {
            Button(
                onClick = { viewModel.finish(completed = true) },
                shape = LiXingRadius.Pill,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("结束并记录", fontWeight = FontWeight.Bold) }

            OutlinedButton(
                onClick = { viewModel.abandon() },
                shape = LiXingRadius.Pill,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("放弃本次") }
        }

        Spacer(Modifier.weight(1f))

        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = LiXingRadius.Tile,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                StatItem("今日专注", "${state.todayFocusMinutes} 分")
                StatItem("累计专注", formatMinutes(state.totalFocusMinutes))
                StatItem("中断", "${state.interruptions} 次")
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            EntryTile(
                emoji = "📖",
                title = "英语积累",
                subtitle = "单词 / 短语 / 句子",
                modifier = Modifier.weight(1f),
                onClick = onOpenEnglish,
            )
            EntryTile(
                emoji = "🤖",
                title = "AI 助手",
                subtitle = "问答与计划建议",
                modifier = Modifier.weight(1f),
                onClick = onOpenAssistant,
            )
        }

        Text(
            text = "计时期间切出应用会计入中断次数，但不会强制拦截你。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** 专注页底部的功能入口卡片。 */
@Composable
private fun EntryTile(
    emoji: String,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = LiXingRadius.Tile,
        onClick = onClick,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(emoji, style = MaterialTheme.typography.titleLarge)
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun formatTime(totalSeconds: Int): String {
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
    else String.format("%02d:%02d", m, s)
}

private fun formatMinutes(totalMinutes: Int): String {
    val h = totalMinutes / 60
    val m = totalMinutes % 60
    return if (h > 0) "${h}h${m}m" else "${m}m"
}
