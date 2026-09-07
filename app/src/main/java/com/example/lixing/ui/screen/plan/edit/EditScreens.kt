package com.example.lixing.ui.screen.plan.edit

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.lixing.domain.model.RepeatRule
import com.example.lixing.domain.model.TargetType
import com.example.lixing.domain.model.TaskType
import com.example.lixing.domain.model.WeekdayMask
import java.time.DayOfWeek

/** 通用编辑页外壳。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditScaffold(
    title: String,
    onBack: () -> Unit,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) { content() }
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun NameField(value: String, onValueChange: (String) -> Unit, label: String = "名称") {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
}

@Composable
private fun WeekdayPicker(mask: WeekdayMask, onToggle: (DayOfWeek) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        DayOfWeek.entries.forEach { day ->
            FilterChip(
                selected = mask.contains(day),
                onClick = { onToggle(day) },
                // SHORT_NAMES = "周一".."周日", take(1) 拿到的是"周"字。
                // 用 last() 取"一".."日"，一眼可辨。
                label = { Text(WeekdayMask.SHORT_NAMES[day.ordinal].last().toString()) },
            )
        }
    }
}

// ---------------- 时段 ----------------

@Composable
fun TimeSlotEditScreen(onBack: () -> Unit, vm: PlanEditViewModel = hiltViewModel()) {
    val name by vm.name.collectAsStateWithLifecycle()
    val note by vm.note.collectAsStateWithLifecycle()
    val start by vm.slotStartText.collectAsStateWithLifecycle()
    val end by vm.slotEndText.collectAsStateWithLifecycle()
    val timeError by vm.slotTimeError.collectAsStateWithLifecycle()
    val weekdays by vm.weekdays.collectAsStateWithLifecycle()
    val requiredTaskCount by vm.slotRequiredTaskCount.collectAsStateWithLifecycle()

    EditScaffold("编辑时段", onBack) {
        NameField(name, vm::setName, "时段名称（如：早读）")
        FieldLabel("开始 / 结束（直接输入，HH:mm）")
        Row(verticalAlignment = Alignment.CenterVertically) {
            TimeText("开始", start, vm::setSlotStartText, isError = timeError != null)
            Text(" – ")
            TimeText("结束", end, vm::setSlotEndText, isError = timeError != null)
        }
        timeError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        FieldLabel("生效星期")
        WeekdayPicker(weekdays, vm::toggleWeekday)
        OutlinedTextField(
            value = requiredTaskCount.toString(),
            onValueChange = { it.toIntOrNull()?.let(vm::setSlotRequiredTaskCount) },
            label = { Text("至少完成几项（0 = 全部）") },
            supportingText = { Text("填 1/2… 表示该时段任务任选相应数量") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        NameField(note, vm::setNote, "用途备注")
        SaveDelete(
            onSave = { vm.saveSlot(onBack) },
            onDelete = { vm.deleteSlot(onBack) },
        )
    }
}

// ---------------- 科目 ----------------

@Composable
fun SubjectEditScreen(onBack: () -> Unit, vm: PlanEditViewModel = hiltViewModel()) {
    val name by vm.name.collectAsStateWithLifecycle()
    val color by vm.colorArgb.collectAsStateWithLifecycle()

    EditScaffold("编辑科目", onBack) {
        NameField(name, vm::setName, "科目名称（如：数学）")
        FieldLabel("颜色")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            com.example.lixing.ui.theme.SubjectPalette.forEach { c ->
                ColorDot(c.toArgbInt(), selected = color == c.toArgbInt()) { vm.setColor(c.toArgbInt()) }
            }
        }
        SaveDelete(onSave = { vm.saveSubject(onBack) }, onDelete = { vm.deleteSubject(onBack) })
    }
}

@Composable
private fun ColorDot(argb: Int, selected: Boolean, onClick: () -> Unit) {
    val color = androidx.compose.ui.graphics.Color(argb)
    Box(
        modifier = Modifier
            .size(if (selected) 40.dp else 34.dp)
            .clip(CircleShape)
            .background(color)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Text("✓", color = androidx.compose.ui.graphics.Color.White, style = MaterialTheme.typography.labelLarge)
        }
    }
}

// ---------------- 阶段 ----------------

@Composable
fun PhaseEditScreen(onBack: () -> Unit, vm: PlanEditViewModel = hiltViewModel()) {
    val name by vm.name.collectAsStateWithLifecycle()
    val note by vm.note.collectAsStateWithLifecycle()
    val start by vm.phaseStart.collectAsStateWithLifecycle()
    val end by vm.phaseEnd.collectAsStateWithLifecycle()

    EditScaffold("编辑阶段", onBack) {
        NameField(name, vm::setName, "阶段名称（如：强化阶段）")
        FieldLabel("起 $start → 止 $end")
        OutlinedTextField(
            value = start.toString(), onValueChange = { runCatching { vm.setPhaseStart(java.time.LocalDate.parse(it)) } },
            label = { Text("开始日 YYYY-MM-DD") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
        )
        OutlinedTextField(
            value = end.toString(), onValueChange = { runCatching { vm.setPhaseEnd(java.time.LocalDate.parse(it)) } },
            label = { Text("结束日 YYYY-MM-DD") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
        )
        NameField(note, vm::setNote, "阶段说明")
        SaveDelete(onSave = { vm.savePhase(onBack) }, onDelete = { vm.deletePhase(onBack) })
    }
}

// ---------------- 任务模板 ----------------

@Composable
fun TaskTemplateEditScreen(onBack: () -> Unit, vm: PlanEditViewModel = hiltViewModel()) {
    val name by vm.name.collectAsStateWithLifecycle()
    val note by vm.note.collectAsStateWithLifecycle()
    val subjects by vm.subjects.collectAsStateWithLifecycle()
    val slots by vm.slots.collectAsStateWithLifecycle()
    val phases by vm.phases.collectAsStateWithLifecycle()
    val subjectSel by vm.subjectIdSel.collectAsStateWithLifecycle()
    val slotSel by vm.slotIdSel.collectAsStateWithLifecycle()
    val phaseSel by vm.phaseIdSel.collectAsStateWithLifecycle()
    val taskType by vm.taskType.collectAsStateWithLifecycle()
    val targetType by vm.targetType.collectAsStateWithLifecycle()
    val targetValue by vm.targetValue.collectAsStateWithLifecycle()
    val keystone by vm.isKeystone.collectAsStateWithLifecycle()
    val repeatRule by vm.repeatRule.collectAsStateWithLifecycle()

    EditScaffold("编辑任务", onBack) {
        NameField(name, vm::setName, "任务标题")

        FieldLabel("科目")
        EntityPicker(items = subjects.map { it.id to it.name }, selected = subjectSel, onSelect = vm::setSubject)
        FieldLabel("时段")
        EntityPicker(items = slots.map { it.id to it.name }, selected = slotSel, onSelect = vm::setSlot)
        FieldLabel("限定阶段（不选 = 全程）")
        EntityPicker(items = listOf("" to "全程") + phases.map { it.id to it.name }, selected = phaseSel ?: "", onSelect = { vm.setPhase(if (it == "") null else it) })

        FieldLabel("类型")
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TaskType.entries.forEach { t ->
                FilterChip(selected = taskType == t, onClick = { vm.setTaskType(t) }, label = { Text(t.label) })
            }
        }

        FieldLabel("量化方式")
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TargetType.entries.forEach { t ->
                FilterChip(selected = targetType == t, onClick = { vm.setTargetType(t) }, label = { Text(t.label) })
            }
        }

        if (targetType.isQuantified) {
            OutlinedTextField(
                value = targetValue.toString(),
                onValueChange = { it.toIntOrNull()?.let(vm::setTargetValue) },
                label = { Text("目标量（${targetType.unit}）") },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
            )
        }

        FieldLabel("重复")
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            RepeatRule.entries.forEach { r ->
                FilterChip(selected = repeatRule == r, onClick = { vm.setRepeatRule(r) }, label = { Text(r.label) })
            }
        }
        if (repeatRule == RepeatRule.WEEKLY_DAYS) {
            val weekdays by vm.weekdays.collectAsStateWithLifecycle()
            WeekdayPicker(weekdays, vm::toggleWeekday)
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("关键任务（积分翻倍）", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Switch(checked = keystone, onCheckedChange = vm::setKeystone)
        }

        NameField(note, vm::setNote, "备注")
        SaveDelete(onSave = { vm.saveTemplate(onBack) }, onDelete = { vm.deleteTemplate(onBack) })
    }
}

// ---------------- 共用小组件 ----------------

@Composable
private fun EntityPicker(
    items: List<Pair<String, String>>,
    selected: String?,
    onSelect: (String) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        items.forEach { (id, label) ->
            FilterChip(selected = selected == id, onClick = { onSelect(id) }, label = { Text(label) })
        }
    }
}

@Composable
private fun SaveDelete(onSave: () -> Unit, onDelete: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        Button(onClick = onSave, modifier = Modifier.weight(1f)) { Text("保存") }
        OutlinedButton(onClick = onDelete, modifier = Modifier.weight(1f)) {
            Text("删除", color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun TimeText(label: String, value: String, onChange: (String) -> Unit, isError: Boolean) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        modifier = Modifier.width(120.dp),
        singleLine = true,
        isError = isError,
        placeholder = { Text("00:00") },
    )
}

private fun androidx.compose.ui.graphics.Color.toArgbInt(): Int =
    android.graphics.Color.argb(
        (alpha * 255f + 0.5f).toInt(),
        (red * 255f + 0.5f).toInt(),
        (green * 255f + 0.5f).toInt(),
        (blue * 255f + 0.5f).toInt(),
    )
