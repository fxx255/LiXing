package com.example.lixing.ui.screen.today.component

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.lixing.domain.settle.DayStats
import com.example.lixing.ui.theme.LiXingRadius
import com.example.lixing.ui.theme.LocalHeroGradient
import com.example.lixing.ui.theme.StatusMissed
import com.example.lixing.ui.theme.StreakFlame

/** 并排两张小卡：左「今日进度」，右「连续打卡」。 */
@Composable
fun ProgressAndStreakRow(
    stats: DayStats,
    currentStreak: Int,
    weekAchieved: List<Boolean?>,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Tile(Modifier.weight(1f)) {
            ProgressRing(
                ratio = stats.completionRate,
                doneCount = stats.doneTasks,
                totalCount = stats.countedTasks,
            )
        }
        Tile(Modifier.weight(1f)) {
            StreakBlock(currentStreak = currentStreak, weekAchieved = weekAchieved)
        }
    }
}

@Composable
private fun Tile(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = LiXingRadius.Tile,
        shadowElevation = 1.dp,
    ) {
        Box(
            Modifier.padding(vertical = 16.dp, horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) { content() }
    }
}

/** 进度环。渐变描边 + 弹性动画，完成度变化时有回弹感。 */
@Composable
private fun ProgressRing(
    ratio: Float,
    doneCount: Int,
    totalCount: Int,
) {
    val animated by animateFloatAsState(
        targetValue = ratio.coerceIn(0f, 1f),
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessLow,
        ),
        label = "ring",
    )
    val gradient = LocalHeroGradient.current
    val track = MaterialTheme.colorScheme.surfaceContainerHighest

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier.size(92.dp),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.size(92.dp)) {
                val stroke = 11.dp.toPx()
                val arcSize = Size(size.minDimension - stroke, size.minDimension - stroke)
                val topLeft = Offset(
                    (size.width - arcSize.width) / 2f,
                    (size.height - arcSize.height) / 2f,
                )
                drawArc(
                    color = track,
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
                if (animated > 0f) {
                    drawArc(
                        brush = Brush.sweepGradient(gradient),
                        startAngle = -90f,
                        sweepAngle = 360f * animated,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(stroke, cap = StrokeCap.Round),
                    )
                }
            }

            Text(
                text = "${(ratio * 100).toInt()}%",
                style = MaterialTheme.typography.titleLarge.copy(fontSize = 23.sp),
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        Spacer(Modifier.height(8.dp))
        Text(
            text = "今日 $doneCount/$totalCount",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 连续打卡：火焰数字 + 本周 7 点。 */
@Composable
private fun StreakBlock(
    currentStreak: Int,
    weekAchieved: List<Boolean?>,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = "🔥", fontSize = 26.sp)
            Spacer(Modifier.width(6.dp))
            Text(
                text = "$currentStreak",
                style = MaterialTheme.typography.displayMedium.copy(fontSize = 34.sp),
                fontWeight = FontWeight.Bold,
                color = StreakFlame,
            )
        }

        Text(
            text = "天连续",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            weekAchieved.take(7).forEach { achieved -> WeekDot(achieved) }
        }
    }
}

/** 单个周点：达成 = 主色实心；漏 = 淡红；未到 = 灰。 */
@Composable
private fun WeekDot(achieved: Boolean?) {
    val color = when (achieved) {
        true -> MaterialTheme.colorScheme.primary
        false -> StatusMissed.copy(alpha = 0.45f)
        null -> MaterialTheme.colorScheme.surfaceContainerHighest
    }
    Box(
        Modifier
            .size(9.dp)
            .clip(LiXingRadius.Pill)
            .background(color),
    )
}
