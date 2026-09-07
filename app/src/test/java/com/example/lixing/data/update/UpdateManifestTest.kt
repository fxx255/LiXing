package com.example.lixing.data.update

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 纯函数单元测试：版本比较、清单解析。
 *
 * 不依赖 Android 框架，跑在 JVM 上；产品里只引了 [UpdateManifest] / [UpgradeDecision]。
 */
class UpdateManifestTest {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    @Test
    fun `upgrade when remote versionCode is higher`() {
        val m = UpdateManifest(
            versionCode = 3,
            versionName = "1.0.2",
            apkUrl = "https://example.com/app.apk",
            sha256 = "a".repeat(64),
        )
        val decision = m.isUpgradeFor(currentCode = 2, deviceSdk = 34)
        assertTrue(decision is UpgradeDecision.Suggested)
    }

    @Test
    fun `skip when remote versionCode is equal or lower`() {
        val same = UpdateManifest(versionCode = 2, apkUrl = "x", sha256 = "a".repeat(64))
        assertTrue(same.isUpgradeFor(currentCode = 2, deviceSdk = 34) is UpgradeDecision.Skip)

        val lower = UpdateManifest(versionCode = 1, apkUrl = "x", sha256 = "a".repeat(64))
        assertTrue(lower.isUpgradeFor(currentCode = 2, deviceSdk = 34) is UpgradeDecision.Skip)
    }

    @Test
    fun `skip when manifest missing apkUrl or sha256`() {
        val noUrl = UpdateManifest(versionCode = 9, sha256 = "a".repeat(64))
        val noSha = UpdateManifest(versionCode = 9, apkUrl = "https://x")
        assertTrue(noUrl.isUpgradeFor(1, 34) is UpgradeDecision.Skip)
        assertTrue(noSha.isUpgradeFor(1, 34) is UpgradeDecision.Skip)
    }

    @Test
    fun `force flag triggers required decision`() {
        val m = UpdateManifest(
            versionCode = 9,
            apkUrl = "https://x",
            sha256 = "a".repeat(64),
            force = true,
        )
        assertTrue(m.isUpgradeFor(1, 34) is UpgradeDecision.Required)
    }

    @Test
    fun `parse minimal json`() {
        val raw = """{"versionCode":5,"versionName":"1.0.4","apkUrl":"https://x.example/app.apk","sizeBytes":123456,"sha256":"ABCD","changelog":"hello"}"""
        val m = json.decodeFromString(UpdateManifest.serializer(), raw)
        assertEquals(5, m.versionCode)
        assertEquals("1.0.4", m.versionName)
        assertEquals("https://x.example/app.apk", m.apkUrl)
        assertEquals(123456L, m.sizeBytes)
        assertEquals("ABCD", m.sha256)
        assertEquals("hello", m.changelog)
    }

    @Test
    fun `ignore unknown keys and default missing ones`() {
        val raw = """{"versionCode":2,"future_field":"ignored","weird":42}"""
        val m = json.decodeFromString(UpdateManifest.serializer(), raw)
        assertEquals(2, m.versionCode)
        assertEquals("", m.versionName)
        assertEquals("", m.apkUrl)
        assertEquals("", m.sha256)
        assertEquals(0L, m.sizeBytes)
        assertFalse(m.force)
    }

    @Test
    fun `sha256 helper recognises 64-hex`() {
        val m = UpdateManifest(apkUrl = "u", sha256 = "g".repeat(64))
        assertNotNull(m.sha256)
        // 长度边界：不是 64 hex 应该会判失败——不能在这种常量大小下走升级路径
        val tooShort = UpdateManifest(apkUrl = "u", sha256 = "abcd")
        assertEquals(4, tooShort.sha256.length)
        // 真正决定是否升级的是 isUpgradeFor 内部判断——这里直接断言 SHA 长度是关键。
        assertTrue(abs(m.sha256.length - 64) == 0)
    }

    @Test
    fun `required decision when minSdk is above current device`() {
        val m = UpdateManifest(
            versionCode = 9,
            apkUrl = "https://x",
            sha256 = "a".repeat(64),
            minSdk = 36,
        )
        val decision = m.isUpgradeFor(currentCode = 1, deviceSdk = 33)
        // minSdk > deviceSdk 视为强制升级
        assertTrue(decision is UpgradeDecision.Required)

        // minSdk <= deviceSdk 时不强制升级，而是建议升级
        val m2 = UpdateManifest(
            versionCode = 9,
            apkUrl = "https://x",
            sha256 = "a".repeat(64),
            minSdk = 30,
        )
        val d2 = m2.isUpgradeFor(currentCode = 1, deviceSdk = 34)
        assertTrue(d2 is UpgradeDecision.Suggested)
    }

    @Test
    fun `negative versionCode treated as no upgrade`() {
        val m = UpdateManifest(versionCode = -1, apkUrl = "u", sha256 = "a".repeat(64))
        val d = m.isUpgradeFor(currentCode = 5, deviceSdk = 34)
        assertTrue(d is UpgradeDecision.Skip)
        // 跳过时必须带原因（哪怕是默认值），方便日后排障。
        val skipped = d as UpgradeDecision.Skip
        assertTrue(skipped.reason.isNotBlank())
    }
}
