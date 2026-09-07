package com.example.lixing.data.sync

import com.example.lixing.data.backup.DbCell
import kotlinx.serialization.Serializable

/** 同步协议版本。只有破坏性变更才 +1；两端不一致时拒绝同步而不是写坏数据。 */
const val SYNC_FORMAT = 1

/** 每张表统一的同步列：Lamport 逻辑时钟。 */
const val SYNC_CLOCK_COLUMN = "sync_modified_at"

/**
 * 表在同步里的元信息。
 *
 * @property pkColumn 定位一行用的主键列（14 张 UUID 表是 id，day_record 是 date，两个单例表是 id）。
 * @property skippedColumns 不参与同步的列——目前只有照片路径：
 *   照片体积占备份 99.3%，日常增量不搬；且对端的空值不能反过来清空本端照片。
 */
data class SyncTableSpec(
    val name: String,
    val pkColumn: String,
    val skippedColumns: Set<String> = emptySet(),
)

/**
 * 一行（或一个墓碑）的同步信封。
 *
 * columns 用备份格式里的 [DbCell] 承载，保留 SQLite 原始存储类型，
 * 避免枚举/时间/布尔在 JSON 往返中变形。
 */
@Serializable
data class SyncRow(
    val table: String,
    val rowId: String,
    val clock: Long,
    val deleted: Boolean = false,
    val columns: Map<String, DbCell> = emptyMap(),
)

/** 基线快照：新设备首次接入时直接建基线，不必重放几千条日志。 */
@Serializable
data class SyncSnapshot(
    val format: Int = SYNC_FORMAT,
    val databaseVersion: Int,
    val deviceId: String,
    val clock: Long,
    val createdAtEpochMillis: Long,
    val rows: List<SyncRow>,
)

/** 远端文件。size 用于统计流量（盯免费额度）。 */
data class SyncRemoteFile(val name: String, val sizeBytes: Long)

/**
 * 游标存在本地 sync_peer 表里（这张表不参与传输）：
 * - key = 对端设备 id → 已消费到它的哪个时钟；
 * - key = [SELF_CURSOR_KEY] → 本端已推送到哪个时钟，默认 -1 表示"还没发过基线"。
 */
const val SELF_CURSOR_KEY = "self"

/** 一次同步的结果，供 UI 展示与问题排查。 */
data class SyncReport(
    val pushedRows: Int = 0,
    val pushedTombstones: Int = 0,
    val appliedRows: Int = 0,
    val appliedTombstones: Int = 0,
    val skippedRows: Int = 0,
    val uploadedBytes: Int = 0,
    val downloadedBytes: Int = 0,
    val snapshotWritten: Boolean = false,
    val logCompacted: Boolean = false,
    val peers: List<String> = emptyList(),
    val errors: List<String> = emptyList(),
) {
    val isSuccess: Boolean get() = errors.isEmpty()
    val changedRows: Int get() = appliedRows + appliedTombstones
}

/**
 * 云端布局：每台设备只写自己的两个文件，只读别人的。
 * 传输层天然零冲突——不需要锁，A 永远不碰 B 的文件。
 */
object SyncFiles {
    fun snapshot(deviceId: String): String = "device-$deviceId.snapshot"

    fun log(deviceId: String): String = "device-$deviceId.jsonl"

    private val SNAPSHOT_RE = Regex("^device-(.+)\\.snapshot$")
    private val LOG_RE = Regex("^device-(.+)\\.jsonl$")

    /** 从远端文件列表里认出所有设备 id（含自己）。 */
    fun deviceIds(files: List<SyncRemoteFile>): Set<String> =
        files.mapNotNull { it.name }
            .mapNotNull { name ->
                SNAPSHOT_RE.matchEntire(name)?.groupValues?.get(1)
                    ?: LOG_RE.matchEntire(name)?.groupValues?.get(1)
            }
            .toSet()
}
