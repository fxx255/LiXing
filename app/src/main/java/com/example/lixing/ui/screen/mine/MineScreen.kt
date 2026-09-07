package com.example.lixing.ui.screen.mine

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.lixing.data.local.entity.AchievementEntity
import com.example.lixing.ui.theme.StatusDone
import java.time.format.DateTimeFormatter

private val DATE_FMT = DateTimeFormatter.ofPattern("MM-dd")

/** 我的页：档案头 + 连续记录 + 成就墙 + 积分流水。 */
@Composable
fun MineScreen(
    onOpenSettings: () -> Unit = {},
    onOpenReports: () -> Unit = {},
    viewModel: MineViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val draft by viewModel.commitmentEditor.collectAsStateWithLifecycle()

    draft?.let { d ->
        CommitmentDialog(
            draft = d,
            phases = state.phases,
            onUpdate = viewModel::updateDraft,
            onPickPhase = viewModel::pickPhase,
            onConfirm = viewModel::saveCommitment,
            onDismiss = viewModel::dismissCommitmentEditor,
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "我的",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                androidx.compose.material3.TextButton(onClick = onOpenReports) {
                    Text("周报")
                }
                androidx.compose.material3.TextButton(onClick = onOpenSettings) {
                    Text("设置")
                }
            }
        }
        item { ProfileHeader(state) }
        item { StreakSummary(state) }

        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "自我承诺",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                androidx.compose.material3.TextButton(onClick = viewModel::startNewCommitment) {
                    Text("立约")
                }
            }
        }
        if (state.activeCommitments.isEmpty() && state.settledCommitments.isEmpty()) {
            item { CommitmentEmptyHint() }
        }
        items(state.activeCommitments, key = { "commit-active-${it.commitment.id}" }) { item ->
            ActiveCommitmentCard(item, onAbandon = viewModel::abandonCommitment)
        }
        items(state.settledCommitments, key = { "commit-settled-${it.id}" }) { commitment ->
            SettledCommitmentCard(commitment, onDelete = viewModel::deleteCommitment)
        }

        item {
            SectionTitle("成就  ${state.unlockedCount}/${state.achievements.size}")
        }
        items(state.achievements, key = { "achv-${it.id}" }) { achievement ->
            AchievementCard(achievement)
        }

        item { SectionTitle("最近积分") }
        items(state.recentLedger.take(30), key = { "ledger-${it.id}" }) { entry ->
            LedgerRow(entry)
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

/** 档案头：渐变底 + 等级进度。和今日页的 D-Day 卡同一视觉家族。 */
@Composable
private fun ProfileHeader(state: MineUiState) {
    val profile = state.profile
    val gradient = com.example.lixing.ui.theme.LocalHeroGradient.current

    Box(
        Modifier
            .fillMaxWidth()
            .clip(com.example.lixing.ui.theme.LiXingRadius.Hero)
            .background(
                androidx.compose.ui.graphics.Brush.linearGradient(
                    colors = gradient,
                    start = androidx.compose.ui.geometry.Offset.Zero,
                    end = androidx.compose.ui.geometry.Offset.Infinite,
                ),
            )
            .padding(20.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = profile?.nickname?.ifEmpty { "同学" } ?: "同学",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = androidx.compose.ui.graphics.Color.White,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "Lv.${profile?.level ?: 1} · ${profile?.title ?: "初心者"}",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = androidx.compose.ui.graphics.Color.White,
                    modifier = Modifier
                        .clip(com.example.lixing.ui.theme.LiXingRadius.Pill)
                        .background(androidx.compose.ui.graphics.Color.White.copy(alpha = 0.22f))
                        .padding(horizontal = 12.dp, vertical = 5.dp),
                )
            }

            Spacer(Modifier.height(14.dp))

            LinearProgressIndicator(
                progress = { state.levelProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(9.dp)
                    .clip(com.example.lixing.ui.theme.LiXingRadius.Pill),
                color = androidx.compose.ui.graphics.Color.White,
                trackColor = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.3f),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "距下一级还需 ${state.pointsToNext} 分 · 累计 ${profile?.totalPoints ?: 0} 分",
                style = MaterialTheme.typography.bodySmall,
                color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.92f),
            )
        }
    }
}

@Composable
private fun StreakSummary(state: MineUiState) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            StatItem("🔥 当前连续", "${state.currentStreak} 天")
            StatItem("🏅 历史最长", "${state.longestStreak} 天")
        }
    }
}

@Composable
private fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
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

/** 没有任何承诺时的引导。说清立约的意义，而不是干巴巴一句「暂无」。 */
@Composable
private fun CommitmentEmptyHint() {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                text = "给未来的自己定个硬指标",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "定约的这一刻你是清醒的，到期由 App 翻账本判定。" +
                    "达成发奖励积分，没达成也不扣分。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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

@Composable
private fun AchievementCard(achievement: AchievementEntity) {
    val unlocked = achievement.unlockedAt != null
    val tierColor = when (achievement.tier) {
        3 -> MaterialTheme.colorScheme.tertiary
        2 -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.primary
    }

    Surface(
        color = if (unlocked) MaterialTheme.colorScheme.surfaceContainerHigh
        else MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(14.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 档位色点
            Box(
                Modifier.size(10.dp).clip(RoundedCornerShape(50))
                    .background(if (unlocked) tierColor else MaterialTheme.colorScheme.outline),
            )
            Spacer(Modifier.width(12.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    text = achievement.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (unlocked) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                )
                Text(
                    text = achievement.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!unlocked && achievement.threshold > 0) {
                    val progress = achievement.progress.coerceAtMost(achievement.threshold)
                    Text(
                        text = "进度 $progress/${achievement.threshold}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (unlocked) {
                    achievement.unlockedDetail?.let {
                        Text(
                            text = "达成：$it",
                            style = MaterialTheme.typography.labelSmall,
                            color = StatusDone,
                        )
                    }
                }
            }

            Spacer(Modifier.width(8.dp))
            Text(
                text = if (unlocked) "+${achievement.rewardPoints}" else "🔒",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = if (unlocked) StatusDone else MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
private fun LedgerRow(entry: com.example.lixing.data.local.entity.PointLedgerEntity) {
    val positive = entry.delta >= 0
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = entry.reason.label + (if (entry.detail.isNotEmpty()) " · ${entry.detail}" else ""),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = entry.date.format(DATE_FMT),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = if (positive) "+${entry.delta}" else "${entry.delta}",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = if (positive) StatusDone else MaterialTheme.colorScheme.error,
        )
    }
}
