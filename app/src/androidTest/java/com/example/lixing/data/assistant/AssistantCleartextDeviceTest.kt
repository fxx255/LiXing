package com.example.lixing.data.assistant

import android.security.NetworkSecurityPolicy
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.lixing.assistant.AssistantAcceptanceDependencies
import com.example.lixing.domain.assistant.AssistantMessage
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
class AssistantCleartextDeviceTest {
    @Test fun modelsAndStreamingChatAcceptHttpBaseUrl() = runBlocking {
        assertTrue(NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted("192.0.2.1"))
        val fixture = HttpFixture()
        val deps = EntryPointAccessors.fromApplication(
            ApplicationProvider.getApplicationContext(), AssistantAcceptanceDependencies::class.java)
        val previousProfileId = deps.credentials().activeProfileId()
        val wasEnabled = deps.prefs().current().aiAssistantEnabled
        val profile = deps.credentials().upsertProfile(
            id = null, name = "HTTP 临时测试", baseUrl = fixture.baseUrl,
            model = "mumu-http-probe", apiKey = "temporary-test-key", visionEnabled = false,
            searchProtocol = AiSearchProtocol.OFF,
        )
        try {
            deps.prefs().setAiAssistantEnabled(true)
            assertEquals(listOf("mumu-http-probe"), deps.client().fetchModels(
                fixture.baseUrl, "temporary-test-key"))
            val reply = deps.client().chat(listOf(AssistantMessage("user", "测试连接")), "")
            assertEquals("HTTP 模拟服务已连通", reply.reply)
        } finally {
            deps.credentials().deleteProfile(profile.id)
            if (previousProfileId != null) deps.credentials().selectProfile(previousProfileId)
            deps.prefs().setAiAssistantEnabled(wasEnabled)
            fixture.close()
        }
    }

    private class HttpFixture : Closeable {
        private val server = ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))
        val baseUrl = "http://127.0.0.1:" + server.localPort + "/v1"
        private val worker = thread(isDaemon = true, name = "assistant-http-fixture") {
            var served = 0
            while (served < 2 && !server.isClosed) {
                try {
                    server.accept().use { socket ->
                        socket.soTimeout = 10_000
                        val input = socket.getInputStream()
                        val requestLine = readLine(input)
                        var contentLength = 0
                        while (true) {
                            val header = readLine(input)
                            if (header.isEmpty()) break
                            if (header.startsWith("Content-Length:", ignoreCase = true)) {
                                contentLength = header.substringAfter(':').trim().toInt()
                            }
                        }
                        repeat(contentLength) { check(input.read() >= 0) }
                        val models = requestLine.startsWith("GET /v1/models ")
                        val replyJson = """{"reply":"HTTP 模拟服务已连通","plan_actions":[],"english_actions":[],"plots":[],"diagrams":[]}"""
                        val event = "{\"choices\":[{\"index\":0,\"delta\":{\"content\":" +
                            org.json.JSONObject.quote(replyJson) + "},\"finish_reason\":null}]}"
                        val body = if (models) {
                            """{"data":[{"id":"mumu-http-probe"}]}"""
                        } else {
                            "data: " + event + "\n\n" +
                                "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n" +
                                "data: [DONE]\n\n"
                        }
                        val bytes = body.toByteArray(Charsets.UTF_8)
                        val headers = "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: " + (if (models) "application/json" else "text/event-stream") + "\r\n" +
                            "Content-Length: " + bytes.size + "\r\nConnection: close\r\n\r\n"
                        socket.getOutputStream().apply {
                            write(headers.toByteArray(Charsets.US_ASCII))
                            write(bytes)
                            flush()
                        }
                    }
                    served++
                } catch (closed: SocketException) {
                    if (!server.isClosed) throw closed
                }
            }
        }

        override fun close() {
            server.close()
            worker.join(1_000)
        }

        private fun readLine(input: InputStream): String {
            val bytes = ByteArrayOutputStream()
            while (true) {
                val value = input.read()
                if (value < 0 || value == '\n'.code) break
                if (value != '\r'.code) bytes.write(value)
            }
            return bytes.toString("UTF-8")
        }
    }
}
