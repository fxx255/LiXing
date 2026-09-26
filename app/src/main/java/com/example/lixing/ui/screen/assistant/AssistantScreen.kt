package com.example.lixing.ui.screen.assistant

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.util.Log
import android.view.MotionEvent
import android.view.View
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddComment
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.lixing.R
import com.example.lixing.data.assistant.isPlaceholderReply
import com.example.lixing.data.assistant.normalizeAssistantMarkdown
import com.example.lixing.data.assistant.sanitizeAssistantLatex
import com.example.lixing.data.assistant.wrapLongFormulas
import com.example.lixing.data.local.entity.AssistantConversationEntity
import com.example.lixing.domain.assistant.AssistantContextKind
import com.example.lixing.ui.photo.PhotoEdits
import com.example.lixing.ui.screen.today.dialog.PhotoCropDialog
import com.example.lixing.ui.theme.LiXingRadius
import com.example.lixing.ui.util.ScreenOrientationGuard
import io.noties.markwon.Markwon
import io.noties.markwon.ext.latex.JLatexMathPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.tables.TableTheme
import io.noties.markwon.inlineparser.MarkwonInlineParserPlugin
import java.io.File
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import kotlin.math.sin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ru.noties.jlatexmath.JLatexMathDrawable

private data class PhotoViewerState(val paths: List<String>, val initialIndex: Int)

/** AI 学习助手页：对话 + 可选上下文 + 计划修改预览确认。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistantScreen(
    onBack: () -> Unit,
    /**
     * 从通知点进来时要直接打开的会话 id。
     *
     * 只消费一次；导航事件带自增 id，重复点击同一条 route 会重新进入本屏，
     * 从而再次打开对应会话。
     */
    initialConversationId: String? = null,
    viewModel: AssistantViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // 打开通知指向的会话（每个 VM 只打开一次：页面重建重跑本效应时不会重置会话）。
    LaunchedEffect(initialConversationId) {
        if (!initialConversationId.isNullOrBlank()) {
            viewModel.openInitialConversation(initialConversationId)
        }
    }
    val snackbar = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val context = LocalContext.current
    // 等相机回写的临时文件：用路径 + rememberSaveable 存，
    // 这样旋转屏幕或被系统回收重建后仍能取回照片（普通 remember 会丢）。
    var pendingCapturePath by rememberSaveable { mutableStateOf<String?>(null) }
    var photoViewer by remember { mutableStateOf<PhotoViewerState?>(null) }
    // 待裁剪的照片队列：存路径并用 rememberSaveable。
    // 部分 ROM 在写入 requestedOrientation 时会顺带重建 Activity，
    // 普通 remember 一丢，刚弹出的裁剪界面就「闪退」（第一次拍照有概率消失、第二次正常的根因）。
    var cropQueue by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
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
        val file = pendingCapturePath?.let { File(it) }
        pendingCapturePath = null
        if (success && file != null) cropQueue = cropQueue + file.absolutePath else file?.delete()
    }
    val pickPhotos = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        val imported = uris.mapNotNull { uri -> copyAssistantPhoto(context, uri)?.absolutePath }
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
    LaunchedEffect(state.currentConversationId, state.messages.lastOrNull()?.id) {
        if (state.messages.isNotEmpty() || state.pendingActions.isNotEmpty()) {
            listState.scrollToItem(0)
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
    cropQueue.firstOrNull()?.let { path ->
        PhotoCropDialog(
            path = path,
            onCropped = {
                viewModel.onPhotoTaken(path)
                cropQueue = cropQueue.drop(1)
            },
            onDismiss = {
                File(path).delete()
                cropQueue = cropQueue.drop(1)
            },
            onUseOriginal = {
                viewModel.onPhotoTaken(path)
                cropQueue = cropQueue.drop(1)
            },
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
                            // 这里的行为是「开一个全新对话」，不是删除：用「气泡 + 加号」表达新建语义。
                            // 历史列表里的删除仍用垃圾桶图标（AssistantHistorySheet），两者不要混。
                            IconButton(onClick = viewModel::startNewConversation) {
                                Icon(Icons.Filled.AddComment, contentDescription = "新建对话")
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
                if (!state.busy && state.messages.isEmpty()) {
                    item(key = "empty-hint") { EmptyHint() }
                }
                itemsIndexed(state.messages.asReversed(), key = { index, message -> message.id ?: "local-$index" }) { index, message ->
                    val messageIndex = state.messages.size - 1 - index
                    val streamingAnswer = index == 0 && state.busy && message.role == "assistant"
                    val displayContent = message.displayContent ?: message.content
                    // The newest answer shares a stable list item with thinking, above its bubble.
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (index == 0 && state.busy) {
                            ThinkingPanel(
                                reasoning = state.activeReasoning,
                                answerStarted = state.activeAnswerStarted,
                                expanded = state.reasoningExpanded,
                                onToggle = viewModel::toggleReasoningExpanded,
                            )
                        }
                        // A persisted placeholder (for example "见上") can be
                        // present before the first visible answer delta. Keep
                        // that transient bubble hidden while the thinking
                        // panel is active; reveal the bubble only after the
                        // answer stream has actually started.
                        val hasAnswerText = displayContent.isNotBlank() &&
                            !isAssistantPlaceholder(displayContent)
                        val showBubble = if (message.role == "assistant") {
                            // Keep figure slots (including failed slots with a
                            // retry hint), but never show a useless placeholder
                            // or an empty answer while the thinking panel runs.
                            val hasFigureSlot = message.imagePaths.any(String::isNotBlank) ||
                                displayContent.contains("[[FIGURE:")
                            hasFigureSlot ||
                                (streamingAnswer && state.activeAnswerStarted && hasAnswerText) ||
                                (!streamingAnswer && hasAnswerText)
                        } else {
                            !streamingAnswer || displayContent.isNotBlank() || message.imagePaths.isNotEmpty()
                        }
                        if (showBubble) {
                            MessageBubble(
                                role = message.role,
                                content = displayContent,
                                imagePaths = message.imagePaths,
                                streaming = streamingAnswer,
                                onImageClick = { paths, imageIndex -> photoViewer = PhotoViewerState(paths, imageIndex) },
                            )
                        }
                        if (message.role == "assistant" && !(index == 0 && state.busy)) {
                            AssistantMessageActionBar(
                                planCount = if (state.pendingActionsOwnerIndex == messageIndex) state.pendingActions.size else 0,
                                englishCount = if (state.pendingEnglishOwnerIndex == messageIndex) state.pendingEnglishActions.size else 0,
                                onOpenPlan = viewModel::openPlanReview,
                                onOpenEnglish = viewModel::openEnglishReview,
                                planAppliedCount = if (state.pendingActionsOwnerIndex == messageIndex) {
                                    state.planReviewAppliedCount
                                } else {
                                    0
                                },
                            )
                        }
                    }
                }
                if (state.busy && state.messages.isEmpty()) {
                    item(key = "thinking") {
                        ThinkingPanel(state.activeReasoning, state.activeAnswerStarted,
                            state.reasoningExpanded, viewModel::toggleReasoningExpanded)
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
                        state.lastContextNote?.let { note ->
                            Text(
                                // 显示上一轮实际附带的资料，包含模型按问题自动补充的部分。
                                "上轮实际附带：$note",
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
                        // 输入框左侧的「重新发送」：只在当前会话最新请求中断且可重试时出现。
                        // 无中断时不占位，因此不会多出一行常驻提示。
                        state.retryRequestId?.let { requestId ->
                            RetrySendButton(
                                reason = state.retryReason,
                                busy = state.busy || state.retrying,
                                onClick = viewModel::retryInterrupted,
                                requestId = requestId,
                            )
                        }
                        IconButton(
                            onClick = {
                                val file = createAssistantPhotoFile(context)
                                pendingCapturePath = file.absolutePath
                                val uri = FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    file,
                                )
                                // 相机普遍声明竖屏且不受用户方向锁定约束，先把方向锁在当前方向，
                                // 回到前台时由 MainActivity.onResume 解除（见 ScreenOrientationGuard）。
                                ScreenOrientationGuard.armBeforeExternalCapture(context)
                                runCatching { takePicture.launch(uri) }
                                    .onFailure { pendingCapturePath = null; file.delete() }
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
                        // ⊕ 放回输入框与发送之间（v1.0.8 误移到左侧工具组，已改回）
                        FilledTonalIconButton(
                            onClick = { extrasExpanded = !extrasExpanded },
                            enabled = !state.busy && !holdingTalk && voiceInputStatus == VoiceInputStatus.IDLE,
                        ) {
                            Icon(
                                if (extrasExpanded) Icons.Filled.Close else Icons.Filled.Add,
                                contentDescription = if (extrasExpanded) "收起更多选项" else "展开更多选项",
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
internal fun ThinkingPanel(
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

/**
 * 气泡最大宽度：按「当前可用宽度」的比例算，而不是写死固定值。
 *
 * 写死值在手机上合适，但平板横屏（可用宽度 1000dp+）下会变成一条偏窄的文字带、
 * 右侧大片留白。这里取 min(可用宽 × 0.92, 1400dp)：
 * - 手机 400dp → 368dp（和原来手感一致）
 * - 平板横屏 1200dp → 1104dp（几乎铺满，只剩一点对侧留白区分收发双方）
 * 1400dp 只是给超宽屏/桌面窗口兜底，正常平板由比例决定。
 */
private val BUBBLE_MAX_WIDTH = 1400.dp
private const val BUBBLE_WIDTH_RATIO = 0.92f

/**
 * 内嵌在回答里的生成图：独占整行、宽度撑满气泡，点击可查看大图。
 *
 * 与缩略图分开处理的原因：缩略图适合「用户拍了一叠照片」的横向排布，
 * 而生成的图表要看清刻度和曲线，必须占满整行才有意义。
 */
@Composable
internal fun InlineGeneratedImage(path: String, onClick: () -> Unit) {
    // 解码失败短暂重试：流式回答里「PNG 落盘」与「路径进消息」之间可能有极短时间差，
    // 恢复历史消息时也可能撞上存储尚未就绪。remember 的 key 带上 attempt，
    // 重试时才会重新尝试解码。
    var decodeAttempt by remember(path) { mutableIntStateOf(0) }
    val photoRevision by PhotoEdits.revision.collectAsStateWithLifecycle()
    val bitmap = remember(path, decodeAttempt, photoRevision) {
        val file = java.io.File(path)
        // 文件不存在就直接返回 null，不必走一遍解码
        if (!file.exists() || file.length() <= 0L) null else decodeSampledBitmap(path, 1600)
    }
    LaunchedEffect(path, bitmap) {
        if (bitmap == null && decodeAttempt < DECODE_RETRY_MAX) {
            delay(DECODE_RETRY_DELAY_MS)
            decodeAttempt++
        }
    }
    if (bitmap == null) {
        // 图还没生成好、读取失败，或缓存 PNG 已被系统回收：
        // 给一个占位，避免整条回答的排版错位。区分「加载中」与「已失效」，
        // 后者不该一直显示「加载中…」让用户干等。
        //
        // 占位框也必须可点击（v1.0.26 用户反馈「只有第一张图能点，其余点了没反应」）：
        // 以前占位框上没有任何 clickable，一旦走到这里这张图就永久「点了没反应」。
        // 现在点占位同样打开查看器——文件还在就正常显示，不在也给出明确文案，
        // 绝不出现「无响应」。
        val expired = remember(path) { !java.io.File(path).exists() }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                // zIndex：命中测试按 zIndex 从高到低进行，所以图片要排在文本块之后
                // （见下方 Image 的说明）。占位框同样需要，否则「加载中」时也会被吃掉触摸。
                .zIndex(FIGURE_Z_INDEX)
                .height(110.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable { onClick() },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                when {
                    decodeAttempt < DECODE_RETRY_MAX -> "图表加载中…"
                    expired -> "图表已过期（可重新生成一次）"
                    else -> "图表暂时无法显示"
                },
                style = MaterialTheme.typography.labelMedium,
            )
        }
        return
    }
    val isDiagram = remember(path, bitmap.width, bitmap.height) {
        shouldScrollGeneratedImage(File(path), bitmap.width, bitmap.height)
    }
    if (isDiagram) {
        // Wide textbook diagrams become unreadably small when they are always
        // fitted to a phone-width bubble. Keep a readable minimum canvas and
        // let the bubble scroll horizontally. Measure the finite viewport
        // before adding horizontalScroll, which gives its child infinite width.
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .zIndex(FIGURE_Z_INDEX)
                .clip(RoundedCornerShape(10.dp)),
        ) {
            val viewportWidth = maxWidth.takeIf { it.value.isFinite() && it.value > 0f }
                ?: DIAGRAM_MIN_INLINE_WIDTH
            val diagramWidth = maxOf(viewportWidth, DIAGRAM_MIN_INLINE_WIDTH)
            Box(Modifier.width(viewportWidth).horizontalScroll(rememberScrollState())) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "生成的图表",
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier
                        .width(diagramWidth)
                        .clickable { onClick() },
                )
            }
        }
    } else {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "生成的图表",
            contentScale = ContentScale.FillWidth,
            modifier = Modifier
                .fillMaxWidth()
                // 为什么图片要抬高 zIndex：同一个 Column 里的文本块是真实 Android 视图
                // （interop），它的实际高度一旦比 Compose 给它的格位高，多出来的部分就
                // 盖在这张图片上、把本该落到图片上的触摸先吃掉。Compose 的 zIndex 会把
                // 命中测试顺序改成「先测图片」，于是即便文本块略有溢出，点击也能落在图上。
                // 这个是**结构性兜底**：不依赖「高度一定准」这个前提。
                .zIndex(FIGURE_Z_INDEX)
                .clip(RoundedCornerShape(10.dp))
                .clickable { onClick() },
        )
    }
}

/**
 * 图片/表格在气泡内的层级。
 *
 * 文本块与图片是同一 Column 里的兄弟。命中测试按 zIndex 从高到低进行，
 * 给交互元素抬到 1f 就能让它们先于文本块被命中 —— 文本块的高度只要有一点偏差，
 * 就会盖住靠后的图片与表格并吃掉它们的触摸（用户反馈的「越靠后越点不动」）。
 */
private const val FIGURE_Z_INDEX = 1f
// A 640dp minimum made the phone preview needlessly wide. The compact
// textbook layout remains horizontally scrollable when needed, but exposes
// more of the diagram in the initial viewport.
private val DIAGRAM_MIN_INLINE_WIDTH = 520.dp

/** The dimensions also cover diagrams restored into the generic backup folder. */
internal fun shouldScrollGeneratedImage(file: File, width: Int, height: Int): Boolean =
    file.parentFile?.name == "diagrams" ||
        (file.extension.equals("png", ignoreCase = true) &&
            (file.name.startsWith("diagram_") ||
                (width >= 1000 && height > 0 && width.toFloat() / height >= 2.1f)))

private const val DECODE_RETRY_MAX = 3
private const val DECODE_RETRY_DELAY_MS = 600L

@Composable
internal fun MessageBubble(
    role: String,
    content: String,
    imagePaths: List<String>,
    streaming: Boolean = false,
    onImageClick: (List<String>, Int) -> Unit,
) {
    val isUser = role == "user"
    // BoxWithConstraints 拿的是「父容器实际给到的最大宽度」，
    // 比 LocalConfiguration 的屏幕宽度更准（含列表内边距、分屏/多窗口）。
    // 注意：align 必须直接用在 BoxWithConstraints 作用域里——中间不能再包一层
    // 收缩宽度的 Box，否则靠右对齐失效（v1.0.5 用户气泡全跑到左边的根因）。
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val bubbleMaxWidth = minOf(maxWidth * BUBBLE_WIDTH_RATIO, BUBBLE_MAX_WIDTH)
        // A very tall rounded Surface clip can reject touches on later visible children
        // on dense screens. Paint the rounded background without clipping the whole answer.
        // Padding and each image/TextView's own clip keep content within its bounds.
        Box(
            modifier = Modifier
                .align(if (isUser) Alignment.CenterEnd else Alignment.CenterStart)
                .widthIn(max = bubbleMaxWidth)
                .background(
                    color = if (isUser) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(
                        topStart = 14.dp, topEnd = 14.dp,
                        bottomStart = if (isUser) 14.dp else 4.dp,
                        bottomEnd = if (isUser) 4.dp else 14.dp,
                    ),
                ),
        ) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // 用户拍的题图：小缩略图排在文字上方（拍照问答的既有形态不变）
                if (isUser && imagePaths.isNotEmpty()) {
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
                    // v1.0.23 的 bug：SelectionContainer 原先包在整个气泡外层，
                    // 它会安装自己的 pointerInput 并把事件标记为已消费，
                    // 结果内层的表格 horizontalScroll 拖不动、图片的 clickable 也失效
                    // （用户反馈「表格无法拖动、图片点不开」）。
                    // 现在只把「可选择的长按复制」限制在文字上，图片与滚动容器放在外面。
                    if (isUser) {
                        SelectionContainer {
                            Text(content, style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    } else {
                        if (streaming) {
                            StreamingMarkdownBody(content)
                        } else {
                            AssistantMarkdownBody(content, imagePaths, onImageClick)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Stable, throttled rendering for an answer that is still arriving.
 *
 * Markwon and JLatexMath need a complete Markdown/LaTeX span before they can
 * measure it. Feeding the whole answer to Markwon for every token makes an
 * unfinished formula acquire a new height repeatedly, which moves the rest of
 * the chat while the answer is being generated. The tokenizer therefore keeps
 * the unfinished suffix as plain text and only hands closed blocks to the
 * normal Markdown renderer. A small ticker limits AndroidView/Markwon updates
 * to at most one batch roughly every 80 ms while preserving the final full
 * rendering path once streaming ends.
 */
@Composable
private fun StreamingMarkdownBody(content: String) {
    val tokenizer = remember { StreamingMarkdownTokenizer() }
    val latestContent = rememberUpdatedState(content)
    var snapshot by remember { mutableStateOf(tokenizer.update(content)) }
    var renderedContent by remember { mutableStateOf(content) }

    LaunchedEffect(tokenizer) {
        while (isActive) {
            val nextContent = latestContent.value
            if (nextContent != renderedContent) {
                snapshot = tokenizer.update(nextContent)
                renderedContent = nextContent
            }
            delay(STREAMING_MARKDOWN_TICK_MS)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        snapshot.blocks.forEach { block ->
            key("stream-block-${block.id}") {
                MarkdownAnswer(block.text)
            }
        }
        if (snapshot.tail.isNotEmpty()) {
            key("stream-tail-${snapshot.tailId}") {
                var maxTailHeightPx by remember { mutableIntStateOf(0) }
                val density = LocalDensity.current
                Box(
                    modifier = Modifier
                        .heightIn(min = with(density) { maxTailHeightPx.toDp() })
                        .onSizeChanged { maxTailHeightPx = maxOf(maxTailHeightPx, it.height) },
                ) {
                    // The same TextView remains mounted while the paragraph
                    // grows. Closed formulas are parsed once, then subsequent
                    // source text is appended as plain text to the cached
                    // Spanned; opening another formula cannot make the first
                    // one disappear or schedule it for a fresh parse.
                    StreamingMarkdownChunk(snapshot)
                }
            }
        }
    }
}

private const val STREAMING_MARKDOWN_TICK_MS = 80L

private data class StreamingMarkdownRenderCache(
    val tailId: Long,
    val prefixLength: Int,
    val widthPx: Int,
    val textColor: Int,
    val linkColor: Int,
    val renderedPrefix: Spanned,
)

@Composable
private fun StreamingMarkdownChunk(snapshot: StreamingMarkdownSnapshot) {
    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val linkColor = MaterialTheme.colorScheme.primary.toArgb()
    var widthPx by remember { mutableIntStateOf(0) }
    AndroidView(
        modifier = Modifier.fillMaxWidth().clipToBounds().onSizeChanged { widthPx = it.width },
        factory = { context -> createMarkdownTextView(context, textColor, linkColor) },
        update = { view ->
            renderStreamingMarkdown(view, snapshot, widthPx, textColor, linkColor)
        },
    )
}

/** Reuse already parsed formula spans while appending the still-growing suffix. */
private fun renderStreamingMarkdown(
    view: TextView,
    snapshot: StreamingMarkdownSnapshot,
    widthPx: Int,
    textColor: Int,
    linkColor: Int,
) {
    view.setTextColor(textColor)
    view.setLinkTextColor(linkColor)
    val prefixLength = (snapshot.mathPrefixEnd - snapshot.tailStart).coerceIn(0, snapshot.tail.length)
    if (prefixLength == 0 || widthPx <= 0) {
        // Before the first formula closes, there is no Markdown work to do.
        // Keep the same TextView so a later formula does not swap UI types.
        view.text = snapshot.tail
        view.setTag(R.id.streaming_markdown_cache, null)
        return
    }
    val old = view.getTag(R.id.streaming_markdown_cache) as? StreamingMarkdownRenderCache
    val cached = old?.takeIf {
        it.tailId == snapshot.tailId && it.prefixLength == prefixLength &&
            it.widthPx == widthPx && it.textColor == textColor && it.linkColor == linkColor
    }
    val prefix = cached?.renderedPrefix ?: runCatching {
        val source = snapshot.tail.substring(0, prefixLength)
        val prepared = wrapLongFormulas(
            sanitizeAssistantLatex(normalizeAssistantMarkdown(source)),
            formulaMaxWidthPx(view, widthPx),
            formulaWidthMeasurer(view),
        )
        (view.tag as Markwon).toMarkdown(prepared)
    }.onFailure { error ->
        Log.e(RENDER_LOG_TAG, "streaming formula render failed, fallback to plain text", error)
        appendRenderErrorLog(view.context, snapshot.tail, error)
    }.getOrNull()
    if (prefix == null) {
        view.text = snapshot.tail
        view.setTag(R.id.streaming_markdown_cache, null)
        return
    }
    if (cached == null) {
        view.setTag(R.id.streaming_markdown_cache,
            StreamingMarkdownRenderCache(snapshot.tailId, prefixLength, widthPx,
                textColor, linkColor, prefix))
    }
    val combined = SpannableStringBuilder(prefix)
        .append(snapshot.tail.substring(prefixLength))
    runCatching {
        (view.tag as Markwon).setParsedMarkdown(view, combined)
        view.scrollTo(0, 0)
    }.onFailure { error ->
        Log.e(RENDER_LOG_TAG, "streaming formula display failed, fallback to plain text", error)
        appendRenderErrorLog(view.context, snapshot.tail, error)
        view.text = snapshot.tail
        view.setTag(R.id.streaming_markdown_cache, null)
    }
}

private fun isAssistantPlaceholder(content: String): Boolean =
    isPlaceholderReply(content)

/**
 * 回答正文渲染。
 *
 * 模型偶尔会输出 JLatexMath 解析不了的 LaTeX（缺右括号、残留 \tag、aligned 前导非法字符等），
 * 插件默认行为是把 ParseException 包成 RuntimeException 抛出，会在 UI 线程把 App 带崩。
 * 这里做三层兜底：单条公式失败画占位 → 整段渲染失败回退纯文本 → 异常写入本地日志便于定位。
 */
/**
 * 表格画布相对气泡宽度的**最大**放大倍数。
 *
 * 只在表格自然宽度放不下时才撑宽，且撑到「刚好够」为止。早期版本是**无条件**乘 1.8，
 * 于是只有两三列短文字的窄表格也被拉成 1.8 倍 —— 用户反馈「宽度富裕很多但依然控了
 * 很大」，右侧一大半被顶到屏幕外，还得手动横拖才看得到。现在 1.8 只是上限。
 */
internal const val TABLE_MAX_WIDTH_FACTOR = 1.8f

/** 正文 Markdown 字号（sp）。估算表格自然宽度时必须与 TextView 的实际字号一致。 */
internal const val MARKDOWN_TEXT_SIZE_SP = 17f

/**
 * 估算表格宽度时的安全余量。
 *
 * 估算本身是近似的（中英文混排、行内公式都算不精确），留点余量避免「刚好差一点」
 * 导致单元格被换行挤压。宁可多撑一点点，也不要把公式挤变形。
 */
private const val TABLE_WIDTH_ESTIMATE_MARGIN = 1.08f

/** 一段回答的渲染片段。 */
internal data class MarkdownChunkSpec(val text: String, val isTable: Boolean)

/**
 * 把回答按「表格块 / 非表格块」切开。
 *
 * 目的是让渲染层单独对表格启用横向滚动：Markwon 的表格会压缩到容器宽度，
 * 窄气泡里单元格内容（尤其行内公式）会被挤成一团——公式被压成小字号、
 * 中文竖排。切开后表格按更宽的画布绘制，放不下时可以左右滑。
 * 代码围栏内的 `|` 不算表格。
 */
internal fun splitMarkdownTableBlocks(markdown: String): List<MarkdownChunkSpec> {
    val lines = markdown.split('\n')
    val chunks = mutableListOf<MarkdownChunkSpec>()
    val text = StringBuilder()
    var fence: String? = null

    fun flushText() {
        if (text.isNotEmpty()) {
            chunks += MarkdownChunkSpec(text.toString().trimEnd('\n'), isTable = false)
            text.clear()
        }
    }

    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trimStart()
        if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
            val marker = if (trimmed.startsWith("```")) "```" else "~~~"
            fence = if (fence == null) marker else if (fence == marker) null else fence
            text.append(line).append('\n')
            i++
            continue
        }
        if (fence != null) {
            text.append(line).append('\n')
            i++
            continue
        }
        val header = lines.getOrNull(i + 1)
        if ('|' in line && header != null && isTableSeparator(header)) {
            flushText()
            val table = StringBuilder().append(line).append('\n').append(header).append('\n')
            var j = i + 2
            while (j < lines.size && '|' in lines[j]) {
                table.append(lines[j]).append('\n')
                j++
            }
            chunks += MarkdownChunkSpec(table.toString().trimEnd('\n'), isTable = true)
            i = j
            continue
        }
        text.append(line).append('\n')
        i++
    }
    flushText()
    return chunks
}

/** Markdown 表格的分隔行：`| --- | :--: |` 这类。 */
private fun isTableSeparator(line: String): Boolean {
    val trimmed = line.trim()
    if ('-' !in trimmed) return false
    val cells = trimmed.trim('|').split('|')
    return cells.isNotEmpty() && cells.all { cell ->
        val token = cell.trim()
        token.isNotEmpty() && token.all { it == '-' || it == ':' } && '-' in token
    }
}

/**
 * 助手回答正文：把生成的图表**插进正文里**，而不是统一堆在气泡末尾。
 *
 * v1.0.23 的 bug：`InlineGeneratedImage` 直接追加在 `MarkdownAnswer` 之后的
 * Column 末尾，于是「正文里说『见图 1』」和真正的图隔着好几段文字，
 * 用户反馈「图像没有嵌入在文字中间，而是附加在消息气泡末尾」。
 *
 * 现在的做法是解析正文里的插图锚点，按锚点把内容切成
 * 「文字段 / 图 / 文字段 / 图 …」交替渲染：
 * - 模型按提示词约定输出独立一行 `[[FIGURE:1]]`（1-based，对应 plots 数组下标）
 * - 没有锚点时：正文照常渲染，图表统一接在末尾（旧行为兜底，不会丢图）
 *
 * @param segments 已解析出的「文字/图」交替片段
 */
@Composable
private fun AssistantMarkdownBody(
    content: String,
    imagePaths: List<String>,
    onImageClick: (List<String>, Int) -> Unit,
) {
    val segments = remember(content, imagePaths.size) { splitFigureSegments(content, imagePaths.size) }
    val visibleIndices = remember(imagePaths) { imagePaths.indices.filter { imagePaths[it].isNotBlank() } }
    val viewerImages = remember(imagePaths) { visibleIndices.map { imagePaths[it] } }
    val hasInlineFigure = segments.any { it.figureIndex != null }
    if (!hasInlineFigure) {
        // 没写锚点（或图没生成出来）：正文 + 末尾图（保持旧排版，模型偶尔不守约定时也不会丢图）。
        //
        // 注意这里的正文用 segments 拼回来而不是直接用 content：splitFigureSegments
        // 会把锚点行剥掉，图没渲染出来时锚点才不会以 `[[FIGURE:1]]` 的字面量露给用户。
        val plainText = remember(segments) {
            segments.joinToString("\n\n") { it.text }.trim()
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (plainText.isNotBlank()) key("text") { MarkdownAnswer(plainText) }
            imagePaths.forEachIndexed { index, path ->
                key("figure-$index") {
                    if (path.isBlank()) FailedGeneratedImageHint(index)
                    else InlineGeneratedImage(path = path) { onImageClick(viewerImages, visibleIndices.indexOf(index)) }
                }
            }
        }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        segments.forEachIndexed { segmentIndex, segment ->
            key("seg-$segmentIndex") {
                if (segment.figureIndex != null) {
                    val index = segment.figureIndex
                    // 锚点越界（模型写了 [[FIGURE:3]] 但只有 2 张图）直接跳过；
                    // 槽位为空串表示该图渲染失败——留出编号但不画图，绝不去占用别的图的位次。
                    val path = imagePaths.getOrNull(index)
                    if (!path.isNullOrBlank() && index in imagePaths.indices) {
                        InlineGeneratedImage(path = path) { onImageClick(viewerImages, visibleIndices.indexOf(index)) }
                    } else if (index in imagePaths.indices) {
                        FailedGeneratedImageHint(index)
                    }
                } else if (segment.text.isNotBlank()) {
                    MarkdownAnswer(segment.text)
                }
            }
        }
        val anchored = segments.mapNotNull { it.figureIndex }.toSet()
        imagePaths.indices.filter { it !in anchored }.forEach { index ->
            key("tail-$index") {
                if (imagePaths[index].isBlank()) FailedGeneratedImageHint(index)
                else InlineGeneratedImage(path = imagePaths[index]) {
                    onImageClick(viewerImages, visibleIndices.indexOf(index))
                }
            }
        }
    }
}

@Composable
private fun FailedGeneratedImageHint(index: Int) {
    Text(
        text = "第 ${index + 1} 张图未能生成，可让助手重新绘制。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 正文片段：要么是一段 Markdown 文字，要么指向某张生成图（0-based）。 */
internal data class FigureSegment(val text: String, val figureIndex: Int?)

/** 模型插入的插图锚点：单独一行的 `[[FIGURE:n]]`（大小写不敏感，允许行内留白）。 */
private val FIGURE_ANCHOR = Regex("""(?im)^[ \t]*\[\[\s*FIGURE\s*:\s*(\d+)\s*\]\][ \t]*$""")

/**
 * 按 `[[FIGURE:n]]` 锚点把正文切成文字段与图段交替的列表。
 *
 * `figureCount` 用于拦掉越界锚点（越界时该锚点退化成空文字段，不产生占位）。
 *
 * **无论能否取到图，锚点行都必须从文字里剥掉**。以前在 `figureCount <= 0` 时直接
 * 返回原文，于是图没渲染出来（plots 解析失败 / 被截断丢掉）时，正文里就裸着
 * `[[FIGURE:1]]` 这样的内部标记显示给用户。现在统一走剥离逻辑，只是不产生图段。
 */
internal fun splitFigureSegments(content: String, figureCount: Int): List<FigureSegment> {
    val matches = FIGURE_ANCHOR.findAll(content).toList()
    if (matches.isEmpty()) return listOf(FigureSegment(content, null))

    val segments = mutableListOf<FigureSegment>()
    var cursor = 0
    matches.forEach { match ->
        val before = content.substring(cursor, match.range.first).trim('\n')
        if (before.isNotBlank()) segments += FigureSegment(before, null)
        val oneBased = match.groupValues[1].toIntOrNull()
        val index = oneBased?.minus(1)
        // 只有确实存在对应图片时才产生图段；否则该锚点被静默丢弃（不露字面量）。
        //
        // figureCount 是**槽位数**而不是「成功渲染的图数」：渲染失败的槽位由调用方
        // 填成空串占位（见 AssistantViewModel.renderFigures），编号因此保持稳定 ——
        // 第 3 张图失败时 [[FIGURE:3]] 仍然指向第 3 个槽位，只是那个槽位不画图，
        // 而不是让后面所有图的编号往前挪一位（那样图和正文就对不上了）。
        if (figureCount > 0 && index != null && index in 0 until figureCount) {
            segments += FigureSegment("", index)
        }
        cursor = match.range.last + 1
    }
    val tail = content.substring(cursor).trim('\n')
    if (tail.isNotBlank()) segments += FigureSegment(tail, null)
    return segments.ifEmpty { listOf(FigureSegment("", null)) }
}

/**
 * 回答正文渲染。
 *
 * 模型偶尔会输出 JLatexMath 解析不了的 LaTeX（缺右括号、残留 \tag、aligned 前导非法字符等），
 * 插件默认行为是把 ParseException 包成 RuntimeException 抛出，会在 UI 线程把 App 带崩。
 * 这里做三层兜底：单条公式失败画占位 → 整段渲染失败回退纯文本 → 异常写入本地日志便于定位。
 *
 * 含表格的回答按块拆开渲染，表格单独走「更宽画布 + 横向滚动」。
 */
@Composable
internal fun MarkdownAnswer(content: String) {
    val chunks = remember(content) { splitMarkdownTableBlocks(content) }
    // 绝大多数回答不含表格：沿用原来的单块路径，零回归
    if (chunks.none { it.isTable }) {
        MarkdownChunk(content, fixedWidthPx = null)
        return
    }
    var containerWidthPx by remember { mutableIntStateOf(0) }
    // 估算表格自然宽度用的画笔。字号必须与 createMarkdownTextView 里的一致
    // （那里单位是 sp，这里要换算成 px），否则估算出来的宽度对不上真实渲染。
    val context = LocalContext.current
    val metrics = context.resources.displayMetrics
    // 与 tableThemeFor() 保持同一套算法，避免两处算出不同的内边距
    val cellPaddingPx = (TABLE_CELL_PADDING_DP * metrics.density).toInt().coerceAtLeast(1)
    val measurePaint = remember(context) {
        android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textSize = MARKDOWN_TEXT_SIZE_SP * metrics.scaledDensity
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .onSizeChanged { containerWidthPx = it.width },
    ) {
        chunks.forEachIndexed { index, chunk ->
            // 显式 key：Column 的内容没有 key 时按**位置**做差量复用，上一帧的
            // [文字块, 表格块] 变成这一帧的 [文字块, 图, 表格块] 时，第 2 个槽位会被
            // 原地复用成「图」——而里面挂的 AndroidView 是真实 Android 视图，
            // 复用/重建时机比纯 Compose 节点脆弱得多，容易留下尺寸与位置都错配的旧视图，
            // 表现为后面的图与表格点不动、拖不动。给 key 让 Compose 按身份匹配。
            key(index) {
                // 注意：key 的 lambda 里**不能写 return@key**——那会让 Kotlin 给这个
                // 匿名函数生成非法方法名 `<anonymous>`，运行期直接 ClassFormatError
                // （编译能过、全套单测一起爆）。所以这里用 if/else 而不是提前返回。
                if (!chunk.isTable || containerWidthPx <= 0) {
                    // 非表格块正常铺满；表格块等测量到宽度再渲染，避免按 0 宽拆分公式
                    if (!chunk.isTable) MarkdownChunk(chunk.text, fixedWidthPx = null)
                } else {
                    // 按需撑宽：先估算表格的自然宽度，放得下就不撑，放不下才撑到刚好够，
                    // 上限 TABLE_MAX_WIDTH_FACTOR。
                    //
                    // 旧版是**无条件**乘 1.8，只有两三列短文字的窄表格也被拉成 1.8 倍
                    // （用户反馈「宽度富裕很多但依然控了很大」，右侧大半在屏幕外）。
                    // 估算本身是纯文本计算（微秒级），可以安全地在流式期间反复调用 ——
                    // 这也是没有改用「试排 + 实测」的原因，那样每帧都要完整排版一次。
                    val tableWidthPx = remember(chunk.text, containerWidthPx, cellPaddingPx) {
                        val natural = estimateTableNaturalWidthPx(chunk.text, measurePaint, cellPaddingPx)
                        resolveTableWidthPx(containerWidthPx, natural)
                    }
                    val scroll = rememberScrollState()
                    // 宽表格可左右拖动。
                    //
                    // 两个必须同时成立的条件（缺一个就拖不动）：
                    // ① 外层 Box 用 wrapContentWidth 而不是 fillMaxWidth —— horizontalScroll 只在
                    //    「内容宽度 > 容器宽度」时才产生可滚动区间；若外层被 fillMaxWidth 撑满，
                    //    可滚动距离就是 0，横滑毫无反应；
                    // ② 内层 TextView 给足固定宽度 tableWidthPx（1.8 倍气泡宽），它才是那个「更宽的内容」。
                    //
                    // clipToBounds：内容是 1.8 倍宽的真实 Android 视图，若不显式裁剪，
                    // 溢出视口的部分会横向画到气泡外面，盖住旁边的文字（用户反馈的
                    // 「表格遮挡了部分文字内容」）。滚动容器本身不保证裁剪主轴。
                    Box(
                        modifier = Modifier
                            .wrapContentWidth()
                            // 与图片同理：抬高 zIndex，保证表格的横向拖动不会因为
                            // 上方文本块的高度偏差而被吃掉（用户反馈「末尾的表格拖不动」）。
                            .zIndex(FIGURE_Z_INDEX)
                            .horizontalScroll(scroll, reverseScrolling = false)
                            .clipToBounds(),
                    ) {
                        // selectable=false：见 createMarkdownTextView 内注释。
                        // 表格块交给外层滚动处理手势，TextView 自己不参与触摸消费。
                        MarkdownChunk(
                            chunk.text,
                            fixedWidthPx = tableWidthPx,
                            selectable = false,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 由 AndroidView 的正常测量流程决定高度。
 * Markwon 在表格首次绘制和公式异步加载后会 setText/requestLayout，Interop 随之重新测量。
 * 不能用固定 height 缓存截住该请求，也不能在 Compose 布局之外反复 measure 同一个 View：
 * 前者留下陈旧的兄弟节点位置，后者让 View 的 measuredHeight 与 Compose 的格位不一致。
 */
@Composable
internal fun MarkdownChunk(
    content: String,
    fixedWidthPx: Int?,
    selectable: Boolean = true,
) {
    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val linkColor = MaterialTheme.colorScheme.primary.toArgb()
    var widthPx by remember { mutableIntStateOf(0) }
    val renderWidthPx = fixedWidthPx ?: widthPx
    val density = LocalDensity.current
    val widthModifier = if (fixedWidthPx != null) {
        Modifier.width(with(density) { fixedWidthPx.coerceAtLeast(1).toDp() })
    } else {
        Modifier.fillMaxWidth()
    }
    AndroidView(
        modifier = widthModifier.clipToBounds().onSizeChanged { widthPx = it.width },
        factory = { context ->
            createMarkdownTextView(context, textColor, linkColor, selectable = selectable)
        },
        update = { view ->
            // 同一份内容只渲染一次，避免思考面板更新/滚动时重新排队异步公式。
            val renderKey = "$content|$renderWidthPx|$textColor|$linkColor"
            if (renderWidthPx > 0 && view.getTag(R.id.markdown_render_key) != renderKey) {
                renderMarkdown(view, content, renderWidthPx, textColor, linkColor)
                view.setTag(R.id.markdown_render_key, renderKey)
            }
        },
    )
}

/** 表格单元格内边距（dp）。Markwon 默认 4dp，这里收紧一档让单元格更紧凑。 */
private const val TABLE_CELL_PADDING_DP = 2

/**
 * 表格主题：在 Markwon 默认值之上**只改单元格内边距**。
 *
 * 默认内边距来自 `TableTheme.buildWithDefaults` 的 `Dip.toPx(4)` = 4dp，
 * 而行高公式是「单元格内容高 + 2 × padding」（反编译 `TableRowSpan.getSize` 确认）。
 * 于是 density 3 的真机上，每行光上下内边距就吃掉 24px —— 文字很少的表格也会显得很空，
 * 还会把内容顶得显示不下。收紧到 [TABLE_CELL_PADDING_DP]。
 *
 * 注意必须从 [TableTheme.create] 的默认值出发（`asBuilder()`），
 * **不能**改用 `TablePlugin.create { }`：那个入口内部走的是 `emptyBuilder()`，
 * 会把边框宽度/颜色、奇偶行底色一起丢成 0。
 */
/** 表格分隔行：`|---|---|` / `|:--:|--:|` 这类只由 `|` `-` `:` 空白组成的行。 */
private val TABLE_SEPARATOR_ROW = Regex("""^[|\-:\s]+$""")

/** 行内公式 `$…$` / `$$…$$`。 */
private val INLINE_MATH_CELL = Regex("""\$\$?([^$]+)\$\$?""")

/** 不占显示宽度的强调标记：`**` `*`` ` `` `_` `{` `}` `~`。 */
private val EMPHASIS_MARKS = Regex("""[*`_{}~]""")

/**
 * 估算表格「每列内容都不换行」时需要的宽度（px）。
 *
 * **为什么是估算而不是试排**：这个宽度在流式输出期间会被反复计算，试排意味着
 * 反复 `setText` + `measure`（一次就是一次完整文本排版），在长回答里会直接把
 * 主线程压垮 —— 那正是「长回答里表格拖不动、图点不开」的可疑根因
 * （见 `docs/known-issues.md` #A）。纯文本估算只有微秒级开销。
 *
 * **判据按「均分列宽」来算**：Markwon 把可用宽度**均分**给每一列，而不是按内容
 * 分配。所以「不挤压」的条件不是「各列宽度之和 ≤ 容器宽」，而是
 * 「**最宽那一列**的宽度 × 列数 ≤ 容器宽」。用前者会严重低估需求 —— 例如三列
 * 200/50/50 的表格，和是 300，看起来正好，但均分后每列只有 100，第一列照样被挤换行。
 */
internal fun estimateTableNaturalWidthPx(
    table: String,
    paint: android.text.TextPaint,
    cellPaddingPx: Int,
    /**
     * 测宽函数，默认用 `paint.measureText`。
     *
     * 做成可注入是为了**可测**：Robolectric 不栅格化字体，它的 `measureText` 直接
     * 返回字符数（实测「名称」= 2.0），拿它断言宽度全是假绿/假红。测试里注入一个
     * 确定性的近似实现，「按需撑宽」这个行为才验得动。
     */
    measureText: (String) -> Float = { paint.measureText(it) },
): Int {
    val textSizePx = paint.textSize
    val columnMax = mutableListOf<Float>()

    table.lineSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() && !TABLE_SEPARATOR_ROW.matches(it) }
        .forEach { row ->
            // `| a | b |` → 去掉首尾的竖线再切，得到 [a, b]
            val cells = row.trim('|').split('|')
            cells.forEachIndexed { index, raw ->
                val cell = raw.trim()
                if (cell.isEmpty() && cells.size == 1) return@forEachIndexed
                val width = measureCellTextPx(cell, textSizePx, measureText)
                while (columnMax.size <= index) columnMax += 0f
                if (width > columnMax[index]) columnMax[index] = width
            }
        }

    if (columnMax.isEmpty()) return 0
    // 均分列宽 ⇒ 需要的总宽 = 最宽列 × 列数，而不是各列之和
    val widest = columnMax.maxOrNull() ?: return 0
    val columns = columnMax.size
    val total = widest * columns + columns * 2f * cellPaddingPx
    return (total * TABLE_WIDTH_ESTIMATE_MARGIN).toInt().coerceAtLeast(1)
}

/**
 * 决定表格最终用多宽：放得下就不撑，放不下才撑到刚好够，但不超过上限。
 *
 * 单独抽成纯函数是为了能直接测 —— 「撑不撑、撑多少」正是这次要修的行为，
 * 埋在 Composable 里就没法断言了。
 */
internal fun resolveTableWidthPx(containerWidthPx: Int, naturalWidthPx: Int): Int {
    if (containerWidthPx <= 0) return 0
    return if (naturalWidthPx <= containerWidthPx) containerWidthPx
    else minOf(naturalWidthPx, (containerWidthPx * TABLE_MAX_WIDTH_FACTOR).toInt())
}

/**
 * 测量一个单元格的显示宽度。
 *
 * Markdown 标记不占显示宽度，先剥掉；行内公式**不能**按源码字符宽度算 —— 渲染出来的
 * 公式通常比源码宽得多。这里按源码长度保守估（并给一个小公式的宽度下限），
 * 宁可多撑一点也不能把公式挤变形。
 */
private fun measureCellTextPx(
    cell: String,
    textSizePx: Float,
    measureText: (String) -> Float,
): Float {
    var width = 0f
    var cursor = 0
    INLINE_MATH_CELL.findAll(cell).forEach { match ->
        width += measureText(EMPHASIS_MARKS.replace(cell.substring(cursor, match.range.first), ""))
        val latex = match.groupValues[1]
        width += maxOf(latex.length * 0.5f * textSizePx, textSizePx * 2f)
        cursor = match.range.last + 1
    }
    width += measureText(EMPHASIS_MARKS.replace(cell.substring(cursor), ""))
    return width
}

internal fun tableThemeFor(context: Context): TableTheme {
    val paddingPx = (TABLE_CELL_PADDING_DP * context.resources.displayMetrics.density)
        .toInt()
        .coerceAtLeast(1)
    return TableTheme.create(context).asBuilder()
        .tableCellPadding(paddingPx)
        .build()
}

internal fun createMarkdownTextView(
    context: Context,
    textColor: Int,
    linkColor: Int,
    selectable: Boolean = true,
): TextView =
    object : TextView(context) {
        /**
         * 只在**真的按在链接/可点片段上**时才消费触摸，其余一律放行。
         *
         * 这一段是「文本块不得抢占触摸」的**真正兜底**，不能省。原因见下方 `apply {}`
         * 块内的长注释：`isClickable` / `isLongClickable` 这些属性会被 AOSP 框架随时
         * 改回来，靠「设一次属性」是不可靠的；而在 `onTouchEvent` 这一层按落点判定，
         * 才是与框架无关的最终裁决点。
         */
        override fun onTouchEvent(event: MotionEvent): Boolean {
            val text = text as? Spannable
            if (text != null &&
                (event.actionMasked == MotionEvent.ACTION_DOWN ||
                    event.actionMasked == MotionEvent.ACTION_UP)
            ) {
                if (!isPointInsideClickableSpan(text, event)) {
                    // 没有落在可点片段上 ⇒ 明确放行，交给父级（图片的 clickable、
                    // 表格的 horizontalScroll、页面的纵向滚动）。
                    // ACTION_DOWN 返回 false 会让后续 MOVE/UP 不再派发给本 View，
                    // 这正合我们要的语义。
                    return false
                }
            }
            return super.onTouchEvent(event)
        }
    }.apply {
        setTextColor(textColor)
        setLinkTextColor(linkColor)
        // selectable=false 用于表格块：setTextIsSelectable(true) 会顺带 setClickable(true)
        // + setLongClickable(true)，把 TextView 变成一个「点击可聚焦」的 View。在真机的
        // 触摸管线上，这类 View 有可能先于父级手势吃掉触摸事件，让外层的横向滚动拖不动。
        // 表格以「读 + 横向拖动」为主，牺牲单元格内的长按选中是划算的。
        if (selectable) {
            setTextIsSelectable(true)
            // ⚠️ 顺序至关重要：**先**设 MovementMethod，**再**关属性。
            //
            // AOSP `TextView.setMovementMethod()` 内部会调用
            // `fixFocusableAndClickableSettings()`，而它在 `mMovement != null` 时会
            // **重新打开** focusable/clickable/longClickable：
            //
            //     private void fixFocusableAndClickableSettings() {
            //         if (mMovement != null) {
            //             setFocusable(FOCUSABLE);
            //             setClickable(true);       // ← 又被打开
            //             setLongClickable(true);   // ← 又被打开
            //         } else { … }
            //     }
            //
            // v1.0.36 里我们把这三行写在了 `setMovementMethod()` **之前**，于是刚关掉就
            // 立刻被框架翻回来，「文本块不抢占触摸」这条修复实际上从未生效 ——
            // 这正是用户反馈「靠后的图还是点不开」迟迟不愈的原因。测试也因此在
            // `!view.isClickable` 上稳定变红（AOSP 源码为证，不是测试环境失真）。
            //
            // 因此这里把顺序倒过来：先挂 MovementMethod（长按选中/复制、链接点击靠它），
            // 再把框架顺手打开的三个属性关掉。
            //
            // 但仅靠「关属性」仍然脆弱：任何插件在 setText 之后重新 setMovementMethod
            // 都会再次触发 fix。所以真正的保障在上面的 `onTouchEvent` 覆写里 ——
            // 那里按「落点是否在可点片段上」决定是否消费触摸，与属性值无关。
            movementMethod = LinkMovementMethod.getInstance()
            isClickable = false
            isLongClickable = false
            isFocusable = false
        } else {
            // 表格块：**尽量不给 MovementMethod**。
            // LinkMovementMethod 继承 ScrollingMovementMethod，只要表格内容比格位高，
            // 用户就能在表格里上下拖动文字，和页面的纵向滚动直接打架（用户反馈
            // 「表格上下拖动与整个页面上下滚动冲突」）。表格的横向滚动由外层 Compose 的
            // horizontalScroll 负责，纵向完全交给页面；单元格内既不需要滚动也不需要选中。
            //
            // ⚠️ 但「设成 null」**拦不住 Markwon**：`CorePlugin.afterSetText()` 会在每次
            // setText 之后检查，只要发现 `getMovementMethod() == null` 就**强行塞进**
            // LinkMovementMethod：
            //
            //     public void afterSetText(TextView view) {
            //         if (!hasExplicitMovementMethod && view.getMovementMethod() == null) {
            //             view.setMovementMethod(LinkMovementMethod.getInstance());
            //         }
            //     }
            //
            // 而 `setMovementMethod` 又会通过 AOSP 的 `fixFocusableAndClickableSettings()`
            // 把 clickable / longClickable / focusable 一并打开（探针实测：表格块渲染后
            // 三个属性全是 true）。所以表格块同样会抢占触摸 —— 这正是「末尾表格拖不动」
            // 反复不愈的机制。**唯一可靠的防线是上面重写的 `onTouchEvent`**：表格块的内容
            // 里没有 `ClickableSpan`，于是任何触摸都会被它判定为「未命中可点片段」而放行。
            //
            // 这里仍然设置成 null（意图明确、且 Markwon 若未来版本尊重该值就能直接生效），
            // 但不再依赖它 —— 真正的保障在 `onTouchEvent`。
            movementMethod = null
        }
        textSize = MARKDOWN_TEXT_SIZE_SP
        val fallbackSizePx = 14f * resources.displayMetrics.scaledDensity
        val renderer = Markwon.builder(context)
            .usePlugin(MarkwonInlineParserPlugin.create())
            .usePlugin(TablePlugin.create(tableThemeFor(context)))
            .usePlugin(
                JLatexMathPlugin.create(this.textSize) { builder ->
                    builder.inlinesEnabled(true)
                    builder.theme().textColor(textColor)
                    // 单条公式解析失败时画占位，绝不让 ParseException 冒泡成整页闪退。
                    // 失败的 latex 与异常类型一并写入本地日志，便于事后定位（用户截图
                    // 只有 60 字符片段，根本无法重建真实失败原因）。
                    builder.errorHandler { latex, error ->
                        Log.w(RENDER_LOG_TAG, "latex render failed: $latex", error)
                        appendRenderErrorLog(
                            context,
                            "LATEX-PIECE:\n$latex\n---",
                            error,
                        )
                        LatexFallbackDrawable(
                            textColor,
                            fallbackSizePx,
                            latex,
                            error.javaClass.simpleName,
                        )
                    }
                },
            )
            .build()
        tag = renderer
    }

/**
 * 触摸点是否落在一个 `ClickableSpan` 上（判定用 widget 内坐标，与本 View 的滚动偏移无关）。
 *
 * 计算方式与 AOSP `LinkMovementMethod.onTouchEvent` 保持一致：先把坐标换算到
 * `Layout` 的坐标系（减内边距、加滚动量），再交给 `Layout` 反查字符偏移。
 *
 * 任何一步越界都返回 false —— 「判不出来」时必须**放行**触摸，宁可让链接偶尔点不到，
 * 也不能因为判定异常而把下方图片的点击整片吃掉。
 */
private fun TextView.isPointInsideClickableSpan(text: Spannable, event: MotionEvent): Boolean {
    val textLayout = layout ?: return false
    return runCatching {
        val x = event.x.toInt() - totalPaddingLeft + scrollX
        val y = event.y.toInt() - totalPaddingTop + scrollY
        if (y < 0 || y >= textLayout.height) return@runCatching false
        val line = textLayout.getLineForVertical(y)
        if (x < textLayout.getLineLeft(line) || x > textLayout.getLineRight(line)) {
            return@runCatching false
        }
        val offset = textLayout.getOffsetForHorizontal(line, x.toFloat())
        text.getSpans(offset, offset, ClickableSpan::class.java).isNotEmpty()
    }.getOrDefault(false)
}

/**
 * 渲染一次回答。
 *
 * 宽度为 0（还没完成布局）时**直接跳过**：以前这里会用屏幕宽度兜底，
 * 而气泡很可能只有 480～760dp，于是长公式不拆、直接溢出被裁；
 * 现在等 [widthPx] 到位后再渲染，最多晚一帧，不会错。
 */
private fun renderMarkdown(
    view: TextView,
    content: String,
    widthPx: Int,
    textColor: Int,
    linkColor: Int,
) {
    if (widthPx <= 0) return
    view.setTextColor(textColor)
    view.setLinkTextColor(linkColor)
    val rendered = wrapLongFormulas(
        sanitizeAssistantLatex(normalizeAssistantMarkdown(content)),
        formulaMaxWidthPx(view, widthPx),
        formulaWidthMeasurer(view),
    )
    runCatching {
        (view.tag as Markwon).setMarkdown(view, rendered)
        // 重渲染后复位内部滚动：LinkMovementMethod 继承自 ScrollingMovementMethod，
        // 内容比框高的瞬间用户能把文字拖出偏移，重新渲染时必须清零，
        // 否则新内容会带着旧的滚动偏移显示（头尾被挡的观感来源之一）。
        view.scrollTo(0, 0)
    }.onFailure { error ->
        Log.e(RENDER_LOG_TAG, "markdown render failed, fallback to plain text", error)
        appendRenderErrorLog(view.context, rendered, error)
        view.text = buildString {
            append(content)
            append("\n\n[部分内容无法渲染，已回退为纯文本]")
        }
    }
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
    errorType: String? = null,
) : Drawable() {
    private val title = if (errorType != null) "⚠ 公式无法渲染 ($errorType)" else "⚠ 公式无法渲染"
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

/**
 * 公式可用宽度：TextView 实测宽度减去左右留白。
 *
 * 只认实测宽度——屏幕宽度在平板上远大于气泡宽度，用它兜底等于「不拆公式」。
 */
private fun formulaMaxWidthPx(view: TextView, measuredWidthPx: Int): Int {
    val metrics = view.resources.displayMetrics
    val padding = (metrics.density * 24).toInt()
    return (measuredWidthPx - padding).coerceAtLeast((metrics.density * 120).toInt())
}

/** 优先用 JLatexMath 真实测量公式宽度；测量失败时按字符数估算。 */
private fun formulaWidthMeasurer(view: TextView): (String) -> Int {
    val textSizePx = 17f * view.resources.displayMetrics.scaledDensity
    return { latex ->
        runCatching { JLatexMathDrawable.builder(latex).textSize(textSizePx).build().intrinsicWidth }
            .getOrDefault((latex.length * textSizePx * 0.62f).toInt())
    }
}

/**
 * 输入框左侧的「重新发送」按钮。
 *
 * 设计要点（对应实施文档第三节）：
 * - **只在中断时出现**：`state.retryRequestId` 为空就整块不渲染，
 *   所以平时不会占据一个常驻提示行；
 * - 图标是回旋箭头（[Icons.Filled.RotateRight]），内容描述为「重新发送」；
 * - 点击立即防重入（`busy`/`retrying` 期间禁用），不会连点发出多次请求；
 * - 失败原因作为副标题展示，让用户知道上次为什么中断，而不是只看到一个孤立的箭头。
 */
@Composable
private fun RetrySendButton(
    requestId: String,
    reason: String?,
    busy: Boolean,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(
            onClick = onClick,
            enabled = !busy,
            modifier = Modifier.semantics { contentDescription = "重新发送" },
        ) {
            Icon(
                imageVector = Icons.Filled.RotateRight,
                contentDescription = "重新发送",
                tint = if (busy) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
        }
        reason?.takeIf { it.isNotBlank() }?.let { text ->
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}
