package com.example.lixing.data.update

/**
 * APK 下载源策略：直连 GitHub 在国内经常不通，失败时依次尝试加速镜像。
 *
 * 镜像只是「字节搬运工」，安全性由清单里的 SHA-256 校验兜底——
 * 无论哪个源返回的内容被篡改，校验失败都会拒绝安装。
 */
object DownloadMirrors {

    /** 第 1 个是清单原始地址（直连），后面是加速镜像（把完整 URL 拼在前缀后）。 */
    private val PREFIXES = listOf(
        "",
        "https://ghfast.top/",
        "https://gh-proxy.com/",
    )

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
