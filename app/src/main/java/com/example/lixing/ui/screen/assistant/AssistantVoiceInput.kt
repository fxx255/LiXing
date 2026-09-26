package com.example.lixing.ui.screen.assistant

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import java.util.Locale
internal enum class VoiceInputStatus { IDLE, LISTENING, PROCESSING }

internal class VoiceInputSession {
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

internal fun Bundle.bestSpeechResult(): String? =
    getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        ?.firstOrNull()
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

internal fun speechRecognitionIntent(context: Context): Intent =
    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        putExtra(RecognizerIntent.EXTRA_PROMPT, "请说话")
    }

@Suppress("DEPRECATION")
internal fun configuredSpeechRecognitionService(context: Context): ComponentName? =
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
internal const val VOICE_RECOGNITION_SEGMENT_MS = 4_000L

/** 按住说话时输入条内的声纹波形：右侧滚动显示最新振幅，静默时保持呼吸动画。 */
@Composable
internal fun VoiceWaveform(levels: List<Float>, active: Boolean, modifier: Modifier = Modifier) {
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
