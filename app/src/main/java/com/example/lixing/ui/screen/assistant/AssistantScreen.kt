package com.example.lixing.ui.screen.assistant

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.provider.Settings
import android.text.method.LinkMovementMethod
import android.widget.TextView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlin.math.sin
import java.io.File
import androidx.core.content.FileProvider
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.lixing.data.local.entity.AssistantConversationEntity
import com.example.lixing.data.assistant.normalizeAssistantMarkdown
import com.example.lixing.data.assistant.sanitizeAssistantLatex
import com.example.lixing.data.assistant.wrapLongFormulas
import com.example.lixing.domain.assistant.AssistantContextKind
import com.example.lixing.ui.screen.assistant.PlanChangeScope.LONG_TERM
import com.example.lixing.ui.screen.assistant.PlanChangeScope.TODAY
import com.example.lixing.ui.theme.LiXingRadius
import com.example.lixing.ui.screen.today.dialog.PhotoCropDialog
import io.noties.markwon.Markwon
import ru.noties.jlatexmath.JLatexMathDrawable
import io.noties.markwon.ext.latex.JLatexMathPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.inlineparser.MarkwonInlineParserPlugin
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.text.TextPaint
import android.util.Log
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Executors

private val QUICK_PROMPTS = listOf(
    "分析我最近 7 天的薄弱科目",
    "帮我把上午的安排调整得更宽松",
    "用我的英语积累出一组小测验",
    "把这段话里的好词好句记入英语积累",
    "我今天的任务是不是太多了？",
)

private data class PhotoViewerState(val paths: List<String>, val initialIndex: Int)

private enum class VoiceInputStatus { IDLE, LISTENING, PROCESSING }

private class VoiceInputSession {
    var inputBeforeListening: String = ""
    var latestTranscript: String = ""
    var hasRecognizedSpeech: Boolean = false
    var cancelRequested: Boolean = false
    var suppressNextError: Boolean = false
    var segmentStopRequested: Boolean = false
    var recoverableErrorRetries: Int = 0
    // Xiaomi's recognition service can deliver onReadyForSpeech asynchronously.
    // Do not call stopListening until that callback has established the session.
    var recognizerReady: Boolean = false
    var releaseRequested: Boolean = false
}

/** AI 学习助手页：对话 + 可选上下文 + 计划修改预览确认。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistantScreen(
    onBack: () -> Unit,
    viewModel: AssistantViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val context = LocalContext.current
    var pendingCaptureFile by remember { mutableStateOf<File?>(null) }
    var photoViewer by remember { mutableStateOf<PhotoViewerState?>(null) }
    var cropQueue by remember { mutableStateOf<List<File>>(emptyList()) }
    var extrasExpanded by remember { mutableStateOf(false) }
    var voiceInputStatus by remember { mutableStateOf(VoiceInputStatus.IDLE) }
    var voiceInputError by remember { mutableStateOf<String?>(null) }
    var voiceMode by remember { mutableStateOf(false) }
    var holdingTalk by remember { mutableStateOf(false) }
    var pressStartMillis by remember { mutableStateOf(0L) }
    var voiceSegmentRestartToken by remember { mutableStateOf(0) }
    val rmsLevels = remember { mutableStateListOf<Float>() }
    val voiceInputSession = remember { VoiceInputSession() }
    val configuredSpeechService = remember(context) { configuredSpeechRecognitionService(context) }
    val speechRecognizer = remember(context, configuredSpeechService) {
        runCatching {
            if (configuredSpeechService != null && SpeechRecognizer.isRecognitionAvailable(context)) {
                SpeechRecognizer.createSpeechRecognizer(context)
            } else {
                null
            }
        }.getOrNull()
    }

    fun commitVoiceTranscript(transcript: String?): Boolean {
        val recognized = transcript?.trim().orEmpty()
        if (recognized.isEmpty()) return false
        voiceInputSession.inputBeforeListening =
            mergeRecognizedSpeech(voiceInputSession.inputBeforeListening, recognized)
        voiceInputSession.latestTranscript = ""
        voiceInputSession.hasRecognizedSpeech = true
        viewModel.updateInput(voiceInputSession.inputBeforeListening)
        return true
    }

    fun finishVoiceInput(transcript: String?) {
        commitVoiceTranscript(transcript)
        if (voiceInputSession.hasRecognizedSpeech) voiceMode = false
        holdingTalk = false
        voiceInputStatus = VoiceInputStatus.IDLE
    }

    fun startVoiceSegment() {
        voiceInputSession.latestTranscript = ""
        voiceInputSession.recognizerReady = false
        voiceInputSession.releaseRequested = false
        voiceInputSession.segmentStopRequested = false
        val recognizer = speechRecognizer
        if (recognizer == null) {
            val hadRecognizedSpeech = voiceInputSession.hasRecognizedSpeech
            finishVoiceInput(null)
            if (!hadRecognizedSpeech) voiceInputError = "当前设备没有可用的语音识别服务"
            return
        }
        voiceInputStatus = VoiceInputStatus.LISTENING
        runCatching { recognizer.startListening(speechRecognitionIntent(context)) }
            .onFailure {
                val hadRecognizedSpeech = voiceInputSession.hasRecognizedSpeech
                finishVoiceInput(null)
                if (!hadRecognizedSpeech) {
                    voiceInputError = "无法启动语音输入：${it.message ?: "未知错误"}"
                }
            }
    }

    fun restartVoiceSegment() {
        voiceInputStatus = VoiceInputStatus.PROCESSING
        voiceSegmentRestartToken += 1
    }

    fun startHoldListening() {
        voiceInputSession.inputBeforeListening = state.input
        voiceInputSession.latestTranscript = ""
        voiceInputSession.hasRecognizedSpeech = false
        voiceInputSession.cancelRequested = false
        voiceInputSession.suppressNextError = false
        voiceInputSession.segmentStopRequested = false
        voiceInputSession.recoverableErrorRetries = 0
        voiceInputSession.recognizerReady = false
        voiceInputSession.releaseRequested = false
        pressStartMillis = SystemClock.uptimeMillis()
        rmsLevels.clear()
        holdingTalk = true
        voiceInputError = null
        startVoiceSegment()
    }

    fun endHoldListening(cancelled: Boolean) {
        if (!holdingTalk) return
        holdingTalk = false
        if (voiceInputStatus != VoiceInputStatus.LISTENING) return
        val accidentalTap = SystemClock.uptimeMillis() - pressStartMillis < 350
        if (cancelled || accidentalTap) {
            voiceInputSession.cancelRequested = true
            voiceInputSession.releaseRequested = false
            voiceInputSession.suppressNextError = accidentalTap
            speechRecognizer?.cancel()
            voiceInputStatus = VoiceInputStatus.IDLE
            rmsLevels.clear()
        } else {
            voiceInputStatus = VoiceInputStatus.PROCESSING
            voiceInputSession.releaseRequested = true
            if (voiceInputSession.recognizerReady) {
                speechRecognizer?.stopListening()
            }
        }
    }

    val recordAudioPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) voiceMode = true else voiceInputError = "需要麦克风权限才能使用语音输入"
    }

    DisposableEffect(speechRecognizer) {
        if (speechRecognizer == null) {
            onDispose { }
        } else {
            speechRecognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    voiceInputSession.recognizerReady = true
                    if (voiceInputSession.releaseRequested && !holdingTalk) {
                        voiceInputStatus = VoiceInputStatus.PROCESSING
                        speechRecognizer.stopListening()
                    } else if (holdingTalk) {
                        voiceInputStatus = VoiceInputStatus.LISTENING
                    }
                }

                override fun onBeginningOfSpeech() = Unit

                override fun onRmsChanged(rmsdB: Float) {
                    if (holdingTalk) {
                        rmsLevels.add(((rmsdB + 2f) / 12f).coerceIn(0.06f, 1f))
                        if (rmsLevels.size > 48) rmsLevels.removeAt(0)
                    }
                }

                override fun onBufferReceived(buffer: ByteArray?) = Unit

                override fun onEndOfSpeech() {
                    voiceInputStatus = VoiceInputStatus.PROCESSING
                }

                override fun onError(error: Int) {
                    voiceInputSession.recognizerReady = false
                    val wasReleaseRequested = voiceInputSession.releaseRequested
                    voiceInputSession.releaseRequested = false
                    val wasSegmentStopRequested = voiceInputSession.segmentStopRequested
                    voiceInputSession.segmentStopRequested = false
                    val wasCancelled = voiceInputSession.cancelRequested
                    voiceInputSession.cancelRequested = false
                    val suppressed = voiceInputSession.suppressNextError
                    voiceInputSession.suppressNextError = false
                    val partial = voiceInputSession.latestTranscript.takeIf { it.isNotBlank() }
                    if (wasCancelled) {
                        voiceInputSession.latestTranscript = ""
                        viewModel.updateInput(voiceInputSession.inputBeforeListening)
                        holdingTalk = false
                        voiceInputStatus = VoiceInputStatus.IDLE
                        rmsLevels.clear()
                        return
                    }
                    val hadRecognizedSpeech = voiceInputSession.hasRecognizedSpeech
                    commitVoiceTranscript(partial)
                    val canRetry = voiceInputSession.recoverableErrorRetries < 2 &&
                        isRecoverableVoiceRecognitionError(error)
                    val canRestartSegment = holdingTalk && !wasReleaseRequested &&
                        voiceInputSession.recoverableErrorRetries < 2 &&
                        (wasSegmentStopRequested || canRetry)
                    if (canRestartSegment) {
                        voiceInputSession.recoverableErrorRetries += 1
                        rmsLevels.clear()
                        restartVoiceSegment()
                        return
                    }
                    finishVoiceInput(null)
                    rmsLevels.clear()
                    if (suppressed || partial != null || hadRecognizedSpeech) return
                    voiceInputError = speechRecognitionErrorMessage(error)
                }

                override fun onResults(results: Bundle?) {
                    voiceInputSession.recognizerReady = false
                    voiceInputSession.releaseRequested = false
                    voiceInputSession.segmentStopRequested = false
                    val transcript = results?.bestSpeechResult() ?: voiceInputSession.latestTranscript
                    if (holdingTalk) {
                        commitVoiceTranscript(transcript)
                        voiceInputSession.recoverableErrorRetries = 0
                        restartVoiceSegment()
                    } else {
                        finishVoiceInput(transcript)
                    }
                    rmsLevels.clear()
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    partialResults?.bestSpeechResult()?.let { transcript ->
                        voiceInputSession.latestTranscript = transcript
                        viewModel.updateInput(
                            mergeRecognizedSpeech(voiceInputSession.inputBeforeListening, transcript),
                        )
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
            onDispose {
                voiceInputSession.cancelRequested = true
                speechRecognizer.cancel()
                speechRecognizer.destroy()
            }
        }
    }

    LaunchedEffect(voiceSegmentRestartToken) {
        if (voiceSegmentRestartToken == 0) return@LaunchedEffect
        delay(180)
        if (voiceInputSession.cancelRequested) return@LaunchedEffect
        if (holdingTalk) startVoiceSegment() else finishVoiceInput(null)
    }

    LaunchedEffect(holdingTalk, voiceInputStatus) {
        if (!holdingTalk || voiceInputStatus != VoiceInputStatus.LISTENING) return@LaunchedEffect
        delay(VOICE_RECOGNITION_SEGMENT_MS)
        if (holdingTalk && voiceInputStatus == VoiceInputStatus.LISTENING &&
            voiceInputSession.recognizerReady
        ) {
            voiceInputSession.segmentStopRequested = true
            voiceInputStatus = VoiceInputStatus.PROCESSING
            speechRecognizer?.stopListening()
        }
    }

    LaunchedEffect(state.busy) {
        if (state.busy && voiceInputStatus != VoiceInputStatus.IDLE) {
            voiceInputSession.cancelRequested = true
            speechRecognizer?.cancel()
            holdingTalk = false
            voiceInputStatus = VoiceInputStatus.IDLE
        }
    }
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val file = pendingCaptureFile
        pendingCaptureFile = null
        if (success && file != null) cropQueue = cropQueue + file else file?.delete()
    }
    val pickPhotos = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        val imported = uris.mapNotNull { uri -> copyAssistantPhoto(context, uri) }
        cropQueue = cropQueue + imported
    }

    BackHandler(enabled = voiceMode) {
        voiceInputSession.cancelRequested = true
        speechRecognizer?.cancel()
        holdingTalk = false
        voiceInputStatus = VoiceInputStatus.IDLE
        voiceMode = false
    }

    LaunchedEffect(voiceMode) {
        if (!voiceMode) return@LaunchedEffect
        var frame = 0L
        while (true) {
            delay(90)
            if (holdingTalk && voiceInputStatus == VoiceInputStatus.LISTENING && rmsLevels.isEmpty()) {
                val base = 0.18f + 0.08f * sin(frame * 0.4f).toFloat()
                val pattern = List(22) { i ->
                    (base * (0.5f + 0.5f * sin(i * 0.7f).toFloat())).coerceIn(0.08f, 0.30f)
                }
                rmsLevels.addAll(pattern)
            }
            if (rmsLevels.isNotEmpty()) {
                val decayed = rmsLevels.map { it * 0.88f }.dropWhile { it < 0.06f }
                rmsLevels.clear()
                rmsLevels.addAll(decayed)
            }
            frame++
        }
    }

    voiceInputError?.let { message ->
        LaunchedEffect(message) {
            snackbar.showSnackbar(message)
            voiceInputError = null
        }
    }

    state.error?.let { message ->
        LaunchedEffect(message) {
            snackbar.showSnackbar(message)
            viewModel.consumeError()
        }
    }
    state.applyMessage?.let { message ->
        LaunchedEffect(message) {
            snackbar.showSnackbar(message)
            viewModel.consumeApplyMessage()
        }
    }
    LaunchedEffect(state.messages.size, state.pendingActions.size) {
        if (state.messages.isNotEmpty() || state.pendingActions.isNotEmpty()) {
            listState.animateScrollToItem(0)
        }
    }

    if (state.historyOpen) {
        HistoryDialog(
            conversations = state.conversations,
            currentId = state.currentConversationId,
            onOpen = viewModel::openConversation,
            onDelete = viewModel::deleteConversation,
            onDismiss = viewModel::closeHistory,
        )
    }
    photoViewer?.let { viewer ->
        PhotoViewerDialog(
            paths = viewer.paths,
            initialIndex = viewer.initialIndex,
            onDismiss = { photoViewer = null },
        )
    }
    cropQueue.firstOrNull()?.let { file ->
        PhotoCropDialog(
            path = file.absolutePath,
            onCropped = {
                viewModel.onPhotoTaken(file.absolutePath)
                cropQueue = cropQueue.drop(1)
            },
            onDismiss = {
                file.delete()
                cropQueue = cropQueue.drop(1)
            },
            onUseOriginal = {
                viewModel.onPhotoTaken(file.absolutePath)
                cropQueue = cropQueue.drop(1)
            },
        )
    }

    state.ocrProgress?.let { progress ->
        OcrProgressDialog(progress = progress, onCancel = viewModel::cancelOcr)
    }
    state.ocrPreview?.let { preview ->
        OcrPreviewDialog(
            preview = preview,
            onMarkdownChanged = viewModel::updateOcrPreview,
            onRetry = viewModel::retryOcrPreview,
            onCancel = viewModel::cancelOcrPreview,
            onSend = viewModel::send,
        )
    }

    BackHandler(enabled = state.planReviewOpen, onBack = viewModel::closePlanReview)
    BackHandler(enabled = state.englishReviewOpen && !state.planReviewOpen, onBack = viewModel::closeEnglishReview)

    val reviewing = state.planReviewOpen || state.englishReviewOpen

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when {
                            state.planReviewOpen -> "计划修改方案"
                            state.englishReviewOpen -> "英语积累变更"
                            else -> "AI 学习助手"
                        },
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = when {
                            state.planReviewOpen -> viewModel::closePlanReview
                            state.englishReviewOpen -> viewModel::closeEnglishReview
                            else -> onBack
                        },
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (!reviewing) {
                        IconButton(onClick = viewModel::showHistory) {
                            Icon(Icons.Filled.History, contentDescription = "历史对话")
                        }
                        if (state.messages.isNotEmpty()) {
                            IconButton(onClick = viewModel::startNewConversation) {
                                Icon(Icons.Filled.Delete, contentDescription = "开始新对话")
                            }
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (state.planReviewOpen) {
            PlanChangeReviewPage(
                items = state.pendingActions,
                reviewDate = state.planReviewDate,
                applying = state.applying,
                onToggle = viewModel::togglePendingAction,
                onApply = viewModel::applySelected,
                onRejectAll = viewModel::dismissPendingActions,
                modifier = Modifier.fillMaxSize().padding(padding),
            )
            return@Scaffold
        }

        if (state.englishReviewOpen) {
            EnglishChangeReviewPage(
                items = state.pendingEnglishActions,
                applying = state.applyingEnglish,
                onToggle = viewModel::toggleEnglishAction,
                onApply = viewModel::applySelectedEnglish,
                onRejectAll = viewModel::dismissEnglishActions,
                modifier = Modifier.fillMaxSize().padding(padding),
            )
            return@Scaffold
        }

        if (!state.enabled) {
            NotEnabledPanel(padding)
            return@Scaffold
        }

        Column(Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(
                modifier = Modifier.weight(1f),
                state = listState,
                reverseLayout = true,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (state.messages.isEmpty()) {
                    item(key = "empty-hint") { EmptyHint() }
                }
                itemsIndexed(state.messages.asReversed(), key = { index, _ -> "msg-${state.messages.size - 1 - index}" }) { index, message ->
                    if (index == 0 && state.busy && message.role == "user") {
                        // Keep the transient reasoning outside the user bubble while placing it
                        // in the same newest-message item, only a few dp below that bubble.
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            MessageBubble(
                                role = message.role,
                                content = message.displayContent ?: message.content,
                                imagePaths = message.imagePaths,
                                onImageClick = { paths, imageIndex -> photoViewer = PhotoViewerState(paths, imageIndex) },
                            )
                            ThinkingPanel(
                                reasoning = state.activeReasoning,
                                answerStarted = state.activeAnswerStarted,
                                expanded = state.reasoningExpanded,
                                onToggle = viewModel::toggleReasoningExpanded,
                            )
                        }
                    } else {
                        val messageIndex = state.messages.size - 1 - index
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            MessageBubble(
                                role = message.role,
                                content = message.displayContent ?: message.content,
                                imagePaths = message.imagePaths,
                                onImageClick = { paths, imageIndex -> photoViewer = PhotoViewerState(paths, imageIndex) },
                            )
                            if (message.role == "assistant") {
                                AssistantMessageActionBar(
                                    planCount = if (state.pendingActionsOwnerIndex == messageIndex) {
                                        state.pendingActions.size
                                    } else {
                                        0
                                    },
                                    englishCount = if (state.pendingEnglishOwnerIndex == messageIndex) {
                                        state.pendingEnglishActions.size
                                    } else {
                                        0
                                    },
                                    onOpenPlan = viewModel::openPlanReview,
                                    onOpenEnglish = viewModel::openEnglishReview,
                                )
                            }
                        }
                    }
                }
                if (state.busy && state.messages.lastOrNull()?.role != "user") {
                    item(key = "busy") {
                        ThinkingPanel(
                            reasoning = state.activeReasoning,
                            answerStarted = state.activeAnswerStarted,
                            expanded = state.reasoningExpanded,
                            onToggle = viewModel::toggleReasoningExpanded,
                        )
                    }
                }
            }

            Surface(tonalElevation = 2.dp, modifier = Modifier.imePadding()) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (extrasExpanded) {
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                "附带：",
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.align(Alignment.CenterVertically),
                            )
                            AssistantContextKind.entries.forEach { kind ->
                                FilterChip(
                                    selected = kind in state.contextKinds,
                                    onClick = { viewModel.toggleContextKind(kind) },
                                    label = { Text(kind.label) },
                                )
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("智能搜索", style = MaterialTheme.typography.labelMedium)
                            Spacer(Modifier.weight(1f))
                            Switch(
                                checked = state.forceWebSearch,
                                onCheckedChange = viewModel::setForceWebSearch,
                                enabled = !state.busy,
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                "快捷：",
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.align(Alignment.CenterVertically),
                            )
                            QUICK_PROMPTS.forEach { prompt ->
                                FilterChip(
                                    selected = false,
                                    onClick = {
                                        viewModel.updateInput(prompt)
                                        extrasExpanded = false
                                    },
                                    label = { Text(prompt) },
                                )
                            }
                        }
                        state.lastContextNote?.let { note ->
                            Text(
                                "上次附带：$note",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (state.pendingPhotoPaths.isNotEmpty()) {
                        PendingPhotoRow(
                            paths = state.pendingPhotoPaths,
                            onRemove = viewModel::removePendingPhoto,
                            onImageClick = { paths, index -> photoViewer = PhotoViewerState(paths, index) },
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = {
                                val file = createAssistantPhotoFile(context)
                                pendingCaptureFile = file
                                val uri = FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    file,
                                )
                                runCatching { takePicture.launch(uri) }
                                    .onFailure { pendingCaptureFile = null; file.delete() }
                            },
                            enabled = !state.busy && !holdingTalk && voiceInputStatus == VoiceInputStatus.IDLE,
                        ) {
                            Icon(Icons.Filled.PhotoCamera, contentDescription = "拍题")
                        }
                        IconButton(
                            onClick = { pickPhotos.launch("image/*") },
                            enabled = !state.busy && !holdingTalk && voiceInputStatus == VoiceInputStatus.IDLE,
                        ) {
                            Icon(Icons.Filled.PhotoLibrary, contentDescription = "从相册选择")
                        }
                        if (voiceMode) {
                            val voiceBarHeight by animateDpAsState(
                                targetValue = if (holdingTalk) 72.dp else 48.dp,
                                label = "voiceBarHeight",
                            )
                            Surface(
                                modifier = Modifier.weight(1f).height(voiceBarHeight),
                                shape = RoundedCornerShape(24.dp),
                                color = if (holdingTalk) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerHigh
                                },
                            ) {
                                Box(
                                    modifier = Modifier.fillMaxSize().pointerInput(Unit) {
                                        detectTapGestures(onPress = {
                                            if (voiceInputStatus != VoiceInputStatus.PROCESSING) {
                                                startHoldListening()
                                                val released = tryAwaitRelease()
                                                endHoldListening(cancelled = !released)
                                            } else {
                                                tryAwaitRelease()
                                            }
                                        })
                                    },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (holdingTalk) {
                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.spacedBy(3.dp),
                                        ) {
                                            VoiceWaveform(
                                                levels = rmsLevels,
                                                active = true,
                                                modifier = Modifier.fillMaxWidth(0.92f).height(30.dp),
                                            )
                                            Text(
                                                "松开 结束",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                            )
                                        }
                                    } else {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                imageVector = if (voiceInputStatus == VoiceInputStatus.PROCESSING) {
                                                    Icons.Filled.StopCircle
                                                } else {
                                                    Icons.Filled.Mic
                                                },
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                            )
                                            Spacer(Modifier.width(6.dp))
                                            Text(
                                                if (voiceInputStatus == VoiceInputStatus.PROCESSING) "正在识别…" else "按住 说话",
                                                style = MaterialTheme.typography.bodyLarge,
                                            )
                                        }
                                    }
                                }
                            }
                            IconButton(
                                onClick = {
                                    voiceInputSession.cancelRequested = true
                                    speechRecognizer?.cancel()
                                    holdingTalk = false
                                    voiceInputStatus = VoiceInputStatus.IDLE
                                    voiceMode = false
                                },
                                enabled = !state.busy && !holdingTalk,
                            ) {
                                Icon(Icons.Filled.Keyboard, contentDescription = "返回键盘输入")
                            }
                        } else {
                            OutlinedTextField(
                                value = state.input,
                                onValueChange = viewModel::updateInput,
                                modifier = Modifier.weight(1f).heightIn(max = 140.dp),
                                enabled = !state.busy,
                                placeholder = { Text("输入消息") },
                                trailingIcon = {
                                    IconButton(
                                        onClick = {
                                            if (ContextCompat.checkSelfPermission(
                                                    context,
                                                    Manifest.permission.RECORD_AUDIO,
                                                ) == PackageManager.PERMISSION_GRANTED
                                            ) {
                                                voiceMode = true
                                            } else {
                                                recordAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
                                            }
                                        },
                                        enabled = !state.busy,
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Mic,
                                            contentDescription = "语音输入",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                },
                            )
                        }
                        FilledTonalIconButton(
                            onClick = { extrasExpanded = !extrasExpanded },
                            enabled = !state.busy && !holdingTalk && voiceInputStatus == VoiceInputStatus.IDLE,
                        ) {
                            Icon(
                                if (extrasExpanded) Icons.Filled.Close else Icons.Filled.Add,
                                contentDescription = if (extrasExpanded) "收起附带和快捷提问" else "展开附带和快捷提问",
                            )
                        }
                        IconButton(
                            onClick = viewModel::send,
                            enabled = !state.busy &&
                                !holdingTalk &&
                                voiceInputStatus == VoiceInputStatus.IDLE &&
                                (state.input.isNotBlank() || state.pendingPhotoPaths.isNotEmpty()),
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发送")
                        }
                    }
                }
            }
        }
    }
}

private fun Bundle.bestSpeechResult(): String? =
    getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        ?.firstOrNull()
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

private fun speechRecognitionIntent(context: Context): Intent =
    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        putExtra(RecognizerIntent.EXTRA_PROMPT, "请说话")
    }

@Suppress("DEPRECATION")
private fun configuredSpeechRecognitionService(context: Context): ComponentName? =
    Settings.Secure.getString(context.contentResolver, "voice_recognition_service")
        ?.let(ComponentName::unflattenFromString)

internal fun mergeRecognizedSpeech(existingInput: String, transcript: String): String {
    val recognized = transcript.trim()
    if (recognized.isEmpty()) return existingInput
    if (existingInput.isBlank()) return recognized
    val separator = if (existingInput.last().isWhitespace()) "" else " "
    return existingInput + separator + recognized
}

internal fun speechRecognitionErrorMessage(error: Int): String = when (error) {
    SpeechRecognizer.ERROR_AUDIO -> "录音失败，请检查麦克风后重试"
    SpeechRecognizer.ERROR_CLIENT -> "语音识别客户端初始化失败（错误码 $error）"
    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "没有麦克风权限，无法使用语音输入"
    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "语音识别网络异常，请稍后重试"
    SpeechRecognizer.ERROR_NO_MATCH -> "没有听清，请再说一次"
    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "语音识别正忙，请稍后重试"
    SpeechRecognizer.ERROR_SERVER, SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "语音识别服务暂时不可用"
    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "没有检测到语音"
    SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "语音识别请求过于频繁，请稍后重试"
    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "系统语音服务不支持当前语言"
    SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "当前语言的语音识别暂时不可用"
    else -> "语音识别失败（错误码 $error），请重试"
}

internal fun isRecoverableVoiceRecognitionError(error: Int): Boolean = when (error) {
    SpeechRecognizer.ERROR_NETWORK,
    SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
    SpeechRecognizer.ERROR_NO_MATCH,
    SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
    SpeechRecognizer.ERROR_SERVER,
    SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
    SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
    -> true
    else -> false
}

// Xiaomi's bundled recognizer can terminate a single stream after roughly five seconds.
private const val VOICE_RECOGNITION_SEGMENT_MS = 4_000L

/** 按住说话时输入条内的声纹波形：右侧滚动显示最新振幅，静默时保持呼吸动画。 */
@Composable
private fun VoiceWaveform(levels: List<Float>, active: Boolean, modifier: Modifier = Modifier) {
    val barColor = MaterialTheme.colorScheme.primary
    val idleColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.35f)
    Canvas(modifier = modifier) {
        val barCount = 29
        val gap = 2.dp.toPx()
        val barWidth = (size.width - gap * (barCount - 1)) / barCount
        val midY = size.height / 2f
        val recent = levels.takeLast(barCount)
        val startIndex = barCount - recent.size
        val minBar = 5.dp.toPx()
        for (i in 0 until barCount) {
            val level = if (i < startIndex) 0f else recent[i - startIndex].coerceIn(0f, 1f)
            val barHeight = if (level > 0f && active) {
                minBar + (size.height - minBar * 2f) * level
            } else {
                minBar
            }
            drawRoundRect(
                color = if (level > 0f && active) barColor else idleColor,
                topLeft = Offset(i * (barWidth + gap), midY - barHeight / 2f),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2f),
            )
        }
    }
}

/** Pending photo previews. Every photo can be removed or opened at full size. */
@Composable
private fun PendingPhotoRow(
    paths: List<String>,
    onRemove: (String) -> Unit,
    onImageClick: (List<String>, Int) -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = LiXingRadius.Card) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("已选择 ${paths.size} 张照片", style = MaterialTheme.typography.bodyMedium)
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                paths.forEachIndexed { index, path ->
                    Box {
                        AssistantThumbnail(
                            path = path,
                            modifier = Modifier.size(68.dp),
                            onClick = { onImageClick(paths, index) },
                        )
                        Surface(
                            modifier = Modifier.align(Alignment.TopEnd).size(26.dp),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                            shape = RoundedCornerShape(bottomStart = 8.dp),
                        ) {
                            IconButton(onClick = { onRemove(path) }) {
                                Icon(Icons.Filled.Close, contentDescription = "移除照片")
                            }
                        }
                    }
                }
            }
            Text(
                "继续拍摄或从相册多选，发送时会一次提交",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun createAssistantPhotoFile(context: android.content.Context): File {
    val root = context.getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES) ?: context.filesDir
    val dir = File(root, "assistant").apply { mkdirs() }
    return File(dir, "assistant_${System.currentTimeMillis()}.jpg")
}

private fun copyAssistantPhoto(context: android.content.Context, uri: android.net.Uri): File? = runCatching {
    val target = createAssistantPhotoFile(context)
    context.contentResolver.openInputStream(uri)?.use { input ->
        target.outputStream().buffered().use { output -> input.copyTo(output) }
    } ?: return@runCatching null
    target.takeIf { it.isFile && it.length() > 0L }
}.getOrNull()

/** 历史对话弹窗：点击条目续聊，右侧按钮删除（二次确认）。 */
@Composable
private fun HistoryDialog(
    conversations: List<AssistantConversationEntity>,
    currentId: String?,
    onOpen: (String) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var pendingDelete by remember { mutableStateOf<AssistantConversationEntity?>(null) }
    val dateFmt = remember { java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("历史对话") },
        text = {
            if (conversations.isEmpty()) {
                Text("还没有保存的对话。发出第一条消息后会自动保存到这里。")
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    itemsIndexed(conversations, key = { _, c -> c.id }) { _, conversation ->
                        Surface(
                            color = if (conversation.id == currentId) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceContainerHigh,
                            shape = LiXingRadius.Card,
                            onClick = { onOpen(conversation.id) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                                    Text(
                                        conversation.title,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                    )
                                    Text(
                                        java.time.Instant.ofEpochMilli(conversation.createdAt.toEpochMilli())
                                            .atZone(java.time.ZoneId.systemDefault()).format(dateFmt) +
                                            if (conversation.id == currentId) " · 当前" else "",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                IconButton(onClick = { pendingDelete = conversation }) {
                                    Icon(
                                        Icons.Filled.Delete,
                                        contentDescription = "删除这条对话",
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除这条对话？") },
            text = { Text("「${target.title}」及其全部消息将从本机和之后的新备份中删除。") },
            confirmButton = {
                TextButton(onClick = { onDelete(target.id); pendingDelete = null }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun NotEnabledPanel(padding: PaddingValues) {
    Column(
        modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("🤖", style = MaterialTheme.typography.displayMedium)
        Spacer(Modifier.height(16.dp))
        Text("AI 学习助手尚未开启", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(
            "开启后可以向助手提问、让它分析你的学习数据，并对计划修改给出建议。" +
                "支持任意 OpenAI 兼容模型接口（DeepSeek、月之暗面、智谱等），在设置页填好即可。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Text("到「设置 → AI 学习助手」填写接口地址、模型名与 API 密钥后即可使用。")
    }
}

@Composable
private fun EmptyHint() {
    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = LiXingRadius.Card) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("可以问的问题", fontWeight = FontWeight.Bold)
            Text(
                "「最近哪科投入最少？」「把数学挪到上午」「用英语积累出一组测验」。" +
                    "涉及修改计划的回答会生成可确认的建议卡片。",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** The provider's reasoning is visible only while the current request is active. */
@Composable
private fun ThinkingPanel(
    reasoning: String,
    answerStarted: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val reasoningScroll = rememberScrollState()
    LaunchedEffect(reasoning.length, expanded) {
        if (expanded) reasoningScroll.scrollTo(reasoningScroll.maxValue)
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = LiXingRadius.Card,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    if (answerStarted) "正在生成最终回答…" else "助手正在思考…",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.weight(1f))
                if (reasoning.isNotBlank()) {
                    TextButton(onClick = onToggle) { Text(if (expanded) "收起" else "展开") }
                }
            }
            if (reasoning.isNotBlank() && expanded) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp)
                        .verticalScroll(reasoningScroll),
                ) {
                    Text(
                        reasoning,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(
    role: String,
    content: String,
    imagePaths: List<String>,
    onImageClick: (List<String>, Int) -> Unit,
) {
    val isUser = role == "user"
    Box(Modifier.fillMaxWidth()) {
        SelectionContainer(
            modifier = Modifier
                .align(if (isUser) Alignment.CenterEnd else Alignment.CenterStart)
                .widthIn(max = 480.dp),
        ) {
            Surface(
                color = if (isUser) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(
                    topStart = 14.dp, topEnd = 14.dp,
                    bottomStart = if (isUser) 14.dp else 4.dp,
                    bottomEnd = if (isUser) 4.dp else 14.dp,
                ),
            ) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (imagePaths.isNotEmpty()) {
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            imagePaths.forEachIndexed { index, path ->
                                AssistantThumbnail(
                                    path = path,
                                    modifier = Modifier.size(92.dp),
                                    onClick = { onImageClick(imagePaths, index) },
                                )
                            }
                        }
                    }
                    if (content.isNotBlank()) {
                        if (isUser) {
                            Text(content, style = MaterialTheme.typography.bodyLarge)
                        } else {
                            MarkdownAnswer(content)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OcrProgressDialog(
    progress: com.example.lixing.data.assistant.OcrProgress,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("正在整理题目") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(progress.stage, style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(
                    "图片 ${progress.page + 1}/${progress.totalPages}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onCancel) { Text("取消") } },
    )
}

@Composable
private fun OcrPreviewDialog(
    preview: OcrPreview,
    onMarkdownChanged: (String) -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onSend: () -> Unit,
) {
    var sourceMode by remember(preview) { mutableStateOf(false) }
    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(Modifier.fillMaxSize().padding(12.dp), shape = LiXingRadius.Card) {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("OCR 题目预览", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onCancel) { Icon(Icons.Filled.Close, contentDescription = "关闭") }
                }
                if (preview.prompt.isNotBlank()) {
                    Text(preview.prompt, style = MaterialTheme.typography.bodyMedium)
                }
                if (preview.warnings.isNotEmpty()) {
                    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = LiXingRadius.Card) {
                        Text(
                            preview.warnings.joinToString("\n"),
                            modifier = Modifier.fillMaxWidth().padding(10.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !sourceMode, onClick = { sourceMode = false }, label = { Text("渲染预览") })
                    FilterChip(selected = sourceMode, onClick = { sourceMode = true }, label = { Text("Markdown 源码") })
                }
                if (sourceMode) {
                    OutlinedTextField(
                        value = preview.markdown,
                        onValueChange = onMarkdownChanged,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        minLines = 10,
                    )
                } else {
                    Column(
                        Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
                    ) { MarkdownAnswer(preview.markdown.ifBlank { "未识别到可用文字" }) }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onRetry) { Text("重新识别") }
                    TextButton(onClick = onCancel) { Text("返回") }
                    TextButton(onClick = onSend, enabled = !preview.hasBlockingErrors && preview.markdown.isNotBlank()) { Text("发送") }
                }
            }
        }
    }
}

/**
 * 回答正文渲染。
 *
 * 模型偶尔会输出 JLatexMath 解析不了的 LaTeX（缺右括号、残留 \tag、aligned 前导非法字符等），
 * 插件默认行为是把 ParseException 包成 RuntimeException 抛出，会在 UI 线程把 App 带崩。
 * 这里做三层兜底：单条公式失败画占位 → 整段渲染失败回退纯文本 → 异常写入本地日志便于定位。
 */
@Composable
private fun MarkdownAnswer(content: String) {
    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val linkColor = MaterialTheme.colorScheme.primary.toArgb()
    AndroidView(
        modifier = Modifier.fillMaxWidth(),
        factory = { context ->
            TextView(context).apply {
                setTextColor(textColor)
                setLinkTextColor(linkColor)
                setTextIsSelectable(true)
                movementMethod = LinkMovementMethod.getInstance()
                textSize = 17f
                val fallbackSizePx = 14f * resources.displayMetrics.scaledDensity
                val renderer = Markwon.builder(context)
                    .usePlugin(MarkwonInlineParserPlugin.create())
                    .usePlugin(TablePlugin.create(context))
                    .usePlugin(
                        JLatexMathPlugin.create(this.textSize) { builder ->
                            builder.inlinesEnabled(true)
                            builder.theme().textColor(textColor)
                            // 单条公式解析失败时画占位，绝不让 ParseException 冒泡成整页闪退。
                            builder.errorHandler { latex, error ->
                                Log.w(RENDER_LOG_TAG, "latex render failed: $latex", error)
                                LatexFallbackDrawable(textColor, fallbackSizePx, latex)
                            }
                        },
                    )
                    .build()
                tag = renderer
            }
        },
        update = { view ->
            view.setTextColor(textColor)
            view.setLinkTextColor(linkColor)
            val rendered = wrapLongFormulas(
                sanitizeAssistantLatex(normalizeAssistantMarkdown(content)),
                formulaMaxWidthPx(view),
                formulaWidthMeasurer(view),
            )
            runCatching {
                (view.tag as Markwon).setMarkdown(view, rendered)
            }.onFailure { error ->
                Log.e(RENDER_LOG_TAG, "markdown render failed, fallback to plain text", error)
                appendRenderErrorLog(view.context, rendered, error)
                view.text = buildString {
                    append(content)
                    append("\n\n[部分内容无法渲染，已回退为纯文本]")
                }
            }
        },
    )
}

private const val RENDER_LOG_TAG = "MarkdownAnswer"

/**
 * 单条公式解析失败时的占位：灰底 + 标题 + 失败源码片段，保证不再抛异常炸掉整页。
 * 源码超过 60 字符会被截断并加省略号，避免占位占满气泡。
 */
private class LatexFallbackDrawable(
    private val textColor: Int,
    textSizePx: Float,
    rawLatex: String,
) : Drawable() {
    private val title = "⚠ 公式无法渲染"
    private val snippet: String = rawLatex
        .replace('\n', ' ')
        .let { if (it.length > 60) it.substring(0, 60) + "…" else it }
    private val background = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x1F000000
        style = Paint.Style.FILL
    }
    private val foreground = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor
        alpha = 0xB0
        this.textSize = textSizePx
    }
    private val snippetPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor
        alpha = 0x90
        this.textSize = textSizePx * 0.85f
        typeface = android.graphics.Typeface.MONOSPACE
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        canvas.drawRoundRect(
            b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(),
            10f, 10f, background,
        )
        val titleBaseline = b.top + foreground.textSize + 6f
        canvas.drawText(title, (b.left + 12).toFloat(), titleBaseline, foreground)
        if (snippet.isNotEmpty()) {
            val snippetBaseline = titleBaseline + snippetPaint.textSize + 4f
            canvas.drawText(snippet, (b.left + 12).toFloat(), snippetBaseline, snippetPaint)
        }
    }

    override fun setAlpha(alpha: Int) {
        foreground.alpha = alpha
        snippetPaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        foreground.colorFilter = colorFilter
        snippetPaint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun getIntrinsicWidth(): Int {
        val titleWidth = foreground.measureText(title)
        val snippetWidth = if (snippet.isEmpty()) 0f else snippetPaint.measureText(snippet)
        return (maxOf(titleWidth, snippetWidth) + 24).toInt()
    }

    override fun getIntrinsicHeight(): Int =
        (foreground.textSize + snippetPaint.textSize + 28f).toInt()
}

/** 渲染失败的原文与异常写入本机日志，便于事后定位是哪类回答触发的。 */
private val RENDER_LOG_EXECUTOR = Executors.newSingleThreadExecutor()

private fun appendRenderErrorLog(context: Context, markdown: String, error: Throwable) {
    runCatching {
        RENDER_LOG_EXECUTOR.execute {
            runCatching {
                val file = File(context.filesDir, "assistant-render.log")
                val entry = buildString {
                    appendLine("=== ${Instant.now()} ===")
                    appendLine("error: ${error::class.java.simpleName}: ${error.message}")
                    appendLine(markdown.take(1_500))
                    appendLine()
                }
                val kept = if (file.isFile && file.length() <= 200_000) file.readText() else ""
                file.writeText(kept + entry)
            }
        }
    }
}

/** 公式可用宽度：气泡实际宽度（未布局时用屏宽）减去左右留白。 */
private fun formulaMaxWidthPx(view: TextView): Int {
    val metrics = view.resources.displayMetrics
    val available = if (view.width > 0) view.width else metrics.widthPixels
    return available - (metrics.density * 24).toInt()
}

/** 优先用 JLatexMath 真实测量公式宽度；测量失败时按字符数估算。 */
private fun formulaWidthMeasurer(view: TextView): (String) -> Int {
    val textSizePx = 17f * view.resources.displayMetrics.scaledDensity
    return { latex ->
        runCatching { JLatexMathDrawable.builder(latex).textSize(textSizePx).build().intrinsicWidth }
            .getOrDefault((latex.length * textSizePx * 0.62f).toInt())
    }
}

@Composable
private fun AssistantThumbnail(
    path: String,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val bitmap = remember(path) { decodeSampledBitmap(path, 320) }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "查看照片",
            modifier = modifier.clip(LiXingRadius.Card).clickable(onClick = onClick),
            contentScale = ContentScale.Crop,
        )
    } else {
        Box(
            modifier = modifier
                .clip(LiXingRadius.Card)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text("图片不可用", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun PhotoViewerDialog(paths: List<String>, initialIndex: Int, onDismiss: () -> Unit) {
    val pagerState = rememberPagerState(
        initialPage = initialIndex.coerceIn(0, (paths.size - 1).coerceAtLeast(0)),
        pageCount = { paths.size },
    )
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black).clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                val bitmap = remember(paths[page]) { decodeSampledBitmap(paths[page], 2400) }
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "照片大图 ${page + 1}",
                        modifier = Modifier.fillMaxSize().padding(16.dp),
                        contentScale = ContentScale.Fit,
                    )
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("图片文件不可用", color = Color.White)
                    }
                }
            }
            if (paths.size > 1) {
                Text(
                    text = "${pagerState.currentPage + 1}/${paths.size}",
                    color = Color.White,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp),
                )
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
            ) {
                Icon(Icons.Filled.Close, contentDescription = "关闭", tint = Color.White)
            }
        }
    }
}

private fun decodeSampledBitmap(path: String, maxDimension: Int): android.graphics.Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxDimension * 2) sample *= 2
    BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
}.getOrNull()

/**
 * 挂在产生待确认项的那条 assistant 气泡下面的入口按钮。
 *
 * 计划与英语积累各一个、互不合并；对应类别没有待确认内容时该按钮整体不渲染，
 * 两个计数都是 0 时本组件不输出任何内容。
 */
@Composable
private fun AssistantMessageActionBar(
    planCount: Int,
    englishCount: Int,
    onOpenPlan: () -> Unit,
    onOpenEnglish: () -> Unit,
) {
    if (planCount == 0 && englishCount == 0) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (planCount > 0) {
            FilledTonalButton(
                onClick = onOpenPlan,
                modifier = Modifier.fillMaxWidth(),
                shape = LiXingRadius.Pill,
            ) {
                Text("📋 确认计划调整（$planCount 项）")
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
private fun PlanChangeReviewPage(
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
private fun EnglishChangeReviewPage(
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
