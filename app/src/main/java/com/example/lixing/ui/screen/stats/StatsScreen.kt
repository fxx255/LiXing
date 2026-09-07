package com.example.lixing.ui.screen.stats

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.lixing.ui.theme.LocalHeatColors
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/** 统计页：概览 + 热力图 + 时段完成率 + 科目时长。 */
@Composable
fun StatsScreen(
    viewModel: StatsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { OverviewRow(state) }

        item { SectionTitle("年度打卡热力图") }
        item { HeatmapView(viewModel, state.today) }

        item { SectionTitle("时段完成率（近 30 天）") }
        items(state.slotCompletion.size, key = { "slot-${state.slotCompletion[it].slotId}" }) { index ->
            val sc = state.slotCompletion[index]
            SlotCompletionRow(sc.slotName, sc.rate)
        }

        item { SectionTitle("科目投入（近 30 天）") }
        item { SubjectMinutesList(state) }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun OverviewRow(state: StatsUiState) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            StatItem("近30天完成率", "${(state.avgCompletionRate * 100).toInt()}%")
            StatItem("近30天专注", formatMinutes(state.totalFocusMinutes))
        }
    }
}

@Composable
private fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 8.dp),
    )
}

/** GitHub 风格月度分页热力图；栏目仍保留“年度”入口，最近 12 个月逐页查看。 */
@Composable
private fun HeatmapView(viewModel: StatsViewModel, studyToday: LocalDate) {
    val heatColors = LocalHeatColors.current
    val today = studyToday.takeUnless { it == LocalDate.MIN } ?: LocalDate.now()
    var monthOffset by rememberSaveable { mutableIntStateOf(0) }
    val month = YearMonth.from(today).plusMonths(monthOffset.toLong())
    val firstDay = month.atDay(1)
    val gridStart = firstDay.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val weekCount = ((ChronoUnit.DAYS.between(gridStart, month.atEndOfMonth()) + 7) / 7).toInt()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TextButton(
                onClick = { monthOffset-- },
                enabled = monthOffset > -11,
            ) { Text("上月") }
            Text(
                text = "${month.year} 年 ${month.monthValue} 月",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            TextButton(
                onClick = { monthOffset++ },
                enabled = monthOffset < 0,
            ) { Text("下月") }
        }

        Spacer(Modifier.height(6.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            repeat(weekCount) { weekIndex ->
                val weekStart = gridStart.plusWeeks(weekIndex.toLong())
                Column(
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    for (dayOffset in 0 until 7) {
                        val date = weekStart.plusDays(dayOffset.toLong())
                        val inMonth = YearMonth.from(date) == month
                        val value = if (!inMonth || date.isAfter(today)) 0f else viewModel.heatmapValue(date)
                        val level = when {
                            value <= 0f -> 0
                            value < 0.25f -> 1
                            value < 0.5f -> 2
                            value < 0.75f -> 3
                            else -> 4
                        }
                        Box(
                            Modifier
                                .size(18.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (inMonth) heatColors[level] else Color.Transparent),
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        // 图例
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("少", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(6.dp))
            heatColors.forEach { color ->
                Box(
                    Modifier.size(12.dp).clip(RoundedCornerShape(3.dp)).background(color),
                )
                Spacer(Modifier.width(3.dp))
            }
            Text("多", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SlotCompletionRow(slotName: String, rate: Float) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = slotName,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.width(60.dp),
            )
            Box(
                Modifier
                    .weight(1f)
                    .height(10.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(rate.coerceIn(0f, 1f))
                        .height(10.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = "${(rate * 100).toInt()}%",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SubjectMinutesList(state: StatsUiState) {
    if (state.subjectMinutes.isEmpty()) {
        Text(
            text = "还没有打卡记录",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        state.subjectMinutes.sortedByDescending { it.minutes }.forEach { sm ->
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = sm.subjectName,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.width(70.dp),
                )
                Text(
                    text = formatMinutes(sm.minutes),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

private fun formatMinutes(totalMinutes: Int): String {
    val h = totalMinutes / 60
    val m = totalMinutes % 60
    return if (h > 0) "${h}h${m}m" else "${m}m"
}
