package com.example.lixing.ui.screen.plan.manage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.format.DateTimeFormatter

private val TIME_FMT = DateTimeFormatter.ofPattern("HH:mm")

/** 计划管理页：四类实体的列表 + 增改入口。 */
@Composable
fun PlanManageScreen(
    onEditPhase: (String) -> Unit,
    onEditSubject: (String) -> Unit,
    onEditSlot: (String) -> Unit,
    onEditTemplate: (String) -> Unit,
    viewModel: PlanManageViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("计划管理", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(
            "调整阶段、科目、时段和任务。注意给休息留出空间——排得太满反而执行不下去。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // 排程健康度：过满 / 无休息提醒
        LoadCard(state.slots)

        Section("时段", state.slots.map {
            val requirement = if (it.requiredTaskCount > 0) " · 至少完成${it.requiredTaskCount}项" else ""
            "${it.name} ${it.startTime.format(TIME_FMT)}–${it.endTime.format(TIME_FMT)}$requirement" to it.id
        }, onEditSlot)
        Section("科目", state.subjects.map { it.name to it.id }, onEditSubject)
        Section("阶段", state.phases.map { "${it.name} ${it.startDate}~${it.endDate}" to it.id }, onEditPhase)
        Section("任务", state.templates.map { it.title to it.id }, onEditTemplate)

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun LoadCard(slots: List<com.example.lixing.data.local.entity.TimeSlotEntity>) {
    val load = com.example.lixing.domain.schedule.ScheduleLoadAnalyzer.analyze(slots)

    Surface(
        color = if (load.isOverPacked) com.example.lixing.ui.theme.StatusPartial.copy(alpha = 0.15f)
        else com.example.lixing.ui.theme.StatusDone.copy(alpha = 0.12f),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "每日排程：学习 ${load.scheduledMinutes / 60} 小时 ${load.scheduledMinutes % 60} 分 · 空档休息 ${load.restMinutes} 分",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (load.warnings.isEmpty()) {
                Text(
                    "节奏不错，记得保留休息空档。",
                    style = MaterialTheme.typography.bodySmall,
                    color = com.example.lixing.ui.theme.StatusDone,
                )
            } else {
                load.warnings.forEach { w ->
                    Text(
                        "⚠ $w",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

@Composable
private fun Section(
    title: String,
    items: List<Pair<String, String>>,
    onEdit: (String) -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = { onEdit("-1") }) { Text("+ 新增") }
            }
            if (items.isEmpty()) {
                Text("（空）", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items.forEach { (label, id) ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        label,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                    )
                    TextButton(onClick = { onEdit(id) }) { Text("编辑") }
                }
            }
        }
    }
}
