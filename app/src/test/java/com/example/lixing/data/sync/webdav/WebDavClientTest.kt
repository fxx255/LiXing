package com.example.lixing.data.sync.webdav

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WebDavClientTest {

    private lateinit var server: FakeWebDavServer
    private lateinit var client: WebDavClient

    private fun start(seedFolders: Set<String> = setOf("/", "/dav/", "/dav/lixing/")) {
        server = FakeWebDavServer(seedFolders = seedFolders)
        server.start()
        client = WebDavClient(config(), Dispatchers.Unconfined)
    }

    private fun config(password: String = "app-password") = WebDavConfig(
        folderUrl = server.url,
        account = "user@example.com",
        password = password,
    )

    @After
    fun tearDown() {
        if (::server.isInitialized) server.stop()
    }

    // ---------- PROPFIND ----------

    @Test
    fun `list returns files with sizes and skips the folder itself`() = runBlocking {
        start()
        server.seed("device-a.snapshot", "hello")
        server.seed("device-a.jsonl", "12345")

        val entries = client.list()

        // WebDavClient 返回原始条目（含目录本身），过滤在 WebDavTransport 层做
        val files = entries.filter { !it.isDirectory }
        assertEquals(listOf("device-a.jsonl", "device-a.snapshot"), files.map { it.name }.sorted())
        assertEquals(5L, files.first { it.name == "device-a.jsonl" }.sizeBytes)
        assertEquals(5L, files.first { it.name == "device-a.snapshot" }.sizeBytes)

        val folder = entries.first { it.isDirectory }
        assertEquals("同步目录本身要能认出来是目录", "lixing", folder.name)
        assertEquals("/dav/lixing/", folder.href)
    }

    @Test
    fun `list returns empty when the folder does not exist yet`() = runBlocking {
        start(seedFolders = setOf("/"))
        assertTrue(client.list().isEmpty())
    }

    @Test
    fun `list sends basic auth and depth 1`() = runBlocking {
        start()
        client.list()
        val request = server.requests().first()
        assertEquals("PROPFIND", request.method)
        assertEquals("1", request.depth)
        assertTrue(request.authorization?.startsWith("Basic ") == true)
    }

    // ---------- GET / PUT / DELETE ----------

    @Test
    fun `get reads content back`() = runBlocking {
        start()
        server.seed("device-a.snapshot", "payload")

        assertEquals("payload", client.get("device-a.snapshot")?.content)
    }

    @Test
    fun `get returns null when the file is missing`() = runBlocking {
        start()
        assertNull(client.get("nope.snapshot"))
    }

    @Test
    fun `put writes the exact bytes`() = runBlocking {
        start()
        client.put("device-a.snapshot", " written with 中文")

        assertEquals(" written with 中文", server.contentOf("device-a.snapshot"))
    }

    @Test
    fun `delete removes the file and tolerates a missing one`() = runBlocking {
        start()
        server.seed("device-a.jsonl", "x")

        client.delete("device-a.jsonl")
        assertNull(server.contentOf("device-a.jsonl"))

        client.delete("device-a.jsonl") // 幂等，重复删不报错
    }

    // ---------- MKCOL ----------

    @Test
    fun `ensureFolder creates the whole chain level by level`() = runBlocking {
        start(seedFolders = setOf("/"))

        client.ensureFolder()

        // 先 PROPFIND 探测再 MKCOL，所以每个层级各两条
        assertEquals(listOf("MKCOL", "MKCOL"), server.methods().filter { it == "MKCOL" })
        assertEquals(
            listOf("/dav/", "/dav/lixing/"),
            server.requests().filter { it.method == "MKCOL" }.map { it.path },
        )
        // 建完之后目录可见（list() 返回原始条目，所以会带上目录本身）
        assertEquals(listOf("lixing"), client.list().map { it.name })
    }

    @Test
    fun `ensureFolder does not repeat work`() = runBlocking {
        start(seedFolders = setOf("/"))

        client.ensureFolder()
        client.ensureFolder()

        assertEquals(2, server.methods().count { it == "MKCOL" })
    }

    // ---------- append ----------

    @Test
    fun `append creates the file when it does not exist`() = runBlocking {
        start()
        client.appendLines("device-a.jsonl", listOf("one", "two"))

        assertEquals("one\ntwo\n", server.contentOf("device-a.jsonl"))
    }

    @Test
    fun `append concatenates and never glues lines together`() = runBlocking {
        start()
        server.seed("device-a.jsonl", "zero\n")

        client.appendLines("device-a.jsonl", listOf("one", "two"))
        assertEquals("zero\none\ntwo\n", server.contentOf("device-a.jsonl"))

        // 上一位写入者忘了换行也不能把两行粘成一行
        server.seed("device-a.jsonl", "zero")
        client.appendLines("device-a.jsonl", listOf("one"))
        assertEquals("zero\none\n", server.contentOf("device-a.jsonl"))
    }

    @Test
    fun `append retries when another writer wins the race`() = runBlocking {
        start()
        server.seed("device-a.jsonl", "line0\n")
        // 客户端拿到 ETag 之后、PUT 之前，另一个写入者抢先改了文件（会换掉 ETag）
        server.beforePut = { server.seed("device-a.jsonl", "line0\nlineX\n") }

        client.appendLines("device-a.jsonl", listOf("line1"))

        assertEquals("line0\nlineX\nline1\n", server.contentOf("device-a.jsonl"))
        assertEquals("第一次 412，第二次成功", 2, server.requests().count { it.method == "PUT" })
    }

    @Test
    fun `append of an empty list touches nothing`() = runBlocking {
        start()
        client.appendLines("device-a.jsonl", emptyList())

        assertNull(server.contentOf("device-a.jsonl"))
        assertTrue(server.requests().isEmpty())
    }

    // ---------- 错误分类 ----------

    @Test
    fun `wrong password maps to AUTH`() = runBlocking {
        start()
        client = WebDavClient(config(password = "wrong"), Dispatchers.Unconfined)

        val error = assertThrows(WebDavException::class.java) { runBlocking { client.list() } }
        assertEquals(WebDavException.Kind.AUTH, error.kind)
        assertTrue(error.needsUserAction)
    }

    @Test
    fun `server error maps to SERVER`() = runBlocking {
        start()
        server.forceStatus = 500

        val error = assertThrows(WebDavException::class.java) { runBlocking { client.list() } }
        assertEquals(WebDavException.Kind.SERVER, error.kind)
    }

    @Test
    fun `insufficient storage maps to QUOTA`() = runBlocking {
        start()
        server.forceStatus = 507

        val error = assertThrows(WebDavException::class.java) { runBlocking { client.put("a", "b") } }
        assertEquals(WebDavException.Kind.QUOTA, error.kind)
        assertTrue(error.needsUserAction)
    }

    @Test
    fun `stale etag on a missing file maps to CONFLICT`() = runBlocking {
        start()
        val error = assertThrows(WebDavException::class.java) {
            runBlocking { client.put("a", "b", etag = "\"stale\"") }
        }
        assertEquals(WebDavException.Kind.CONFLICT, error.kind)
    }

    @Test
    fun `unreachable host maps to NETWORK`() = runBlocking {
        // 端口 1 基本不会有服务在监听，连不上
        val offline = WebDavClient(
            WebDavConfig(folderUrl = "http://127.0.0.1:1/dav/", account = "a", password = "b"),
            Dispatchers.Unconfined,
        )
        val error = assertThrows(WebDavException::class.java) { runBlocking { offline.list() } }
        assertEquals(WebDavException.Kind.NETWORK, error.kind)
    }

    // ---------- 连通性自检 ----------

    @Test
    fun `check passes when the folder exists`() = runBlocking {
        start()
        assertNull(client.check())
    }

    @Test
    fun `check tolerates a missing folder because sync creates it`() = runBlocking {
        start(seedFolders = setOf("/"))
        assertNull(client.check())
    }

    @Test
    fun `check reports auth failure instead of swallowing it`() = runBlocking {
        start()
        val wrong = WebDavClient(config(password = "nope"), Dispatchers.Unconfined)
        assertEquals(WebDavException.Kind.AUTH, wrong.check()?.kind)
    }

    // ---------- 纯函数 ----------

    @Test
    fun `ancestors walks up one level at a time`() {
        val base = "https://dav.example.com/dav/lixing/".toHttpUrl()
        assertEquals(
            listOf("https://dav.example.com/dav/", "https://dav.example.com/dav/lixing/"),
            WebDavClient.ancestors(base).map { it.toString() },
        )
    }

    @Test
    fun `ancestors is empty for a root level folder because it already exists`() {
        val base = "https://dav.example.com/".toHttpUrl()
        assertTrue(WebDavClient.ancestors(base).isEmpty())
    }

    @Test
    fun `basic auth survives non ascii passwords`() {
        val header = WebDavClient.basicAuth("用户@example.com", "密码 pässword")
        assertTrue(header.startsWith("Basic "))
        // 用同一套编解码还原，确认没走 ISO-8859-1 把中文吃掉
        val decoded = java.util.Base64.getDecoder()
            .decode(header.removePrefix("Basic "))
            .toString(Charsets.UTF_8)
        assertEquals("用户@example.com:密码 pässword", decoded)
    }
}
