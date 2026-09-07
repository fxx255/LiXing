package com.example.lixing.ui.screen.history

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.lixing.data.local.entity.DailyTaskEntity
import com.example.lixing.domain.model.TaskStatus
import com.example.lixing.domain.model.WeekdayMask
import com.example.lixing.ui.screen.today.dialog.PhotoStrip
import com.example.lixing.ui.theme.LiXingRadius
import com.example.lixing.ui.theme.StatusDone
import com.example.lixing.ui.theme.StatusMissed
import com.example.lixing.ui.theme.StatusPartial
import com.example.lixing.ui.theme.StatusSkipped
import java.time.LocalDate

/**
 * 历史回顾：按天倒序列出打卡记录，展开可看每个时段的任务、
 * 详细文字记录与照片（点图可预览/旋转）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    onBack: () -> Unit,
    viewModel: HistoryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("历史回顾", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        if (state.isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        if (state.days.isEmpty()) {
            Box(
                Modifier.fillMaxSize().padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "还没有历史记录。打卡时填写「详细记录」或拍照，之后就能在这里回看。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(state.days, key = { it.date.toEpochDay() }) { day ->
                DayCard(
                    day = day,
                    expanded = state.expanded == day.date,
                    onToggle = { viewModel.toggleExpand(day.date) },
                )
            }

            item {
                OutlinedButton(
                    onClick = viewModel::loadMore,
                    shape = LiXingRadius.Pill,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                ) { Text("再往前看 30 天") }
            }
        }
    }
}

@Composable
private fun DayCard(
    day: HistoryDay,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = LiXingRadius.Card,
        shadowElevation = 1.dp,
    ) {
        Column(Modifier.fillMaxWidth()) {
            // 头部：日期 + 完成率 + 详情数
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "${day.date.monthValue} 月 ${day.date.dayOfMonth} 日 · " +
                            WeekdayMask.SHORT_NAMES[day.date.dayOfWeek.ordinal],
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = buildString {
                            append("完成 ${day.stats.doneTasks}/${day.stats.countedTasks}")
                            if (day.stats.focusMinutes > 0) append(" · 专注 ${day.stats.focusMinutes} 分")
                            if (day.detailCount > 0) append(" · ${day.detailCount} 条记录")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // 完成率徽章
                val rate = (day.stats.completionRate * 100).toInt()
                Text(
                    text = "$rate%",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = when {
                        rate >= 80 -> StatusDone
                        rate >= 50 -> StatusPartial
                        else -> StatusMissed
                    },
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(
                    Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    day.bySlot.forEach { (slotName, tasks) ->
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = slotName,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            tasks.forEach { HistoryTaskRow(it) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryTaskRow(task: DailyTaskEntity) {
    val statusColor = when (task.status) {
        TaskStatus.DONE -> StatusDone
        TaskStatus.PARTIAL -> StatusPartial
        TaskStatus.MISSED -> StatusMissed
        TaskStatus.SKIPPED -> StatusSkipped
        TaskStatus.PENDING -> MaterialTheme.colorScheme.outline
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(LiXingRadius.Card)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(LiXingRadius.Pill)
                    .background(Color(task.subjectColorArgb)),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = task.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = task.status.label,
                style = MaterialTheme.typography.labelSmall,
                color = statusColor,
                fontWeight = FontWeight.Bold,
            )
        }

        // 完成量
        if (task.targetType.isQuantified) {
            Text(
                text = "${task.actualValue} / ${task.targetValue} ${task.targetType.unit}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // 详细文字记录
        if (!task.checkinNote.isNullOrBlank()) {
            Text(
                text = task.checkinNote,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        // 照片（可点开预览/旋转）
        val photos = com.example.lixing.ui.screen.today.dialog.decodePhotos(task.checkinPhoto)
        if (photos.isNotEmpty()) {
            // 只读：历史回顾不允许删照片
            PhotoStrip(paths = photos)
        }
    }
}
