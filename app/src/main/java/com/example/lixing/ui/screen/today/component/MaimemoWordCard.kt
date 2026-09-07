package com.example.lixing.ui.screen.today.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.lixing.domain.word.WordProgress
import com.example.lixing.ui.theme.LiXingRadius
import com.example.lixing.ui.theme.LocalHeroGradient

/**
 * 墨墨背单词同步卡。显示今日进度 + 一键同步。
 * 用渐变描边和主题呼应；未同步过时给出引导文案。
 */
@Composable
fun MaimemoWordCard(
    progress: WordProgress?,
    syncing: Boolean,
    message: String?,
    onSync: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val gradient = LocalHeroGradient.current

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = LiXingRadius.Tile,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shadowElevation = 1.dp,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = "📖", fontSize = 20.sp)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "墨墨背单词",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.weight(1f))
                if (progress != null) {
                    Text(
                        text = "${progress.finished}/${progress.total}",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            if (progress != null) {
                // 进度条
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(LiXingRadius.Pill)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(progress.ratio)
                            .height(8.dp)
                            .clip(LiXingRadius.Pill)
                            .background(Brush.linearGradient(gradient)),
                    )
                }
                Text(
                    text = "今日已背 ${progress.finished} 个 · 学习 ${progress.studyMinutes} 分钟",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = message ?: "点「同步」拉取墨墨今日进度，自动填到单词任务里。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onSync,
                    enabled = !syncing,
                    shape = LiXingRadius.Pill,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                    ),
                ) {
                    if (syncing) {
                        CircularProgressIndicator(
                            Modifier.size(16.dp), strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    Text("同步打卡", fontWeight = FontWeight.Bold)
                }
                androidx.compose.material3.TextButton(onClick = onRefresh) {
                    Text("刷新")
                }
            }
        }
    }
}
