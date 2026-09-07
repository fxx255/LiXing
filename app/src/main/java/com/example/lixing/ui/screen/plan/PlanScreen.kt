package com.example.lixing.ui.screen.plan

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.lixing.ui.theme.LiXingRadius
import com.example.lixing.ui.theme.LocalHeroGradient
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val DATE_FMT = DateTimeFormatter.ofPattern("M月d日")

/** 计划页：宏观总览 + 管理/导入入口。 */
@Composable
fun PlanScreen(
    onOpenManage: () -> Unit = {},
    viewModel: PlanViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("计划", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)

        if (state.hasPlan) {
            PlanOverview(state)

            Button(
                onClick = onOpenManage,
                shape = LiXingRadius.Pill,
                modifier = Modifier.fillMaxWidth().height(50.dp),
            ) {
                Text("管理阶段 / 科目 / 时段 / 任务", fontWeight = FontWeight.Bold)
            }

            OutlinedButton(
                onClick = viewModel::importKaoyanPlan,
                enabled = !state.isImporting,
                shape = LiXingRadius.Pill,
                modifier = Modifier.fillMaxWidth().height(50.dp),
            ) {
                if (state.isImporting) {
                    CircularProgressIndicator(Modifier.height(20.dp).padding(end = 8.dp), strokeWidth = 2.dp)
                }
                Text("重新导入考研计划（会覆盖）")
            }
        } else {
            Text(
                text = "导入「考研全程计划」，按基础/强化/冲刺三个阶段，覆盖数学、英语、政治、专业课。时段与任务之后都可以改。",
                style = MaterialTheme.typography.bodyLarge,
            )

            Button(
                onClick = viewModel::importKaoyanPlan,
                enabled = !state.isImporting,
                shape = LiXingRadius.Pill,
                modifier = Modifier.fillMaxWidth().height(50.dp),
            ) {
                if (state.isImporting) {
                    CircularProgressIndicator(Modifier.height(20.dp).padding(end = 8.dp), strokeWidth = 2.dp)
                }
                Text("一键导入考研全程计划")
            }
        }

        state.importMessage?.let { msg ->
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = LiXingRadius.Card,
            ) {
                Text(
                    text = msg,
                    modifier = Modifier.padding(14.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }

        Text(
            text = "数据仅存本机，不上传。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            textAlign = TextAlign.Center,
        )
    }
}

/** 宏观总览：渐变头卡 + 阶段时间轴 + 规模统计 + 科目色点。 */
@Composable
private fun PlanOverview(state: PlanUiState) {
    val gradient = LocalHeroGradient.current

    Surface(shape = LiXingRadius.Hero, color = Color.Transparent) {
        Box(
            Modifier
                .clip(LiXingRadius.Hero)
                .background(Brush.linearGradient(gradient))
                .padding(20.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                // 名称 + D-Day
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = state.planName.orEmpty(),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                        )
                        state.currentPhaseName?.let {
                            Text(
                                text = "当前阶段 · $it",
                                style = MaterialTheme.typography.labelLarge,
                                color = Color.White.copy(alpha = 0.9f),
                            )
                        }
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "D-${state.daysToTarget}",
                            style = MaterialTheme.typography.displayMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                        )
                        state.targetDate?.let {
                            Text(
                                text = it.format(DATE_FMT),
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.9f),
                            )
                        }
                    }
                }

                // 阶段时间轴
                PhaseTimeline(state.phases, state.currentPhaseName, state.today)

                // 规模统计
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    OverviewStat("${state.subjects.size}", "科目")
                    OverviewStat("${state.slots.size}", "时段/天")
                    OverviewStat("${state.templateCount}", "任务模板")
                }

                // 科目色点
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    state.subjects.forEach { s ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .size(9.dp)
                                    .clip(LiXingRadius.Pill)
                                    .background(Color(s.colorArgb)),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = s.name,
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.92f),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 阶段时间轴：横向分段，当前阶段高亮并显示进度。 */
@Composable
private fun PhaseTimeline(
    phases: List<com.example.lixing.data.local.entity.PhaseEntity>,
    currentName: String?,
    today: LocalDate,
) {
    if (phases.isEmpty()) return
    val sorted = phases.sortedBy { it.startDate }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        sorted.forEach { phase ->
            val isCurrent = phase.name == currentName
            val progress = when {
                today < phase.startDate -> 0f
                today > phase.endDate -> 1f
                else -> {
                    val total = java.time.temporal.ChronoUnit.DAYS.between(phase.startDate, phase.endDate) + 1
                    val done = java.time.temporal.ChronoUnit.DAYS.between(phase.startDate, today) + 1
                    (done.toFloat() / total).coerceIn(0f, 1f)
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.Start,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = phase.name,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                    color = Color.White.copy(alpha = if (isCurrent) 1f else 0.7f),
                )
                // 进度条
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(LiXingRadius.Pill)
                        .background(Color.White.copy(alpha = 0.25f)),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(if (isCurrent) progress else if (progress >= 1f) 1f else 0f)
                            .height(6.dp)
                            .clip(LiXingRadius.Pill)
                            .background(Color.White),
                    )
                }
                Text(
                    text = "${phase.startDate.format(DATE_FMT)}–${phase.endDate.format(DATE_FMT)}",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                    color = Color.White.copy(alpha = 0.6f),
                )
            }
        }
    }
}

@Composable
private fun OverviewStat(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = Color.White,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.85f),
        )
    }
}
