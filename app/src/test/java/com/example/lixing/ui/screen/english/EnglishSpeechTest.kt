package com.example.lixing.ui.screen.english

import android.app.Application
import android.speech.tts.Voice
import com.example.lixing.data.backup.PreferencesPayload
import com.example.lixing.data.prefs.UserPreferences
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class EnglishSpeechTest {
    @Test fun `system fallback uses the best installed offline voice for the requested accent`() {
        val voices = setOf(
            voice("us-low", Locale.US, Voice.QUALITY_LOW),
            voice("uk-high", Locale.UK, Voice.QUALITY_VERY_HIGH),
            voice("us-online", Locale.US, Voice.QUALITY_VERY_HIGH, online = true),
            voice("us-missing", Locale.US, Voice.QUALITY_VERY_HIGH, features = setOf("notInstalled")),
            voice("us-high", Locale.US, Voice.QUALITY_HIGH),
        )
        assertEquals("us-high", preferredEnglishVoice(voices, false)?.name)
        assertEquals("uk-high", preferredEnglishVoice(voices, true)?.name)
        assertNull(preferredEnglishVoice(setOf(voice("au", Locale.forLanguageTag("en-AU"), Voice.QUALITY_HIGH)), false))
    }

    @Test fun `pronunciation choice survives backup restore independently of online definitions`() {
        val original = UserPreferences(englishOnlinePronunciation = false, englishOnlineDictionary = true)
        val restored = PreferencesPayload.from(original).toPreferences()
        assertFalse(restored.englishOnlinePronunciation)
        assertTrue(restored.englishOnlineDictionary)
    }

    private fun voice(name: String, locale: Locale, quality: Int, online: Boolean = false, features: Set<String> = emptySet()) =
        Voice(name, locale, quality, Voice.LATENCY_NORMAL, online, features)
}
