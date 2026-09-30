package com.example.lixing.ui.screen.plan.schedule

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.lixing.domain.planning.ContentSelectionCodec
import com.example.lixing.data.local.entity.ScheduledTaskEntity
import com.example.lixing.domain.model.WeekdayMask
import com.example.lixing.domain.schedule.ScheduleLoadAnalyzer
import com.example.lixing.domain.planning.AvailabilityCodec
import com.example.lixing.domain.planning.AvailabilityWindow
import com.example.lixing.domain.planning.PlanningEngine
import java.time.LocalDate
import java.time.LocalTime
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val dayFormat = DateTimeFormatter.ofPattern("M月d日 E")

/** The selected day uses the same effective schedule resolver as the Today screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DailyScheduleSection(viewModel: DailyScheduleViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var editingDate by remember { mutableStateOf<LocalDate?>(null) }
    var editingEntry by remember { mutableStateOf<ScheduledTaskEntity?>(null) }
    var editingPolicyDate by remember { mutableStateOf<LocalDate?>(null) }
    var showDatePicker by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        LearningGoalsSection(state, viewModel::saveResource, viewModel::saveGoal)
        Text("每日具体安排", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { viewModel.shiftDay(-1) }) { Text("前一天") }
            OutlinedButton(onClick = { showDatePicker = true }) { Text(state.selectedDate.format(dayFormat)) }
            TextButton(onClick = { viewModel.shiftDay(1) }) { Text("后一天") }
        }
        state.message?.let { message ->
            Text(message, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
        }
        val date = state.selectedDate
        val tasks = state.tasksByDate[date].orEmpty()
        val totalMinutes = tasks.mapNotNull { it.plannedMinutes }.sum()
        val policy = state.dayPolicies.firstOrNull { it.studyDate == date }
        val inheritedCapacity = ScheduleLoadAnalyzer.analyze(state.slots.filter {
            it.isEnabled && WeekdayMask(it.weekdayMask).contains(date)
        }).scheduledMinutes
        val policyCapacity = policy?.let { item ->
            AvailabilityCodec.decode(item.windowsJson)?.let { windows ->
                runCatching { PlanningEngine.capacityMinutes(
                    PlanningEngine.validateWindows(date, state.dayStart, windows)) }.getOrNull()
            }
        }
        val capacity = if (policyCapacity == null) inheritedCapacity else
            minOf(policyCapacity, policy.maxPlannedMinutes ?: Int.MAX_VALUE)
        Surface(tonalElevation = 1.dp, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("当天安排", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    TextButton(onClick = { editingEntry = null; editingDate = date }) { Text("添加安排") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = { editingPolicyDate = date }) {
                        Text(if (policy == null) "设置当天可用时间" else "编辑当天可用时间")
                    }
                    if (policy != null) TextButton(onClick = { viewModel.clearDayPolicy(date) }) {
                        Text("恢复周期时段")
                    }
                }
                if (tasks.isEmpty()) Text("暂无学习任务", style = MaterialTheme.typography.bodySmall)
                else {
                    Text("预计 ${totalMinutes / 60} 小时 ${totalMinutes % 60} 分钟" +
                        if (tasks.any { it.plannedMinutes == null }) " · 部分任务未估时" else "",
                        style = MaterialTheme.typography.bodySmall)
                    Text("${if (policy == null) "周期时段约" else "当天可用"} $capacity 分钟" +
                        if (totalMinutes > capacity) " · 已超出 ${totalMinutes - capacity} 分钟，请调整" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (totalMinutes > capacity) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant)
                    tasks.forEachIndexed { index, task ->
                        if (index > 0) HorizontalDivider()
                        val time = if (task.scheduledStart != null && task.scheduledEnd != null) {
                            "${task.scheduledStart}–${task.scheduledEnd}"
                        } else task.slotName
                        Text("$time · ${task.subjectName} · ${task.title}", style = MaterialTheme.typography.bodyMedium)
                        val detail = ContentSelectionCodec.decode(task.contentJson)?.displayText().orEmpty()
                        if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodySmall)
                        val target = when (task.targetType.name) {
                            "COUNT" -> "目标 ${task.targetValue} 题"
                            "PAGES" -> "目标 ${task.targetValue} 页"
                            "MINUTES" -> "目标 ${task.targetValue} 分钟"
                            else -> "完成即可"
                        }
                        Text("$target${task.plannedMinutes?.let { " · 预计 $it 分钟" }.orEmpty()}",
                            style = MaterialTheme.typography.bodySmall)
                        task.scheduleId?.let { scheduleId ->
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                TextButton(onClick = {
                                    editingEntry = state.scheduled.firstOrNull { it.id == scheduleId }
                                    editingDate = date
                                }) { Text("编辑") }
                                TextButton(onClick = { viewModel.cancel(scheduleId) }) { Text("取消此安排") }
                            }
                        }
                    }
                }
                state.scheduled.filter { it.studyDate == date && it.state == "SUPPRESS" }.forEach { suppressed ->
                    TextButton(onClick = { viewModel.restore(suppressed.id) }) {
                        Text("恢复「${suppressed.title}」的重复任务")
                    }
                }
            }
        }
    }
    if (showDatePicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = state.selectedDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = { TextButton(onClick = {
                pickerState.selectedDateMillis?.let {
                    viewModel.selectDate(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate())
                }
                showDatePicker = false
            }) { Text("确定") } },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("取消") } },
        ) { DatePicker(state = pickerState) }
    }
    editingDate?.let { date ->
        AddDatedTaskDialog(
            date = date,
            original = editingEntry,
            state = state,
            onDismiss = { editingDate = null; editingEntry = null },
            onSave = { subjectId, slotId, title, resource, chapter, first, last, start, end, minutes, templateId, goalId ->
                viewModel.save(date, subjectId, slotId, title, resource, chapter, first, last, start, end, minutes,
                    templateId, goalId, editingEntry?.id)
                editingDate = null
                editingEntry = null
            },
        )
    }
    editingPolicyDate?.let { date ->
        DayPolicyDialog(
            date = date,
            current = state.dayPolicies.firstOrNull { it.studyDate == date },
            onDismiss = { editingPolicyDate = null },
            onSave = { windows, maximum, reason ->
                viewModel.saveDayPolicy(date, windows, maximum, reason)
                editingPolicyDate = null
            },
        )
    }
}

@Composable
private fun DayPolicyDialog(
    date: LocalDate,
    current: com.example.lixing.data.local.entity.PlanDayPolicyEntity?,
    onDismiss: () -> Unit,
    onSave: (List<AvailabilityWindow>, Int?, String) -> Unit,
) {
    var windowsText by remember(date, current?.windowsJson) {
        mutableStateOf(AvailabilityCodec.decode(current?.windowsJson.orEmpty())
            ?.joinToString("\n") { "${it.start}-${it.end}" }.orEmpty())
    }
    var maximumText by remember(date, current?.id) { mutableStateOf(current?.maxPlannedMinutes?.toString().orEmpty()) }
    var reason by remember(date, current?.id) { mutableStateOf(current?.reason.orEmpty()) }
    var error by remember(date) { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${date.format(dayFormat)} 的可用时间") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("每行一个时间段，例如 09:00-11:00。留空表示当天没有可用时间。", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(windowsText, { windowsText = it }, label = { Text("可用时间段") })
                OutlinedTextField(maximumText, { maximumText = it }, label = { Text("最多安排分钟（可选）") }, singleLine = true)
                OutlinedTextField(reason, { reason = it }, label = { Text("备注（可选）") }, singleLine = true)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { Button(onClick = {
            try {
                val windows = windowsText.lines().filter { it.isNotBlank() }.map { line ->
                    val parts = line.trim().split("-", limit = 2)
                    require(parts.size == 2) { "时间段应按 HH:mm-HH:mm 填写" }
                    AvailabilityWindow(LocalTime.parse(parts[0].trim()).toString(),
                        LocalTime.parse(parts[1].trim()).toString())
                }
                val maximum = maximumText.trim().takeIf { it.isNotEmpty() }?.toIntOrNull()
                require(maximumText.isBlank() || maximum != null) { "最多安排分钟应填写整数" }
                onSave(windows, maximum, reason)
            } catch (problem: Exception) { error = problem.message ?: "可用时间无效" }
        }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun AddDatedTaskDialog(
    date: LocalDate,
    original: ScheduledTaskEntity?,
    state: DailyScheduleUiState,
    onDismiss: () -> Unit,
    onSave: (String, String, String, String, String, Int?, Int?, LocalTime?, LocalTime?, Int?, String?, String?) -> Unit,
) {
    val originalContent = remember(original?.id) { ContentSelectionCodec.decode(original?.contentJson.orEmpty()) }
    var subjectId by remember(date, original?.id) { mutableStateOf(original?.subjectId ?: state.subjects.firstOrNull()?.id.orEmpty()) }
    var slotId by remember(date, original?.id) { mutableStateOf(original?.timeSlotId ?: state.slots.firstOrNull()?.id.orEmpty()) }
    var templateId by remember(date, original?.id) { mutableStateOf(original?.sourceTemplateId) }
    var goalId by remember(date, original?.id) { mutableStateOf(original?.goalId) }
    var title by remember(date, original?.id) { mutableStateOf(original?.title.orEmpty()) }
    var resource by remember(date, original?.id) { mutableStateOf(originalContent?.resourceName.orEmpty()) }
    var chapter by remember(date, original?.id) { mutableStateOf(originalContent?.chapter.orEmpty()) }
    var first by remember(date, original?.id) { mutableStateOf(originalContent?.intervals?.firstOrNull()?.first?.toString().orEmpty()) }
    var last by remember(date, original?.id) { mutableStateOf(originalContent?.intervals?.lastOrNull()?.last?.toString().orEmpty()) }
    var start by remember(date, original?.id) { mutableStateOf(original?.startTime?.toString().orEmpty()) }
    var end by remember(date, original?.id) { mutableStateOf(original?.endTime?.toString().orEmpty()) }
    var minutes by remember(date, original?.id) { mutableStateOf(original?.plannedMinutes?.toString().orEmpty()) }
    var formError by remember(date) { mutableStateOf<String?>(null) }
    var subjectMenu by remember { mutableStateOf(false) }
    var slotMenu by remember { mutableStateOf(false) }
    var templateMenu by remember { mutableStateOf(false) }
    var goalMenu by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${date.format(dayFormat)} 的具体安排") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("选择“替代重复任务”时，仅修改这一天。", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Column {
                        OutlinedButton(onClick = { subjectMenu = true }) {
                            Text(state.subjects.firstOrNull { it.id == subjectId }?.name ?: "选科目")
                        }
                        DropdownMenu(subjectMenu, onDismissRequest = { subjectMenu = false }) {
                            state.subjects.forEach { subject ->
                                DropdownMenuItem(text = { Text(subject.name) }, onClick = {
                                    subjectId = subject.id
                                    templateId = null
                                    goalId = null
                                    subjectMenu = false
                                })
                            }
                        }
                    }
                    Column {
                        OutlinedButton(onClick = { slotMenu = true }) {
                            Text(state.slots.firstOrNull { it.id == slotId }?.name ?: "选时段")
                        }
                        DropdownMenu(slotMenu, onDismissRequest = { slotMenu = false }) {
                            state.slots.forEach { slot ->
                                DropdownMenuItem(text = { Text(slot.name) }, onClick = {
                                    slotId = slot.id
                                    slotMenu = false
                                })
                            }
                        }
                    }
                }
                Box {
                    OutlinedButton(onClick = { templateMenu = true }) {
                        Text(state.templates.firstOrNull { it.id == templateId }?.title ?: "新增独立任务（可选择替代重复任务）")
                    }
                    DropdownMenu(templateMenu, onDismissRequest = { templateMenu = false }) {
                        DropdownMenuItem(text = { Text("新增独立任务") }, onClick = { templateId = null; templateMenu = false })
                        state.templates.filter { it.subjectId == subjectId && it.isEnabled }.forEach { template ->
                            DropdownMenuItem(text = { Text("替代：${template.title}") }, onClick = {
                                templateId = template.id
                                if (title.isBlank()) title = template.title
                                templateMenu = false
                            })
                        }
                    }
                }
                Box {
                    OutlinedButton(onClick = { goalMenu = true }) {
                        Text(state.goals.firstOrNull { it.id == goalId }?.title ?: "关联学习目标（可选）")
                    }
                    DropdownMenu(goalMenu, onDismissRequest = { goalMenu = false }) {
                        DropdownMenuItem(text = { Text("不关联目标") }, onClick = { goalId = null; goalMenu = false })
                        state.goals.filter { it.subjectId == subjectId }.forEach { goal ->
                            DropdownMenuItem(text = { Text(goal.title) }, onClick = {
                                goalId = goal.id
                                goal.resourceId?.let { resourceId ->
                                    state.resources.firstOrNull { it.id == resourceId }?.let { resource = it.name }
                                }
                                goalMenu = false
                            })
                        }
                    }
                }
                OutlinedTextField(title, { title = it }, label = { Text("任务名称") }, singleLine = true)
                OutlinedTextField(resource, { resource = it }, label = { Text("教材 / 题册名称（可选）") }, singleLine = true)
                OutlinedTextField(chapter, { chapter = it }, label = { Text("章节，如第 2 章（可选）") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(first, { first = it }, label = { Text("起始题号") }, modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(last, { last = it }, label = { Text("结束题号") }, modifier = Modifier.weight(1f), singleLine = true)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(start, { start = it }, label = { Text("开始 HH:mm") }, modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(end, { end = it }, label = { Text("结束 HH:mm") }, modifier = Modifier.weight(1f), singleLine = true)
                }
                OutlinedTextField(minutes, { minutes = it }, label = { Text("预计分钟（可留空）") }, singleLine = true)
                formError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(onClick = {
                try {
                    fun number(value: String, label: String): Int? = value.trim().takeIf { it.isNotEmpty() }?.toIntOrNull()
                        ?: if (value.isBlank()) null else error("$label 应填写整数")
                    fun time(value: String, label: String): LocalTime? = value.trim().takeIf { it.isNotEmpty() }?.let {
                        runCatching { LocalTime.parse(it) }.getOrElse { error("$label 应按 HH:mm 填写") }
                    }
                    require(subjectId.isNotBlank() && slotId.isNotBlank()) { "请先选择科目和时段" }
                    require(title.isNotBlank() || resource.isNotBlank() || chapter.isNotBlank()) { "请填写任务内容" }
                    onSave(subjectId, slotId, title, resource, chapter,
                        number(first, "起始题号"), number(last, "结束题号"),
                        time(start, "开始时间"), time(end, "结束时间"),
                        number(minutes, "预计分钟"), templateId, goalId)
                } catch (exception: Exception) {
                    formError = exception.message
                }
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
