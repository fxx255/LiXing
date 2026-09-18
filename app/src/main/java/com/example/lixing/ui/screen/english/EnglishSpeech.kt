package com.example.lixing.ui.screen.english

import android.content.Context
import android.speech.tts.TextToSpeech
import android.widget.Toast
import java.util.Locale

/** Uses installed system voices; unavailable voices do not stop studying. */
internal class EnglishSpeech(private val context: Context) {
    private var ready = false
    private var pending: Pair<String, Boolean>? = null
    private var engine: TextToSpeech? = null
    private var disposed = false
    init {
        engine = TextToSpeech(context) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (!disposed) pending?.let { pending = null; speak(it.first, it.second) }
        }
    }
    fun speak(text: String, british: Boolean) {
        if (disposed) return
        val tts = engine
        if (!ready || tts == null) {
            pending = text to british
            Toast.makeText(context, "语音尚未就绪；如无声音，请安装系统英文语音", Toast.LENGTH_SHORT).show()
            return
        }
        val locale = if (british) Locale.UK else Locale.US
        val offline = tts.voices.orEmpty().filter { it.locale.language == "en" && !it.isNetworkConnectionRequired }
        val voice = offline.firstOrNull { it.locale.country == locale.country } ?: offline.firstOrNull()
        if (voice == null) {
            Toast.makeText(context, "请先在系统文字转语音设置中安装离线英文语音", Toast.LENGTH_LONG).show()
            return
        }
        tts.voice = voice
        tts.setSpeechRate(.9f)
        if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "english-word") == TextToSpeech.ERROR)
            Toast.makeText(context, "发音播放失败，请检查系统语音设置", Toast.LENGTH_SHORT).show()
    }
    fun stop() { pending = null; engine?.stop() }
    fun close() { disposed = true; stop(); engine?.shutdown(); engine = null }
}
