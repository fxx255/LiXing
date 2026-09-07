package com.example.lixing.data.cloud

import android.content.Context
import com.example.lixing.BuildConfig
import com.example.lixing.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 百度网盘“应用数据”备份。
 *
 * AppKey/SecretKey 永远不进入 APK：授权码兑换和刷新均由 Cloudflare Worker 完成。
 * App 只保存 Keystore 加密后的用户 access_token/refresh_token。
 */
@Singleton
class BaiduNetdiskRepository @Inject internal constructor(
    @param:ApplicationContext private val context: Context,
    private val credentialStore: BaiduCredentialStore,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .build()

    private val _state = MutableStateFlow(
        BaiduNetdiskState(connected = credentialStore.load() != null),
    )
    val state: StateFlow<BaiduNetdiskState> = _state.asStateFlow()

    private val _cloudBackups = MutableStateFlow<List<CloudBackup>>(emptyList())
    val cloudBackups: StateFlow<List<CloudBackup>> = _cloudBackups.asStateFlow()

    /** 网盘版本包下载进度（0～1）；active=false 表示当前没有正在进行的下载。 */
    private val _downloadProgress = MutableStateFlow(BaiduDownloadProgress())
    val downloadProgress: StateFlow<BaiduDownloadProgress> = _downloadProgress.asStateFlow()

    val authorizationUrl: String
        get() = "${BuildConfig.BAIDU_OAUTH_BASE_URL}/oauth/baidu/start"

    fun markAuthorizationStarted() {
        _state.update { it.copy(message = "请在浏览器中登录百度并同意授权") }
    }

    suspend fun completeAuthorization(ticket: String): Boolean = withContext(io) {
        if (!ticket.matches(TICKET_REGEX)) {
            _state.value = BaiduNetdiskState(message = "百度授权票据无效，请重新授权")
            return@withContext false
        }

        _state.update { it.copy(busy = true, message = "正在完成百度网盘连接…") }
        try {
            val envelope = workerPost(
                "/oauth/baidu/token",
                json.encodeToString(TicketRequest(ticket)),
            )
            val token = envelope.token
            require(envelope.ok && token != null && token.accessToken.isNotBlank()) {
                envelope.error ?: "Worker 没有返回授权凭证"
            }
            credentialStore.save(token.toCredentials())
            _state.value = BaiduNetdiskState(
                connected = true,
                busy = false,
                message = "百度网盘连接成功",
            )
            runCatching { loadCloudBackups(validCredentials()) }
            true
        } catch (e: Exception) {
            _state.value = BaiduNetdiskState(
                connected = credentialStore.load() != null,
                busy = false,
                message = "连接失败：${safeMessage(e)}",
            )
            false
        }
    }

    fun disconnect() {
        credentialStore.clear()
        _cloudBackups.value = emptyList()
        _state.value = BaiduNetdiskState(message = "已断开百度网盘")
    }

    suspend fun refreshCloudBackups(): Boolean = withContext(io) {
        _state.update { it.copy(busy = true, message = "正在读取网盘版本…") }
        try {
            loadCloudBackups(validCredentials())
            _state.update {
                it.copy(
                    connected = true,
                    busy = false,
                    message = "已找到 ${_cloudBackups.value.size} 个网盘版本",
                )
            }
            true
        } catch (e: Exception) {
            operationFailed("读取网盘版本失败", e)
            false
        }
    }

    suspend fun uploadBackup(file: File): CloudBackup = withContext(io) {
        require(file.isFile && file.extension == "lixingbackup") { "备份文件不存在或格式不正确" }
        _state.update { it.copy(busy = true, message = "正在上传 ${file.name}…") }
        try {
            val credentials = validCredentials()
            uploadFile(file, credentials)
            loadCloudBackups(credentials)
            val uploaded = _cloudBackups.value.firstOrNull { it.fileName == file.name }
                ?: CloudBackup(0, file.name, "$REMOTE_DIR/${file.name}", file.length(), 0)
            _state.update {
                it.copy(connected = true, busy = false, message = "网盘备份上传成功：${file.name}")
            }
            uploaded
        } catch (e: Exception) {
            operationFailed("上传失败", e)
            throw e
        }
    }

    suspend fun downloadBackup(backup: CloudBackup): File = withContext(io) {
        require(backup.fsId > 0) { "网盘文件标识无效" }
        _state.update { it.copy(busy = true, message = "正在下载 ${backup.fileName}…") }
        _downloadProgress.value = BaiduDownloadProgress(
            active = true,
            fileName = backup.fileName,
            totalBytes = backup.sizeBytes,
        )
        try {
            val credentials = validCredentials()
            val target = downloadFile(backup, credentials)
            _downloadProgress.update { it.copy(active = false, downloadedBytes = it.totalBytes) }
            _state.update {
                it.copy(connected = true, busy = false, message = "下载完成，正在校验备份…")
            }
            target
        } catch (e: Exception) {
            _downloadProgress.update { it.copy(active = false) }
            operationFailed("下载失败", e)
            throw e
        }
    }

    /**
     * 删除网盘上的一个版本包副本。
     *
     * 调用方有责任遵守「对端恢复成功并校验 SHA-256 后才允许删除」的约定——
     * 仓库层面再加一道硬防线：只允许删除应用目录（[REMOTE_DIR]）内的文件；
     * 网盘里已不存在（errno=-9）视为幂等成功。
     */
    suspend fun deleteCloudBackup(backup: CloudBackup): Boolean = withContext(io) {
        require(backup.fsId > 0) { "网盘文件标识无效" }
        _state.update { it.copy(busy = true, message = "正在删除网盘副本 ${backup.fileName}…") }
        try {
            val credentials = validCredentials()
            deleteRemoteFile(backup.remotePath, credentials)
            loadCloudBackups(credentials)
            _state.update {
                it.copy(connected = true, busy = false, message = "已删除网盘副本：${backup.fileName}")
            }
            true
        } catch (e: Exception) {
            operationFailed("删除网盘副本失败", e)
            false
        }
    }

    private fun deleteRemoteFile(remotePath: String, credentials: BaiduCredentials) {
        require(remotePath.startsWith(REMOTE_DIR)) { "只允许删除应用目录内的副本" }
        val url = XPAN_FILE_URL.toHttpUrl().newBuilder()
            .addQueryParameter("method", "filemanager")
            .addQueryParameter("access_token", credentials.accessToken)
            .addQueryParameter("opera", "delete")
            .build()
        val body = FormBody.Builder()
            .add("async", "0") // 单文件同步删除，errno=0 即生效
            .add("filelist", json.encodeToString(listOf(remotePath)))
            .build()
        val responseText = execute(Request.Builder().url(url).post(body).build())
        if (errno(responseText) == -9) return // 已经不在网盘上 → 幂等成功
        ensureSuccess(responseText)
    }

    private fun loadCloudBackups(credentials: BaiduCredentials) {
        val url = XPAN_FILE_URL.toHttpUrl().newBuilder()
            .addQueryParameter("method", "list")
            .addQueryParameter("access_token", credentials.accessToken)
            .addQueryParameter("dir", REMOTE_DIR)
            .addQueryParameter("order", "time")
            .addQueryParameter("desc", "1")
            .addQueryParameter("start", "0")
            .addQueryParameter("limit", "100")
            .addQueryParameter("web", "1")
            .build()
        val body = execute(Request.Builder().url(url).get().build())
        val errno = errno(body)
        if (errno == -9) {
            _cloudBackups.value = emptyList()
            return
        }
        ensureSuccess(body)
        val response = json.decodeFromString<BaiduListResponse>(body)
        _cloudBackups.value = response.list
            .asSequence()
            .filter { it.isdir == 0 && it.fileName.endsWith(".lixingbackup", ignoreCase = true) }
            .map {
                CloudBackup(
                    fsId = it.fsId,
                    fileName = it.fileName,
                    remotePath = it.path,
                    sizeBytes = it.size,
                    modifiedAtEpochSeconds = it.modifiedAt,
                )
            }
            .sortedByDescending { it.modifiedAtEpochSeconds }
            .toList()
    }

    private fun uploadFile(file: File, credentials: BaiduCredentials) {
        require(file.length() in 1..MAX_BACKUP_BYTES) { "备份文件大小异常" }
        val remotePath = "$REMOTE_DIR/${file.name}"
        val blockHashes = blockMd5s(file)
        val precreateBody = FormBody.Builder()
            .add("path", remotePath)
            .add("size", file.length().toString())
            .add("isdir", "0")
            .add("autoinit", "1")
            .add("rtype", "3")
            .add("block_list", json.encodeToString(blockHashes))
            .build()
        val precreateUrl = XPAN_FILE_URL.toHttpUrl().newBuilder()
            .addQueryParameter("method", "precreate")
            .addQueryParameter("access_token", credentials.accessToken)
            .build()
        val precreateText = execute(Request.Builder().url(precreateUrl).post(precreateBody).build())
        ensureSuccess(precreateText)
        val precreate = json.decodeFromString<BaiduPrecreateResponse>(precreateText)
        require(precreate.uploadid.isNotBlank()) { "百度网盘没有返回 uploadid" }

        // return_type=2 表示已命中秒传，无需再次上传分片。
        if (precreate.returnType != 2) {
            FileInputStream(file).use { input ->
                var partSequence = 0
                while (true) {
                    val chunk = input.readChunk(UPLOAD_PART_BYTES) ?: break
                    uploadPart(remotePath, precreate.uploadid, partSequence, chunk, credentials)
                    partSequence++
                }
            }
        }

        val createBody = FormBody.Builder()
            .add("path", remotePath)
            .add("size", file.length().toString())
            .add("isdir", "0")
            .add("rtype", "3")
            .add("uploadid", precreate.uploadid)
            .add("block_list", json.encodeToString(blockHashes))
            .build()
        val createUrl = XPAN_FILE_URL.toHttpUrl().newBuilder()
            .addQueryParameter("method", "create")
            .addQueryParameter("access_token", credentials.accessToken)
            .build()
        ensureSuccess(execute(Request.Builder().url(createUrl).post(createBody).build()))
    }

    private fun uploadPart(
        remotePath: String,
        uploadId: String,
        partSequence: Int,
        chunk: ByteArray,
        credentials: BaiduCredentials,
    ) {
        val url = PCS_SUPERFILE_URL.toHttpUrl().newBuilder()
            .addQueryParameter("method", "upload")
            .addQueryParameter("access_token", credentials.accessToken)
            .addQueryParameter("type", "tmpfile")
            .addQueryParameter("path", remotePath)
            .addQueryParameter("uploadid", uploadId)
            .addQueryParameter("partseq", partSequence.toString())
            .build()
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                "part-$partSequence",
                chunk.toRequestBody(OCTET_STREAM),
            )
            .build()
        ensureSuccess(execute(Request.Builder().url(url).post(body).build()))
    }

    private fun downloadFile(backup: CloudBackup, credentials: BaiduCredentials): File {
        val metadataUrl = XPAN_MULTIMEDIA_URL.toHttpUrl().newBuilder()
            .addQueryParameter("method", "filemetas")
            .addQueryParameter("access_token", credentials.accessToken)
            .addQueryParameter("fsids", json.encodeToString(listOf(backup.fsId)))
            .addQueryParameter("dlink", "1")
            .build()
        val metadataText = execute(Request.Builder().url(metadataUrl).get().build())
        ensureSuccess(metadataText)
        val dlink = json.decodeFromString<BaiduMetaResponse>(metadataText).list.firstOrNull()?.dlink
        require(!dlink.isNullOrBlank()) { "百度网盘没有返回下载地址" }
        val downloadUrl = dlink.toHttpUrl().newBuilder()
            .addQueryParameter("access_token", credentials.accessToken)
            .build()
        require(downloadUrl.isHttps) { "百度返回了不安全的下载地址" }

        val request = Request.Builder()
            .url(downloadUrl)
            .header("User-Agent", "pan.baidu.com")
            .get()
            .build()
        val target = File(context.cacheDir, "baidu_${backup.fsId}_${System.currentTimeMillis()}.lixingbackup")
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("百度下载失败（HTTP ${response.code}）")
            val declaredSize = response.body?.contentLength() ?: -1
            require(declaredSize <= MAX_BACKUP_BYTES) { "网盘备份过大" }
            val input = response.body?.byteStream() ?: error("百度没有返回文件内容")
            var lastReported = 0L
            try {
                target.outputStream().buffered().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= MAX_BACKUP_BYTES) { "网盘备份过大" }
                        output.write(buffer, 0, count)
                        // 每 256KB 上报一次进度（StateFlow 自带去抖，这里节流减少无效更新）
                        if (total - lastReported >= 256 * 1024) {
                            lastReported = total
                            _downloadProgress.update {
                                it.copy(downloadedBytes = total, totalBytes = declaredSize)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                target.delete()
                throw e
            }
        }
        return target
    }

    private fun validCredentials(): BaiduCredentials {
        val current = credentialStore.load() ?: error("请先连接百度网盘")
        if (current.expiresAtEpochMillis - System.currentTimeMillis() > REFRESH_AHEAD_MILLIS) {
            return current
        }

        require(current.refreshToken.isNotBlank()) { "百度授权已过期，请重新连接" }
        val envelope = workerPost(
            "/oauth/baidu/refresh",
            json.encodeToString(RefreshRequest(current.refreshToken)),
        )
        val token = envelope.token
        require(envelope.ok && token != null && token.accessToken.isNotBlank()) {
            envelope.error ?: "刷新百度授权失败"
        }
        return token.toCredentials(current.refreshToken).also(credentialStore::save)
    }

    private fun workerPost(path: String, body: String): WorkerTokenEnvelope {
        val request = Request.Builder()
            .url("${BuildConfig.BAIDU_OAUTH_BASE_URL}$path")
            .header("Accept", "application/json")
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val responseText = execute(request, allowHttpError = true)
        return runCatching { json.decodeFromString<WorkerTokenEnvelope>(responseText) }
            .getOrElse { error("OAuth 服务返回了无法识别的结果") }
    }

    private fun execute(request: Request, allowHttpError: Boolean = false): String =
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!allowHttpError && !response.isSuccessful) {
                error("百度网盘请求失败（HTTP ${response.code}）")
            }
            text
        }

    private fun ensureSuccess(body: String) {
        val code = errno(body)
        if (code == 0) return
        val detail = runCatching {
            val obj = json.parseToJsonElement(body).jsonObject
            obj["errmsg"]?.jsonPrimitive?.contentOrNull
                ?: obj["error_msg"]?.jsonPrimitive?.contentOrNull
        }.getOrNull()
        error("百度网盘错误 $code${detail?.let { "：$it" }.orEmpty()}")
    }

    private fun errno(body: String): Int = runCatching {
        val obj = json.parseToJsonElement(body).jsonObject
        obj["errno"]?.jsonPrimitive?.intOrNull
            ?: obj["error_code"]?.jsonPrimitive?.intOrNull
            ?: 0
    }.getOrDefault(0)

    private fun blockMd5s(file: File): List<String> = FileInputStream(file).use { input ->
        buildList {
            while (true) {
                val chunk = input.readChunk(UPLOAD_PART_BYTES) ?: break
                val hash = MessageDigest.getInstance("MD5").digest(chunk)
                    .joinToString("") { "%02x".format(it) }
                add(hash)
            }
        }
    }

    private fun FileInputStream.readChunk(maxBytes: Int): ByteArray? {
        val buffer = ByteArray(maxBytes)
        var offset = 0
        while (offset < maxBytes) {
            val count = read(buffer, offset, maxBytes - offset)
            if (count < 0) break
            offset += count
        }
        if (offset == 0) return null
        return if (offset == buffer.size) buffer else buffer.copyOf(offset)
    }

    private fun operationFailed(prefix: String, error: Exception) {
        _state.update {
            it.copy(
                connected = credentialStore.load() != null,
                busy = false,
                message = "$prefix：${safeMessage(error)}",
            )
        }
    }

    private fun safeMessage(error: Exception): String =
        error.message?.take(160) ?: error::class.java.simpleName

    private companion object {
        val TICKET_REGEX = Regex("^[A-Za-z0-9_-]{40,100}$")
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        val OCTET_STREAM = "application/octet-stream".toMediaType()
        const val XPAN_FILE_URL = "https://pan.baidu.com/rest/2.0/xpan/file"
        const val XPAN_MULTIMEDIA_URL = "https://pan.baidu.com/rest/2.0/xpan/multimedia"
        const val PCS_SUPERFILE_URL = "https://d.pcs.baidu.com/rest/2.0/pcs/superfile2"
        // 百度会为应用自动建立此目录；直接放在应用目录可避免首次上传时父目录不存在。
        const val REMOTE_DIR = "/apps/砺行"
        const val UPLOAD_PART_BYTES = 4 * 1024 * 1024
        const val MAX_BACKUP_BYTES = 1024L * 1024 * 1024
        const val REFRESH_AHEAD_MILLIS = 5L * 60 * 1_000
    }
}
