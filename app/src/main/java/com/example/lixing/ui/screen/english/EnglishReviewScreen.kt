package com.example.lixing.ui.screen.english

import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.lixing.data.dictionary.DictionaryWord
import com.example.lixing.data.repository.memory
import com.example.lixing.domain.english.FsrsScheduler
import com.example.lixing.domain.english.ReviewGrade
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.ceil

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnglishReviewScreen(onBack: () -> Unit, onOpenNotebook: () -> Unit,
    viewModel: EnglishReviewViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val speech = remember { EnglishSpeech(context) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var licenses by remember { mutableStateOf(false) }
    DisposableEffect(speech, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) speech.stop() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); speech.close() }
    }
    LaunchedEffect(viewModel) {
        viewModel.feedback.collect {
            val vibrator = context.getSystemService(Vibrator::class.java)
            if (vibrator?.hasVibrator() == true) vibrator.vibrate(VibrationEffect.createOneShot(28, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }
    LaunchedEffect(state.current?.id) {
        speech.stop()
        if (state.preferences.englishAutoSpeak) state.current?.let { speech.speak(it.content, state.preferences.englishBritishVoice) }
    }
    val speak: (String) -> Unit = { speech.speak(it, state.preferences.englishBritishVoice) }
    if (licenses) DictionaryLicensesDialog { licenses = false }
    Scaffold(
        topBar = { TopAppBar(title = { Text("背诵复习") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
        }, actions = {
            TextButton(onClick = viewModel::undo, enabled = state.canUndo && !state.saving && !state.loading) { Text("撤销") }
            TextButton(onClick = { licenses = true }) { Text("词源") }
        }) },
        bottomBar = {
            if (state.current != null && state.revealed && state.preferences.englishReviewEnabled) {
                Surface(tonalElevation = 3.dp) {
                    Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(ReviewGrade.GOOD, ReviewGrade.HARD, ReviewGrade.AGAIN).forEach { grade ->
                            val next = FsrsScheduler.next(state.current!!.memory(), grade, state.now)
                            OutlinedButton(onClick = { viewModel.grade(grade) }, enabled = !state.saving,
                                modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(gradeLabel(grade), style = MaterialTheme.typography.titleMedium)
                                    Text(reviewIntervalLabel(next.dueAt!! - state.now), style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (state.error != null) {
                Text(state.error!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp))
                TextButton(onClick = viewModel::start, enabled = !state.saving) { Text("刷新重试") }
            }
            when {
                !state.preferences.englishReviewEnabled -> CenterMessage("英语背诵已关闭", "可以在设置中开启，原有积累和记忆记录会保留。", onOpenNotebook)
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                state.current == null -> {
                    val wait = state.nextLearningAt?.let { reviewIntervalLabel(it - state.now) }
                    CenterMessage(if (wait != null) "本轮待重学" else "当前复习已完成",
                        "本轮已完成 ${state.completed} 次 · 认识 ${state.good} · 模糊 ${state.hard} · 忘记 ${state.again}" +
                            if (wait != null) "\n下一词 $wait，留在此页会自动继续；退出也会保存进度。" else "\n今天的新词和到期复习已处理完，可以稍后再来。", onOpenNotebook)
                }
                else -> {
                    val entry = state.current!!
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("本轮已完成 ${state.completed}", style = MaterialTheme.typography.labelMedium)
                        Text("待复习 ${state.counts.dueReview} · 新词 ${state.counts.newAvailable}", style = MaterialTheme.typography.labelMedium)
                    }
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        Column(Modifier.fillMaxSize()) {
                            Column(Modifier.fillMaxWidth().padding(vertical = 16.dp, horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(entry.content, style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary,
                                    textAlign = TextAlign.Center, fontWeight = FontWeight.SemiBold)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(state.word?.phonetic.orEmpty(), style = MaterialTheme.typography.bodyLarge)
                                    IconButton(onClick = { speak(entry.content) }) { Icon(Icons.Default.VolumeUp, "播放单词发音") }
                                }
                            }
                            HorizontalDivider()
                            if (!state.revealed) {
                                Column(Modifier.fillMaxSize().clickable { viewModel.reveal() }.padding(24.dp),
                                    verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("请回忆单词发音和释义", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                                    Spacer(Modifier.height(16.dp))
                                    OutlinedButton(onClick = viewModel::reveal) { Text("查看释义") }
                                }
                            } else {
                                key(entry.id) { WordDetails(state, speak, viewModel::supplement) }
                            }
                        }
                        if (state.revealed) ReviewGestureButton(
                            state.preferences.englishButtonX, state.preferences.englishButtonY,
                            !state.saving, entry.id, viewModel::moveButton, { speak(entry.content) }, viewModel::grade)
                    }
                }
            }
        }
    }
}

@Composable
private fun WordDetails(state: ReviewSessionState, speak: (String) -> Unit, supplement: () -> Unit) {
    val entry = state.current ?: return
    val word = state.word
    var showTranslation by rememberSaveable { mutableStateOf(true) }
    var exampleSource by remember { mutableStateOf<com.example.lixing.data.dictionary.WordExample?>(null) }
    val uri = LocalUriHandler.current
    exampleSource?.let { source ->
        AlertDialog(onDismissRequest = { exampleSource = null }, title = { Text("例句来源") },
            text = { Text(source.source + if (source.source.contains("Tatoeba")) "\nhttps://creativecommons.org/licenses/by/2.0/" else "") },
            confirmButton = { TextButton(onClick = { exampleSource = null }) { Text("关闭") } },
            dismissButton = { if (source.url.startsWith("https://")) TextButton(onClick = { runCatching { uri.openUri(source.url) } }) { Text("查看原文") } })
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp, 16.dp, 20.dp, 100.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (entry.meaning.trim() != word?.translation?.replace("\\n", "\n")?.trim()) item {
            Text("我的释义", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text(entry.meaning, style = MaterialTheme.typography.titleMedium)
        }
        if (state.dictionaryLoading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        state.dictionaryError?.let { message -> item { Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) } }
        if (!word?.translation.isNullOrBlank()) item {
            SectionLabel("词典释义")
            if (word!!.partOfSpeech.isNotBlank()) Text(word.partOfSpeech, color = MaterialTheme.colorScheme.primary)
            Text(word!!.translation.replace("\\n", "\n"), style = MaterialTheme.typography.bodyLarge)
            Text("ECDICT", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        word?.definitions?.take(5)?.let { definitions -> items(definitions) { definition ->
            Text("${definition.pos}  ${definition.text}", style = MaterialTheme.typography.bodyMedium)
            Text(definition.source, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } }
        if (!word?.examples.isNullOrEmpty()) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("例句", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { showTranslation = !showTranslation }) { Text(if (showTranslation) "隐藏译文" else "显示译文") }
                }
            }
            items(word!!.examples.take(4)) { example ->
                Row(verticalAlignment = Alignment.Top) {
                    HighlightedExample(example.english, word, Modifier.weight(1f))
                    IconButton(onClick = { speak(example.english) }) { Icon(Icons.Default.VolumeUp, "朗读例句") }
                }
                if (showTranslation && example.chinese.isNotBlank()) Text(example.chinese)
                TextButton(onClick = { exampleSource = example }) {
                    Text(when {
                        example.source.startsWith("Tatoeba") -> "Tatoeba · 来源与作者"
                        example.source.startsWith("WordNet") -> "WordNet · 来源"
                        else -> "词典来源与作者"
                    }, style = MaterialTheme.typography.labelSmall)
                }
                HorizontalDivider()
            }
        }
        if (!word?.exchange.isNullOrBlank()) item {
            SectionLabel("词形变化")
            val names = mapOf("p" to "过去式", "d" to "过去分词", "i" to "现在分词", "3" to "第三人称", "r" to "比较级", "t" to "最高级", "s" to "复数", "0" to "原形")
            word!!.exchange.split('/').forEach { change ->
                val pair = change.split(':', limit = 2)
                if (pair.size == 2 && pair[0] in names) Text("${names[pair[0]]}   ${pair[1]}")
            }
        }
        if (state.preferences.englishOnlineDictionary) item {
            OutlinedButton(onClick = supplement, enabled = !state.dictionaryLoading) { Text("免费联网补充此词") }
        }
        item {
            SectionLabel("记忆历史")
            val memory = entry.memory()
            Text("累计 ${memory.reviews} 次 · 忘记 ${memory.lapses} 次", style = MaterialTheme.typography.bodyMedium)
            if (memory.reviews > state.history.size) Text("包含升级前的累计记录；早期逐次历史不可用", style = MaterialTheme.typography.labelSmall)
            if (memory.lastAt != null) Text("预计当前可回忆概率 ${(FsrsScheduler.retrievability(memory, state.now) * 100).toInt()}% · 算法估计", style = MaterialTheme.typography.labelSmall)
        }
        items(state.history.take(40), key = { it.id }) { log ->
            val date = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(log.reviewedAt))
            val grade = runCatching { ReviewGrade.valueOf(log.grade) }.getOrNull()
            Text("$date   ${grade?.let(::gradeLabel) ?: log.grade}", style = MaterialTheme.typography.bodySmall)
        }
        item { Text("圆钮：点击发音 · 上滑认识 · 右滑模糊 · 下滑忘记\n长按圆钮后拖动，可调整并记住位置。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun HighlightedExample(text: String, word: DictionaryWord, modifier: Modifier) {
    val forms = (listOf(word.word) + word.exchange.split('/').mapNotNull { it.substringAfter(':', "").takeIf(String::isNotBlank) }).distinct().sortedByDescending { it.length }
    val regex = Regex("(?i)(?<![a-z])(?:" + forms.joinToString("|") { Regex.escape(it) } + ")(?![a-z])")
    val color = MaterialTheme.colorScheme.primary
    val annotated = buildAnnotatedString {
        var start = 0
        regex.findAll(text).forEach { match ->
            append(text.substring(start, match.range.first))
            withStyle(SpanStyle(color = color, fontWeight = FontWeight.SemiBold)) { append(match.value) }
            start = match.range.last + 1
        }
        append(text.substring(start))
    }
    Text(annotated, modifier, style = MaterialTheme.typography.bodyLarge)
}

@Composable
private fun SectionLabel(text: String) {
    HorizontalDivider(Modifier.padding(bottom = 12.dp))
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
}

@Composable
private fun CenterMessage(title: String, message: String, onOpenNotebook: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))
        Text(message, textAlign = TextAlign.Center)
        TextButton(onClick = onOpenNotebook) { Text("返回英语积累") }
    }
}

internal fun reviewIntervalLabel(delay: Long): String = when {
    delay <= 0 -> "即将出现"
    delay < 3_600_000 -> "${ceil(delay / 60_000.0).toInt()} 分钟后"
    delay < FsrsScheduler.DAY -> "${ceil(delay / 3_600_000.0).toInt()} 小时后"
    else -> "${(delay / FsrsScheduler.DAY).coerceAtLeast(1)} 天后"
}

@Composable
private fun DictionaryLicensesDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var text by remember { mutableStateOf("正在读取词库说明…") }
    LaunchedEffect(Unit) {
        text = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val names = listOf("ECDICT-LICENSE.txt", "WordNet-LICENSE.txt", "Tatoeba-README.txt", "FSRS-LICENSE.txt")
            "离线词库：ECDICT 常用词条、WordNet 3.0 英文释义、Tatoeba 中英例句。词形与例句覆盖不保证完整。\n" +
                "例句作者和原文链接随每条例句保留；Tatoeba 文本采用 CC BY 2.0（https://creativecommons.org/licenses/by/2.0/）。发音使用系统离线 TTS。\n\n" +
                names.joinToString("\n\n") { name -> name + "\n" + context.assets.open("dictionary/licenses/$name").bufferedReader().use { it.readText() } }
        }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("免费词源与许可") },
        text = { Text(text, Modifier.verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } })
}
