package com.example.lixing.data.sync.webdav

import java.io.IOException

/**
 * WebDAV 操作失败。
 *
 * 同步内核只关心「失败可恢复」这一件事，所以所有失败统一成一种异常，
 * 用 [kind] 区分原因，UI 层拿它翻译成人话（见 [userMessage]）。
 */
class WebDavException(
    val kind: Kind,
    override val message: String,
    cause: Throwable? = null,
) : IOException(message, cause) {

    enum class Kind {
        /** 401 / 403：账号或应用密码不对，重试没用，必须让用户改配置。 */
        AUTH,

        /** 404：文件或目录不存在。 */
        NOT_FOUND,

        /** 405 / 409 / 412 / 423：并发写冲突或路径被占用，可以重试。 */
        CONFLICT,

        /** 413 / 429 / 507：空间不足、文件过大或请求过于频繁。 */
        QUOTA,

        /** 5xx：服务器侧问题。 */
        SERVER,

        /** 连不上 / 超时 / 断网。 */
        NETWORK,

        /** 服务器返回的不是合规 WebDAV 响应。 */
        PROTOCOL,
    }

    /** 给用户看的中文说明。 */
    fun userMessage(): String = when (kind) {
        Kind.AUTH -> "账号或应用密码不正确，请在设置里重新填写"
        Kind.NOT_FOUND -> "远端文件或目录不存在"
        Kind.CONFLICT -> "远端文件刚被改动，正在重试"
        Kind.QUOTA -> "云盘空间不足或请求过于频繁"
        Kind.SERVER -> "服务器暂时不可用，稍后再试"
        Kind.NETWORK -> "网络连接失败，请检查网络后重试"
        Kind.PROTOCOL -> "服务器返回的响应无法识别，请确认填写的是 WebDAV 地址"
    }

    /** 这类错误重试也没用，应该让用户去改配置。 */
    val needsUserAction: Boolean
        get() = kind == Kind.AUTH || kind == Kind.QUOTA
}
