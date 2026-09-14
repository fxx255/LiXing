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
}
