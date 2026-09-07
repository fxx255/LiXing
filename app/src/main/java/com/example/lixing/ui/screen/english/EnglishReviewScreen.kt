package com.example.lixing.ui.screen.english

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.lixing.domain.english.EnglishEntryType
import com.example.lixing.domain.english.ReviewGrade
import com.example.lixing.ui.theme.LiXingRadius

/**
 * 英语背诵复习页（墨墨式翻转自评）。
 *
 * 流程：正面只显示英文 → 心里默想释义 → 点卡片翻面 → 选「忘记 / 模糊 / 认识」→ 下一张。
 * 评分交给 SM-2 调度器决定下次出现的时间。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnglishReviewScreen(
    onBack: () -> Unit,
    onOpenNotebook: () -> Unit,
    viewModel: EnglishReviewViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("背诵复习") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "返回") }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                state.loading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }

                state.finished -> {
                    FinishedPanel(
                        good = state.good,
                        hard = state.hard,
                        again = state.again,
                        onRestart = viewModel::start,
                        onBack = onBack,
                    )
                }

                state.queue.isEmpty() -> {
                    EmptyPanel(onOpenNotebook = onOpenNotebook)
                }

                else -> {
                    ReviewCard(
                        state = state,
                        onReveal = viewModel::reveal,
                        onGrade = viewModel::grade,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ReviewCard(
    state: ReviewSessionState,
    onReveal: () -> Unit,
    onGrade: (ReviewGrade) -> Unit,
    modifier: Modifier = Modifier,
) {
    val entry = state.current ?: return
    Column(modifier = modifier) {
        // 进度
        val progress = if (state.total == 0) 0f else state.index.toFloat() / state.total.toFloat()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "第 ${state.index + 1} / ${state.total} 张",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = typeLabel(entry.type),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(24.dp))

        // 卡片（点击翻面）
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clickable(enabled = !state.revealed) { onReveal() },
            shape = LiXingRadius.Card,
            color = MaterialTheme.colorScheme.surfaceVariant,
            tonalElevation = 1.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = entry.content,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )

                if (state.revealed) {
                    Spacer(Modifier.height(24.dp))
                    Text(
                        text = entry.meaning,
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Spacer(Modifier.height(24.dp))
                    Text(
                        text = "点击卡片查看释义",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        if (state.revealed) {
            Row(modifier = Modifier.fillMaxWidth()) {
                FilledTonalButton(
                    onClick = { onGrade(ReviewGrade.AGAIN) },
                    modifier = Modifier.weight(1f),
                ) { Text("忘记") }
                Spacer(Modifier.width(8.dp))
                FilledTonalButton(
                    onClick = { onGrade(ReviewGrade.HARD) },
                    modifier = Modifier.weight(1f),
                ) { Text("模糊") }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { onGrade(ReviewGrade.GOOD) },
                    modifier = Modifier.weight(1f),
                ) { Text("认识") }
            }
        } else {
            OutlinedButton(
                onClick = onReveal,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("显示释义") }
        }
    }
}

@Composable
private fun FinishedPanel(
    good: Int,
    hard: Int,
    again: Int,
    onRestart: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "这一轮完成 🎉",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "认识 $good · 模糊 $hard · 忘记 $again",
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "忘记的卡片 10 分钟后会再来一次",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(28.dp))
        OutlinedButton(onClick = onRestart, modifier = Modifier.fillMaxWidth()) {
            Text("再来一轮")
        }
        Spacer(Modifier.height(8.dp))
        Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text("完成")
        }
    }
}

@Composable
private fun EmptyPanel(onOpenNotebook: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "今天没有待复习的卡片",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "新学的单词/短语会自动进入背诵队列，也可以去积累本补几条。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        OutlinedButton(onClick = onOpenNotebook) { Text("去英语积累") }
    }
}

private fun typeLabel(type: EnglishEntryType): String = when (type) {
    EnglishEntryType.WORD -> "单词"
    EnglishEntryType.PHRASE -> "短语"
    EnglishEntryType.SENTENCE -> "句子"
}
