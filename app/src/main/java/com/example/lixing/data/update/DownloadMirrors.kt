package com.example.lixing.data.update

/**
 * 下载源策略：直连 GitHub 在国内经常不通，优先走加速镜像，最后才退回直连。
 *
 * 镜像只是「字节搬运工」，安全性由清单里的 SHA-256 校验兜底——
 * 无论哪个源返回的内容被篡改，校验失败都会拒绝安装。
 *
 * 实测记录（2026-09-14）：`ghfast.top` 与 `ghproxy.net` 可用；
 * `gh-proxy.com` 已返回 403（失效），不要再加回来。
 */
object DownloadMirrors {

    /** 加速镜像前缀（把完整 URL 拼在前缀后）。 */
    internal val MIRROR_PREFIXES = listOf(
        "https://ghfast.top/",
        "https://ghproxy.net/",
    )

    /**
     * 直连 GitHub。国内手机常连不通，所以排在**最后**：
     * 先试镜像能少一次无谓的超时等待。
     */
    private const val DIRECT = ""

    private val PREFIXES = MIRROR_PREFIXES + DIRECT

    /**
     * 第 [attempt] 次尝试（从 1 开始）应使用的下载地址。
     * attempt 超出列表时返回空串，调用方应视为无更多源。
     */
    fun urlFor(baseUrl: String, attempt: Int): String {
        val prefix = PREFIXES.getOrNull(attempt - 1) ?: return ""
        return if (prefix.isEmpty()) baseUrl else prefix + baseUrl
    }

    /** 是否还有下一个源可以尝试。 */
    fun hasNext(attempt: Int): Boolean = attempt < PREFIXES.size
}
