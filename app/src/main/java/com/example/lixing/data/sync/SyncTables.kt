package com.example.lixing.data.sync

/**
 * 参与同步的 17 张业务表。
 *
 * 顺序即「父表 → 子表」：应用远端变更时按此顺序写入（外键父行先就位），
 * 删除时反向（子表先删，避免 DROP/DELETE 父行触发级联误伤）。
 *
 * 与 VersionedBackupRepository.TABLES 一致，保证备份与同步覆盖同一批数据。
 */
internal val SYNC_TABLE_SPECS: List<SyncTableSpec> = listOf(
    SyncTableSpec("study_plan", "id"),
    SyncTableSpec("phase", "id"),
    SyncTableSpec("subject", "id"),
    SyncTableSpec("time_slot", "id"),
    SyncTableSpec("task_template", "id"),
    SyncTableSpec("daily_task", "id", setOf("checkin_photo")),
    SyncTableSpec("point_ledger", "id"),
    SyncTableSpec("focus_session", "id"),
    SyncTableSpec("assistant_conversation", "id"),
    SyncTableSpec("assistant_message", "id", setOf("image_paths")),
    SyncTableSpec("achievement", "id"),
    SyncTableSpec("commitment", "id"),
    SyncTableSpec("meal_record", "id", setOf("photo_path")),
    SyncTableSpec("english_entry", "id"),
    SyncTableSpec("day_record", "date"),
    SyncTableSpec("check_in_streak", "id"),
    SyncTableSpec("user_profile", "id"),
)

internal val SYNC_TABLE_BY_NAME: Map<String, SyncTableSpec> =
    SYNC_TABLE_SPECS.associateBy { it.name }

/** 主键仍是整数自然键的三张表：day_record 用日期，两个单例表固定 id=1。 */
private val INTEGER_PK_TABLES = setOf("day_record", "check_in_streak", "user_profile")

internal fun SyncTableSpec.pkIsText(): Boolean = name !in INTEGER_PK_TABLES

/** 行 id 一律以字符串搬运（墓碑表 row_id 是 TEXT），写回时按主键真实类型还原。 */
internal fun SyncTableSpec.pkValue(rowId: String): Any =
    if (pkIsText()) rowId else (rowId.toLongOrNull() ?: 0L)
