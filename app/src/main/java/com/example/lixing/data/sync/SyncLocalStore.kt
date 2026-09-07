package com.example.lixing.data.sync

import android.content.ContentValues
import android.database.SQLException
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.withTransaction
import com.example.lixing.data.backup.DbCell
import com.example.lixing.data.local.LiXingDatabase
import com.example.lixing.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** 应用远端变更的结果，用于统计与展示。 */
data class ApplyOutcome(
    val appliedRows: Int = 0,
    val appliedTombstones: Int = 0,
    val skippedRows: Int = 0,
    val errors: List<String> = emptyList(),
)

/**
 * 同步内核的本地存储层：只读/写「同步需要的那一层」，不碰业务 DAO。
 *
 * 三件事：
 * 1. 读本端变更（sync_modified_at > 游标 的行 + 墓碑），照片列不进信封；
 * 2. 应用远端变更（逐行交给 [SyncMerger] 判定，事务内一次写完）；
 * 3. 维护 Lamport 计数器与每台对端设备的游标。
 */
@Singleton
class SyncLocalStore @Inject constructor(
    private val database: LiXingDatabase,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) {

    private val db: SupportSQLiteDatabase get() = database.openHelper.writableDatabase

    // ---------------- 时钟 ----------------

    suspend fun clock(): Long = withContext(io) {
        db.query("SELECT `value` FROM `sync_clock` WHERE `id` = ${SyncTriggerInstaller.CLOCK_ROW_ID}")
            .use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
    }

    /** Lamport 规则：见到更大的时钟就把本端计数器追上去。 */
    private fun bumpClockTo(value: Long) {
        if (value <= 0) return
        db.execSQL(
            "UPDATE `sync_clock` SET `value` = max(`value`, ?) WHERE `id` = ${SyncTriggerInstaller.CLOCK_ROW_ID}",
            arrayOf(value),
        )
    }

    /** 置位期间触发器不记时钟、不写墓碑——合并远端数据与恢复备份都靠它。 */
    suspend fun withApplyingGuard(block: suspend () -> Unit) {
        withContext(io) {
            setApplying(true)
            try {
                block()
            } finally {
                setApplying(false)
            }
        }
    }

    private fun setApplying(applying: Boolean) {
        db.execSQL(
            "INSERT OR REPLACE INTO `sync_clock` (`id`, `value`) VALUES (${SyncTriggerInstaller.APPLYING_ROW_ID}, ?)",
            arrayOf(if (applying) 1 else 0),
        )
    }

    // ---------------- 读本端变更 ----------------

    /** 读取时钟大于 cursor 的全部变更（含墓碑）。cursor 传 -1 即全量，用于写基线快照。 */
    suspend fun readChangesSince(cursor: Long): List<SyncRow> = withContext(io) {
        val rows = mutableListOf<SyncRow>()
        SYNC_TABLE_SPECS.forEach { spec -> rows += readRows(spec, cursor) }
        rows += readTombstones(cursor)
        rows.sortedWith(
            compareBy(
                { row -> SYNC_TABLE_SPECS.indexOfFirst { it.name == row.table }.let { if (it < 0) Int.MAX_VALUE else it } },
                { it.clock },
            ),
        )
    }

    private fun readRows(spec: SyncTableSpec, cursor: Long): List<SyncRow> {
        val sql = "SELECT * FROM `${spec.name}` WHERE `$SYNC_CLOCK_COLUMN` > ?"
        return buildList {
            db.query(sql, arrayOf(cursor)).use { c ->
                val clockIndex = c.getColumnIndexOrThrow(SYNC_CLOCK_COLUMN)
                val pkIndex = c.getColumnIndexOrThrow(spec.pkColumn)
                while (c.moveToNext()) {
                    val columns = linkedMapOf<String, DbCell>()
                    for (i in 0 until c.columnCount) {
                        if (i == clockIndex || i == pkIndex) continue
                        val name = c.getColumnName(i)
                        if (name in spec.skippedColumns) continue
                        columns[name] = DbCell.from(c, i)
                    }
                    add(
                        SyncRow(
                            table = spec.name,
                            rowId = rowIdOf(c, pkIndex, spec),
                            clock = c.getLong(clockIndex),
                            deleted = false,
                            columns = columns,
                        ),
                    )
                }
            }
        }
    }

    private fun readTombstones(cursor: Long): List<SyncRow> = buildList {
        db.query(
            "SELECT `table_name`, `row_id`, `deleted_at` FROM `sync_tombstone` WHERE `deleted_at` > ?",
            arrayOf(cursor),
        ).use { c ->
            while (c.moveToNext()) {
                add(
                    SyncRow(
                        table = c.getString(0),
                        rowId = c.getString(1),
                        clock = c.getLong(2),
                        deleted = true,
                    ),
                )
            }
        }
    }

    private fun rowIdOf(cursor: android.database.Cursor, index: Int, spec: SyncTableSpec): String =
        if (spec.pkIsText()) cursor.getString(index) else cursor.getLong(index).toString()

    // ---------------- 应用远端变更 ----------------

    suspend fun applyRemote(
        rows: List<SyncRow>,
        remoteDeviceId: String,
        localDeviceId: String,
    ): ApplyOutcome = withContext(io) {
        var appliedRows = 0
        var appliedTombstones = 0
        var skipped = 0
        val errors = mutableListOf<String>()
        val unknown = rows.map { it.table }.distinct().filter { it !in SYNC_TABLE_BY_NAME }
        if (unknown.isNotEmpty()) {
            errors += "忽略未知数据表：${unknown.joinToString()}"
        }

        database.withTransaction {
            setApplying(true)
            try {
                val grouped = rows.groupBy { it.table }
                // 删除按子→父，写入按父→子，外键才不会在半途断掉
                for (spec in SYNC_TABLE_SPECS.asReversed()) {
                    grouped[spec.name].orEmpty().filter { it.deleted }.forEach { row ->
                        when (decide(spec, row, localDeviceId, remoteDeviceId)) {
                            SyncMerger.Decision.APPLY_DELETE -> {
                                deleteRow(spec, row, errors)
                                appliedTombstones++
                            }
                            SyncMerger.Decision.APPLY_UPSERT -> {
                                // 远端把删掉的行又建回来了
                                if (upsertRow(spec, row, errors)) appliedRows++
                                else skipped++
                            }
                            SyncMerger.Decision.KEEP_LOCAL -> skipped++
                        }
                    }
                }
                for (spec in SYNC_TABLE_SPECS) {
                    grouped[spec.name].orEmpty().filter { !it.deleted }
                        .sortedBy { it.clock }
                        .forEach { row ->
                            when (decide(spec, row, localDeviceId, remoteDeviceId)) {
                                SyncMerger.Decision.APPLY_UPSERT -> {
                                    if (upsertRow(spec, row, errors)) appliedRows++
                                    else skipped++
                                }
                                SyncMerger.Decision.APPLY_DELETE -> {
                                    deleteRow(spec, row, errors)
                                    appliedTombstones++
                                }
                                SyncMerger.Decision.KEEP_LOCAL -> skipped++
                            }
                        }
                }
                bumpClockTo(rows.maxOfOrNull { it.clock } ?: 0L)
            } finally {
                setApplying(false)
            }
        }
        ApplyOutcome(
            appliedRows = appliedRows,
            appliedTombstones = appliedTombstones,
            skippedRows = skipped,
            errors = errors,
        )
    }

    private fun decide(
        spec: SyncTableSpec,
        row: SyncRow,
        localDeviceId: String,
        remoteDeviceId: String,
    ): SyncMerger.Decision = SyncMerger.decide(
        local = localState(spec, row.rowId),
        remote = row,
        localDeviceId = localDeviceId,
        remoteDeviceId = remoteDeviceId,
    )

    private fun localState(spec: SyncTableSpec, rowId: String): SyncMerger.LocalState {
        val pk = spec.pkValue(rowId)
        val rowClock = db.query(
            "SELECT `$SYNC_CLOCK_COLUMN` FROM `${spec.name}` WHERE `${spec.pkColumn}` = ? LIMIT 1",
            arrayOf(pk),
        ).use { c -> if (c.moveToFirst()) c.getLong(0) else null }
        val tombstoneClock = db.query(
            "SELECT `deleted_at` FROM `sync_tombstone` WHERE `table_name` = ? AND `row_id` = ? LIMIT 1",
            arrayOf(spec.name, rowId),
        ).use { c -> if (c.moveToFirst()) c.getLong(0) else null }
        return SyncMerger.LocalState(rowClock, tombstoneClock)
    }

    private fun upsertRow(spec: SyncTableSpec, row: SyncRow, errors: MutableList<String>): Boolean {
        val pk = spec.pkValue(row.rowId)
        val values = ContentValues(row.columns.size + 2)
        row.columns.forEach { (column, cell) ->
            if (column in spec.skippedColumns || column == spec.pkColumn) return@forEach
            cell.put(values, column)
        }
        if (pk is Long) values.put(spec.pkColumn, pk) else values.put(spec.pkColumn, pk as String)
        values.put(SYNC_CLOCK_COLUMN, row.clock)
        return try {
            val updated = db.update(
                spec.name,
                SQLiteDatabase.CONFLICT_NONE,
                values,
                "`${spec.pkColumn}` = ?",
                arrayOf(pk),
            )
            if (updated == 0) {
                db.insert(spec.name, SQLiteDatabase.CONFLICT_ABORT, values)
            }
            db.delete(
                "sync_tombstone",
                "`table_name` = ? AND `row_id` = ?",
                arrayOf(spec.name, row.rowId),
            )
            true
        } catch (e: SQLException) {
            // 唯一索引冲突、外键缺失等：跳过这一行，不让它拖垮整次同步
            errors += "${spec.name}/${row.rowId} 写入失败：${e.message?.take(80) ?: e::class.java.simpleName}"
            false
        }
    }

    private fun deleteRow(spec: SyncTableSpec, row: SyncRow, errors: MutableList<String>) {
        val pk = spec.pkValue(row.rowId)
        try {
            db.delete(spec.name, "`${spec.pkColumn}` = ?", arrayOf(pk))
        } catch (e: SQLException) {
            errors += "${spec.name}/${row.rowId} 删除失败：${e.message?.take(80) ?: e::class.java.simpleName}"
        }
        db.execSQL(
            "INSERT OR REPLACE INTO `sync_tombstone` (`table_name`, `row_id`, `deleted_at`) VALUES (?, ?, ?)",
            arrayOf(spec.name, row.rowId, row.clock),
        )
    }

    // ---------------- 对端游标 ----------------

    suspend fun cursors(): Map<String, Long> = withContext(io) {
        buildMap {
            db.query("SELECT `peer_id`, `cursor` FROM `sync_peer`").use { c ->
                while (c.moveToNext()) put(c.getString(0), c.getLong(1))
            }
        }
    }

    suspend fun saveCursor(peerId: String, cursor: Long) = withContext(io) {
        db.execSQL(
            "INSERT OR REPLACE INTO `sync_peer` (`peer_id`, `cursor`) VALUES (?, ?)",
            arrayOf(peerId, cursor),
        )
    }

    suspend fun forgetPeers() = withContext(io) {
        db.execSQL("DELETE FROM `sync_peer`")
    }

    /**
     * 恢复备份之后调用：整库被替换过，旧的游标与时钟都不再有意义。
     * 游标归零 → 下次同步会重新拉对端基线、并重新发布本端基线。
     */
    suspend fun resetAfterRestore() = withContext(io) {
        forgetPeers()
        saveCursor(SELF_CURSOR_KEY, -1L)
    }
}
