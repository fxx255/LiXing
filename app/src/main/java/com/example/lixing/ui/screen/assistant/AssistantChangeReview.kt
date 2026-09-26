package com.example.lixing.ui.screen.assistant

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.lixing.ui.screen.assistant.PlanChangeScope.LONG_TERM
import com.example.lixing.ui.screen.assistant.PlanChangeScope.TODAY
import com.example.lixing.ui.theme.LiXingRadius
import java.time.format.DateTimeFormatter
/**
 * 挂在产生待确认项的那条 assistant 气泡下面的入口按钮。
 *
 * 计划与英语积累各一个、互不合并；对应类别没有待确认内容时该按钮整体不渲染，
 * 两个计数都是 0 时本组件不输出任何内容。
 */
@Composable
internal fun AssistantMessageActionBar(
    planCount: Int,
    englishCount: Int,
    onOpenPlan: () -> Unit,
    onOpenEnglish: () -> Unit,
    /**
     * 已确认应用过的条数。
     *
     * 有它才能把入口留成**灰态**而不是让按钮凭空消失：用户点完确认、锁屏或切走
     * 再回来时，需要看得出「那次确认确实生效了」。以前这里什么都没有，
     * 用户只能看到按钮没了，无从判断。
     */
    planAppliedCount: Int = 0,
) {
    if (planCount == 0 && englishCount == 0 && planAppliedCount == 0) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (planCount > 0) {
            FilledTonalButton(
                onClick = onOpenPlan,
                modifier = Modifier.fillMaxWidth(),
                shape = LiXingRadius.Pill,
            ) {
                Text("📋 确认计划调整（$planCount 项）")
            }
        } else if (planAppliedCount > 0) {
            // 不可点的灰态留痕，不是错误提示，所以不用 errorContainer
            OutlinedButton(
                onClick = {},
                enabled = false,
                modifier = Modifier.fillMaxWidth(),
                shape = LiXingRadius.Pill,
            ) {
                Text("✅ 已应用 $planAppliedCount 项修改")
            }
        }
        if (englishCount > 0) {
            FilledTonalButton(
                onClick = onOpenEnglish,
                modifier = Modifier.fillMaxWidth(),
                shape = LiXingRadius.Pill,
            ) {
                Text("📖 确认英语积累（$englishCount 条）")
            }
        }
    }
}

@Composable
internal fun PlanChangeReviewPage(
    items: List<PendingPlanAction>,
    reviewDate: java.time.LocalDate?,
    applying: Boolean,
    onToggle: (Int) -> Unit,
    onApply: () -> Unit,
    onRejectAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val indexedItems = items.withIndex().toList()
    val todayItems = indexedItems.filter { it.value.scope == TODAY }
    val longTermItems = indexedItems.filter { it.value.scope == LONG_TERM }
    val selectableCount = items.count { it.problem == null }
    val selectedCount = items.count { it.selected && it.problem == null }
    val dateText = reviewDate?.format(DateTimeFormatter.ofPattern("M月d日")) ?: "当天"

    Column(modifier) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "review-summary") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "逐项核对后再决定",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "未勾选的修改不会执行。存在校验问题的项目已自动禁用。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (todayItems.isNotEmpty()) {
                item(key = "today-heading") {
                    PlanChangeSectionHeading(
                        title = "仅今日生效",
                        description = "只调整 $dateText 的任务，不改变任务模板和之后的安排。",
                    )
                }
                itemsIndexed(todayItems, key = { _, entry -> "today-${entry.index}" }) { _, entry ->
                    PlanChangeReviewItem(
                        item = entry.value,
                        applying = applying,
                        onToggle = { onToggle(entry.index) },
                    )
                }
            }
            if (longTermItems.isNotEmpty()) {
                item(key = "long-term-heading") {
                    PlanChangeSectionHeading(
                        title = "长期计划",
                        description = "会修改时段或任务模板，并影响之后生成的任务。",
                    )
                }
                itemsIndexed(longTermItems, key = { _, entry -> "long-term-${entry.index}" }) { _, entry ->
                    PlanChangeReviewItem(
                        item = entry.value,
                        applying = applying,
                        onToggle = { onToggle(entry.index) },
                    )
                }
            }
            item(key = "review-safety-note") {
                Text(
                    "接受前会自动创建 before_ai_apply 恢复点；历史任务、积分和成就不会被修改。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Surface(tonalElevation = 3.dp) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    "已选择 $selectedCount/$selectableCount 项可应用修改",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = onRejectAll,
                        enabled = !applying,
                        modifier = Modifier.weight(1f),
                    ) { Text("全部拒绝") }
                    Button(
                        onClick = onApply,
                        enabled = selectedCount > 0 && !applying,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(if (applying) "正在应用" else "接受所选")
                    }
                }
            }
        }
    }
}

@Composable
private fun PlanChangeSectionHeading(title: String, description: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PlanChangeReviewItem(
    item: PendingPlanAction,
    applying: Boolean,
    onToggle: () -> Unit,
) {
    val enabled = item.problem == null && !applying
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = LiXingRadius.Card,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 12.dp, end = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Checkbox(
                checked = item.selected && item.problem == null,
                onCheckedChange = { onToggle() },
                enabled = enabled,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(item.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        "修改前",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(item.before, style = MaterialTheme.typography.bodyMedium)
                }
                HorizontalDivider()
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        "修改后",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(item.after, style = MaterialTheme.typography.bodyMedium)
                }
                if (item.action.reason.isNotBlank()) {
                    Text(
                        "调整原因：${item.action.reason}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                item.problem?.let { problem ->
                    Text(
                        "无法应用：$problem",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
internal fun EnglishChangeReviewPage(
    items: List<PendingEnglishAction>,
    applying: Boolean,
    onToggle: (Int) -> Unit,
    onApply: () -> Unit,
    onRejectAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val selectableCount = items.count { it.problem == null }
    val selectedCount = items.count { it.selected && it.problem == null }

    Column(modifier) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "english-review-summary") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "逐项核对后再写入英语积累",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "未勾选的不会执行。删除只有在这里勾选并接受后才会真正生效。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            itemsIndexed(items, key = { index, _ -> "english-$index" }) { index, item ->
                EnglishChangeReviewItem(
                    item = item,
                    applying = applying,
                    onToggle = { onToggle(index) },
                )
            }
        }

        Surface(tonalElevation = 3.dp) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    "已选择 $selectedCount/$selectableCount 项",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = onRejectAll,
                        enabled = !applying,
                        modifier = Modifier.weight(1f),
                    ) { Text("全部拒绝") }
                    Button(
                        onClick = onApply,
                        enabled = selectedCount > 0 && !applying,
                        modifier = Modifier.weight(1f),
                    ) { Text(if (applying) "正在写入" else "接受所选") }
                }
            }
        }
    }
}

@Composable
private fun EnglishChangeReviewItem(
    item: PendingEnglishAction,
    applying: Boolean,
    onToggle: () -> Unit,
) {
    val enabled = item.problem == null && !applying
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = LiXingRadius.Card,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 12.dp, end = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Checkbox(
                checked = item.selected && item.problem == null,
                onCheckedChange = { onToggle() },
                enabled = enabled,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(item.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        "变更前",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(item.before, style = MaterialTheme.typography.bodyMedium)
                }
                HorizontalDivider()
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        "变更后",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(item.after, style = MaterialTheme.typography.bodyMedium)
                }
                if (item.action.reason.isNotBlank()) {
                    Text(
                        "调整原因：${item.action.reason}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                item.problem?.let { problem ->
                    Text(
                        "无法应用：$problem",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}
