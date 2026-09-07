package com.example.lixing.ui.screen.mine

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import com.example.lixing.data.local.entity.PhaseEntity
import com.example.lixing.domain.model.CommitmentMetric
import com.example.lixing.ui.theme.LiXingRadius
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 目标完成率的常用档位。手输太麻烦，这几个值覆盖绝大多数情况。 */
private val TARGET_PRESETS = listOf(70, 80, 85, 90, 95)

/**
 * 立约对话框。
 *
 * 定约的这一刻用户是清醒的，所以这里让他把话说明白：目标多少、区间多长、
 * 给自己留句什么话。到期由 App 翻账本判定，由不得当时的自己解释。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommitmentDialog(
    draft: CommitmentDraft,
    phases: List<PhaseEntity>,
    onUpdate: ((CommitmentDraft) -> CommitmentDraft) -> Unit,
    onPickPhase: (String?) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    var picking by remember { mutableStateOf<DateField?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = LiXingRadius.Hero,
        title = { Text("给自己立个约", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = draft.title,
                    onValueChange = { v -> onUpdate { it.copy(title = v) } },
                    label = { Text("承诺") },
                    placeholder = { Text("如：强化阶段完成率不低于 85%") },
                    modifier = Modifier.fillMaxWidth(),
                    isError = draft.title.isBlank() && draft.error != null,
                )

                if (phases.isNotEmpty()) {
                    Text(
                        "区间",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(
                            selected = draft.phaseId == null,
                            onClick = { onPickPhase(null) },
                            label = { Text("自定义") },
                        )
                        phases.forEach { phase ->
                            FilterChip(
                                selected = draft.phaseId == phase.id,
                                onClick = { onPickPhase(phase.id) },
                                label = { Text(phase.name) },
                            )
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { picking = DateField.START },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("从 ${draft.startDate}", style = MaterialTheme.typography.labelLarge)
                    }
                    OutlinedButton(
                        onClick = { picking = DateField.END },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("到 ${draft.endDate}", style = MaterialTheme.typography.labelLarge)
                    }
                }

                Text(
                    "目标完成率",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CommitmentMetric.entries.forEach { metric ->
                        FilterChip(
                            selected = draft.metric == metric,
                            onClick = {
                                onUpdate {
                                    it.copy(
                                        metric = metric,
                                        targetRatePercent = when (metric) {
                                            CommitmentMetric.COMPLETION_RATE -> 85
                                            CommitmentMetric.FOCUS_MINUTES -> 600
                                            CommitmentMetric.ACHIEVED_DAYS -> 10
                                        },
                                    )
                                }
                            },
                            label = { Text(metric.label) },
                        )
                    }
                    (if (draft.metric == CommitmentMetric.COMPLETION_RATE) TARGET_PRESETS else emptyList()).forEach { preset ->
                        FilterChip(
                            selected = draft.targetRatePercent == preset,
                            onClick = { onUpdate { it.copy(targetRatePercent = preset) } },
                            label = { Text("$preset%") },
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = draft.targetRatePercent.toString(),
                        onValueChange = { v ->
                            val n = v.filter { it.isDigit() }.take(6).toIntOrNull() ?: 0
                            onUpdate { it.copy(targetRatePercent = n) }
                        },
                        label = { Text("目标") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        isError = !draft.targetValid,
                        supportingText = { Text("${draft.metric.label}目标，单位：${draft.metric.unit}") },
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = draft.rewardPoints.toString(),
                        onValueChange = { v ->
                            val n = v.filter { it.isDigit() }.take(4).toIntOrNull() ?: 0
                            onUpdate { it.copy(rewardPoints = n) }
                        },
                        label = { Text("达成奖励") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        isError = !draft.rewardValid,
                        modifier = Modifier.weight(1f),
                    )
                }

                OutlinedTextField(
                    value = draft.customReward,
                    onValueChange = { v -> onUpdate { it.copy(customReward = v) } },
                    label = { Text("自定义奖励（可选）") },
                    placeholder = { Text("例如：看一场电影、买一本想要的书") },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 2,
                )

                OutlinedTextField(
                    value = draft.note,
                    onValueChange = { v -> onUpdate { it.copy(note = v) } },
                    label = { Text("给自己的一句话（可选）") },
                    placeholder = { Text("结算时会连着成绩一起回看") },
                    modifier = Modifier.fillMaxWidth().height(84.dp),
                    maxLines = 3,
                )

                Text(
                    text = "按加权完成率判定，请假日和没排任务的日子不计入。" +
                        "达成发奖励积分，没达成只如实记录，不扣分。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                draft.error?.let { msg ->
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = draft.canSubmit) {
                Text("立约", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )

    picking?.let { field ->
        val initial = when (field) {
            DateField.START -> draft.startDate
            DateField.END -> draft.endDate
        }
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = initial
                .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { picking = null },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { millis ->
                        val date = Instant.ofEpochMilli(millis)
                            .atZone(ZoneId.systemDefault()).toLocalDate()
                        onUpdate { d -> d.withDate(field, date) }
                    }
                    picking = null
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { picking = null }) { Text("取消") } },
        ) {
            DatePicker(state = pickerState)
        }
    }
}

private enum class DateField { START, END }

/**
 * 改日期时顺带保证区间不倒挂：改开始日超过结束日就把结束日推齐，反之同理。
 * 让用户先存一个非法区间再报错是多余的一步。
 */
private fun CommitmentDraft.withDate(field: DateField, date: LocalDate): CommitmentDraft =
    when (field) {
        DateField.START -> copy(
            startDate = date,
            endDate = if (endDate.isBefore(date)) date else endDate,
            // 手改日期意味着不再跟着阶段走
            phaseId = null,
        )
        DateField.END -> copy(
            endDate = date,
            startDate = if (date.isBefore(startDate)) date else startDate,
            phaseId = null,
        )
    }
