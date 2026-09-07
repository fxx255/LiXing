package com.example.lixing.data.sync.webdav

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Base64
import kotlin.concurrent.thread

/**
 * 最小 WebDAV 服务，给单测用。
 *
 * 为什么不引 mockwebserver：它没进离线缓存。为什么不直接用 JDK 的
 * `com.sun.net.httpserver`：它不在单测编译的 JDK API 白名单里（Kotlin `-Xjdk-release`
 * 会挡掉 `com.sun.*`）。所以干脆用 ServerSocket 手写一个——请求和响应两端都在自己手里，
 * 只需要支持 WebDAV 用得到的那几个方法和状态码。
 */
internal class FakeWebDavServer(
    private val account: String = "user@example.com",
    private val password: String = "app-password",
    seedFolders: Set<String> = setOf("/", "/dav/", "/dav/lixing/"),
) {
    private val files = LinkedHashMap<String, String>()
    private val etags = LinkedHashMap<String, String>()
    private val folders = LinkedHashSet<String>(seedFolders)
    private val requests = mutableListOf<Recorded>()
    private val lock = Any()
    private var etagSeq = 0

    /** 每次 PUT 前触发一次然后自动清空，用来模拟"另一个写入者抢先改了文件"。 */
    var beforePut: (() -> Unit)? = null

    /** 强制返回这个状态码，用来测错误分类。默认只生效一次。 */
    var forceStatus: Int? = null
    var forceStatusOnce: Boolean = true

    private val serverSocket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val acceptor = thread(name = "fake-webdav", isDaemon = true) { acceptLoop() }

    val url: String get() = "http://127.0.0.1:${serverSocket.localPort}$BASE"

    fun start() {
        // 构造时就已开始监听；留个空方法让测试读起来更明确
        require(acceptor.isAlive) { "假服务器线程没起来" }
    }

    fun stop() = serverSocket.close()

    fun seed(name: String, content: String) = synchronized(lock) {
        files[name] = content
        etags[name] = nextEtag()
    }

    fun contentOf(name: String): String? = synchronized(lock) { files[name] }

    fun requests(): List<Recorded> = synchronized(lock) { requests.toList() }

    fun methods(): List<String> = requests().map { it.method }

    // ---------- 网络循环 ----------

    private fun acceptLoop() {
        while (!serverSocket.isClosed) {
            val socket = try {
                serverSocket.accept()
            } catch (e: Exception) {
                return // 服务器关了
            }
            try {
                socket.soTimeout = 10_000
                handle(socket)
            } catch (e: Exception) {
                runCatching { respond(socket, 500) }
            } finally {
                socket.close()
            }
        }
    }

    private fun handle(socket: Socket) {
        val input: InputStream = socket.getInputStream().buffered()
        val head = readHead(input)
        if (head.isBlank()) return

        val lines = head.split("\r\n")
        val requestLine = lines.first()
        val method = requestLine.substringBefore(' ')
        val path = requestLine.substringAfter(' ').substringBeforeLast(' ')
        val headers = lines.drop(1)
            .mapNotNull { line ->
                val index = line.indexOf(':')
                if (index <= 0) null else line.substring(0, index).trim().lowercase() to line.substring(index + 1).trim()
            }
            .toMap()

        val bodyLength = headers["content-length"]?.toIntOrNull() ?: 0
        val body = if (bodyLength > 0) readExactly(input, bodyLength) else ""

        val recorded = Recorded(
            method = method,
            path = path,
            body = body,
            ifMatch = headers["if-match"],
            authorization = headers["authorization"],
            depth = headers["depth"],
        )
        synchronized(lock) { requests += recorded }

        if (headers["authorization"] != expectedAuth) {
            respond(socket, 401)
            return
        }

        synchronized(lock) {
            when (method) {
                "PROPFIND" -> propfind(socket, path, headers)
                "GET" -> get(socket, path)
                "PUT" -> put(socket, path, body, headers)
                "DELETE" -> delete(socket, path)
                "MKCOL" -> mkcol(socket, path)
                else -> respond(socket, 405)
            }
        }
    }

    private fun propfind(socket: Socket, path: String, headers: Map<String, String>) {
        takeForcedStatus()?.let { return respond(socket, it) }
        if (normalizeFolder(path) !in folders) return respond(socket, 404)

        val depth = headers["depth"] ?: "0"
        val body = StringBuilder()
        body.append(folderResponse(BASE))
        if (depth != "0") files.keys.sorted().forEach { body.append(fileResponse(it)) }
        respond(socket, 207, MULTISTATUS_OPEN + body + MULTISTATUS_CLOSE)
    }

    private fun get(socket: Socket, path: String) {
        takeForcedStatus()?.let { return respond(socket, it) }
        val name = path.removePrefix(BASE)
        val content = files[name] ?: return respond(socket, 404)
        respond(socket, 200, content, mapOf("ETag" to etags[name].orEmpty()))
    }

    private fun put(socket: Socket, path: String, body: String, headers: Map<String, String>) {
        takeForcedStatus()?.let { return respond(socket, it) }
        val name = path.removePrefix(BASE)
        if (BASE !in folders) return respond(socket, 409)

        beforePut?.let { hook ->
            beforePut = null
            hook()
        }

        val ifMatch = headers["if-match"]
        if (ifMatch != null && ifMatch != etags[name]) return respond(socket, 412)

        files[name] = body
        val etag = nextEtag()
        etags[name] = etag
        respond(socket, 201, "", mapOf("ETag" to etag))
    }

    private fun delete(socket: Socket, path: String) {
        takeForcedStatus()?.let { return respond(socket, it) }
        val name = path.removePrefix(BASE)
        if (files.remove(name) == null) return respond(socket, 404)
        etags.remove(name)
        respond(socket, 204)
    }

    private fun mkcol(socket: Socket, path: String) {
        val folder = normalizeFolder(path)
        if (folder in folders) return respond(socket, 405)
        val parent = folder.trimEnd('/').substringBeforeLast('/', "").let { if (it.isEmpty()) "/" else "$it/" }
        if (parent !in folders) return respond(socket, 409)
        folders += folder
        respond(socket, 201)
    }

    // ---------- 工具 ----------

    private fun respond(socket: Socket, code: Int, body: String = "", extraHeaders: Map<String, String> = emptyMap()) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val text = buildString {
            append("HTTP/1.1 $code ${REASONS[code] ?: "Unknown"}\r\n")
            append("Content-Type: application/xml; charset=utf-8\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Connection: close\r\n")
            extraHeaders.forEach { (key, value) -> append("$key: $value\r\n") }
            append("\r\n")
        }
        socket.getOutputStream().use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            if (bytes.isNotEmpty()) out.write(bytes)
            out.flush()
        }
    }

    private fun readHead(input: InputStream): String {
        val buffer = ByteArrayOutputStream()
        var matched = 0
        while (true) {
            val byte = input.read()
            if (byte == -1) break
            buffer.write(byte)
            matched = when {
                byte == '\r'.code && matched == 0 -> 1
                byte == '\n'.code && matched == 1 -> 2
                byte == '\r'.code && matched == 2 -> 3
                byte == '\n'.code && matched == 3 -> 4
                else -> 0
            }
            if (matched == 4) break
        }
        return buffer.toString(Charsets.UTF_8)
    }

    private fun readExactly(input: InputStream, length: Int): String {
        val buffer = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(buffer, offset, length - offset)
            if (read == -1) break
            offset += read
        }
        return buffer.decodeToString(endIndex = offset)
    }

    private fun takeForcedStatus(): Int? {
        val code = forceStatus ?: return null
        if (forceStatusOnce) forceStatus = null
        return code
    }

    private fun nextEtag(): String = "\"etag-${++etagSeq}\""

    private fun normalizeFolder(path: String): String {
        val trimmed = path.substringBefore('?').trimEnd('/')
        return if (trimmed.isEmpty()) "/" else "$trimmed/"
    }

    private fun folderResponse(href: String) =
        "<D:response><D:href>$href</D:href><D:propstat><D:prop><D:resourcetype><D:collection/>" +
            "</D:resourcetype></D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>"

    private fun fileResponse(name: String) =
        "<D:response><D:href>$BASE$name</D:href><D:propstat><D:prop>" +
            "<D:getcontentlength>${files[name].orEmpty().toByteArray(Charsets.UTF_8).size}</D:getcontentlength>" +
            "<D:getlastmodified>Mon, 01 Jan 2024 00:00:00 GMT</D:getlastmodified>" +
            "<D:resourcetype/></D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>"

    private val expectedAuth: String by lazy {
        val raw = "$account:$password".toByteArray(Charsets.UTF_8)
        "Basic " + Base64.getEncoder().encodeToString(raw)
    }

    data class Recorded(
        val method: String,
        val path: String,
        val body: String,
        val ifMatch: String?,
        val authorization: String?,
        val depth: String?,
    )

    companion object {
        const val BASE = "/dav/lixing/"
        private const val MULTISTATUS_OPEN =
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<D:multistatus xmlns:D=\"DAV:\">"
        private const val MULTISTATUS_CLOSE = "</D:multistatus>"

        private val REASONS = mapOf(
            200 to "OK",
            201 to "Created",
            204 to "No Content",
            207 to "Multi-Status",
            401 to "Unauthorized",
            404 to "Not Found",
            405 to "Method Not Allowed",
            409 to "Conflict",
            412 to "Precondition Failed",
            500 to "Internal Server Error",
            507 to "Insufficient Storage",
        )
    }
}
