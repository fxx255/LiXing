package com.example.lixing.data.sync.webdav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebDavConfigTest {

    @Test
    fun `bare host becomes https directory url`() {
        assertEquals(
            "https://dav.jianguoyun.com/dav/lixing/",
            WebDavConfig.normalizeUrl("dav.jianguoyun.com/dav/lixing"),
        )
    }

    @Test
    fun `trailing slash is preserved exactly once`() {
        assertEquals(
            "https://dav.jianguoyun.com/dav/lixing/",
            WebDavConfig.normalizeUrl("https://dav.jianguoyun.com/dav/lixing////"),
        )
        assertEquals(
            "https://dav.jianguoyun.com/",
            WebDavConfig.normalizeUrl("https://dav.jianguoyun.com"),
        )
    }

    @Test
    fun `user input is trimmed`() {
        assertEquals(
            "https://dav.jianguoyun.com/dav/lixing/",
            WebDavConfig.normalizeUrl("  https://dav.jianguoyun.com/dav/lixing  "),
        )
    }

    @Test
    fun `query and fragment are dropped`() {
        assertEquals(
            "https://dav.jianguoyun.com/dav/lixing/",
            WebDavConfig.normalizeUrl("https://dav.jianguoyun.com/dav/lixing?a=1#b"),
        )
    }

    @Test
    fun `explicit port is kept`() {
        assertEquals(
            "https://nas.example.com:8443/dav/",
            WebDavConfig.normalizeUrl("https://nas.example.com:8443/dav"),
        )
    }

    @Test
    fun `plain http over the public internet is rejected`() {
        // Basic 认证是 base64 明文，公网 http 等于广播密码
        assertNull(WebDavConfig.normalizeUrl("http://dav.jianguoyun.com/dav/"))
        assertNull(WebDavConfig.normalizeUrl("http://example.com/dav/"))
    }

    @Test
    fun `plain http is allowed on localhost and lan`() {
        assertEquals(
            "http://127.0.0.1:8080/dav/",
            WebDavConfig.normalizeUrl("http://127.0.0.1:8080/dav"),
        )
        assertEquals("http://localhost/dav/", WebDavConfig.normalizeUrl("http://localhost/dav"))
        assertEquals("http://192.168.1.9/dav/", WebDavConfig.normalizeUrl("http://192.168.1.9/dav"))
        assertEquals("http://10.0.0.5/dav/", WebDavConfig.normalizeUrl("http://10.0.0.5/dav"))
        assertEquals("http://172.20.1.1/dav/", WebDavConfig.normalizeUrl("http://172.20.1.1/dav"))
        assertEquals("http://nas.local/dav/", WebDavConfig.normalizeUrl("http://nas.local/dav"))
    }

    @Test
    fun `unrecognizable input returns null`() {
        assertNull(WebDavConfig.normalizeUrl(""))
        assertNull(WebDavConfig.normalizeUrl("   "))
        assertNull(WebDavConfig.normalizeUrl("ftp://example.com/dav/"))
        assertNull(WebDavConfig.normalizeUrl("https://no such host/dav/"))
    }

    @Test
    fun `password never leaks into toString`() {
        val config = WebDavConfig(
            folderUrl = "https://dav.jianguoyun.com/dav/lixing/",
            account = "me@example.com",
            password = "topsecret",
        )
        assertFalse(config.toString().contains("topsecret"))
        assertTrue(config.toString().contains("***"))
    }

    @Test
    fun `config is only usable when all three fields are filled`() {
        assertFalse(WebDavConfig(account = "a", password = "b").isComplete)
        assertFalse(WebDavConfig(folderUrl = "https://x/dav/", password = "b").isComplete)
        assertFalse(WebDavConfig(folderUrl = "https://x/dav/", account = "a").isComplete)
        assertTrue(
            WebDavConfig(folderUrl = "https://x/dav/", account = "a", password = "b").isComplete,
        )
    }
}
