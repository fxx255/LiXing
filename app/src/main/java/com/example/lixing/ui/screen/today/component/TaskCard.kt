package com.example.lixing.ui.screen.today.component

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.example.lixing.data.local.entity.DailyTaskEntity
import com.example.lixing.domain.model.TargetType
import com.example.lixing.domain.model.TaskStatus
import com.example.lixing.ui.theme.LiXingRadius
import com.example.lixing.ui.theme.StatusDone
import com.example.lixing.ui.theme.StatusMissed
import com.example.lixing.ui.theme.StatusPartial
import com.example.lixing.ui.theme.StatusSkipped

/**
 * 任务卡片。晨光风：大圆角、柔阴影、科目色圆点，右侧圆形打卡钮。
 * 打卡时圆钮有弹性放大回弹（spring），给动作一点实感。
 */
@Composable
fun TaskCard(
    task: DailyTaskEntity,
    isCurrentSlot: Boolean,
    onCheckInClick: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val subjectColor = Color(task.subjectColorArgb)
    val done = task.status.isEngaged
    val missed = task.status == TaskStatus.MISSED
    val skipped = task.status == TaskStatus.SKIPPED

    val surfaceColor = when {
        missed -> StatusMissed.copy(alpha = 0.07f)
        done -> MaterialTheme.colorScheme.surfaceContainerLow
        isCurrentSlot -> MaterialTheme.colorScheme.surfaceContainerHigh
        else -> MaterialTheme.colorScheme.surfaceContainerLow
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = "${task.title}，${task.subjectName}，${task.status.label}" },
        color = surfaceColor,
        shape = LiXingRadius.Card,
        shadowElevation = if (isCurrentSlot && !done) 2.dp else 0.dp,
    ) {
        Row(
            modifier = Modifier
                .combinedClickable(onClick = onCheckInClick, onLongClick = onLongPress)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 科目色圆点
            Box(
                Modifier
                    .size(10.dp)
                    .clip(LiXingRadius.Pill)
                    .background(if (skipped) StatusSkipped else subjectColor),
            )
            Spacer(Modifier.width(14.dp))

            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = task.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = when {
                            done -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            missed -> StatusMissed
                            else -> MaterialTheme.colorScheme.onSurface
                        },
                        textDecoration = if (done) TextDecoration.LineThrough else null,
                    )
                    if (task.isKeystone) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "重点",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clip(LiXingRadius.Pill)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
                                .padding(horizontal = 7.dp, vertical = 2.dp),
                        )
                    }
                }
                if (!task.checkinNote.isNullOrBlank()) {
                    // 有详细记录：记录作为主副标题，原目标降为更小一行
                    Text(
                        text = task.checkinNote,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                        maxLines = 2,
                    )
                    SubjectSubtitleLine(task, MaterialTheme.typography.labelSmall)
                } else {
                    SubjectSubtitleLine(task, MaterialTheme.typography.bodySmall)
                }
            }

            Spacer(Modifier.width(10.dp))
            CheckButton(task)
        }
    }
}

/** 副标题行：学科名用学科色标出，后跟目标/进度/标记。 */
@Composable
private fun SubjectSubtitleLine(task: DailyTaskEntity, style: androidx.compose.ui.text.TextStyle) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = task.subjectName,
            style = style,
            fontWeight = FontWeight.SemiBold,
            color = if (task.status == TaskStatus.SKIPPED) StatusSkipped else Color(task.subjectColorArgb),
        )
        Text(
            text = " · ${buildSubtitle(task)}",
            style = style,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun buildSubtitle(task: DailyTaskEntity): String {
    val target = when (task.targetType) {
        TargetType.BOOLEAN -> task.taskType.label
        TargetType.MINUTES -> "${task.targetValue} 分钟"
        else -> "${task.targetValue} ${task.targetType.unit}"
    }
    val progress = if (task.actualValue > 0 && task.targetType.isQuantified) {
        "已做 ${task.actualValue}"
    } else {
        null
    }
    val photoCount = com.example.lixing.ui.screen.today.dialog.decodePhotos(task.checkinPhoto).size
    val flags = listOfNotNull(
        if (task.isLate) "迟到" else null,
        if (task.isMakeup) "补卡" else null,
        if (task.status == TaskStatus.SKIPPED) "已请假" else null,
        if (photoCount > 0) "📷x$photoCount" else null,
    )
    return (listOfNotNull(target, progress) + flags).joinToString(" · ")
}

/** 圆形打卡钮。完成时填充状态色 + 白勾，并带一次弹性放大。 */
@Composable
private fun CheckButton(task: DailyTaskEntity) {
    val done = task.status.isEngaged
    val statusColor = when (task.status) {
        TaskStatus.DONE -> StatusDone
        TaskStatus.PARTIAL -> StatusPartial
        TaskStatus.MISSED -> StatusMissed
        TaskStatus.SKIPPED -> StatusSkipped
        TaskStatus.PENDING -> MaterialTheme.colorScheme.primary
    }

    val scale by animateFloatAsState(
        targetValue = if (done) 1f else 0.92f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "checkScale",
    )

    Box(
        modifier = Modifier
            .size(34.dp)
            .scale(scale)
            .clip(LiXingRadius.Pill)
            .background(if (done) statusColor else statusColor.copy(alpha = 0.13f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Check,
            contentDescription = if (done) "已完成，点击修改" else "打卡",
            tint = if (done) Color.White else statusColor.copy(alpha = 0.75f),
            modifier = Modifier.size(19.dp),
        )
    }
}
