package com.example.lixing.data.sync

import com.example.lixing.data.local.LiXingDatabase
import com.example.lixing.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 同步引擎：拉对端 → 合并 → 推本端。
 *
 * 云端布局是「每台设备只写自己的两个文件」（snapshot 基线 + jsonl 增量日志），
 * 所以读写天然零冲突、不需要锁；新设备接入时先拉对端快照建基线，再拉增量。
 *
 * 失败可恢复：任一环节出错都只记 error 并继续，游标只在成功后推进，
 * 下次同步从上次成功的位置继续，绝不半途写坏本地数据。
 */
@Singleton
class SyncEngine @Inject constructor(
    private val store: SyncLocalStore,
    private val identity: SyncIdentity,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /**
     * 跑一次完整同步。
     *
     * @param transport 由调用方注入（WebDAV / 测试用的假实现）
     */
    suspend fun sync(transport: SyncTransport): SyncReport =
        sync(transport, identity.deviceId())

    /** 测试与内部复用：显式指定本端设备 id，模拟不同设备。 */
    internal suspend fun sync(transport: SyncTransport, localDeviceId: String): SyncReport =
        withContext(io) {
            val files = runCatching { transport.list() }
                .onFailure { return@withContext SyncReport(errors = listOf("读取远端文件失败：${it.safeMessage()}")) }
                .getOrDefault(emptyList())
        val peers = SyncFiles.deviceIds(files).filter { it != localDeviceId }.sorted()
        val cursors = store.cursors().toMutableMap()

        val errors = mutableListOf<String>()
        var downloadedBytes = 0
        var appliedRows = 0
        var appliedTombstones = 0
        var skippedRows = 0

        for (peer in peers) {
            var cursor = cursors[peer] ?: 0L
            val hasBaseline = cursor > 0L

            // 1) 基线快照：只有还没接过这个设备的基线时才拉
            val snapshotName = SyncFiles.snapshot(peer)
            if (!hasBaseline && files.any { it.name == snapshotName }) {
                val text = transport.read(snapshotName).orEmpty()
                downloadedBytes += text.toByteArray(Charsets.UTF_8).size
                runCatching { json.decodeFromString<SyncSnapshot>(text) }
                    .onSuccess { snapshot ->
                        when {
                            snapshot.format != SYNC_FORMAT ->
                                errors += "$peer 的同步协议版本为 ${snapshot.format}（本机 $SYNC_FORMAT），已跳过"

                            snapshot.databaseVersion != LiXingDatabase.VERSION ->
                                errors += "$peer 的数据库版本为 ${snapshot.databaseVersion}，" +
                                    "与本机 ${LiXingDatabase.VERSION} 不一致，已跳过同步"

                            else -> {
                                val outcome = store.applyRemote(snapshot.rows, peer, localDeviceId)
                                appliedRows += outcome.appliedRows
                                appliedTombstones += outcome.appliedTombstones
                                skippedRows += outcome.skippedRows
                                errors += outcome.errors
                                cursor = maxOf(cursor, snapshot.clock, snapshot.rows.maxOfOrNull { it.clock } ?: 0L)
                            }
                        }
                    }
                    .onFailure { errors += "$peer 的快照无法解析：${it.safeMessage()}" }
            }

            // 2) 增量日志：只处理时钟大于游标的部分
            val logName = SyncFiles.log(peer)
            if (files.any { it.name == logName }) {
                val text = transport.read(logName).orEmpty()
                downloadedBytes += text.toByteArray(Charsets.UTF_8).size
                val rows = parseLog(text, errors)
                val pending = rows.filter { it.clock > cursor }
                if (pending.isNotEmpty()) {
                    val outcome = store.applyRemote(pending, peer, localDeviceId)
                    appliedRows += outcome.appliedRows
                    appliedTombstones += outcome.appliedTombstones
                    skippedRows += outcome.skippedRows
                    errors += outcome.errors
                    cursor = maxOf(cursor, pending.maxOf { it.clock })
                }
            }

            if (cursor != (cursors[peer] ?: 0L)) {
                store.saveCursor(peer, cursor)
                cursors[peer] = cursor
            }
        }

        // 3) 推本端：没有快照 / 日志膨胀时重写快照并截断日志，否则只追加增量
        val selfCursor = cursors[SELF_CURSOR_KEY] ?: -1L
        val clock = store.clock()
        val ownSnapshotName = SyncFiles.snapshot(localDeviceId)
        val ownLogName = SyncFiles.log(localDeviceId)
        val hasSnapshot = files.any { it.name == ownSnapshotName }
        val logSize = files.firstOrNull { it.name == ownLogName }?.sizeBytes ?: 0L
        // 没发过基线（新设备或刚恢复过备份）也要重写快照，否则历史数据永远推不上去
        val writeSnapshot = !hasSnapshot || logSize > COMPACT_LOG_BYTES || selfCursor < 0L

        val changes = store.readChangesSince(if (writeSnapshot) -1L else selfCursor)
        var uploadedBytes = 0
        var snapshotWritten = false
        var logCompacted = false

        if (writeSnapshot) {
            val snapshot = SyncSnapshot(
                databaseVersion = LiXingDatabase.VERSION,
                deviceId = localDeviceId,
                clock = clock,
                createdAtEpochMillis = System.currentTimeMillis(),
                rows = changes,
            )
            val text = json.encodeToString(snapshot)
            transport.write(ownSnapshotName, text)
            uploadedBytes += text.toByteArray(Charsets.UTF_8).size
            if (hasSnapshot) {
                transport.write(ownLogName, "")
                logCompacted = true
            }
            snapshotWritten = true
        } else if (changes.isNotEmpty()) {
            val lines = changes.map { json.encodeToString(it) }
            transport.append(ownLogName, lines)
            uploadedBytes += lines.sumOf { it.toByteArray(Charsets.UTF_8).size }
        }

        store.saveCursor(SELF_CURSOR_KEY, clock)

        SyncReport(
            pushedRows = changes.count { !it.deleted },
            pushedTombstones = changes.count { it.deleted },
            appliedRows = appliedRows,
            appliedTombstones = appliedTombstones,
            skippedRows = skippedRows,
            uploadedBytes = uploadedBytes,
            downloadedBytes = downloadedBytes,
            snapshotWritten = snapshotWritten,
            logCompacted = logCompacted,
            peers = peers,
            errors = errors,
        )
    }

    private fun parseLog(text: String, errors: MutableList<String>): List<SyncRow> =
        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { line ->
                runCatching { json.decodeFromString<SyncRow>(line) }
                    .onFailure { errors += "跳过无法解析的日志行：${it.safeMessage()}" }
                    .getOrNull()
            }
            .toList()

    private fun Throwable.safeMessage(): String =
        message?.take(120)?.takeIf { it.isNotBlank() } ?: this::class.java.simpleName

    companion object {
        /** 日志超过这个体积就重写快照并截断，避免无限膨胀。 */
        const val COMPACT_LOG_BYTES = 512L * 1024
    }
}
