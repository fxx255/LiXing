package com.example.lixing.data.dictionary

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class PronunciationAudio(
    val url: String,
    val sourceUrl: String,
    val british: Boolean,
)

internal data class CachedPronunciation(val file: File, val audio: PronunciationAudio)

/** Uses the same public audio endpoint as Youdao's dictionary page, without a key. */
internal class EnglishPronunciationRepository(
    private val directory: File,
    private val client: OkHttpClient = OkHttpClient.Builder().callTimeout(4, TimeUnit.SECONDS).build(),
) {
    private val cacheLock = Mutex()

    suspend fun recording(raw: String, british: Boolean): CachedPronunciation? = withContext(Dispatchers.IO) {
        cacheLock.withLock {
            val word = pronunciationText(raw) ?: return@withLock null
            val key = MessageDigest.getInstance("SHA-256").digest("$word:$british".toByteArray())
                .joinToString("") { "%02x".format(it) }
            directory.mkdirs()
            val audioFile = File(directory, "$key.mp3")
            val audio = pronunciationSource(word, british)
            if (audioFile.length() in 3..MAX_AUDIO_BYTES && isMp3(audioFile.readBytes())) {
                audioFile.setLastModified(System.currentTimeMillis())
                return@withLock CachedPronunciation(audioFile, audio)
            }
            val bytes = client.newCall(Request.Builder().url(audio.url).build()).awaitBytes(MAX_AUDIO_BYTES.toInt())
            require(isMp3(bytes)) { "词典录音格式无效" }
            // Cancellation can never leave a partial .mp3 marked as a valid cached recording.
            val temporary = File.createTempFile("pronunciation-", ".tmp", directory)
            try {
                temporary.writeBytes(bytes)
                audioFile.delete()
                check(temporary.renameTo(audioFile)) { "无法缓存词典录音" }
            } finally { temporary.delete() }
            trimCache(audioFile)
            CachedPronunciation(audioFile, audio)
        }
    }

    private fun trimCache(vararg protected: File) {
        val files = directory.listFiles().orEmpty()
        var total = files.sumOf { it.length() }
        files.sortedBy { it.lastModified() }.forEach { file ->
            if (total > MAX_CACHE_BYTES && file !in protected) {
                val size = file.length()
                if (file.delete()) total -= size
            }
        }
    }

    companion object {
        private const val MAX_AUDIO_BYTES = 2_097_152L
        private const val MAX_CACHE_BYTES = 25_165_824L

        fun pronunciationText(raw: String): String? = DictionaryRepository.normalize(raw)
            .takeIf { it.length in 1..80 && it.matches(Regex("[a-z]+(?:['-][a-z]+)*(?: [a-z]+(?:['-][a-z]+)*)*")) }

        fun pronunciationSource(word: String, british: Boolean): PronunciationAudio {
            val audioUrl = "https://dict.youdao.com/dictvoice".toHttpUrl().newBuilder()
                .addQueryParameter("audio", word).addQueryParameter("type", if (british) "1" else "2").build()
            val sourceUrl = "https://dict.youdao.com/w/eng/".toHttpUrl().newBuilder().addPathSegment(word).addPathSegment("").build()
            return PronunciationAudio(audioUrl.toString(), sourceUrl.toString(), british)
        }

        private fun isMp3(bytes: ByteArray): Boolean = bytes.size >= 3 &&
            (bytes.take(3) == listOf(0x49.toByte(), 0x44.toByte(), 0x33.toByte()) ||
                (bytes[0].toInt() and 0xff == 0xff && bytes[1].toInt() and 0xe0 == 0xe0))
    }
}

private suspend fun Call.awaitBytes(limit: Int): ByteArray = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            try {
                val bytes = response.use {
                    check(it.isSuccessful) { "免费词典录音暂时不可用" }
                    val body = it.body ?: error("录音返回空内容")
                    require(body.contentLength() <= limit) { "录音文件过大" }
                    body.byteStream().use { input ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            require(output.size() + n <= limit) { "录音文件过大" }
                            output.write(buffer, 0, n)
                        }
                        output.toByteArray()
                    }
                }
                if (continuation.isActive) continuation.resume(bytes)
            } catch (e: Exception) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
        }
    })
}
