package com.example.lixing.data

import com.example.lixing.data.dictionary.EnglishPronunciationRepository
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class EnglishPronunciationRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `uses the dictionary accent mapping and correctly encodes a phrase`() {
        val us = EnglishPronunciationRepository.pronunciationSource("in the long run", false)
        val uk = EnglishPronunciationRepository.pronunciationSource("in the long run", true)
        assertEquals("2", us.url.toHttpUrl().queryParameter("type"))
        assertEquals("1", uk.url.toHttpUrl().queryParameter("type"))
        assertEquals("in the long run", us.url.toHttpUrl().queryParameter("audio"))
        assertTrue(us.sourceUrl.contains("in%20the%20long%20run"))
        assertEquals("dict.youdao.com", us.url.toHttpUrl().host)
    }

    @Test fun `normalizes English words and phrases and rejects long or non-English content`() {
        assertEquals("in the long run", EnglishPronunciationRepository.pronunciationText(" IN   THE LONG RUN "))
        assertEquals("don't", EnglishPronunciationRepository.pronunciationText("DON’T"))
        assertNull(EnglishPronunciationRepository.pronunciationText("中文释义"))
        assertNull(EnglishPronunciationRepository.pronunciationText("a".repeat(81)))
    }

    @Test fun `invalid content never goes to the dictionary audio service`() = runBlocking {
        val calls = AtomicInteger()
        val client = client(calls, "ID3recording".toByteArray())
        val repository = EnglishPronunciationRepository(temporary.newFolder(), client)
        assertNull(repository.recording("This is my private example sentence.", false))
        assertNull(repository.recording("中文释义", false))
        assertEquals(0, calls.get())
    }

    @Test fun `recordings are reused without network and accents have separate caches`() = runBlocking {
        val calls = AtomicInteger()
        val directory = temporary.newFolder()
        val repository = EnglishPronunciationRepository(directory, client(calls, "ID3recording".toByteArray()))
        val first = repository.recording(" APPLE ", false)!!
        assertEquals(1, calls.get())
        assertTrue(first.file.readBytes().contentEquals("ID3recording".toByteArray()))
        val freshRepository = EnglishPronunciationRepository(directory, client(calls, "ID3recording".toByteArray()))
        val cached = freshRepository.recording("apple", false)!!
        assertEquals(first.audio, cached.audio)
        assertEquals(1, calls.get())
        val british = freshRepository.recording("apple", true)!!
        assertNotEquals(cached.file, british.file)
        assertEquals(2, calls.get())
    }

    @Test fun `an error page is not cached as playable audio`() = runBlocking {
        val directory = temporary.newFolder()
        val repository = EnglishPronunciationRepository(directory, client(AtomicInteger(), "<html>error</html>".toByteArray()))
        try {
            repository.recording("apple", false)
            fail("Expected invalid audio to fall back instead of entering the cache")
        } catch (_: IllegalArgumentException) { }
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test fun `cancelled lookup does not save or resume a stale pronunciation`() = runBlocking {
        val started = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            started.countDown()
            check(finish.await(5, TimeUnit.SECONDS))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("ID3recording".toResponseBody("audio/mpeg".toMediaType())).build()
        }.build()
        val directory = temporary.newFolder()
        val repository = EnglishPronunciationRepository(directory, client)
        var returned = false
        val job = launch(Dispatchers.Default) { repository.recording("apple", false); returned = true }
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS))
            job.cancelAndJoin()
            assertFalse(returned)
            assertTrue(directory.listFiles().orEmpty().isEmpty())
        } finally { finish.countDown() }
    }

    private fun client(calls: AtomicInteger, audio: ByteArray) = OkHttpClient.Builder().addInterceptor { chain ->
        calls.incrementAndGet()
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(audio.toResponseBody("audio/mpeg".toMediaType())).build()
    }.build()
}
