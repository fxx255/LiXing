package com.example.lixing.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 清单源列表与下载源顺序。
 *
 * 背景（2026-09-14）：App 检查更新提示 **HTTP 502**。根因是云函数跑在 CloudBase（上海），
 * 免费版单次执行上限 3 秒，而它回源 GitHub 常态 2~10 秒 ⇒ 必然超时并返回 502。
 * 修复方向是**让 App 不再依赖云函数**：云函数失败后走加速镜像直取 GitHub Release 附件，
 * 最后才退回直连。
 *
 * 本文件钉住这个回退顺序，避免以后有人把直连排到前面（国内手机上那会白等一次超时）。
 */
class UpdateManifestSourceTest {

    @Test
    fun `配置了云函数时它排在第一位`() {
        val sources = manifestSources("https://example.com/update/check")
        assertEquals("https://example.com/update/check", sources.first())
    }

    @Test
    fun `未配置云函数时只用镜像与直连`() {
        val sources = manifestSources("   ")
        assertTrue("空白端点不该产生空串源", sources.none { it.isBlank() })
        assertTrue(sources.isNotEmpty())
    }

    @Test
    fun `加速镜像优先、直连排最后`() {
        val sources = manifestSources("https://endpoint")
        val mirrors = DownloadMirrors.MIRROR_PREFIXES.map { it + MANIFEST_ASSET_URL }
        assertEquals(mirrors, sources.drop(1).dropLast(1))
        assertEquals("直连必须排在最后（国内常不通，先试它会白等一次超时）", MANIFEST_ASSET_URL, sources.last())
    }

    @Test
    fun `镜像列表不含已失效的 gh-proxy-com`() {
        assertFalse(
            "gh-proxy.com 实测已 403 失效，不该留在列表里",
            DownloadMirrors.MIRROR_PREFIXES.any { it.contains("gh-proxy.com") },
        )
        assertTrue(DownloadMirrors.MIRROR_PREFIXES.any { it.contains("ghfast.top") })
        assertTrue(DownloadMirrors.MIRROR_PREFIXES.any { it.contains("ghproxy.net") })
    }

    @Test
    fun `镜像与直连源都指向 GitHub 附件清单`() {
        val sources = manifestSources("https://endpoint")
        // 第 0 个是云函数端点，其余才是「GitHub 清单附件」源
        sources.drop(1).forEach { source ->
            assertTrue("源应指向最新版清单附件：$source", source.endsWith(MANIFEST_ASSET_URL))
        }
    }

    @Test
    fun `urlFor 按顺序取源且越界返回空`() {
        assertEquals(DownloadMirrors.MIRROR_PREFIXES[0] + "BASE", DownloadMirrors.urlFor("BASE", 1))
        assertEquals(DownloadMirrors.MIRROR_PREFIXES[1] + "BASE", DownloadMirrors.urlFor("BASE", 2))
        assertEquals("最后一个是直连", "BASE", DownloadMirrors.urlFor("BASE", 3))
        assertEquals("越界没有源了", "", DownloadMirrors.urlFor("BASE", 4))
        // 源总数 = 镜像数 + 1（直连）
        assertTrue(DownloadMirrors.hasNext(1))
        assertTrue(DownloadMirrors.hasNext(2))
        assertFalse(DownloadMirrors.hasNext(3))
        assertFalse(DownloadMirrors.hasNext(4))
    }

    // ------------------------------------------------------------------
    // 双重前缀（2026-09-15）：v1.0.31 起「下载必失败」的真根因
    //
    // 1.0.23 的源列表第一个是空串（直接用清单地址），所以清单里
    // `apkUrl` 自带 `ghfast.top/` 前缀也能正常下载。
    // e207612 改成「镜像优先」后，前两个源都变成镜像前缀，而清单地址
    // **本身已带前缀** ⇒ 拼出 `ghfast.top/https://ghfast.top/https://github.com/...`，
    // 实测稳定 403（`ghproxy.net/` + `ghfast.top/...` 同样 403）。
    //
    // 修法是拼接前先剥离既有前缀。以下用例把「不论清单给什么形态，
    // 都不许出现双重前缀」钉死。
    // ------------------------------------------------------------------

    private val GITHUB_APK =
        "https://github.com/fxx255/LiXing/releases/download/v1.0.32/LiXing-1.0.32-arm64.apk"

    @Test
    fun `清单里的 apkUrl 自带镜像前缀时不再叠加第二层前缀`() {
        // 历史 update.json 的真实形态：apkUrl 已经带了 ghfast.top 前缀
        val dirty = "https://ghfast.top/$GITHUB_APK"

        assertEquals("第 1 源必须是与清单同前缀的干净拼接", "https://ghfast.top/$GITHUB_APK", DownloadMirrors.urlFor(dirty, 1))
        assertEquals("第 2 源换镜像，但仍只叠一层", "https://ghproxy.net/$GITHUB_APK", DownloadMirrors.urlFor(dirty, 2))
        assertEquals("第 3 源直连，回到原始 GitHub 地址", GITHUB_APK, DownloadMirrors.urlFor(dirty, 3))

        // 任何一次尝试都不允许出现两层及以上前缀。
        // 注意第 3 源是直连（空前缀）⇒ 层数本来就该是 0，所以判据是「至多一层」而不是「恰好一层」。
        (1..3).forEach { attempt ->
            val url = DownloadMirrors.urlFor(dirty, attempt)
            val layers = DownloadMirrors.ALL_KNOWN_PREFIXES.count { url.contains(it) }
            assertTrue("attempt=$attempt 出现了 $layers 层前缀（至多允许 1 层）：$url", layers <= 1)
        }
        // 再明确钉一下：只有「带镜像前缀的两源」是 1 层，直连兜底是 0 层
        assertEquals(1, DownloadMirrors.ALL_KNOWN_PREFIXES.count { DownloadMirrors.urlFor(dirty, 1).contains(it) })
        assertEquals(1, DownloadMirrors.ALL_KNOWN_PREFIXES.count { DownloadMirrors.urlFor(dirty, 2).contains(it) })
        assertEquals(0, DownloadMirrors.ALL_KNOWN_PREFIXES.count { DownloadMirrors.urlFor(dirty, 3).contains(it) })
    }

    @Test
    fun `干净的清单地址同样只叠一层前缀`() {
        assertEquals("https://ghfast.top/$GITHUB_APK", DownloadMirrors.urlFor(GITHUB_APK, 1))
        assertEquals("https://ghproxy.net/$GITHUB_APK", DownloadMirrors.urlFor(GITHUB_APK, 2))
        assertEquals(GITHUB_APK, DownloadMirrors.urlFor(GITHUB_APK, 3))
    }

    @Test
    fun `已失效的 gh-proxy-com 前缀也能被剥掉`() {
        val dirty = "https://gh-proxy.com/$GITHUB_APK"
        assertEquals("https://ghfast.top/$GITHUB_APK", DownloadMirrors.urlFor(dirty, 1))
        assertEquals(GITHUB_APK, DownloadMirrors.urlFor(dirty, 3))
    }

    @Test
    fun `多层嵌套前缀一次剥干净`() {
        val nested = "https://ghfast.top/https://ghproxy.net/https://gh-proxy.com/$GITHUB_APK"
        assertEquals("应剥到只剩原始 GitHub 地址", GITHUB_APK, DownloadMirrors.stripMirrors(nested))
        assertEquals("https://ghfast.top/$GITHUB_APK", DownloadMirrors.urlFor(nested, 1))
    }

    @Test
    fun `stripMirrors 对干净地址与前后空白都是幂等的`() {
        assertEquals(GITHUB_APK, DownloadMirrors.stripMirrors(GITHUB_APK))
        assertEquals(GITHUB_APK, DownloadMirrors.stripMirrors("  $GITHUB_APK  "))
        assertEquals(GITHUB_APK, DownloadMirrors.stripMirrors("https://ghfast.top/$GITHUB_APK"))
        assertEquals("", DownloadMirrors.stripMirrors(""))
    }
}
