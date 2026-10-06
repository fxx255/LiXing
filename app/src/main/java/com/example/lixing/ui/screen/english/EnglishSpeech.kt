package com.example.lixing.ui.screen.english

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.lixing.data.dictionary.CachedPronunciation
import com.example.lixing.data.dictionary.EnglishPronunciationRepository
import com.example.lixing.data.dictionary.PronunciationAudio
import kotlinx.coroutines.*
import java.io.File
import java.util.Locale

/** Prefer a dictionary recording; sentences and unavailable recordings use installed TTS. */
internal class EnglishSpeech(context: Context) {
    private val context = context.applicationContext
    private val recordings = EnglishPronunciationRepository(File(context.cacheDir, "english-pronunciation"))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var request: Job? = null
    private var player: MediaPlayer? = null
    private var generation = 0
    private var initialized = false
    private var ready = false
    private var pending: Pair<String, Boolean>? = null
    private var engine: TextToSpeech? = null
    private var disposed = false
    var source by mutableStateOf<PronunciationAudio?>(null)
        private set

    init {
        engine = TextToSpeech(this.context) { status ->
            initialized = true
            ready = status == TextToSpeech.SUCCESS
            if (!disposed) pending?.let { pending = null; speakOffline(it.first, it.second) }
        }
    }

    fun speak(text: String, british: Boolean, online: Boolean) {
        if (disposed || text.isBlank()) return
        stop()
        val token = generation
        request = scope.launch {
            val recording = if (online) try {
                withTimeoutOrNull(6_000) { recordings.recording(text, british) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) { null } else null
            ensureActive()
            if (recording != null) play(recording, text, british, token)
            else speakOffline(text, british)
        }
    }

    private fun play(recording: CachedPronunciation, text: String, british: Boolean, token: Int) {
        val media = try { MediaPlayer() } catch (_: Exception) { speakOffline(text, british); return }
        player = media
        fun fallback() {
            if (disposed || generation != token || player !== media) return
            player = null
            media.release()
            source = null
            speakOffline(text, british)
        }
        try {
            media.setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            media.setDataSource(recording.file.absolutePath)
            media.setOnPreparedListener {
                if (!disposed && generation == token && player === media) {
                    try {
                        media.start()
                        source = recording.audio
                    } catch (_: Exception) { fallback() }
                }
            }
            media.setOnCompletionListener {
                if (player === media) { player = null; media.release() }
            }
            media.setOnErrorListener { _, _, _ -> fallback(); true }
            media.prepareAsync()
        } catch (_: Exception) { fallback() }
    }

    private fun speakOffline(text: String, british: Boolean) {
        if (disposed) return
        val tts = engine
        if (!initialized || tts == null) {
            pending = text to british
            return
        }
        if (!ready) {
            Toast.makeText(context, "系统语音无法启动，请检查文字转语音设置", Toast.LENGTH_LONG).show()
            return
        }
        val voice = preferredEnglishVoice(tts.voices.orEmpty(), british)
        if (voice == null) {
            Toast.makeText(context, "没有可用的${if (british) "英式" else "美式"}语音，请在系统文字转语音设置中下载对应英文语音包", Toast.LENGTH_LONG).show()
            return
        }
        tts.voice = voice
        tts.setSpeechRate(1f)
        tts.setPitch(1f)
        if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "english-word") == TextToSpeech.ERROR)
            Toast.makeText(context, "发音播放失败，请检查系统语音设置", Toast.LENGTH_SHORT).show()
    }

    fun stop() {
        generation++
        request?.cancel()
        request = null
        pending = null
        player?.release()
        player = null
        source = null
        engine?.stop()
    }

    fun close() {
        disposed = true
        stop()
        scope.cancel()
        engine?.shutdown()
        engine = null
    }
}

internal fun preferredEnglishVoice(voices: Set<Voice>, british: Boolean): Voice? {
    val locale = if (british) Locale.UK else Locale.US
    return voices.asSequence().filter {
        it.locale.language == "en" && it.locale.country == locale.country &&
            !it.isNetworkConnectionRequired && "notInstalled" !in it.features.orEmpty()
    }.sortedWith(compareByDescending<Voice> { it.quality }.thenBy { it.latency }.thenBy { it.name }).firstOrNull()
}
