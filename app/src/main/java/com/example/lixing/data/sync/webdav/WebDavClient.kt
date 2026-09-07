package com.example.lixing.data.sync.webdav

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** 一次 GET 的结果。 */
data class DavFile(
    val content: String,
    /** 强 ETag（`W/"..."` 这种弱 ETag 不能用于 If-Match，直接不返回）。 */
    val etag: String?,
)

/**
 * 极简 WebDAV 客户端：只实现同步用得到的五个动作（PROPFIND / GET / PUT / MKCOL / DELETE）。
 *
 * 设计取舍：
 * - **不引第三方 WebDAV 库**：需要的能力就这几个方法，自己写更好控错误分类和超时。
 * - **不缓存**：同步是低频操作，正确性 > 性能。
 * - 所有 I/O 都在 [io] 上跑，调用方可以直接在协程里 await。
 */
class WebDavClient internal constructor(
    val config: WebDavConfig,
    private val io: CoroutineDispatcher,
    client: OkHttpClient? = null,
) {
    private val base: HttpUrl = config.folderUrl.toHttpUrl()
    private val http: OkHttpClient = client ?: defaultClient()
    private val folderReady = AtomicBoolean(false)

    /**
     * 列出同步目录下的条目；目录不存在时返回空列表。
     *
     * 注意：Depth: 1 的 PROPFIND 按规范会把目录本身也列出来（[DavEntry.isDirectory] = true），
     * 这里保留原始语义不做过滤——只要文件的调用方请用 [WebDavTransport.list]。
     */
    suspend fun list(): List<DavEntry> = withContext(io) {
        val request = propfind(base, depth = 1)
        execute(request).use { response ->
            if (response.code == 404) return@use emptyList()
            rejectUnlessSuccessful(response)
            DavPropfind.parse(response.body?.string().orEmpty())
        }
    }

    /** 读取文件全文；不存在返回 null。 */
    suspend fun get(name: String): DavFile? = withContext(io) {
        execute(Request.Builder().url(child(name)).get().build()).use { response ->
            if (response.code == 404) return@use null
            rejectUnlessSuccessful(response)
            DavFile(
                content = response.body?.string().orEmpty(),
                etag = response.header("ETag")?.takeIf { it.isNotBlank() && !it.startsWith("W/") },
            )
        }
    }

    /**
     * 覆盖写。
     *
     * @param etag 非空时带 `If-Match`，服务端若已被改过会返回 412，
     *   调用方据此重试（乐观并发，防止两边同时追加丢更新）。
     */
    suspend fun put(name: String, content: String, etag: String? = null) = withContext(io) {
        val builder = Request.Builder()
            .url(child(name))
            .put(content.toRequestBody(TEXT_MEDIA))
        if (!etag.isNullOrBlank()) builder.header("If-Match", etag)
        execute(builder.build()).use { rejectUnlessSuccessful(it) }
    }

    /**
     * 追加若干行。WebDAV 没有 append 语义，只能「读回来 + 拼上 + PUT」。
     *
     * 同步文件只有几十~几百 KB，代价可接受；写的时候带 `If-Match`，
     * 撞上并发就重读重写，最多试 [MAX_APPEND_RETRIES] 次。
     */
    suspend fun appendLines(name: String, lines: List<String>) = withContext(io) {
        if (lines.isEmpty()) return@withContext
        ensureFolder()
        val payload = buildString {
            lines.forEach { append(it).append('\n') }
        }
        repeat(MAX_APPEND_RETRIES) { attempt ->
            val current = get(name)
            val merged = buildString {
                val prefix = current?.content.orEmpty()
                if (prefix.isNotEmpty()) {
                    append(prefix)
                    if (!prefix.endsWith('\n')) append('\n')
                }
                append(payload)
            }
            try {
                put(name, merged, current?.etag)
                return@withContext
            } catch (e: WebDavException) {
                if (e.kind != WebDavException.Kind.CONFLICT || attempt == MAX_APPEND_RETRIES - 1) throw e
            }
        }
    }

    /** 删除文件；文件本来就不存在时视为成功。 */
    suspend fun delete(name: String) = withContext(io) {
        execute(Request.Builder().url(child(name)).delete().build()).use { response ->
            if (response.code == 404) return@use
            rejectUnlessSuccessful(response)
        }
    }

    /**
     * 确保同步目录存在：逐级 MKCOL（坚果云这类服务不允许多级目录一次建出来）。
     * 每个实例只做一次，之后直接跳过。
     */
    suspend fun ensureFolder() = withContext(io) {
        if (folderReady.get()) return@withContext
        ancestors(base).forEach { candidate ->
            if (!exists(candidate)) mkcol(candidate)
        }
        folderReady.set(true)
    }

    /**
     * 连通性自检：只发一次 PROPFIND，不写任何文件。
     *
     * @return null 表示连通；否则返回具体原因（404 也算连通，目录会在首次写入时自动创建）。
     */
    suspend fun check(): WebDavException? = withContext(io) {
        runCatching {
            execute(propfind(base, depth = 0)).use { response ->
                when {
                    response.code == 404 -> null // 目录不存在，首次同步会自动创建
                    response.isSuccessful -> null
                    else -> response.toException()
                }
            }
        }.getOrElse {
            it as? WebDavException
                ?: WebDavException(WebDavException.Kind.NETWORK, it.message ?: it.javaClass.simpleName, it)
        }
    }

    // ---------- 内部 ----------

    private fun propfind(url: HttpUrl, depth: Int): Request = Request.Builder()
        .url(url)
        .method("PROPFIND", DavPropfind.REQUEST_BODY.toRequestBody(XML_MEDIA))
        .header("Depth", depth.toString())
        .build()

    private fun exists(url: HttpUrl): Boolean =
        execute(propfind(url, depth = 0)).use { response ->
            when (response.code) {
                404 -> false
                in 200..299 -> true
                else -> throw response.toException()
            }
        }

    private fun mkcol(url: HttpUrl) {
        execute(Request.Builder().url(url).method("MKCOL", null).build()).use { response ->
            // 部分服务器对已存在的目录返回 405，这不算失败
            if (response.code == 405) return@use
            rejectUnlessSuccessful(response)
        }
    }

    private fun execute(request: Request): Response =
        try {
            http.newCall(authenticated(request)).execute()
        } catch (e: IOException) {
            throw WebDavException(
                WebDavException.Kind.NETWORK,
                "无法连接服务器（${e.message?.take(80) ?: e.javaClass.simpleName}）",
                e,
            )
        }

    private fun authenticated(request: Request): Request = request.newBuilder()
        .header("Authorization", basicAuth(config.account, config.password))
        .build()

    private fun child(name: String): HttpUrl {
        require(name.isNotBlank() && name.none { it == '/' || it == '\\' } && name != "." && name != "..") {
            "非法的远端文件名：$name"
        }
        return base.newBuilder().addPathSegment(name).build()
    }

    private fun rejectUnlessSuccessful(response: Response) {
        if (!response.isSuccessful) throw response.toException()
    }

    private fun Response.toException(): WebDavException {
        val kind = when (code) {
            401, 403 -> WebDavException.Kind.AUTH
            404 -> WebDavException.Kind.NOT_FOUND
            405, 409, 412, 423 -> WebDavException.Kind.CONFLICT
            413, 429, 507 -> WebDavException.Kind.QUOTA
            in 500..599 -> WebDavException.Kind.SERVER
            else -> WebDavException.Kind.PROTOCOL
        }
        return WebDavException(kind, "${failureReason()}（HTTP $code）")
    }

    /** 注意：OkHttp 的 Response 自带 `message`（状态行的原因短语），这里必须换个名字，
     *  否则扩展属性会被成员属性遮蔽，抛出去的就是 "Not Found" 这种没人看得懂的英文。 */
    private fun Response.failureReason(): String = when (code) {
            401, 403 -> "账号或应用密码不正确"
            404 -> "远端路径不存在"
            405 -> "该目录已存在"
            409 -> "父目录不存在，无法写入"
            412 -> "远端文件刚被改动"
            413 -> "文件过大，服务器拒绝接收"
            423 -> "远端文件被锁定"
            429 -> "请求过于频繁，被服务器限流"
            507 -> "云盘空间不足"
            in 500..599 -> "服务器出错"
            else -> "服务器返回了无法处理的响应"
        }

    companion object {
        private val XML_MEDIA = "application/xml; charset=utf-8".toMediaType()
        private val TEXT_MEDIA = "text/plain; charset=utf-8".toMediaType()
        private const val MAX_APPEND_RETRIES = 3

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()

        /** 目录 URL 的逐级祖先：/dav/lixing/ → [/dav/, /dav/lixing/]。 */
        internal fun ancestors(base: HttpUrl): List<HttpUrl> {
            val segments = base.pathSegments.filter { it.isNotEmpty() }
            return (1..segments.size).map { index ->
                val defaultPort = if (base.scheme == "https") 443 else 80
                val port = if (base.port == defaultPort) "" else ":${base.port}"
                val path = segments.take(index).joinToString("/", prefix = "/", postfix = "/") { it }
                "${base.scheme}://${base.host}$port$path".toHttpUrl()
            }
        }

        /** 自己拼 Basic 头而不用 OkHttp 的 Credentials.basic：那条走 ISO-8859-1，中文密码会炸。 */
        internal fun basicAuth(account: String, password: String): String {
            val raw = "$account:$password".toByteArray(Charsets.UTF_8)
            return "Basic " + Base64.getEncoder().encodeToString(raw)
        }
    }
}
