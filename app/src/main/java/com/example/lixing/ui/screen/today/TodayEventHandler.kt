package com.example.lixing.ui.screen.today

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.lixing.domain.usecase.CheckInResult

/**
 * 处理一次性事件：
 * - 打卡成功：底部 Snackbar 飘出「+N 积分」+ 激励文案，命中升级/成就时升级为弹窗。
 * - 补卡被拒：弹窗说明原因。
 */
@Composable
fun TodayEventHandler(
    events: TodayEvent?,
    onConsume: () -> Unit,
) {
    if (events == null) return

    when (events) {
        is TodayEvent.CheckInSuccess -> {
            val result = events.result
            val leveledUp = result.levelChange?.leveledUp == true
            val hasAchievement = result.newAchievements.isNotEmpty()

            if (leveledUp || hasAchievement) {
                CelebrationDialog(result, onConsume)
            } else {
                SimpleToast(result, onConsume)
            }
        }

        is TodayEvent.CommitmentSettled -> {
            CommitmentSettledDialog(events, onConsume)
        }

        is TodayEvent.MakeupRejected -> {
            AlertDialog(
                onDismissRequest = onConsume,
                title = { Text("无法补卡") },
                text = {
                    Text(
                        when (events.result.reason) {
                            CheckInResult.MakeupRejected.Reason.TOO_OLD ->
                                "只能补昨天的任务。更早的已经无从补起了。"

                            CheckInResult.MakeupRejected.Reason.QUOTA_EXHAUSTED ->
                                "本周的补卡额度已经用完了，明天再来。"
                        },
                    )
                },
                confirmButton = { TextButton(onClick = onConsume) { Text("知道了") } },
            )
        }
    }
}

/**
 * 承诺到期结算结果。
 *
 * 一条一条看，不合并成一个列表——半年前给自己写的那句话值得单独占一屏，
 * 混在列表里划过去就没意义了。
 */
@Composable
private fun CommitmentSettledDialog(
    event: TodayEvent.CommitmentSettled,
    onConsume: () -> Unit,
) {
    var index by remember { mutableStateOf(0) }
    val outcome = event.outcomes.getOrNull(index) ?: run {
        onConsume()
        return
    }
    val commitment = outcome.settled
    val isLast = index == event.outcomes.lastIndex

    AlertDialog(
        onDismissRequest = onConsume,
        title = {
            Text(
                text = when {
                    outcome.succeeded -> "🎯 承诺达成"
                    outcome.voided -> "承诺作废"
                    else -> "承诺到期"
                },
                fontWeight = FontWeight.Bold,
                color = if (outcome.succeeded) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
        },
        text = {
            Column {
                Text(commitment.title, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(
                    text = outcome.summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (outcome.rewardPoints > 0) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "+${outcome.rewardPoints} 积分",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                outcome.customReward?.let { reward ->
                    Spacer(Modifier.height(8.dp))
                    Text("请兑现你的奖励：$reward", fontWeight = FontWeight.Bold)
                }
                if (commitment.note.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "当初你写的：",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "「${commitment.note}」",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (!outcome.succeeded && !outcome.voided) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "没达成不扣分。下次把目标定在够得着的地方。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { if (isLast) onConsume() else index++ }) {
                Text(if (isLast) "知道了" else "下一条")
            }
        },
    )
}

/** 普通打卡成功：一个自动消失的弹窗，展示积分 + 文案。 */
@Composable
private fun SimpleToast(result: CheckInResult.Success, onConsume: () -> Unit) {
    LaunchedEffect(result) {
        kotlinx.coroutines.delay(2200)
        onConsume()
    }
    AlertDialog(
        onDismissRequest = onConsume,
        title = {
            Text(
                if (result.pointsGained > 0) "+${result.pointsGained} 积分" else "已记录",
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
        },
        text = { Text(result.encouragement) },
        confirmButton = { TextButton(onClick = onConsume) { Text("继续") } },
    )
}

/** 升级 / 解锁成就：全屏庆祝弹窗。 */
@Composable
private fun CelebrationDialog(result: CheckInResult.Success, onConsume: () -> Unit) {
    val levelChange = result.levelChange
    val achievements = result.newAchievements

    AlertDialog(
        onDismissRequest = onConsume,
        title = {
            Text(
                when {
                    levelChange?.leveledUp == true && levelChange.unlockedNewTitle ->
                        "🎉 升级啦！新称号「${levelChange.newTitle}」"

                    levelChange?.leveledUp == true -> "🎉 升到 Lv.${levelChange.newLevel}"

                    achievements.isNotEmpty() -> "🏆 解锁新成就"

                    else -> "干得漂亮"
                },
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column {
                achievements.forEach { a ->
                    Text(
                        text = "★ ${a.achievement.title}",
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = a.achievement.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (result.pointsGained > 0) {
                    Spacer(Modifier.height(8.dp))
                    Text("本次共获得 +${result.pointsGained} 积分")
                }
            }
        },
        confirmButton = { TextButton(onClick = onConsume) { Text("太棒了") } },
    )
}
