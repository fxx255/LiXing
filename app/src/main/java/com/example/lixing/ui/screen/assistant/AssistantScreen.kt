package com.example.lixing.ui.screen.assistant

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.lixing.data.local.entity.AssistantConversationEntity
import com.example.lixing.domain.assistant.AssistantContextKind
import com.example.lixing.ui.photo.PhotoEdits
import com.example.lixing.ui.screen.today.dialog.PhotoCropDialog
import com.example.lixing.ui.theme.LiXingRadius
import com.example.lixing.ui.util.ScreenOrientationGuard
import java.io.File
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlin.math.sin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
internal const val FIGURE_Z_INDEX = 1f
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
