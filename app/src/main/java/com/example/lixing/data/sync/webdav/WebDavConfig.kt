package com.example.lixing.data.sync.webdav

import kotlinx.serialization.Serializable
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * WebDAV 连接配置。
 *
 * @property folderUrl 同步目录的完整 URL（一定以 `/` 结尾），
 *   例如坚果云：`https://dav.jianguoyun.com/dav/lixing/`。
 * @property account 登录账号（坚果云是注册邮箱）。
 * @property password **应用密码**。坚果云等服务不允许直接用登录密码做 Basic 认证，
 *   必须在网页端「账户信息 → 安全选项 → 添加应用」里生成专用密码，这里存的就是它。
 */
@Serializable
data class WebDavConfig(
    val folderUrl: String = "",
    val account: String = "",
    val password: String = "",
) {

    val isComplete: Boolean
        get() = folderUrl.isNotBlank() && account.isNotBlank() && password.isNotBlank()

    /** 给用户看的地址（去掉账号密码）。 */
    val displayUrl: String get() = folderUrl

    /** 密码绝不能进日志 / 崩溃堆栈。 */
    override fun toString(): String =
        "WebDavConfig(folderUrl=$folderUrl, account=$account, password=***)"

    companion object {

        /**
         * 把用户随手填的地址规整成可用的**目录 URL**（补 scheme、去掉多余斜杠、末尾补 `/`）。
         * 无法识别、或公网用了明文 http 时返回 null。
         */
        fun normalizeUrl(input: String): String? {
            val raw = input.trim()
            if (raw.isEmpty()) return null

            val withScheme = when {
                raw.startsWith("https://", ignoreCase = true) -> raw
                raw.startsWith("http://", ignoreCase = true) -> raw
                // 明确写了别的协议（ftp:// 之类）就直接拒绝：
                // 否则 "ftp://example.com/dav/" 会被当成主机名 "ftp" 拼成 https 地址
                SCHEME_RE.containsMatchIn(raw) -> return null
                else -> "https://$raw"
            }
            val url: HttpUrl = withScheme.toHttpUrlOrNull() ?: return null
            val host = url.host
            if (host.isBlank() || host.contains(' ')) return null

            val scheme = url.scheme.lowercase()
            if (scheme != "http" && scheme != "https") return null
            // Basic 认证是 base64 明文可逆的，公网走 http 等于把密码广播出去
            if (scheme == "http" && !isPlainTextHttpAllowed(host)) return null

            val dirPath = url.encodedPath.trimEnd('/').let { if (it.isEmpty()) "/" else "$it/" }
            val defaultPort = if (scheme == "https") 443 else 80
            val port = if (url.port == defaultPort) "" else ":${url.port}"
            return "$scheme://$host$port$dirPath".toHttpUrlOrNull()?.toString()
        }

        /**
         * 明文 http 只对本机 / 局域网放行（自建 NAS、测试用）。公网一律要求 https。
         */
        fun isPlainTextHttpAllowed(host: String): Boolean {
            val h = host.lowercase()
            if (h == "localhost" || h == "::1" || h == "127.0.0.1" || h == "10.0.2.2") return true
            if (h.endsWith(".local")) return true
            if (h.startsWith("10.") || h.startsWith("192.168.")) return true
            // 172.16.0.0 ~ 172.31.255.255
            return PRIVATE_172.containsMatchIn(h)
        }

        private val PRIVATE_172 = Regex("^172\\.(1[6-9]|2\\d|3[01])\\.")
        private val SCHEME_RE = Regex("^[a-zA-Z][a-zA-Z0-9+.\\-]*://")
    }
}
