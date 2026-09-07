package com.example.lixing.data.sync.webdav

import com.example.lixing.data.sync.SyncRemoteFile
import com.example.lixing.data.sync.SyncTransport
import com.example.lixing.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 把 WebDAV 接到同步内核的 [SyncTransport] 上。
 *
 * 内核只认这五个动作，所以这一层很薄——真正的复杂度和错误分类都在 [WebDavClient] 里。
 */
class WebDavTransport internal constructor(
    private val client: WebDavClient,
) : SyncTransport {

    /** 目录本身会出现在 PROPFIND 结果里，过滤掉，只留文件。 */
    override suspend fun list(): List<SyncRemoteFile> =
        client.list()
            .filter { !it.isDirectory }
            .map { SyncRemoteFile(it.name, it.sizeBytes) }

    override suspend fun read(name: String): String? = client.get(name)?.content

    override suspend fun write(name: String, content: String) {
        client.ensureFolder()
        client.put(name, content)
    }

    override suspend fun append(name: String, lines: List<String>) = client.appendLines(name, lines)

    override suspend fun delete(name: String) = client.delete(name)
}

/**
 * 造 [WebDavTransport] 的工厂。
 *
 * 配置来自「用户在设置里填的凭据」，不是编译期常量，所以不能让 Hilt 直接注入 client，
 * 得由调用方（同步入口 / 设置页的连接测试）显式建。
 */
@Singleton
class WebDavTransportFactory @Inject constructor(
    private val credentials: WebDavCredentialStore,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) {

    fun client(config: WebDavConfig): WebDavClient = WebDavClient(config, io)

    fun transport(config: WebDavConfig): WebDavTransport = WebDavTransport(client(config))

    /** 用已保存的凭据建 transport；用户还没配过（或填了一半）返回 null。 */
    fun fromStoredConfig(): WebDavTransport? =
        credentials.load()?.takeIf { it.isComplete }?.let { transport(it) }

    /** 连接测试：只 PROPFIND 一次，不写任何文件。返回 null 表示通。 */
    suspend fun checkConnection(config: WebDavConfig): WebDavException? = client(config).check()
}
