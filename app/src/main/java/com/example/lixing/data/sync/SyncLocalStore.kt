package com.example.lixing.data.sync

import android.content.ContentValues
import android.database.SQLException
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.withTransaction
import com.example.lixing.data.backup.DbCell
import com.example.lixing.data.local.LiXingDatabase
import com.example.lixing.data.local.entity.PlanningSyncConflictEntity
import com.example.lixing.data.local.entity.DayRecordEntity
import com.example.lixing.domain.settle.DayStatsCalculator
import com.example.lixing.domain.model.TaskStatus
import com.example.lixing.domain.rules.LevelRules
import com.example.lixing.domain.rules.PointRules
import com.example.lixing.domain.settle.StreakCalculator
import com.example.lixing.domain.time.StudyClock
import com.example.lixing.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.time.LocalDate
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

    suspend fun recordPlanningConflict(peer: String, rows: List<SyncRow>, reason: String) = withContext(io) {
        val planning = rows.filter { it.table in setOf("scheduled_task", "plan_day_policy", "plan_change_set",
            "learning_goal", "learning_unit", "study_resource", "task_content_progress", "manual_study_time") }
        if (planning.isEmpty()) return@withContext
        val payload = Json.encodeToString(rows)
        val id = MessageDigest.getInstance("SHA-256")
            .digest("$peer:$payload".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        val summary = planning.take(4).joinToString("、") { row ->
            val title = row.columns["title"]?.value ?: row.columns["name"]?.value ?: row.rowId
            "${row.table}：$title"
        }
        database.planningDao().upsertSyncConflict(PlanningSyncConflictEntity(
            id = id, peerId = peer, reason = reason.take(300), summary = summary,
            rowsJson = payload, detectedAt = System.currentTimeMillis(),
        ))
    }

    suspend fun resolvePlanningConflicts(peer: String) = withContext(io) {
        database.planningDao().resolveSyncConflictsForPeer(peer, System.currentTimeMillis())
    }

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
            database.withTransaction {
                setApplying(true)
                try {
                    block()
                } finally {
                    setApplying(false)
                }
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
    suspend fun readChangesSince(cursor: Long): List<SyncRow> = database.withTransaction {
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
                            originDeviceId = originOf(spec.name, rowIdOf(c, pkIndex, spec), c.getLong(clockIndex)).orEmpty(),
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
                        originDeviceId = originOf(c.getString(0), c.getString(1), c.getLong(2)).orEmpty(),
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
        achieveThreshold: Float = 0.6f,
        studyToday: LocalDate = StudyClock().today(),
        rescueCardsPerMonth: Int = 1,
    ): ApplyOutcome = withContext(io) {
        var appliedRows = 0
        var appliedTombstones = 0
        var skipped = 0
        val errors = mutableListOf<String>()
        val affectedDates = rows.mapNotNull { row ->
            val epochDay = when (row.table) {
                "daily_task", "point_ledger" -> row.columns["date"]?.value?.toLongOrNull()
                    ?: if (row.deleted) db.query(
                        "SELECT `date` FROM `${row.table}` WHERE `id` = ?",
                        arrayOf(localId(row.table, row.rowId)),
                    ).use { if (it.moveToFirst()) it.getLong(0) else null } else null
                "day_record" -> row.rowId.toLongOrNull()
                else -> null
            }
            epochDay?.let(LocalDate::ofEpochDay)
        }.toSet()
        val containsPlanning = rows.any { it.table in setOf(
            "scheduled_task", "plan_day_policy", "plan_change_set", "learning_goal", "learning_unit", "study_resource",
            "task_content_progress", "manual_study_time",
        ) }
        val unknown = rows.map { it.table }.distinct().filter { it !in SYNC_TABLE_BY_NAME }
        if (unknown.isNotEmpty()) {
            errors += "忽略未知数据表：${unknown.joinToString()}"
        }

        database.withTransaction {
            setApplying(true)
            try {
                preflightPlanningRows(rows)
                val grouped = rows.groupBy { it.table }
                // 删除按子→父，写入按父→子，外键才不会在半途断掉
                for (spec in SYNC_TABLE_SPECS.asReversed()) {
                    grouped[spec.name].orEmpty().filter { it.deleted }.forEach { incoming ->
                        val row = normalizeIncoming(spec, incoming, remoteDeviceId)
                        when (decide(spec, row, localDeviceId, remoteDeviceId)) {
                            SyncMerger.Decision.APPLY_DELETE -> {
                                checkPlanningConflict(spec, row)
                                if (deleteRow(spec, row, errors)) appliedTombstones++ else {
                                    if (containsPlanning) error(errors.lastOrNull() ?: "规划批次删除失败")
                                    skipped++
                                }
                            }
                            SyncMerger.Decision.APPLY_UPSERT -> {
                                // 远端把删掉的行又建回来了
                                checkPlanningConflict(spec, row)
                                if (upsertRow(spec, row, errors)) appliedRows++ else {
                                    if (containsPlanning) error(errors.lastOrNull() ?: "规划批次写入失败")
                                    skipped++
                                }
                            }
                            SyncMerger.Decision.KEEP_LOCAL -> skipped++
                        }
                    }
                }
                for (spec in SYNC_TABLE_SPECS) {
                    grouped[spec.name].orEmpty().filter { !it.deleted }
                        .sortedBy { it.clock }
                        .forEach { incoming ->
                            val row = normalizeIncoming(spec, incoming, remoteDeviceId)
                            when (decide(spec, row, localDeviceId, remoteDeviceId)) {
                                SyncMerger.Decision.APPLY_UPSERT -> {
                                    checkPlanningConflict(spec, row)
                                    if (upsertRow(spec, row, errors)) appliedRows++ else {
                                        if (containsPlanning) error(errors.lastOrNull() ?: "规划批次写入失败")
                                        skipped++
                                    }
                                }
                                SyncMerger.Decision.APPLY_DELETE -> {
                                    checkPlanningConflict(spec, row)
                                    if (deleteRow(spec, row, errors)) appliedTombstones++ else {
                                        if (containsPlanning) error(errors.lastOrNull() ?: "规划批次删除失败")
                                        skipped++
                                    }
                                }
                                SyncMerger.Decision.KEEP_LOCAL -> skipped++
                            }
                        }
                }
                if (grouped.containsKey("english_review_log")) {
                    val affected = mutableSetOf<String>()
                    rows.filter { it.table == "english_review_log" }.forEach { row ->
                        row.columns["entry_id"]?.value?.let { affected += it }
                    }
                    affected.forEach { com.example.lixing.data.repository.reconcileEnglishMemory(database.englishEntryDao(), it) }
                }
                // day_record is a projection. A stale remote percentage must not outlive
                // the task rows that were just reconciled in this same transaction.
                val refundedRescues = mutableMapOf<Int, Int>()
                for (date in affectedDates) {
                    val tasks = database.dailyTaskDao().getTasksOfDay(date)
                    val current = database.dayRecordDao().getRecord(date)
                    if (tasks.isEmpty() && current == null) continue
                    // A different device may already have charged a missed penalty before
                    // an offline same-day check-in arrived. A genuine next-day makeup keeps
                    // its original missed penalty, as in the normal check-in flow.
                    val completedIds = tasks.filter {
                        (it.status == TaskStatus.DONE || it.status == TaskStatus.PARTIAL) && !it.isMakeup
                    }.map { it.id }
                    if (completedIds.isNotEmpty()) {
                        val keys = completedIds.map(PointRules::keyMissed)
                        database.gamificationDao().deleteLedgerByDedupeKeys(keys)
                    }
                    val stats = DayStatsCalculator.calculate(tasks)
                    val achieved = current?.isDayOff != true && stats.isAchieved(achieveThreshold)
                    val rescueNoLongerNeeded = current?.usedRescueCard == true && achieved &&
                        DayStatsCalculator.calculate(tasks.map { task ->
                            if (task.isMakeup && task.status.isEngaged) {
                                task.copy(status = TaskStatus.MISSED, actualValue = 0)
                            } else task
                        }).isAchieved(achieveThreshold)
                    if (rescueNoLongerNeeded) {
                        val month = StreakCalculator.monthKeyOf(date)
                        refundedRescues[month] = (refundedRescues[month] ?: 0) + 1
                    }
                    database.dayRecordDao().upsert((current ?: DayRecordEntity(date = date)).copy(
                        totalTasks = stats.totalTasks,
                        doneTasks = stats.doneTasks,
                        partialTasks = stats.partialTasks,
                        missedTasks = stats.missedTasks,
                        skippedTasks = stats.skippedTasks,
                        completionRate = stats.completionRate,
                        focusMinutes = stats.focusMinutes,
                        pointsEarned = database.gamificationDao().getPointsOfDay(date),
                        isAchieved = achieved,
                        isFullDay = !current.let { it?.isDayOff ?: false } && stats.isFullDay,
                        usedRescueCard = current?.usedRescueCard == true && !rescueNoLongerNeeded,
                    ))
                }
                if (affectedDates.isNotEmpty() || grouped.containsKey("point_ledger") || grouped.containsKey("user_profile")) {
                    val gamification = database.gamificationDao()
                    gamification.getProfile()?.let { profile ->
                        val total = gamification.getTotalPoints().coerceAtLeast(0)
                        val level = LevelRules.levelOf(total)
                        gamification.upsertProfile(profile.copy(
                            totalPoints = total,
                            level = level,
                            title = LevelRules.titleOf(level),
                        ))
                    }
                }
                if (affectedDates.isNotEmpty() || grouped.containsKey("check_in_streak")) {
                    val gamification = database.gamificationDao()
                    gamification.getStreak()?.let { current ->
                        val records = database.dayRecordDao().getRecordsBetween(studyToday.minusDays(399), studyToday)
                        val rebuilt = StreakCalculator.rebuildCurrent(records, studyToday)
                        gamification.upsertStreak(current.copy(
                            currentStreak = rebuilt.days,
                            longestStreak = maxOf(current.longestStreak, rebuilt.days),
                            lastAchievedDate = rebuilt.lastContinuousDate,
                            totalAchievedDays = database.dayRecordDao().countAchievedDays(),
                            rescueCardsLeft = refundedRescues[current.rescueCardsMonth]?.let { refunded ->
                                (current.rescueCardsLeft + refunded).coerceAtMost(rescueCardsPerMonth)
                            } ?: current.rescueCardsLeft,
                        ))
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
    ): SyncMerger.Decision {
        val usual = SyncMerger.decide(
            local = localState(spec, row.rowId), remote = row,
            localDeviceId = localDeviceId, remoteDeviceId = remoteDeviceId,
        )
        if (spec.name != "daily_task" || row.deleted) return usual
        val remote = TaskExecutionSyncPolicy.State(
            status = row.columns["status"]?.value.orEmpty(),
            actualValue = row.columns["actual_value"]?.value?.toIntOrNull() ?: 0,
            checked = row.columns["checked_at"]?.value != null,
            skipReason = row.columns["skip_reason"]?.value.orEmpty(),
        )
        val local = db.query(
            "SELECT `status`, `actual_value`, `checked_at`, `skip_reason` FROM `daily_task` WHERE `id` = ?",
            arrayOf(row.rowId),
        ).use { cursor ->
            if (!cursor.moveToFirst()) null else TaskExecutionSyncPolicy.State(
                cursor.getString(0), cursor.getInt(1), !cursor.isNull(2), cursor.getString(3).orEmpty(),
            )
        }
        return TaskExecutionSyncPolicy.decide(local, remote, usual)
    }

    /** A remote plan cannot replace an execution snapshot that this device already started. */
    private fun checkPlanningConflict(spec: SyncTableSpec, row: SyncRow) {
        if (!row.deleted && spec.name in setOf("task_content_progress", "manual_study_time")) {
            val predecessor = row.columns["supersedes_id"]?.value
            if (predecessor != null) {
                val competing = db.query(
                    "SELECT `id` FROM `${spec.name}` WHERE `supersedes_id` = ? AND `id` != ? LIMIT 1",
                    arrayOf(predecessor, row.rowId),
                ).use { it.moveToFirst() }
                if (competing) error("${spec.name} 的同一记录出现并发更正；本轮同步已回滚")
            } else if (spec.name == "task_content_progress") {
                val taskId = row.columns["daily_task_id"]?.value
                if (taskId != null) {
                    val competing = db.query(
                        "SELECT `id` FROM `task_content_progress` WHERE `daily_task_id` = ? AND `supersedes_id` IS NULL AND `id` != ? LIMIT 1",
                        arrayOf(taskId, row.rowId),
                    ).use { it.moveToFirst() }
                    if (competing) error("该任务的内容完成记录出现并发初始提交；本轮同步已回滚")
                }
            }
        }
        if (spec.name == "scheduled_task") {
            val local = db.query("SELECT * FROM `scheduled_task` WHERE `id` = ? LIMIT 1", arrayOf(row.rowId))
                .use { cursor ->
                    if (!cursor.moveToFirst()) null else (0 until cursor.columnCount).associate {
                        cursor.getColumnName(it) to DbCell.from(cursor, it)
                    }
                }
            val date = row.columns["study_date"]?.value ?: local?.get("study_date")?.value
            val template = row.columns["source_template_id"]?.value ?: local?.get("source_template_id")?.value
            val taskIds = mutableSetOf<String>()
            db.query("SELECT `id` FROM `daily_task` WHERE `schedule_id` = ?", arrayOf(row.rowId)).use { cursor ->
                while (cursor.moveToNext()) taskIds += cursor.getString(0)
            }
            if (date != null && template != null) {
                db.query("SELECT `id` FROM `daily_task` WHERE `date` = ? AND `template_id` = ?",
                    arrayOf(date, template)).use { cursor ->
                    while (cursor.moveToNext()) taskIds += cursor.getString(0)
                }
            }
            if (taskIds.any(::hasTaskExecution)) {
                val materialFields = listOf("study_date", "source_template_id", "subject_id", "time_slot_id",
                    "resource_id", "goal_id", "round_key", "title", "task_type", "target_type",
                    "target_value", "content_json", "planned_minutes", "start_time", "end_time", "state")
                val changed = row.deleted || local == null || materialFields.any { key ->
                    row.columns[key] != null && row.columns[key] != local[key]
                }
                if (changed) error("按日安排 ${row.rowId} 与本机已开始的任务冲突；本轮同步已回滚")
            }
        }
        if (spec.name == "daily_task") {
            val local = db.query("SELECT * FROM `daily_task` WHERE `id` = ? LIMIT 1", arrayOf(row.rowId))
                .use { cursor ->
                    if (!cursor.moveToFirst()) null else (0 until cursor.columnCount).associate {
                        cursor.getColumnName(it) to DbCell.from(cursor, it)
                    }
                }
            if (local?.get("schedule_id")?.value != null && hasTaskExecution(row.rowId)) {
                val fields = listOf("date", "template_id", "schedule_id", "subject_id", "time_slot_id",
                    "title", "target_type", "target_value", "content_json", "planned_minutes",
                    "scheduled_start", "scheduled_end", "status", "actual_value", "checked_at")
                if (row.deleted || fields.any { key -> row.columns[key] != null && row.columns[key] != local[key] }) {
                    error("每日任务 ${row.rowId} 已有本机执行记录；本轮同步已回滚")
                }
            }
        }
    }

    /** A same-or-older revision with different contents is a concurrent plan edit, regardless of LWW clock. */
    private fun preflightPlanningRows(rows: List<SyncRow>) {
        val fields = mapOf(
            "scheduled_task" to listOf("study_date", "source_template_id", "subject_id", "time_slot_id",
                "resource_id", "goal_id", "round_key", "title", "task_type", "target_type",
                "target_value", "content_json", "planned_minutes", "start_time", "end_time", "state", "is_locked"),
            "plan_day_policy" to listOf("windows_json", "max_planned_minutes", "is_locked", "reason"),
        )
        rows.filter { !it.deleted && it.table in fields }
            .groupBy { it.table to it.rowId }.values.map { group -> group.maxBy { it.clock } }
            .forEach { incoming ->
            val local = db.query("SELECT * FROM `${incoming.table}` WHERE `id` = ? LIMIT 1",
                arrayOf(incoming.rowId)).use { cursor ->
                if (!cursor.moveToFirst()) null else (0 until cursor.columnCount).associate {
                    cursor.getColumnName(it) to DbCell.from(cursor, it)
                }
            } ?: return@forEach
            val changed = fields.getValue(incoming.table).any { field ->
                incoming.columns[field] != null && incoming.columns[field] != local[field]
            }
            if (!changed) return@forEach
            val localRevision = local["revision"]?.value?.toLongOrNull() ?: 0L
            val remoteRevision = incoming.columns["revision"]?.value?.toLongOrNull() ?: 0L
            val localClock = local[SYNC_CLOCK_COLUMN]?.value?.toLongOrNull() ?: 0L
            val stale = remoteRevision < localRevision && incoming.clock <= localClock
            if (!stale && (remoteRevision <= localRevision || incoming.clock <= localClock)) {
                error("${incoming.table}/${incoming.rowId} 存在并发或过期的规划修改；本轮同步已回滚")
            }
        }
    }

    private fun hasTaskExecution(id: String): Boolean {
        val taskStarted = db.query(
            "SELECT `status`, `checked_at`, `actual_value`, `focused_minutes`, `skip_reason` FROM `daily_task` WHERE `id` = ?",
            arrayOf(id),
        ).use { cursor -> cursor.moveToFirst() && (
            cursor.getString(0) !in setOf("PENDING", "SKIPPED") ||
                cursor.getString(0) == "SKIPPED" && cursor.getString(4) != "PLAN_CANCELLED" ||
                !cursor.isNull(1) || cursor.getInt(2) > 0 || cursor.getInt(3) > 0) }
        if (taskStarted) return true
        return listOf("focus_session", "manual_study_time", "task_content_progress").any { table ->
            db.query("SELECT 1 FROM `$table` WHERE `daily_task_id` = ? LIMIT 1", arrayOf(id))
                .use { it.moveToFirst() }
        }
    }

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
        return SyncMerger.LocalState(rowClock, tombstoneClock, originOf(spec.name, rowId, maxOf(rowClock ?: 0, tombstoneClock ?: 0)))
    }

    private fun originOf(table: String, id: String, clock: Long): String? = db.query(
        "SELECT `origin` FROM `sync_row_version` WHERE `table_name` = ? AND `row_id` = ? AND `clock` = ?",
        arrayOf(table, id, clock),
    ).use { if (it.moveToFirst()) it.getString(0) else null }

    private fun recordOrigin(row: SyncRow) {
        db.execSQL("INSERT OR REPLACE INTO `sync_row_version` (`table_name`, `row_id`, `clock`, `origin`) VALUES (?, ?, ?, ?)",
            arrayOf<Any>(row.table, row.rowId, row.clock, row.originDeviceId))
    }

    /** Keep existing local IDs and photo paths; remember equivalent IDs for later edits/deletions. */
    private fun localId(table: String, id: String): String = db.query(
        "SELECT `local_id` FROM `sync_row_alias` WHERE `table_name` = ? AND `remote_id` = ?",
        arrayOf(table, id),
    ).use { if (it.moveToFirst()) it.getString(0) else id }

    private fun normalizeIncoming(spec: SyncTableSpec, incoming: SyncRow, peer: String): SyncRow {
        val columns = incoming.columns.toMutableMap()
        if (spec.name == "focus_session" || spec.name == "point_ledger") {
            columns["daily_task_id"]?.value?.let { id ->
                columns["daily_task_id"] = DbCell("s", localId("daily_task", id))
            }
        }
        if (spec.name == "point_ledger") {
            columns["dedupe_key"]?.value?.let { key ->
                val prefix = key.substringBefore(':')
                if (prefix in setOf("checkin", "over", "missed") && ':' in key) {
                    columns["dedupe_key"] = DbCell("s", "$prefix:${localId("daily_task", key.substringAfter(':'))}")
                }
            }
        }
        var id = localId(spec.name, incoming.rowId)
        if (!incoming.deleted) {
            val keys = when (spec.name) {
                "daily_task" -> listOf("date", "template_id")
                "achievement" -> listOf("code")
                "meal_record" -> listOf("date", "meal_type")
                "point_ledger" -> listOf("dedupe_key")
                else -> emptyList()
            }
            if (keys.isNotEmpty() && keys.all { columns[it]?.value != null }) {
                val equivalent = db.query(
                    "SELECT `${spec.pkColumn}` FROM `${spec.name}` WHERE ${keys.joinToString(" AND ") { "`$it` = ?" }} LIMIT 1",
                    keys.map { requireNotNull(columns[it]?.value) }.toTypedArray(),
                ).use { if (it.moveToFirst()) it.getString(0) else null }
                if (equivalent != null) id = equivalent
            }
        }
        if (id != incoming.rowId) {
            db.execSQL("INSERT OR REPLACE INTO `sync_row_alias` (`table_name`, `remote_id`, `local_id`) VALUES (?, ?, ?)",
                arrayOf(spec.name, incoming.rowId, id))
        }
        return incoming.copy(rowId = id, columns = columns, originDeviceId = incoming.originDeviceId.ifBlank { peer })
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
                // meal_record.photo_path is NOT NULL without a SQL default. Its local file is deliberately not transferred.
                if (spec.name == "meal_record") values.put("photo_path", "")
                db.insert(spec.name, SQLiteDatabase.CONFLICT_ABORT, values)
            }
            db.delete(
                "sync_tombstone",
                "`table_name` = ? AND `row_id` = ?",
                arrayOf(spec.name, row.rowId),
            )
            recordOrigin(row)
            true
        } catch (e: SQLException) {
            // 唯一索引冲突、外键缺失等：跳过这一行，不让它拖垮整次同步
            errors += "${spec.name}/${row.rowId} 写入失败：${e.message?.take(80) ?: e::class.java.simpleName}"
            false
        }
    }

    private fun deleteRow(spec: SyncTableSpec, row: SyncRow, errors: MutableList<String>): Boolean {
        val pk = spec.pkValue(row.rowId)
        try {
            db.delete(spec.name, "`${spec.pkColumn}` = ?", arrayOf(pk))
        } catch (e: SQLException) {
            errors += "${spec.name}/${row.rowId} 删除失败：${e.message?.take(80) ?: e::class.java.simpleName}"
            return false
        }
        db.execSQL(
            "INSERT OR REPLACE INTO `sync_tombstone` (`table_name`, `row_id`, `deleted_at`) VALUES (?, ?, ?)",
            arrayOf(spec.name, row.rowId, row.clock),
        )
        recordOrigin(row)
        return true
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
    suspend fun resetAfterRestore() = database.withTransaction {
            db.execSQL("DELETE FROM `sync_peer`")
            db.execSQL("DELETE FROM `sync_tombstone`")
            db.execSQL("DELETE FROM `sync_row_version`")
            db.execSQL("DELETE FROM `sync_row_alias`")
            db.execSQL("DELETE FROM `planning_sync_conflict`")
            val highest = SYNC_TABLE_SPECS.maxOf { spec ->
                db.query("SELECT COALESCE(MAX(`$SYNC_CLOCK_COLUMN`), 0) FROM `${spec.name}`")
                    .use { it.moveToFirst(); it.getLong(0) }
            }
            bumpClockTo(highest)
            db.execSQL("INSERT OR REPLACE INTO `sync_peer` (`peer_id`, `cursor`) VALUES (?, -1)", arrayOf(SELF_CURSOR_KEY))
    }
}
