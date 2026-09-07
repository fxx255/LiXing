package com.example.lixing.ui.screen.assistant

import android.speech.SpeechRecognizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantVoiceInputTest {
    @Test
    fun `recognized speech fills an empty input`() {
        assertEquals("帮我分析今天的任务", mergeRecognizedSpeech("", " 帮我分析今天的任务 "))
    }

    @Test
    fun `recognized speech is appended to existing input`() {
        assertEquals("请帮我 调整下午的计划", mergeRecognizedSpeech("请帮我", "调整下午的计划"))
        assertEquals("请帮我 调整下午的计划", mergeRecognizedSpeech("请帮我 ", "调整下午的计划"))
    }

    @Test
    fun `blank recognition keeps the existing input`() {
        assertEquals("不要覆盖这段话", mergeRecognizedSpeech("不要覆盖这段话", "   "))
    }

    @Test
    fun `transient recognition failures can restart a held session`() {
        assertTrue(isRecoverableVoiceRecognitionError(SpeechRecognizer.ERROR_NETWORK))
        assertTrue(isRecoverableVoiceRecognitionError(SpeechRecognizer.ERROR_NETWORK_TIMEOUT))
        assertTrue(isRecoverableVoiceRecognitionError(SpeechRecognizer.ERROR_SERVER_DISCONNECTED))
        assertFalse(isRecoverableVoiceRecognitionError(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS))
        assertFalse(isRecoverableVoiceRecognitionError(SpeechRecognizer.ERROR_AUDIO))
    }
}
