package com.example.lixing.data.update

/**
 * 下载源策略：直连 GitHub 在国内经常不通，优先走加速镜像，最后才退回直连。
 *
 * 镜像只是「字节搬运工」，安全性由清单里的 SHA-256 校验兜底——
 * 无论哪个源返回的内容被篡改，校验失败都会拒绝安装。
 *
 * ⚠️ 铁律：清单里的 `apkUrl` **可能本身就带镜像前缀**（历史 update.json 就是这么写的，
 * 云函数只做透传）。所以拼接前必须先 [stripMirrors] 剥掉既有前缀，否则会拼出
 * `https://ghfast.top/https://ghfast.top/https://github.com/...` 这种**双重前缀**地址
 * ——实测稳定返回 403，表现为「检查更新一直正常，但点下载必然失败」。
 *
 * 实测记录（2026-09-14）：`ghfast.top` 与 `ghproxy.net` 可用；
 * `gh-proxy.com` 已返回 403（失效），不再作为下载源，
 * 但**保留在剥离列表里**——老清单可能还引用它。
 */
object DownloadMirrors {

    /** 加速镜像前缀（把完整 URL 拼在前缀后）。 */
    internal val MIRROR_PREFIXES = listOf(
        "https://ghfast.top/",
        "https://ghproxy.net/",
    )

    /**
     * 历史失效前缀：不再作为下载源候选，但**必须参与剥离**。
     * 老版本的 update.json 里可能还留着这些前缀，不剥掉就会双重前缀。
     */
    private val LEGACY_PREFIXES = listOf(
        "https://gh-proxy.com/",
    )

    /** 全部可识别前缀，仅用于剥离。 */
    internal val ALL_KNOWN_PREFIXES = MIRROR_PREFIXES + LEGACY_PREFIXES

    /**
     * 直连 GitHub。国内手机常连不通，所以排在**最后**：
     * 先试镜像能少一次无谓的超时等待。
     */
    private const val DIRECT = ""

    private val PREFIXES = MIRROR_PREFIXES + DIRECT

    /**
     * 剥掉所有已知镜像前缀，得到「原始地址」（通常是 GitHub 直链）。
     *
     * 用循环而不是单次 `removePrefix`：清单可能被多层套过前缀
     * （`ghfast.top/ghproxy.net/https://github.com/...`），要一次剥干净。
     */
    internal fun stripMirrors(url: String): String {
        var result = url.trim()
        var changed = true
        while (changed) {
            changed = false
            for (prefix in ALL_KNOWN_PREFIXES) {
                if (result.startsWith(prefix)) {
                    result = result.removePrefix(prefix)
                    changed = true
                }
            }
        }
        return result
    }

    /**
     * 第 [attempt] 次尝试（从 1 开始）应使用的下载地址。
     * attempt 超出列表时返回空串，调用方应视为无更多源。
     *
     * 无论 [baseUrl] 是干净的 GitHub 直链还是已带镜像前缀的地址，
     * 结果都不会出现双重前缀。
     */
    fun urlFor(baseUrl: String, attempt: Int): String {
        val prefix = PREFIXES.getOrNull(attempt - 1) ?: return ""
        val clean = stripMirrors(baseUrl)
        return if (prefix.isEmpty()) clean else prefix + clean
    }

    /** 是否还有下一个源可以尝试。 */
    fun hasNext(attempt: Int): Boolean = attempt < PREFIXES.size
}
