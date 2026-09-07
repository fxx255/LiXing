package com.example.lixing.ui.screen.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.lixing.ui.screen.today.component.DDayHeader
import com.example.lixing.ui.screen.today.component.MaimemoWordCard
import com.example.lixing.ui.screen.today.component.ProgressAndStreakRow
import com.example.lixing.ui.screen.today.component.SlotSectionBlock
import com.example.lixing.ui.screen.today.dialog.CheckInDialog

/** 首页「今日」。 */
@Composable
fun TodayScreen(
    viewModel: TodayViewModel = hiltViewModel(),
    onNavigateToPlan: () -> Unit = {},
    onOpenHistory: () -> Unit = {},
    onOpenMeals: () -> Unit = {},
    onOpenEnglish: () -> Unit = {},
    onOpenAssistant: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val checkInTarget by viewModel.checkInTarget.collectAsStateWithLifecycle()
    val events by viewModel.events.collectAsStateWithLifecycle()

    if (state.isLoading) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator()
        }
        return
    }

    if (state.hasNoPlan) {
        EmptyPlanState(onNavigateToPlan, onOpenMeals, onOpenEnglish, onOpenAssistant)
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 8.dp,
                bottom = 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item { GreetingRow(state.today, onOpenHistory, onOpenMeals) }

            item {
                DDayHeader(
                    daysToTarget = state.daysToTarget,
                    targetDate = state.plan?.targetDate,
                    phaseName = state.currentPhase?.name,
                    level = state.level,
                    title = state.title,
                )
            }

            item {
                ProgressAndStreakRow(
                    stats = state.stats,
                    currentStreak = state.currentStreak,
                    weekAchieved = state.weekAchieved,
                )
            }

            if (state.maimemoEnabled) {
                item {
                    MaimemoWordCard(
                        progress = state.maimemoProgress,
                        syncing = state.maimemoSyncing,
                        message = state.maimemoMessage,
                        onSync = viewModel::syncWords,
                        onRefresh = viewModel::refreshMaimemo,
                    )
                }
            }

            items(
                items = state.slotSections,
                key = { "slot-${it.slotId}" },
            ) { section ->
                SlotSectionBlock(
                    section = section,
                    onCheckInClick = { viewModel.onCheckInClick(it) },
                    onLongPress = { viewModel.onTaskLongPress(it) },
                )
            }

            if (state.slotSections.isEmpty()) {
                item {
                    Text(
                        text = "今天还没有安排任务",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 32.dp),
                    )
                }
            }
        }
    }

    checkInTarget?.let { task ->
        CheckInDialog(
            task = task,
            onDismiss = { viewModel.dismissCheckIn() },
            onSubmit = { value, note, photo -> viewModel.submitCheckIn(task.id, value, note, photo) },
            onRevoke = { viewModel.revokeCheckIn(task.id) },
        )
    }

    TodayEventHandler(
        events = events,
        onConsume = viewModel::consumeEvent,
    )
}

/**
 * 顶部问候。按当前钟点换措辞——清晨到深夜四档，
 * 让打开 App 的第一眼是「有人在陪着」而不是「又要打卡了」。
 */
@Composable
private fun GreetingRow(
    today: java.time.LocalDate,
    onOpenHistory: () -> Unit = {},
    onOpenMeals: () -> Unit = {},
) {
    val hour = java.time.LocalTime.now().hour
    val greeting = when (hour) {
        in 5..10 -> "早上好，新的一天"
        in 11..13 -> "中午好，别忘了歇一会"
        in 14..18 -> "下午好，继续推进"
        in 19..22 -> "晚上好，收个尾"
        else -> "夜深了，注意休息"
    }
    Row(
        modifier = Modifier.padding(start = 4.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = greeting,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
            )
            Text(
                text = "${today.monthValue} 月 ${today.dayOfMonth} 日 · " +
                    com.example.lixing.domain.model.WeekdayMask.SHORT_NAMES[today.dayOfWeek.ordinal],
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        androidx.compose.material3.TextButton(onClick = onOpenMeals) { Text("饮食") }
        androidx.compose.material3.TextButton(onClick = onOpenHistory) { Text("回顾") }
    }
}

@Composable
private fun EmptyPlanState(
    onNavigateToPlan: () -> Unit,
    onOpenMeals: () -> Unit,
    onOpenEnglish: () -> Unit,
    onOpenAssistant: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "📚",
            style = MaterialTheme.typography.displayMedium,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "还没有学习计划",
            style = MaterialTheme.typography.titleLarge,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "导入「考研全程计划」，或自己创建一份，开始按时段打卡。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(24.dp))
        androidx.compose.material3.Button(onClick = onNavigateToPlan) {
            Text("去创建计划")
        }
        Spacer(Modifier.height(10.dp))
        androidx.compose.material3.TextButton(onClick = onOpenMeals) {
            Text("记录今日饮食")
        }
        androidx.compose.material3.TextButton(onClick = onOpenEnglish) {
            Text("打开英语积累")
        }
        androidx.compose.material3.TextButton(onClick = onOpenAssistant) {
            Text("问 AI 学习助手")
        }
    }
}
