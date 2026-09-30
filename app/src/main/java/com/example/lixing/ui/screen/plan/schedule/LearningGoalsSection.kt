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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.lixing.data.local.entity.LearningGoalEntity
import com.example.lixing.data.local.entity.StudyResourceEntity
import com.example.lixing.domain.planning.ContentInterval
import com.example.lixing.domain.planning.ContentRangeText
import com.example.lixing.domain.planning.ContentSelection
import com.example.lixing.domain.planning.ContentSelectionCodec
import com.example.lixing.domain.planning.IntervalMath
import java.time.LocalDate

/** Small catalog editor used by dated tasks and the assistant's planning context. */
@Composable
fun LearningGoalsSection(
    state: DailyScheduleUiState,
    onSaveResource: (StudyResourceEntity) -> Unit,
    onSaveGoal: (LearningGoalEntity) -> Unit,
) {
    var editingResource by remember { mutableStateOf<StudyResourceEntity?>(null) }
    var editingGoal by remember { mutableStateOf<LearningGoalEntity?>(null) }
    var addingResource by remember { mutableStateOf(false) }
    var addingGoal by remember { mutableStateOf(false) }
    var showCatalog by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { showCatalog = !showCatalog }, modifier = Modifier.fillMaxWidth()) {
            Text(if (showCatalog) "收起学习资料与目标" else "查看学习资料与目标")
        }
        if (showCatalog) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { editingResource = null; addingResource = true }) { Text("添加资料") }
                OutlinedButton(onClick = { editingGoal = null; addingGoal = true }) { Text("添加目标") }
            }
            if (state.resources.isEmpty() && state.goals.isEmpty()) {
                Text("可以先录入题册、章节范围与截止日期，再逐日安排。", style = MaterialTheme.typography.bodySmall)
            }
            state.resources.filterNot { it.isArchived }.forEach { resource ->
                Surface(shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${resource.name}${resource.edition.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty()}",
                            modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { editingResource = resource; addingResource = true }) { Text("编辑") }
                    }
                }
            }
            state.goals.forEach { goal ->
                val scope = ContentSelectionCodec.decode(goal.scopeJson)?.displayText().orEmpty()
                val progress = state.goalStats.firstOrNull { it.goalId == goal.id }
                Surface(shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text(goal.title, style = MaterialTheme.typography.bodyMedium)
                            Text(listOfNotNull(scope.takeIf(String::isNotBlank), goal.dueDate?.let { "截止 $it" })
                                .joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                            progress?.targetCount?.let { target ->
                                Text("已确认 ${progress.knownCompleted}/$target · 剩余 ${progress.remainingCount ?: 0}",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            progress?.remainingRanges?.takeIf { it.isNotEmpty() }?.let { ranges ->
                                Text("待做编号：${ContentRangeText.format(ranges.take(8))}${if (ranges.size > 8) " 等" else ""}",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            progress?.quantityOnly?.takeIf { it > 0 }?.let { count ->
                                Text("另有 $count 项只记录了数量，未对应具体编号", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        TextButton(onClick = { editingGoal = goal; addingGoal = true }) { Text("编辑") }
                    }
                }
            }
        }
    }
    if (addingResource) ResourceDialog(state, editingResource,
        onDismiss = { addingResource = false },
        onSave = { onSaveResource(it); addingResource = false })
    if (addingGoal) GoalDialog(state, editingGoal,
        onDismiss = { addingGoal = false },
        onSave = { onSaveGoal(it); addingGoal = false })
}

@Composable
private fun ResourceDialog(
    state: DailyScheduleUiState,
    original: StudyResourceEntity?,
    onDismiss: () -> Unit,
    onSave: (StudyResourceEntity) -> Unit,
) {
    var subjectId by remember(original?.id) { mutableStateOf(original?.subjectId ?: state.subjects.firstOrNull()?.id.orEmpty()) }
    var name by remember(original?.id) { mutableStateOf(original?.name.orEmpty()) }
    var edition by remember(original?.id) { mutableStateOf(original?.edition.orEmpty()) }
    var menu by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (original == null) "添加学习资料" else "编辑学习资料") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box {
                    OutlinedButton(onClick = { menu = true }, enabled = original == null) {
                        Text(state.subjects.firstOrNull { it.id == subjectId }?.name ?: "选择科目")
                    }
                    DropdownMenu(menu, onDismissRequest = { menu = false }) {
                        state.subjects.filterNot { it.isArchived }.forEach { subject ->
                            DropdownMenuItem(text = { Text(subject.name) }, onClick = {
                                subjectId = subject.id; menu = false
                            })
                        }
                    }
                }
                OutlinedTextField(name, { name = it }, label = { Text("教材 / 题册 / 课程名称") }, singleLine = true)
                OutlinedTextField(edition, { edition = it }, label = { Text("版本（可选）") }, singleLine = true)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }, confirmButton = { Button(onClick = {
            if (state.planId == null || subjectId.isBlank() || name.isBlank()) {
                error = "请选择科目并填写资料名称"
            } else onSave((original ?: StudyResourceEntity(planId = state.planId, subjectId = subjectId,
                name = name.trim())).copy(subjectId = subjectId, name = name.trim(), edition = edition.trim()))
        }) { Text("保存") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
private fun GoalDialog(
    state: DailyScheduleUiState,
    original: LearningGoalEntity?,
    onDismiss: () -> Unit,
    onSave: (LearningGoalEntity) -> Unit,
) {
    val originalScope = remember(original?.id) { ContentSelectionCodec.decode(original?.scopeJson.orEmpty()) }
    val originalInitial = remember(original?.id) { ContentSelectionCodec.decode(original?.initialProgressJson.orEmpty()) }
    var subjectId by remember(original?.id) { mutableStateOf(original?.subjectId ?: state.subjects.firstOrNull()?.id.orEmpty()) }
    var resourceId by remember(original?.id) { mutableStateOf(original?.resourceId) }
    var title by remember(original?.id) { mutableStateOf(original?.title.orEmpty()) }
    var chapter by remember(original?.id) { mutableStateOf(originalScope?.chapter.orEmpty()) }
    var first by remember(original?.id) { mutableStateOf(originalScope?.intervals?.firstOrNull()?.first?.toString().orEmpty()) }
    var last by remember(original?.id) { mutableStateOf(originalScope?.intervals?.lastOrNull()?.last?.toString().orEmpty()) }
    var initial by remember(original?.id) { mutableStateOf(ContentRangeText.format(originalInitial?.intervals.orEmpty())) }
    var due by remember(original?.id) { mutableStateOf(original?.dueDate?.toString().orEmpty()) }
    var subjectMenu by remember { mutableStateOf(false) }
    var resourceMenu by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (original == null) "添加学习目标" else "编辑学习目标") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box {
                    OutlinedButton(onClick = { subjectMenu = true }, enabled = original == null) {
                        Text(state.subjects.firstOrNull { it.id == subjectId }?.name ?: "选择科目")
                    }
                    DropdownMenu(subjectMenu, onDismissRequest = { subjectMenu = false }) {
                        state.subjects.filterNot { it.isArchived }.forEach { subject ->
                            DropdownMenuItem(text = { Text(subject.name) }, onClick = {
                                subjectId = subject.id; resourceId = null; subjectMenu = false
                            })
                        }
                    }
                }
                Box {
                    OutlinedButton(onClick = { resourceMenu = true }, enabled = original == null) {
                        Text(state.resources.firstOrNull { it.id == resourceId }?.name ?: "选择资料（可选）")
                    }
                    DropdownMenu(resourceMenu, onDismissRequest = { resourceMenu = false }) {
                        DropdownMenuItem(text = { Text("不关联资料") }, onClick = { resourceId = null; resourceMenu = false })
                        state.resources.filter { it.subjectId == subjectId && !it.isArchived }.forEach { resource ->
                            DropdownMenuItem(text = { Text(resource.name) }, onClick = {
                                resourceId = resource.id; resourceMenu = false
                            })
                        }
                    }
                }
                OutlinedTextField(title, { title = it }, label = { Text("目标名称") }, singleLine = true)
                OutlinedTextField(chapter, { chapter = it }, label = { Text("章节（可选）") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(first, { first = it }, label = { Text("起始题号") }, modifier = Modifier.weight(1f))
                    OutlinedTextField(last, { last = it }, label = { Text("结束题号") }, modifier = Modifier.weight(1f))
                }
                OutlinedTextField(initial, { initial = it }, label = { Text("已有进度范围（可选）") },
                    supportingText = { Text("例如 190-203；只记录已确认题号") })
                OutlinedTextField(due, { due = it }, label = { Text("截止日期 YYYY-MM-DD（可选）") })
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }, confirmButton = { Button(onClick = {
            runCatching {
                val planId = requireNotNull(state.planId) { "当前没有计划" }
                require(subjectId.isNotBlank() && title.isNotBlank()) { "请选择科目并填写目标名称" }
                require((first.isBlank()) == (last.isBlank())) { "请同时填写起止题号" }
                val interval = if (first.isBlank()) null else ContentInterval(
                    first.toIntOrNull() ?: error("起始题号无效"), last.toIntOrNull() ?: error("结束题号无效"))
                val initialRanges = ContentRangeText.parse(initial) ?: error("已有进度范围格式无效")
                require(initialRanges.isEmpty() || interval != null &&
                    IntervalMath.remaining(initialRanges, listOf(interval)).isEmpty()) {
                    "已有进度必须在目标题号范围内"
                }
                val resource = state.resources.firstOrNull { it.id == resourceId }
                val scope = ContentSelection(resourceName = resource?.name.orEmpty(), edition = resource?.edition.orEmpty(),
                    chapter = chapter.trim(), kind = if (interval == null) "UNIT" else "QUESTION",
                    intervals = listOfNotNull(interval))
                (original ?: LearningGoalEntity(planId = planId, subjectId = subjectId,
                    resourceId = resourceId, title = title.trim())).copy(
                    subjectId = subjectId, resourceId = resourceId, title = title.trim(),
                    scopeJson = ContentSelectionCodec.encode(scope),
                    initialProgressJson = if (initialRanges.isEmpty()) "" else
                        ContentSelectionCodec.encode(scope.copy(intervals = initialRanges)),
                    dueDate = due.trim().takeIf(String::isNotBlank)?.let(LocalDate::parse),
                )
            }.onSuccess(onSave).onFailure { error = it.message ?: "目标信息无效" }
        }) { Text("保存") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
