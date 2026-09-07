package com.example.lixing.data.sync

import androidx.sqlite.db.SupportSQLiteDatabase
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 用 SQLite 触发器统一维护 Lamport 时钟与墓碑。
 *
 * 17 张表、几十个 @Update/@Upsert，手写时间戳必漏；触发器在数据库层兜住所有写入，DAO 一行不改。
 *
 * 三条约定：
 * 1. 每次改动先把 `sync_clock` 的计数器 +1，再用新值写回该行的 sync_modified_at；
 * 2. 删除写墓碑（sync_tombstone），删除才能同步；重新插入同一行时清掉墓碑；
 * 3. `sync_clock` 的 id=2 是「正在应用远端变更 / 正在恢复备份」标志位：
 *    置 1 时触发器不记时钟、不写墓碑。否则合并远端数据会被当成"本端又改了一次"，
 *    每次同步都互相推回去，形成无限乒乓。
 *
 * SQLite 默认 recursive_triggers = OFF，触发器体内改同一张表不会递归触发；
 * UPDATE 触发器又额外用 `OLD.sync_modified_at = NEW.sync_modified_at` 兜了一层，双保险。
 */
@Singleton
class SyncTriggerInstaller @Inject constructor() {

    fun install(db: SupportSQLiteDatabase) {
        // 全新安装时 Room 只建实体表，同步元表只在迁移里建过 → 这里兜底建
        db.execSQL("CREATE TABLE IF NOT EXISTS `sync_clock` (`id` INTEGER PRIMARY KEY NOT NULL, `value` INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `sync_tombstone` (`table_name` TEXT NOT NULL, `row_id` TEXT NOT NULL, `deleted_at` INTEGER NOT NULL, PRIMARY KEY (`table_name`, `row_id`))")
        db.execSQL("CREATE TABLE IF NOT EXISTS `sync_peer` (`peer_id` TEXT PRIMARY KEY NOT NULL, `cursor` INTEGER NOT NULL)")
        db.execSQL("INSERT OR IGNORE INTO `sync_clock` (`id`, `value`) VALUES (1, 1)")
        db.execSQL("INSERT OR IGNORE INTO `sync_clock` (`id`, `value`) VALUES (2, 0)")
        if (installed(db)) return
        SYNC_TABLE_SPECS.forEach { spec ->
            db.execSQL(insertTrigger(spec))
            db.execSQL(updateTrigger(spec))
            db.execSQL(deleteTrigger(spec))
        }
    }

    /** 每次开库都建一遍没必要，已齐就直接跳过。 */
    private fun installed(db: SupportSQLiteDatabase): Boolean {
        val expected = SYNC_TABLE_SPECS.size * 3
        val count = db.query("SELECT COUNT(*) FROM sqlite_master WHERE type = 'trigger' AND substr(name, 1, 6) = 'sync_a'")
            .use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
        return count >= expected
    }

    private fun insertTrigger(spec: SyncTableSpec): String = """
        CREATE TRIGGER IF NOT EXISTS `sync_ai_${spec.name}`
        AFTER INSERT ON `${spec.name}`
        BEGIN
            UPDATE `sync_clock` SET `value` = `value` + 1
                WHERE `id` = 1 AND (SELECT `value` FROM `sync_clock` WHERE `id` = 2) = 0;
            DELETE FROM `sync_tombstone`
                WHERE `table_name` = '${spec.name}'
                  AND `row_id` = CAST(NEW.`${spec.pkColumn}` AS TEXT)
                  AND (SELECT `value` FROM `sync_clock` WHERE `id` = 2) = 0;
            UPDATE `${spec.name}` SET `$SYNC_CLOCK_COLUMN` =
                    (SELECT `value` FROM `sync_clock` WHERE `id` = 1)
                WHERE `${spec.pkColumn}` = NEW.`${spec.pkColumn}`
                  AND (SELECT `value` FROM `sync_clock` WHERE `id` = 2) = 0;
        END
    """.trimIndent()

    private fun updateTrigger(spec: SyncTableSpec): String = """
        CREATE TRIGGER IF NOT EXISTS `sync_au_${spec.name}`
        AFTER UPDATE ON `${spec.name}`
        BEGIN
            UPDATE `sync_clock` SET `value` = `value` + 1
                WHERE `id` = 1
                  AND (SELECT `value` FROM `sync_clock` WHERE `id` = 2) = 0
                  AND OLD.`$SYNC_CLOCK_COLUMN` = NEW.`$SYNC_CLOCK_COLUMN`;
            UPDATE `${spec.name}` SET `$SYNC_CLOCK_COLUMN` =
                    (SELECT `value` FROM `sync_clock` WHERE `id` = 1)
                WHERE `${spec.pkColumn}` = OLD.`${spec.pkColumn}`
                  AND (SELECT `value` FROM `sync_clock` WHERE `id` = 2) = 0
                  AND OLD.`$SYNC_CLOCK_COLUMN` = NEW.`$SYNC_CLOCK_COLUMN`;
        END
    """.trimIndent()

    private fun deleteTrigger(spec: SyncTableSpec): String = """
        CREATE TRIGGER IF NOT EXISTS `sync_ad_${spec.name}`
        AFTER DELETE ON `${spec.name}`
        BEGIN
            UPDATE `sync_clock` SET `value` = `value` + 1
                WHERE `id` = 1 AND (SELECT `value` FROM `sync_clock` WHERE `id` = 2) = 0;
            INSERT OR REPLACE INTO `sync_tombstone` (`table_name`, `row_id`, `deleted_at`)
                SELECT '${spec.name}',
                       CAST(OLD.`${spec.pkColumn}` AS TEXT),
                       (SELECT `value` FROM `sync_clock` WHERE `id` = 1)
                WHERE (SELECT `value` FROM `sync_clock` WHERE `id` = 2) = 0;
        END
    """.trimIndent()

    companion object {
        /** sync_clock 行含义：1 = Lamport 计数器，2 = 正在应用远端/恢复备份的标志位。 */
        const val CLOCK_ROW_ID = 1
        const val APPLYING_ROW_ID = 2
    }
}
