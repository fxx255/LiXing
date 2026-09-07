package com.example.lixing.ui.screen.today.component

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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.lixing.data.local.entity.DailyTaskEntity
import com.example.lixing.domain.time.SlotState
import com.example.lixing.ui.screen.today.SlotSection
import com.example.lixing.ui.theme.LiXingRadius
import com.example.lixing.ui.theme.StatusDone
import java.time.format.DateTimeFormatter

private val TIME_FMT = DateTimeFormatter.ofPattern("HH:mm")

/** 一个时段下的全部任务。 */
@Composable
fun SlotSectionBlock(
    section: SlotSection,
    onCheckInClick: (DailyTaskEntity) -> Unit,
    onLongPress: (DailyTaskEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        SlotHeader(section)
        Spacer(Modifier.height(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            section.tasks.forEach { task ->
                TaskCard(
                    task = task,
                    isCurrentSlot = section.isCurrent,
                    onCheckInClick = { onCheckInClick(task) },
                    onLongPress = { onLongPress(task) },
                )
            }
            if (section.tasks.isEmpty()) {
                Text(
                    text = "暂无任务",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 20.dp),
                )
            }
        }
    }
}

@Composable
private fun SlotHeader(section: SlotSection) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 状态圆点：进行中用主色实心，其余用描边感的淡色
        Box(
            Modifier
                .size(if (section.isCurrent) 9.dp else 7.dp)
                .clip(LiXingRadius.Pill)
                .background(
                    when (section.state) {
                        SlotState.ONGOING -> MaterialTheme.colorScheme.primary
                        SlotState.PASSED ->
                            if (section.allDone) StatusDone.copy(alpha = 0.6f)
                            else MaterialTheme.colorScheme.outlineVariant

                        SlotState.UPCOMING -> MaterialTheme.colorScheme.outlineVariant
                    },
                ),
        )
        Spacer(Modifier.width(9.dp))

        Text(
            text = section.slotName,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = if (section.isCurrent) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "${section.start.format(TIME_FMT)}–${section.end.format(TIME_FMT)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.weight(1f))
        SlotBadge(section)
    }
}

@Composable
private fun SlotBadge(section: SlotSection) {
    val (label, color) = when (section.state) {
        SlotState.ONGOING -> "进行中" to MaterialTheme.colorScheme.primary
        SlotState.PASSED ->
            if (section.allDone) "已完成" to StatusDone
            else "已过" to MaterialTheme.colorScheme.onSurfaceVariant

        SlotState.UPCOMING -> "未开始" to MaterialTheme.colorScheme.onSurfaceVariant
    }

    // 进行中和已完成给实底胶囊，其余只用文字，避免页面上到处是色块
    if (section.state == SlotState.ONGOING || section.allDone) {
            Text(
            text = if (section.requiredTaskCount > 0) "$label ${section.doneCount}/${section.requiredTaskCount}（${section.totalCount}项可选）"
            else "$label ${section.doneCount}/${section.totalCount}",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = color,
            modifier = Modifier
                .clip(LiXingRadius.Pill)
                .background(color.copy(alpha = 0.14f))
                .padding(horizontal = 10.dp, vertical = 4.dp),
        )
    } else {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
        )
    }
}
