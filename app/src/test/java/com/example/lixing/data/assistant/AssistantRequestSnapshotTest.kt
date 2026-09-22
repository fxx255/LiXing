package com.example.lixing.data.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 生成请求**身份快照**验收（协调者阻塞第 4 条）。
 *
 * 快照的用途：重试时判断「配置是否被改过」，从而避免把**原来那道题**
 * 发给一个用户没预期的供应商。因此它必须：
 * 1. 描述**实际会被调用的**端点（不是偏好设置里那份可能被覆盖的值）；
 * 2. **绝不含密钥**；
 * 3. 可安全往返（编码/解码），损坏时优雅退化。
 */
class AssistantRequestSnapshotTest {

    // ── 端点身份：必须去掉凭证与查询串 ──

    @Test
    fun `endpoint identity drops query string and credentials`() {
        val identity = endpointIdentityOf(
            "https://user:secret@api.example.com/v1?api-version=2024-01-01&key=leaked",
        )
        assertFalse("不得带查询串", identity.contains("api-version"))
        assertFalse("不得带查询串", identity.contains("leaked"))
        assertFalse("不得带 userInfo 凭证", identity.contains("secret"))
        assertFalse("不得带 userInfo 凭证", identity.contains("user:"))
        assertTrue("必须保留主机与路径", identity.contains("api.example.com"))
    }

    @Test
    fun `endpoint identity distinguishes providers with the same path`() {
        val a = endpointIdentityOf("https://api.openai.com/v1")
        val b = endpointIdentityOf("https://api.deepseek.com/v1")
        assertEquals("https://api.openai.com/v1", a)
        assertEquals("https://api.deepseek.com/v1", b)
        assertFalse("不同供应商必须区分开", a == b)
    }

    @Test
    fun `endpoint identity keeps an explicit non default port`() {
        assertEquals("http://127.0.0.1:8080/v1", endpointIdentityOf("http://127.0.0.1:8080/v1"))
    }

    @Test
    fun `endpoint identity tolerates empty and malformed input`() {
        assertEquals("", endpointIdentityOf(""))
        assertEquals("", endpointIdentityOf("   "))
        // 不是合法 URL 也不能崩，且仍要砍掉查询串。
        val malformed = endpointIdentityOf("not a url?token=abc")
        assertFalse(malformed.contains("token=abc"))
    }

    // ── 归集键：端点 + 模型 + 协议 ──

    @Test
    fun `usage group key includes endpoint model and protocol`() {
        val snapshot = AssistantRequestSnapshot(
            model = "deepseek-chat",
            endpointIdentity = "https://api.deepseek.com/v1",
            protocol = "chat_completions",
        )
        val key = snapshot.usageGroupKey
        assertTrue(key.contains("api.deepseek.com"))
        assertTrue(key.contains("deepseek-chat"))
        assertTrue(key.contains("chat_completions"))
    }

    @Test
    fun `usage group key separates same model on different endpoints`() {
        val a = AssistantRequestSnapshot(
            model = "gpt-4o",
            endpointIdentity = "https://api.openai.com/v1",
            protocol = "chat_completions",
        )
        val b = a.copy(endpointIdentity = "https://gateway.example.com/v1")
        assertFalse(
            "同一模型名挂在两个供应商时必须分开归集",
            a.usageGroupKey == b.usageGroupKey,
        )
    }

    // ── 往返与退化 ──

    @Test
    fun `snapshot round trips all identity fields`() {
        val snapshot = AssistantRequestSnapshot(
            model = "test-model",
            endpointIdentity = "https://api.example.com/v1",
            protocol = "responses",
            reasoningEffort = "HIGH",
            webSearchEnabled = true,
            contextKinds = listOf("PLAN", "TODAY"),
            contextChars = 1234,
            historyMessages = 7,
            hasImages = true,
            sourceContext = "只读上下文",
            sourceUserText = "用户原文",
        )
        val decoded = AssistantSnapshotCodec.decode(AssistantSnapshotCodec.encode(snapshot))
        assertEquals(snapshot, decoded)
    }

    @Test
    fun `corrupt snapshot decodes to null instead of throwing`() {
        assertNull(AssistantSnapshotCodec.decode(""))
        assertNull(AssistantSnapshotCodec.decode("   "))
        assertNull(AssistantSnapshotCodec.decode("{ 这不是 JSON"))
        // 缺字段的旧快照：ignoreUnknownKeys + 默认值应当仍然解析成功。
        assertNotNull(AssistantSnapshotCodec.decode("""{"model":"m"}"""))
    }

    /**
     * **绝不持久化密钥**：快照里不能出现 Authorization/密钥字样。
     *
     * 这是隐私边界，不能只靠"我们没写"—— 用序列化结果反查一遍。
     */
    @Test
    fun `encoded snapshot never contains secret material`() {
        val snapshot = AssistantRequestSnapshot(
            model = "m",
            endpointIdentity = "https://api.example.com/v1",
            protocol = "chat_completions",
            sourceContext = "上下文",
            sourceUserText = "题目",
        )
        val encoded = AssistantSnapshotCodec.encode(snapshot).lowercase()
        listOf("authorization", "api_key", "apikey", "bearer", "sk-", "secret", "token").forEach {
            assertFalse("快照不得包含 $it", encoded.contains(it))
        }
    }
}
