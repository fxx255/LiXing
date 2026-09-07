package com.example.lixing.data.sync

/**
 * 同步文件的读写抽象。
 *
 * 内核只依赖这个接口，WebDAV（坚果云等）是它的一个实现，
 * 这样同步逻辑可以脱离网络做单测。
 */
interface SyncTransport {

    /** 列出远端同步目录下的文件；不存在时返回空列表。 */
    suspend fun list(): List<SyncRemoteFile>

    /** 读取文件全文；文件不存在返回 null。 */
    suspend fun read(name: String): String?

    /** 覆盖写（用于快照与日志截断）。 */
    suspend fun write(name: String, content: String)

    /** 追加若干行（用于增量日志，避免每次重传整个文件）。 */
    suspend fun append(name: String, lines: List<String>)

    suspend fun delete(name: String)
}
