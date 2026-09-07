package com.example.lixing.ui.screen.mine

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.lixing.data.local.entity.CommitmentEntity
import com.example.lixing.domain.model.CommitmentStatus
import com.example.lixing.domain.settle.CommitmentProgress
import com.example.lixing.ui.theme.LiXingRadius
import com.example.lixing.ui.theme.StatusDone
import com.example.lixing.ui.theme.StatusMissed
import com.example.lixing.ui.theme.StatusPartial
import java.time.format.DateTimeFormatter

private val RANGE_FMT = DateTimeFormatter.ofPattern("M/d")

/**
 * 进行中的承诺卡。
 *
 * 三个数常驻显示：已过天数/总天数、当前完成率、还差多少。
 * 只在到期那天算一次的话它就只是个许愿池——中途看得见才推得动人。
 */
@Composable
fun ActiveCommitmentCard(
    item: ActiveCommitment,
    onAbandon: (CommitmentEntity) -> Unit,
) {
    val c = item.commitment
    val p = item.progress
    var confirmAbandon by remember { mutableStateOf(false) }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = LiXingRadius.Card,
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = c.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = buildString {
                        append("+${c.rewardPoints}分")
                        if (c.customReward.isNotBlank()) append(" · ${c.customReward}")
                    },
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            Spacer(Modifier.height(4.dp))
            Text(
                text = "${c.startDate.format(RANGE_FMT)} – ${c.endDate.format(RANGE_FMT)}" +
                    " · 第 ${p.elapsedDays}/${p.totalDays} 天" +
                    if (p.isLastDay) " · 今天最后一天" else " · 还剩 ${p.daysLeft} 天",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(12.dp))

            Text(
                text = "${c.metric.label}：${p.currentRatePercent}${c.metric.unit} / ${p.targetRatePercent}${c.metric.unit}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))

            // 进度条按「当前完成率 / 目标完成率」画，满格 = 达标，不是画绝对完成率
            val ratio = if (p.targetRatePercent == 0) {
                0f
            } else {
                (p.currentRatePercent.toFloat() / p.targetRatePercent).coerceIn(0f, 1f)
            }
            val barColor = when {
                !p.hasData -> MaterialTheme.colorScheme.outline
                p.onTrack -> StatusDone
                ratio >= 0.9f -> StatusPartial
                else -> StatusMissed
            }
            LinearProgressIndicator(
                progress = { ratio },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(LiXingRadius.Pill),
                color = barColor,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            )

            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = when {
                        !p.hasData -> "还没有可统计的学习日"
                        p.onTrack -> "当前 ${p.currentRatePercent}% · 已达标 ${p.targetRatePercent}%"
                        else -> "当前 ${p.currentRatePercent}% · 距目标 ${p.targetRatePercent}% 还差 ${p.gapPercent} 个点"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (p.onTrack) StatusDone else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { confirmAbandon = !confirmAbandon }) {
                    Text(
                        text = if (confirmAbandon) "点此确认" else "放弃",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (confirmAbandon) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }

            if (confirmAbandon) {
                Text(
                    text = "放弃不扣分，只是如实记下这次没走完。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(
                    onClick = {
                        confirmAbandon = false
                        onAbandon(c)
                    },
                ) {
                    Text("确认放弃", color = MaterialTheme.colorScheme.error)
                }
            }

            if (c.note.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                CommitmentNote(c.note)
            }
        }
    }
}

/** 已结算的承诺：当初的目标、最终成绩、当时写给自己的那句话。 */
@Composable
fun SettledCommitmentCard(
    commitment: CommitmentEntity,
    onDelete: (String) -> Unit,
) {
    val statusColor = when (commitment.status) {
        CommitmentStatus.SUCCEEDED -> StatusDone
        CommitmentStatus.FAILED -> StatusMissed
        else -> MaterialTheme.colorScheme.outline
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = LiXingRadius.Card,
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = commitment.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = commitment.status.label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = statusColor,
                )
            }

            Spacer(Modifier.height(4.dp))
            Text(
                text = buildString {
                    append("${commitment.startDate.format(RANGE_FMT)} – ")
                    append(commitment.endDate.format(RANGE_FMT))
                    append(" · 目标 ${commitment.targetRatePercent}%")
                    commitment.actualRatePercent?.let { append(" · 实际 $it%") }
                    if (commitment.status == CommitmentStatus.SUCCEEDED) {
                        append(" · +${commitment.rewardPoints} 分")
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (commitment.note.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                CommitmentNote(commitment.note)
            }

            if (commitment.status == CommitmentStatus.SUCCEEDED && commitment.customReward.isNotBlank()) {
                Text(
                    text = "自定义奖励：${commitment.customReward}",
                    style = MaterialTheme.typography.bodySmall,
                    color = StatusDone,
                )
            }

            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = { onDelete(commitment.id) }) {
                    Text(
                        "删除",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** 给自己的那句话。半年后在结算页读到时，分量和当初写的时候不一样。 */
@Composable
private fun CommitmentNote(note: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = LiXingRadius.Card,
    ) {
        Text(
            text = "「$note」",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(10.dp),
        )
    }
}
