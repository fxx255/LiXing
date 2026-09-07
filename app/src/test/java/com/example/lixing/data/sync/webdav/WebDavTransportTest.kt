package com.example.lixing.data.sync.webdav

import com.example.lixing.data.sync.SyncRemoteFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [WebDavTransport] 到 [com.example.lixing.data.sync.SyncTransport] 的适配层测试。 */
class WebDavTransportTest {

    private lateinit var server: FakeWebDavServer
    private lateinit var transport: WebDavTransport

    private fun start(seedFolders: Set<String> = setOf("/", "/dav/", "/dav/lixing/")) {
        server = FakeWebDavServer(seedFolders = seedFolders)
        server.start()
        val config = WebDavConfig(
            folderUrl = server.url,
            account = "user@example.com",
            password = "app-password",
        )
        transport = WebDavTransport(WebDavClient(config, Dispatchers.Unconfined))
    }

    @After
    fun tearDown() {
        if (::server.isInitialized) server.stop()
    }

    @Test
    fun `list maps remote files to sync files with sizes`() = runBlocking {
        start()
        server.seed("device-a.snapshot", "abc")
        server.seed("device-b.jsonl", "abcdef")

        val files: List<SyncRemoteFile> = transport.list()

        assertEquals(
            listOf("device-a.snapshot" to 3L, "device-b.jsonl" to 6L),
            files.sortedBy { it.name }.map { it.name to it.sizeBytes },
        )
    }

    @Test
    fun `list is empty before anything is uploaded`() = runBlocking {
        start()
        assertTrue(transport.list().isEmpty())
    }

    @Test
    fun `write and read round trip`() = runBlocking {
        start()
        val payload = "{\"rows\":[]}"
        transport.write("device-a.snapshot", payload)

        assertEquals(payload, transport.read("device-a.snapshot"))
        assertEquals(payload.toByteArray(Charsets.UTF_8).size.toLong(), transport.list().first().sizeBytes)
    }

    @Test
    fun `write creates the sync folder when it is missing`() = runBlocking {
        start(seedFolders = setOf("/"))

        transport.write("device-a.snapshot", "x")

        assertEquals("x", transport.read("device-a.snapshot"))
        assertTrue("应逐级 MKCOL 建出目录", server.methods().contains("MKCOL"))
    }

    @Test
    fun `read returns null for a missing file`() = runBlocking {
        start()
        assertNull(transport.read("device-a.snapshot"))
    }

    @Test
    fun `append accumulates log lines across calls`() = runBlocking {
        start()
        transport.append("device-a.jsonl", listOf("line-1"))
        transport.append("device-a.jsonl", listOf("line-2", "line-3"))

        assertEquals("line-1\nline-2\nline-3\n", transport.read("device-a.jsonl"))
    }

    @Test
    fun `delete removes the file`() = runBlocking {
        start()
        transport.write("device-a.snapshot", "x")
        transport.delete("device-a.snapshot")

        assertNull(transport.read("device-a.snapshot"))
        assertTrue(transport.list().isEmpty())
    }

    @Test
    fun `transport survives a stale etag by retrying the append`() = runBlocking {
        start()
        server.seed("device-a.jsonl", "old\n")
        server.beforePut = { server.seed("device-a.jsonl", "old\nintruder\n") }

        transport.append("device-a.jsonl", listOf("mine"))

        assertEquals("old\nintruder\nmine\n", transport.read("device-a.jsonl"))
    }
}
