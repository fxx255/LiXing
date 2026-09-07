package com.example.lixing.data.local

import androidx.room.migration.Migration

/**
 * 数据库迁移集合。
 *
 * 每次改 schema 都往 [ALL] 里追加一条，并同步提升 [LiXingDatabase.VERSION]。
 * 禁止用破坏性迁移兜底——用户的打卡历史是唯一不可再生的数据。
 */
object Migrations {

    /** v1 → v2：daily_task 增加打卡详情字段（文字 + 拍照）。 */
    private val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE daily_task ADD COLUMN checkin_note TEXT")
            db.execSQL("ALTER TABLE daily_task ADD COLUMN checkin_photo TEXT")
        }
    }

    private val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE time_slot ADD COLUMN required_task_count INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE daily_task ADD COLUMN slot_required_task_count INTEGER NOT NULL DEFAULT 0")
        }
    }


    private val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE commitment ADD COLUMN metric TEXT NOT NULL DEFAULT 'COMPLETION_RATE'")
            db.execSQL("ALTER TABLE commitment ADD COLUMN custom_reward TEXT NOT NULL DEFAULT ''")
        }
    }

    private val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `meal_record` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `date` INTEGER NOT NULL,
                    `meal_type` TEXT NOT NULL,
                    `photo_path` TEXT NOT NULL,
                    `food_name` TEXT NOT NULL,
                    `serving_grams` INTEGER NOT NULL,
                    `calories_kcal` INTEGER NOT NULL,
                    `protein_grams` REAL NOT NULL,
                    `carbs_grams` REAL NOT NULL,
                    `fat_grams` REAL NOT NULL,
                    `fiber_grams` REAL NOT NULL,
                    `model_label` TEXT NOT NULL,
                    `confidence` REAL NOT NULL,
                    `advice` TEXT NOT NULL,
                    `is_manually_edited` INTEGER NOT NULL,
                    `analyzed_at` INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_meal_record_date_meal_type` ON `meal_record` (`date`, `meal_type`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_meal_record_date` ON `meal_record` (`date`)")
        }
    }

    /** v5 → v6：新增完全本地的英语单词/短语/句子积累表。 */
    private val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `english_entry` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `type` TEXT NOT NULL,
                    `content` TEXT NOT NULL,
                    `meaning` TEXT NOT NULL,
                    `created_at` INTEGER NOT NULL,
                    `updated_at` INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_english_entry_type` ON `english_entry` (`type`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_english_entry_updated_at` ON `english_entry` (`updated_at`)")
        }
    }

    /** v6 → v7：新增 AI 助手会话与消息表。 */
    private val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `assistant_conversation` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `title` TEXT NOT NULL,
                    `created_at` INTEGER NOT NULL,
                    `updated_at` INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `assistant_message` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `conversation_id` INTEGER NOT NULL,
                    `role` TEXT NOT NULL,
                    `content` TEXT NOT NULL,
                    `created_at` INTEGER NOT NULL,
                    FOREIGN KEY(`conversation_id`) REFERENCES `assistant_conversation`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_assistant_message_conversation_id` ON `assistant_message` (`conversation_id`)")
        }
    }

    /** v7 -> v8: keep assistant image attachments with their messages. */
    private val MIGRATION_7_8 = object : Migration(7, 8) {
        override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE assistant_message ADD COLUMN image_paths TEXT NOT NULL DEFAULT ''")
        }
    }

    /** v8 -> v9: keep a compact UI body separate from model-only OCR text. */
    private val MIGRATION_8_9 = object : Migration(8, 9) {
        override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE assistant_message ADD COLUMN display_content TEXT")
        }
    }

    /** v9 → v10：14 张自增主键表改为 UUID，全部 17 张表加 sync_modified_at（Lamport 时钟），并建同步元表。 */
    private val MIGRATION_9_10 = object : Migration(9, 10) {
        override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            // 1) 同步元表（同步引擎专用，不进 Room 实体）
            db.execSQL("CREATE TABLE IF NOT EXISTS `sync_clock` (`id` INTEGER PRIMARY KEY NOT NULL, `value` INTEGER NOT NULL)")
            db.execSQL("INSERT OR IGNORE INTO `sync_clock` (`id`, `value`) VALUES (1, 1)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `sync_tombstone` (`table_name` TEXT NOT NULL, `row_id` TEXT NOT NULL, `deleted_at` INTEGER NOT NULL, PRIMARY KEY (`table_name`, `row_id`))")
            db.execSQL("CREATE TABLE IF NOT EXISTS `sync_peer` (`peer_id` TEXT PRIMARY KEY NOT NULL, `cursor` INTEGER NOT NULL)")

            // 2) 旧表整体改名（索引名仍属于旧表，名字稍后让给新表）
            db.execSQL("ALTER TABLE `study_plan` RENAME TO `old_study_plan`")
            db.execSQL("ALTER TABLE `phase` RENAME TO `old_phase`")
            db.execSQL("ALTER TABLE `subject` RENAME TO `old_subject`")
            db.execSQL("ALTER TABLE `time_slot` RENAME TO `old_time_slot`")
            db.execSQL("ALTER TABLE `task_template` RENAME TO `old_task_template`")
            db.execSQL("ALTER TABLE `daily_task` RENAME TO `old_daily_task`")
            db.execSQL("ALTER TABLE `day_record` RENAME TO `old_day_record`")
            db.execSQL("ALTER TABLE `focus_session` RENAME TO `old_focus_session`")
            db.execSQL("ALTER TABLE `check_in_streak` RENAME TO `old_check_in_streak`")
            db.execSQL("ALTER TABLE `point_ledger` RENAME TO `old_point_ledger`")
            db.execSQL("ALTER TABLE `achievement` RENAME TO `old_achievement`")
            db.execSQL("ALTER TABLE `user_profile` RENAME TO `old_user_profile`")
            db.execSQL("ALTER TABLE `commitment` RENAME TO `old_commitment`")
            db.execSQL("ALTER TABLE `meal_record` RENAME TO `old_meal_record`")
            db.execSQL("ALTER TABLE `english_entry` RENAME TO `old_english_entry`")
            db.execSQL("ALTER TABLE `assistant_conversation` RENAME TO `old_assistant_conversation`")
            db.execSQL("ALTER TABLE `assistant_message` RENAME TO `old_assistant_message`")

            // 3) 释放旧索引名，避免与新表索引重名导致 CREATE INDEX IF NOT EXISTS 空转
            db.execSQL("DROP INDEX IF EXISTS `index_phase_plan_id`")
            db.execSQL("DROP INDEX IF EXISTS `index_phase_plan_id_start_date`")
            db.execSQL("DROP INDEX IF EXISTS `index_subject_plan_id`")
            db.execSQL("DROP INDEX IF EXISTS `index_time_slot_plan_id`")
            db.execSQL("DROP INDEX IF EXISTS `index_task_template_subject_id`")
            db.execSQL("DROP INDEX IF EXISTS `index_task_template_time_slot_id`")
            db.execSQL("DROP INDEX IF EXISTS `index_task_template_phase_id`")
            db.execSQL("DROP INDEX IF EXISTS `index_daily_task_date_template_id`")
            db.execSQL("DROP INDEX IF EXISTS `index_daily_task_date`")
            db.execSQL("DROP INDEX IF EXISTS `index_daily_task_template_id`")
            db.execSQL("DROP INDEX IF EXISTS `index_daily_task_date_time_slot_id`")
            db.execSQL("DROP INDEX IF EXISTS `index_daily_task_subject_id`")
            db.execSQL("DROP INDEX IF EXISTS `index_focus_session_daily_task_id`")
            db.execSQL("DROP INDEX IF EXISTS `index_focus_session_subject_id`")
            db.execSQL("DROP INDEX IF EXISTS `index_focus_session_date`")
            db.execSQL("DROP INDEX IF EXISTS `index_point_ledger_date`")
            db.execSQL("DROP INDEX IF EXISTS `index_point_ledger_daily_task_id`")
            db.execSQL("DROP INDEX IF EXISTS `index_point_ledger_dedupe_key`")
            db.execSQL("DROP INDEX IF EXISTS `index_achievement_code`")
            db.execSQL("DROP INDEX IF EXISTS `index_commitment_phase_id`")
            db.execSQL("DROP INDEX IF EXISTS `index_meal_record_date_meal_type`")
            db.execSQL("DROP INDEX IF EXISTS `index_meal_record_date`")
            db.execSQL("DROP INDEX IF EXISTS `index_english_entry_type`")
            db.execSQL("DROP INDEX IF EXISTS `index_english_entry_updated_at`")
            db.execSQL("DROP INDEX IF EXISTS `index_assistant_message_conversation_id`")

            // 4) UUID 映射表（old_id -> new_id）
            db.execSQL("CREATE TABLE `tmp_map_study_plan` (`old_id` INTEGER PRIMARY KEY NOT NULL, `new_id` TEXT NOT NULL)")
            db.execSQL("CREATE TABLE `tmp_map_phase` (`old_id` INTEGER PRIMARY KEY NOT NULL, `new_id` TEXT NOT NULL)")
            db.execSQL("CREATE TABLE `tmp_map_subject` (`old_id` INTEGER PRIMARY KEY NOT NULL, `new_id` TEXT NOT NULL)")
            db.execSQL("CREATE TABLE `tmp_map_time_slot` (`old_id` INTEGER PRIMARY KEY NOT NULL, `new_id` TEXT NOT NULL)")
            db.execSQL("CREATE TABLE `tmp_map_task_template` (`old_id` INTEGER PRIMARY KEY NOT NULL, `new_id` TEXT NOT NULL)")
            db.execSQL("CREATE TABLE `tmp_map_daily_task` (`old_id` INTEGER PRIMARY KEY NOT NULL, `new_id` TEXT NOT NULL)")
            db.execSQL("CREATE TABLE `tmp_map_focus_session` (`old_id` INTEGER PRIMARY KEY NOT NULL, `new_id` TEXT NOT NULL)")
            db.execSQL("CREATE TABLE `tmp_map_point_ledger` (`old_id` INTEGER PRIMARY KEY NOT NULL, `new_id` TEXT NOT NULL)")
            db.execSQL("CREATE TABLE `tmp_map_achievement` (`old_id` INTEGER PRIMARY KEY NOT NULL, `new_id` TEXT NOT NULL)")
            db.execSQL("CREATE TABLE `tmp_map_commitment` (`old_id` INTEGER PRIMARY KEY NOT NULL, `new_id` TEXT NOT NULL)")
            db.execSQL("CREATE TABLE `tmp_map_meal_record` (`old_id` INTEGER PRIMARY KEY NOT NULL, `new_id` TEXT NOT NULL)")
            db.execSQL("CREATE TABLE `tmp_map_english_entry` (`old_id` INTEGER PRIMARY KEY NOT NULL, `new_id` TEXT NOT NULL)")
            db.execSQL("CREATE TABLE `tmp_map_assistant_conversation` (`old_id` INTEGER PRIMARY KEY NOT NULL, `new_id` TEXT NOT NULL)")
            db.execSQL("CREATE TABLE `tmp_map_assistant_message` (`old_id` INTEGER PRIMARY KEY NOT NULL, `new_id` TEXT NOT NULL)")

            // 5) 逐表为旧 id 生成 UUID 映射
            fillUuidMap(db, "study_plan"); fillUuidMap(db, "phase"); fillUuidMap(db, "subject"); fillUuidMap(db, "time_slot"); fillUuidMap(db, "task_template")
            fillUuidMap(db, "daily_task"); fillUuidMap(db, "focus_session"); fillUuidMap(db, "point_ledger"); fillUuidMap(db, "achievement")
            fillUuidMap(db, "commitment"); fillUuidMap(db, "meal_record"); fillUuidMap(db, "english_entry")
            fillUuidMap(db, "assistant_conversation"); fillUuidMap(db, "assistant_message")

            // 6) 建新表（DDL 与 Room 导出的 v10 schema 逐字一致）
            db.execSQL(
                """
CREATE TABLE IF NOT EXISTS `study_plan` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `start_date` INTEGER NOT NULL, `target_date` INTEGER NOT NULL, `is_active` INTEGER NOT NULL, `note` TEXT NOT NULL, `created_at` INTEGER NOT NULL, `sync_modified_at` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`id`))
                """.trimIndent(),
            )
            db.execSQL(
                """
CREATE TABLE IF NOT EXISTS `phase` (`id` TEXT NOT NULL, `plan_id` TEXT NOT NULL, `name` TEXT NOT NULL, `start_date` INTEGER NOT NULL, `end_date` INTEGER NOT NULL, `description` TEXT NOT NULL, `sort_order` INTEGER NOT NULL, `sync_modified_at` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`id`), FOREIGN KEY(`plan_id`) REFERENCES `study_plan`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )
                """.trimIndent(),
            )
            db.execSQL(
                """
CREATE TABLE IF NOT EXISTS `subject` (`id` TEXT NOT NULL, `plan_id` TEXT NOT NULL, `name` TEXT NOT NULL, `color_argb` INTEGER NOT NULL, `target_total_minutes` INTEGER NOT NULL, `icon_key` TEXT NOT NULL, `sort_order` INTEGER NOT NULL, `is_archived` INTEGER NOT NULL, `sync_modified_at` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`id`), FOREIGN KEY(`plan_id`) REFERENCES `study_plan`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )
                """.trimIndent(),
            )
            db.execSQL(
                """
CREATE TABLE IF NOT EXISTS `time_slot` (`id` TEXT NOT NULL, `plan_id` TEXT NOT NULL, `name` TEXT NOT NULL, `start_time` INTEGER NOT NULL, `end_time` INTEGER NOT NULL, `weekday_mask` INTEGER NOT NULL, `sort_order` INTEGER NOT NULL, `note` TEXT NOT NULL, `required_task_count` INTEGER NOT NULL DEFAULT 0, `is_enabled` INTEGER NOT NULL, `sync_modified_at` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`id`), FOREIGN KEY(`plan_id`) REFERENCES `study_plan`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )
                """.trimIndent(),
            )
            db.execSQL(
                """
CREATE TABLE IF NOT EXISTS `task_template` (`id` TEXT NOT NULL, `subject_id` TEXT NOT NULL, `time_slot_id` TEXT NOT NULL, `title` TEXT NOT NULL, `task_type` TEXT NOT NULL, `target_type` TEXT NOT NULL, `target_value` INTEGER NOT NULL, `repeat_rule` TEXT NOT NULL, `weekday_mask` INTEGER NOT NULL, `interval_days` INTEGER NOT NULL, `anchor_date` INTEGER, `phase_id` TEXT, `active_from` INTEGER, `active_until` INTEGER, `is_keystone` INTEGER NOT NULL, `note` TEXT NOT NULL, `sort_order` INTEGER NOT NULL, `is_enabled` INTEGER NOT NULL, `sync_modified_at` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`id`), FOREIGN KEY(`subject_id`) REFERENCES `subject`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`time_slot_id`) REFERENCES `time_slot`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`phase_id`) REFERENCES `phase`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )
                """.trimIndent(),
            )
            db.execSQL(
                """
CREATE TABLE IF NOT EXISTS `daily_task` (`id` TEXT NOT NULL, `date` INTEGER NOT NULL, `template_id` TEXT, `subject_id` TEXT NOT NULL, `subject_name` TEXT NOT NULL, `subject_color_argb` INTEGER NOT NULL, `time_slot_id` TEXT NOT NULL, `slot_name` TEXT NOT NULL, `slot_start` INTEGER NOT NULL, `slot_end` INTEGER NOT NULL, `slot_sort_order` INTEGER NOT NULL, `slot_required_task_count` INTEGER NOT NULL DEFAULT 0, `title` TEXT NOT NULL, `task_type` TEXT NOT NULL, `target_type` TEXT NOT NULL, `target_value` INTEGER NOT NULL, `is_keystone` INTEGER NOT NULL, `note` TEXT NOT NULL, `sort_order` INTEGER NOT NULL, `status` TEXT NOT NULL, `actual_value` INTEGER NOT NULL, `checked_at` INTEGER, `is_late` INTEGER NOT NULL, `is_makeup` INTEGER NOT NULL, `makeup_reason` TEXT, `focused_minutes` INTEGER NOT NULL, `checkin_note` TEXT, `checkin_photo` TEXT, `mood` TEXT, `reflection` TEXT, `sync_modified_at` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`id`), FOREIGN KEY(`template_id`) REFERENCES `task_template`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )
                """.trimIndent(),
            )
            db.execSQL(
                """
CREATE TABLE IF NOT EXISTS `day_record` (`date` INTEGER NOT NULL, `total_tasks` INTEGER NOT NULL, `done_tasks` INTEGER NOT NULL, `partial_tasks` INTEGER NOT NULL, `missed_tasks` INTEGER NOT NULL, `skipped_tasks` INTEGER NOT NULL, `completion_rate` REAL NOT NULL, `focus_minutes` INTEGER NOT NULL, `points_earned` INTEGER NOT NULL, `is_achieved` INTEGER NOT NULL, `is_full_day` INTEGER NOT NULL, `is_day_off` INTEGER NOT NULL, `used_rescue_card` INTEGER NOT NULL, `mood` TEXT, `reflection` TEXT, `is_settled` INTEGER NOT NULL, `sync_modified_at` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`date`))
                """.trimIndent(),
            )
            db.execSQL(
                """
CREATE TABLE IF NOT EXISTS `focus_session` (`id` TEXT NOT NULL, `date` INTEGER NOT NULL, `daily_task_id` TEXT, `subject_id` TEXT, `started_at` INTEGER NOT NULL, `ended_at` INTEGER, `mode` TEXT NOT NULL, `planned_minutes` INTEGER NOT NULL, `effective_minutes` INTEGER NOT NULL, `interruption_count` INTEGER NOT NULL, `counts_toward_task` INTEGER NOT NULL, `is_completed` INTEGER NOT NULL, `sync_modified_at` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`id`), FOREIGN KEY(`daily_task_id`) REFERENCES `daily_task`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL , FOREIGN KEY(`subject_id`) REFERENCES `subject`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )
                """.trimIndent(),
            )
            db.execSQL(
                """
CREATE TABLE IF NOT EXISTS `check_in_streak` (`id` INTEGER NOT NULL, `current_streak` INTEGER NOT NULL, `longest_streak` INTEGER NOT NULL, `last_achieved_date` INTEGER, `rescue_cards_left` INTEGER NOT NULL, `rescue_cards_month` INTEGER NOT NULL, `total_achieved_days` INTEGER NOT NULL, `makeups_used_this_week` INTEGER NOT NULL, `makeup_week_key` INTEGER NOT NULL, `day_offs_used_this_month` INTEGER NOT NULL, `day_off_month_key` INTEGER NOT NULL, `sync_modified_at` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`id`))
                """.trimIndent(),
            )
            db.execSQL(
                """
CREATE TABLE IF NOT EXISTS `point_ledger` (`id` TEXT NOT NULL, `date` INTEGER NOT NULL, `delta` INTEGER NOT NULL, `reason` TEXT NOT NULL, `daily_task_id` TEXT, `detail` TEXT NOT NULL, `dedupe_key` TEXT, `created_at` INTEGER NOT NULL, `sync_modified_at` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`id`))
                """.trimIndent(),
            )
            db.execSQL(
                """
CREATE TABLE IF NOT EXISTS `achievement` (`id` TEXT NOT NULL, `code` TEXT NOT NULL, `title` TEXT NOT NULL, `description` TEXT NOT NULL, `icon_key` TEXT NOT NULL, `condition` TEXT NOT NULL, `threshold` INTEGER NOT NULL, `extra_key` TEXT, `reward_points` INTEGER NOT NULL, `tier` INTEGER NOT NULL, `sort_order` INTEGER NOT NULL, `unlocked_at` INTEGER, `unlocked_detail` TEXT, `progress` INTEGER NOT NULL, `sync_modified_at` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`id`))
                """.trimIndent(),
            )
            db.execSQL(
                """
CREATE TABLE IF NOT EXISTS `user_profile` (`id` INTEGER NOT NULL, `nickname` TEXT NOT NULL, `total_points` INTEGER NOT NULL, `level` INTEGER NOT NULL, `title` TEXT NOT NULL, `total_focus_minutes` INTEGER NOT NULL, `total_check_ins` INTEGER NOT NULL, `joined_date` INTEGER NOT NULL, `sync_modified_at` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`id`))
                """.trimIndent(),
            )
            db.execSQL(
                """
CREATE TABLE IF NOT EXISTS `commitment` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `phase_id` TEXT, `start_date` INTEGER NOT NULL, `end_date` INTEGER NOT NULL, `target_rate_percent` INTEGER NOT NULL, `metric` TEXT NOT NULL DEFAULT 'COMPLETION_RATE', `reward_points` INTEGER NOT NULL, `custom_reward` TEXT NOT NULL DEFAULT '', `status` TEXT NOT NULL, `actual_rate_percent` INTEGER, `settled_date` INTEGER, `note` TEXT NOT NULL, `sync_modified_at` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`id`), FOREIGN KEY(`phase_id`) REFERENCES `phase`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )
                """.trimIndent(),
            )
            db.execSQL(
                """
CREATE TABLE IF NOT EXISTS `meal_record` (`id` TEXT NOT NULL, `date` INTEGER NOT NULL, `meal_type` TEXT NOT NULL, `photo_path` TEXT NOT NULL, `food_name` TEXT NOT NULL, `serving_grams` INTEGER NOT NULL, `calories_kcal` INTEGER NOT NULL, `protein_grams` REAL NOT NULL, `carbs_grams` REAL NOT NULL, `fat_grams` REAL NOT NULL, `fiber_grams` REAL NOT NULL, `model_label` TEXT NOT NULL, `confidence` REAL NOT NULL, `advice` TEXT NOT NULL, `is_manually_edited` INTEGER NOT NULL, `analyzed_at` INTEGER NOT NULL, `sync_modified_at` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`id`))
                """.trimIndent(),
            )
            db.execSQL(
                """
CREATE TABLE IF NOT EXISTS `english_entry` (`id` TEXT NOT NULL, `type` TEXT NOT NULL, `content` TEXT NOT NULL, `meaning` TEXT NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, `sync_modified_at` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`id`))
                """.trimIndent(),
            )
            db.execSQL(
                """
CREATE TABLE IF NOT EXISTS `assistant_conversation` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, `sync_modified_at` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`id`))
                """.trimIndent(),
            )
            db.execSQL(
                """
CREATE TABLE IF NOT EXISTS `assistant_message` (`id` TEXT NOT NULL, `conversation_id` TEXT NOT NULL, `role` TEXT NOT NULL, `content` TEXT NOT NULL, `image_paths` TEXT NOT NULL DEFAULT '', `display_content` TEXT, `created_at` INTEGER NOT NULL, `sync_modified_at` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`id`), FOREIGN KEY(`conversation_id`) REFERENCES `assistant_conversation`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )
                """.trimIndent(),
            )

            // 7) 建索引（名字已无冲突，会真正落在新表上）
            db.execSQL(
                """
CREATE INDEX IF NOT EXISTS `index_phase_plan_id` ON `phase` (`plan_id`)
                """.trimIndent(),
            )
            db.execSQL(
                """
CREATE INDEX IF NOT EXISTS `index_phase_plan_id_start_date` ON `phase` (`plan_id`, `start_date`)
                """.trimIndent(),
            )
            db.execSQL(
                """
CREATE INDEX IF NOT EXISTS `index_subject_plan_id` ON `subject` (`plan_id`)
                """.trimIndent(),
            )
            db.execSQL(
                """
CREATE INDEX IF NOT EXISTS `index_time_slot_plan_id` ON `time_slot` (`plan_id`)
                """.trimIndent(),
            )
            db.execSQL(
                """
CREATE INDEX IF NOT EXISTS `index_task_template_subject_id` ON `task_template` (`subject_id`)
                """.trimIndent(),
            )
            db.execSQL(
                """
CREATE INDEX IF NOT EXISTS `index_task_template_time_slot_id` ON `task_template` (`time_slot_id`)
                """.trimIndent(),
            )
            db.execSQL(
                """
CREATE INDEX IF NOT EXISTS `index_task_template_phase_id` ON `task_template` (`phase_id`)
                """.trimIndent(),
            )
            db.execSQL(
                """
CREATE UNIQUE INDEX IF NOT EXISTS `index_daily_task_date_template_id` ON `daily_task` (`date`, `template_id`)
                """.trimIndent(),
            )
            db.execSQL(
                """
CREATE INDEX IF NOT EXISTS `index_daily_task_date` ON `daily_task` (`date`)
                """.trimIndent(),
            )
            db.execSQL(
                """
CREATE INDEX IF NOT EXISTS `index_daily_task_template_id` ON `daily_task` (`template_id`)
                """.trimIndent(),
            )
            db.execSQL(
                """
CREATE INDEX IF NOT EXISTS `index_daily_task_date_time_slot_id` ON `daily_task` (`date`, `time_slot_id`)
                """.trimIndent(),
            )
            db.execSQL(
                """
CREATE INDEX IF NOT EXISTS `index_daily_task_subject_id` ON `daily_task` (`subject_id`)
                """.trimIndent(),
            )
            db.execSQL(
                """
CREATE INDEX IF NOT EXISTS `index_focus_session_daily_task_id` ON `focus_session` (`daily_task_id`)
                """.trimIndent(),
            )
            db.execSQL(
                """
CREATE INDEX IF NOT EXISTS `index_focus_session_subject_id` ON `focus_session` (`subject_id`)
                """.trimIndent(),
            )
            db.execSQL(
                """
CREATE INDEX IF NOT EXISTS `index_focus_session_date` ON `focus_session` (`date`)
                """.trimIndent(),
            )
            db.execSQL(
                """
CREATE INDEX IF NOT EXISTS `index_point_ledger_date` ON `point_ledger` (`date`)
                """.trimIndent(),
            )
            db.execSQL(
                """
CREATE INDEX IF NOT EXISTS `index_point_ledger_daily_task_id` ON `point_ledger` (`daily_task_id`)
                """.trimIndent(),
            )
            db.execSQL(
                """
CREATE UNIQUE INDEX IF NOT EXISTS `index_point_ledger_dedupe_key` ON `point_ledger` (`dedupe_key`)
                """.trimIndent(),
            )
            db.execSQL(
                """
CREATE UNIQUE INDEX IF NOT EXISTS `index_achievement_code` ON `achievement` (`code`)
                """.trimIndent(),
            )
            db.execSQL(
                """
CREATE INDEX IF NOT EXISTS `index_commitment_phase_id` ON `commitment` (`phase_id`)
                """.trimIndent(),
            )
            db.execSQL(
                """
CREATE UNIQUE INDEX IF NOT EXISTS `index_meal_record_date_meal_type` ON `meal_record` (`date`, `meal_type`)
                """.trimIndent(),
            )
            db.execSQL(
                """
CREATE INDEX IF NOT EXISTS `index_meal_record_date` ON `meal_record` (`date`)
                """.trimIndent(),
            )
            db.execSQL(
                """
CREATE INDEX IF NOT EXISTS `index_english_entry_type` ON `english_entry` (`type`)
                """.trimIndent(),
            )
            db.execSQL(
                """
CREATE INDEX IF NOT EXISTS `index_english_entry_updated_at` ON `english_entry` (`updated_at`)
                """.trimIndent(),
            )
            db.execSQL(
                """
CREATE INDEX IF NOT EXISTS `index_assistant_message_conversation_id` ON `assistant_message` (`conversation_id`)
                """.trimIndent(),
            )

            // 8) 灌数据：显式列清单（含 sync_modified_at），旧整数外键翻译成父表新 UUID
            db.execSQL(
                """
INSERT INTO `study_plan` (`id`, `name`, `start_date`, `target_date`, `is_active`, `note`, `created_at`, `sync_modified_at`) SELECT (SELECT `new_id` FROM `tmp_map_study_plan` m WHERE m.`old_id` = o.`id`), o.`name`, o.`start_date`, o.`target_date`, o.`is_active`, o.`note`, o.`created_at`, 0 FROM `old_study_plan` o
                """.trimIndent(),
            )
            db.execSQL(
                """
INSERT INTO `phase` (`id`, `plan_id`, `name`, `start_date`, `end_date`, `description`, `sort_order`, `sync_modified_at`) SELECT (SELECT `new_id` FROM `tmp_map_phase` m WHERE m.`old_id` = o.`id`), (SELECT `new_id` FROM `tmp_map_study_plan` m WHERE m.`old_id` = o.`plan_id`), o.`name`, o.`start_date`, o.`end_date`, o.`description`, o.`sort_order`, 0 FROM `old_phase` o
                """.trimIndent(),
            )
            db.execSQL(
                """
INSERT INTO `subject` (`id`, `plan_id`, `name`, `color_argb`, `target_total_minutes`, `icon_key`, `sort_order`, `is_archived`, `sync_modified_at`) SELECT (SELECT `new_id` FROM `tmp_map_subject` m WHERE m.`old_id` = o.`id`), (SELECT `new_id` FROM `tmp_map_study_plan` m WHERE m.`old_id` = o.`plan_id`), o.`name`, o.`color_argb`, o.`target_total_minutes`, o.`icon_key`, o.`sort_order`, o.`is_archived`, 0 FROM `old_subject` o
                """.trimIndent(),
            )
            db.execSQL(
                """
INSERT INTO `time_slot` (`id`, `plan_id`, `name`, `start_time`, `end_time`, `weekday_mask`, `sort_order`, `note`, `required_task_count`, `is_enabled`, `sync_modified_at`) SELECT (SELECT `new_id` FROM `tmp_map_time_slot` m WHERE m.`old_id` = o.`id`), (SELECT `new_id` FROM `tmp_map_study_plan` m WHERE m.`old_id` = o.`plan_id`), o.`name`, o.`start_time`, o.`end_time`, o.`weekday_mask`, o.`sort_order`, o.`note`, o.`required_task_count`, o.`is_enabled`, 0 FROM `old_time_slot` o
                """.trimIndent(),
            )
            db.execSQL(
                """
INSERT INTO `task_template` (`id`, `subject_id`, `time_slot_id`, `title`, `task_type`, `target_type`, `target_value`, `repeat_rule`, `weekday_mask`, `interval_days`, `anchor_date`, `phase_id`, `active_from`, `active_until`, `is_keystone`, `note`, `sort_order`, `is_enabled`, `sync_modified_at`) SELECT (SELECT `new_id` FROM `tmp_map_task_template` m WHERE m.`old_id` = o.`id`), (SELECT `new_id` FROM `tmp_map_subject` m WHERE m.`old_id` = o.`subject_id`), (SELECT `new_id` FROM `tmp_map_time_slot` m WHERE m.`old_id` = o.`time_slot_id`), o.`title`, o.`task_type`, o.`target_type`, o.`target_value`, o.`repeat_rule`, o.`weekday_mask`, o.`interval_days`, o.`anchor_date`, (SELECT `new_id` FROM `tmp_map_phase` m WHERE m.`old_id` = o.`phase_id`), o.`active_from`, o.`active_until`, o.`is_keystone`, o.`note`, o.`sort_order`, o.`is_enabled`, 0 FROM `old_task_template` o
                """.trimIndent(),
            )
            db.execSQL(
                """
INSERT INTO `daily_task` (`id`, `date`, `template_id`, `subject_id`, `subject_name`, `subject_color_argb`, `time_slot_id`, `slot_name`, `slot_start`, `slot_end`, `slot_sort_order`, `slot_required_task_count`, `title`, `task_type`, `target_type`, `target_value`, `is_keystone`, `note`, `sort_order`, `status`, `actual_value`, `checked_at`, `is_late`, `is_makeup`, `makeup_reason`, `focused_minutes`, `checkin_note`, `checkin_photo`, `mood`, `reflection`, `sync_modified_at`) SELECT (SELECT `new_id` FROM `tmp_map_daily_task` m WHERE m.`old_id` = o.`id`), o.`date`, (SELECT `new_id` FROM `tmp_map_task_template` m WHERE m.`old_id` = o.`template_id`), (SELECT `new_id` FROM `tmp_map_subject` m WHERE m.`old_id` = o.`subject_id`), o.`subject_name`, o.`subject_color_argb`, (SELECT `new_id` FROM `tmp_map_time_slot` m WHERE m.`old_id` = o.`time_slot_id`), o.`slot_name`, o.`slot_start`, o.`slot_end`, o.`slot_sort_order`, o.`slot_required_task_count`, o.`title`, o.`task_type`, o.`target_type`, o.`target_value`, o.`is_keystone`, o.`note`, o.`sort_order`, o.`status`, o.`actual_value`, o.`checked_at`, o.`is_late`, o.`is_makeup`, o.`makeup_reason`, o.`focused_minutes`, o.`checkin_note`, o.`checkin_photo`, o.`mood`, o.`reflection`, 0 FROM `old_daily_task` o
                """.trimIndent(),
            )
            db.execSQL(
                """
INSERT INTO `point_ledger` (`id`, `date`, `delta`, `reason`, `daily_task_id`, `detail`, `dedupe_key`, `created_at`, `sync_modified_at`) SELECT (SELECT `new_id` FROM `tmp_map_point_ledger` m WHERE m.`old_id` = o.`id`), o.`date`, o.`delta`, o.`reason`, (SELECT `new_id` FROM `tmp_map_daily_task` m WHERE m.`old_id` = o.`daily_task_id`), o.`detail`, o.`dedupe_key`, o.`created_at`, 0 FROM `old_point_ledger` o
                """.trimIndent(),
            )
            db.execSQL(
                """
INSERT INTO `focus_session` (`id`, `date`, `daily_task_id`, `subject_id`, `started_at`, `ended_at`, `mode`, `planned_minutes`, `effective_minutes`, `interruption_count`, `counts_toward_task`, `is_completed`, `sync_modified_at`) SELECT (SELECT `new_id` FROM `tmp_map_focus_session` m WHERE m.`old_id` = o.`id`), o.`date`, (SELECT `new_id` FROM `tmp_map_daily_task` m WHERE m.`old_id` = o.`daily_task_id`), (SELECT `new_id` FROM `tmp_map_subject` m WHERE m.`old_id` = o.`subject_id`), o.`started_at`, o.`ended_at`, o.`mode`, o.`planned_minutes`, o.`effective_minutes`, o.`interruption_count`, o.`counts_toward_task`, o.`is_completed`, 0 FROM `old_focus_session` o
                """.trimIndent(),
            )
            db.execSQL(
                """
INSERT INTO `assistant_conversation` (`id`, `title`, `created_at`, `updated_at`, `sync_modified_at`) SELECT (SELECT `new_id` FROM `tmp_map_assistant_conversation` m WHERE m.`old_id` = o.`id`), o.`title`, o.`created_at`, o.`updated_at`, 0 FROM `old_assistant_conversation` o
                """.trimIndent(),
            )
            db.execSQL(
                """
INSERT INTO `assistant_message` (`id`, `conversation_id`, `role`, `content`, `image_paths`, `display_content`, `created_at`, `sync_modified_at`) SELECT (SELECT `new_id` FROM `tmp_map_assistant_message` m WHERE m.`old_id` = o.`id`), (SELECT `new_id` FROM `tmp_map_assistant_conversation` m WHERE m.`old_id` = o.`conversation_id`), o.`role`, o.`content`, o.`image_paths`, o.`display_content`, o.`created_at`, 0 FROM `old_assistant_message` o
                """.trimIndent(),
            )
            db.execSQL(
                """
INSERT INTO `achievement` (`id`, `code`, `title`, `description`, `icon_key`, `condition`, `threshold`, `extra_key`, `reward_points`, `tier`, `sort_order`, `unlocked_at`, `unlocked_detail`, `progress`, `sync_modified_at`) SELECT (SELECT `new_id` FROM `tmp_map_achievement` m WHERE m.`old_id` = o.`id`), o.`code`, o.`title`, o.`description`, o.`icon_key`, o.`condition`, o.`threshold`, o.`extra_key`, o.`reward_points`, o.`tier`, o.`sort_order`, o.`unlocked_at`, o.`unlocked_detail`, o.`progress`, 0 FROM `old_achievement` o
                """.trimIndent(),
            )
            db.execSQL(
                """
INSERT INTO `commitment` (`id`, `title`, `phase_id`, `start_date`, `end_date`, `target_rate_percent`, `metric`, `reward_points`, `custom_reward`, `status`, `actual_rate_percent`, `settled_date`, `note`, `sync_modified_at`) SELECT (SELECT `new_id` FROM `tmp_map_commitment` m WHERE m.`old_id` = o.`id`), o.`title`, (SELECT `new_id` FROM `tmp_map_phase` m WHERE m.`old_id` = o.`phase_id`), o.`start_date`, o.`end_date`, o.`target_rate_percent`, o.`metric`, o.`reward_points`, o.`custom_reward`, o.`status`, o.`actual_rate_percent`, o.`settled_date`, o.`note`, 0 FROM `old_commitment` o
                """.trimIndent(),
            )
            db.execSQL(
                """
INSERT INTO `meal_record` (`id`, `date`, `meal_type`, `photo_path`, `food_name`, `serving_grams`, `calories_kcal`, `protein_grams`, `carbs_grams`, `fat_grams`, `fiber_grams`, `model_label`, `confidence`, `advice`, `is_manually_edited`, `analyzed_at`, `sync_modified_at`) SELECT (SELECT `new_id` FROM `tmp_map_meal_record` m WHERE m.`old_id` = o.`id`), o.`date`, o.`meal_type`, o.`photo_path`, o.`food_name`, o.`serving_grams`, o.`calories_kcal`, o.`protein_grams`, o.`carbs_grams`, o.`fat_grams`, o.`fiber_grams`, o.`model_label`, o.`confidence`, o.`advice`, o.`is_manually_edited`, o.`analyzed_at`, 0 FROM `old_meal_record` o
                """.trimIndent(),
            )
            db.execSQL(
                """
INSERT INTO `english_entry` (`id`, `type`, `content`, `meaning`, `created_at`, `updated_at`, `sync_modified_at`) SELECT (SELECT `new_id` FROM `tmp_map_english_entry` m WHERE m.`old_id` = o.`id`), o.`type`, o.`content`, o.`meaning`, o.`created_at`, o.`updated_at`, 0 FROM `old_english_entry` o
                """.trimIndent(),
            )
            db.execSQL(
                """
INSERT INTO `day_record` (`date`, `total_tasks`, `done_tasks`, `partial_tasks`, `missed_tasks`, `skipped_tasks`, `completion_rate`, `focus_minutes`, `points_earned`, `is_achieved`, `is_full_day`, `is_day_off`, `used_rescue_card`, `mood`, `reflection`, `is_settled`, `sync_modified_at`) SELECT o.`date`, o.`total_tasks`, o.`done_tasks`, o.`partial_tasks`, o.`missed_tasks`, o.`skipped_tasks`, o.`completion_rate`, o.`focus_minutes`, o.`points_earned`, o.`is_achieved`, o.`is_full_day`, o.`is_day_off`, o.`used_rescue_card`, o.`mood`, o.`reflection`, o.`is_settled`, 0 FROM `old_day_record` o
                """.trimIndent(),
            )
            db.execSQL(
                """
INSERT INTO `check_in_streak` (`id`, `current_streak`, `longest_streak`, `last_achieved_date`, `rescue_cards_left`, `rescue_cards_month`, `total_achieved_days`, `makeups_used_this_week`, `makeup_week_key`, `day_offs_used_this_month`, `day_off_month_key`, `sync_modified_at`) SELECT o.`id`, o.`current_streak`, o.`longest_streak`, o.`last_achieved_date`, o.`rescue_cards_left`, o.`rescue_cards_month`, o.`total_achieved_days`, o.`makeups_used_this_week`, o.`makeup_week_key`, o.`day_offs_used_this_month`, o.`day_off_month_key`, 0 FROM `old_check_in_streak` o
                """.trimIndent(),
            )
            db.execSQL(
                """
INSERT INTO `user_profile` (`id`, `nickname`, `total_points`, `level`, `title`, `total_focus_minutes`, `total_check_ins`, `joined_date`, `sync_modified_at`) SELECT o.`id`, o.`nickname`, o.`total_points`, o.`level`, o.`title`, o.`total_focus_minutes`, o.`total_check_ins`, o.`joined_date`, 0 FROM `old_user_profile` o
                """.trimIndent(),
            )

            // 9) 删旧表：子表先于父表（反向于灌数据顺序），避免 DROP 父表触发级联
            db.execSQL("DROP TABLE `old_user_profile`")
            db.execSQL("DROP TABLE `old_check_in_streak`")
            db.execSQL("DROP TABLE `old_day_record`")
            db.execSQL("DROP TABLE `old_english_entry`")
            db.execSQL("DROP TABLE `old_meal_record`")
            db.execSQL("DROP TABLE `old_commitment`")
            db.execSQL("DROP TABLE `old_achievement`")
            db.execSQL("DROP TABLE `old_assistant_message`")
            db.execSQL("DROP TABLE `old_assistant_conversation`")
            db.execSQL("DROP TABLE `old_focus_session`")
            db.execSQL("DROP TABLE `old_point_ledger`")
            db.execSQL("DROP TABLE `old_daily_task`")
            db.execSQL("DROP TABLE `old_task_template`")
            db.execSQL("DROP TABLE `old_time_slot`")
            db.execSQL("DROP TABLE `old_subject`")
            db.execSQL("DROP TABLE `old_phase`")
            db.execSQL("DROP TABLE `old_study_plan`")

            // 10) 清理映射表
            db.execSQL("DROP TABLE `tmp_map_study_plan`")
            db.execSQL("DROP TABLE `tmp_map_phase`")
            db.execSQL("DROP TABLE `tmp_map_subject`")
            db.execSQL("DROP TABLE `tmp_map_time_slot`")
            db.execSQL("DROP TABLE `tmp_map_task_template`")
            db.execSQL("DROP TABLE `tmp_map_daily_task`")
            db.execSQL("DROP TABLE `tmp_map_focus_session`")
            db.execSQL("DROP TABLE `tmp_map_point_ledger`")
            db.execSQL("DROP TABLE `tmp_map_achievement`")
            db.execSQL("DROP TABLE `tmp_map_commitment`")
            db.execSQL("DROP TABLE `tmp_map_meal_record`")
            db.execSQL("DROP TABLE `tmp_map_english_entry`")
            db.execSQL("DROP TABLE `tmp_map_assistant_conversation`")
            db.execSQL("DROP TABLE `tmp_map_assistant_message`")
        }

        private fun fillUuidMap(db: androidx.sqlite.db.SupportSQLiteDatabase, table: String) {
            db.query("SELECT `id` FROM `old_" + table + "`").use { c ->
                while (c.moveToNext()) {
                    val oldId = c.getLong(0)
                    db.execSQL(
                        "INSERT OR IGNORE INTO `tmp_map_" + table + "` (`old_id`, `new_id`) VALUES (?, ?)",
                        arrayOf(oldId, java.util.UUID.randomUUID().toString()),
                    )
                }
            }
        }
    }

    val ALL: Array<Migration> = arrayOf(
        MIGRATION_1_2,
        MIGRATION_2_3,
        MIGRATION_3_4,
        MIGRATION_4_5,
        MIGRATION_5_6,
        MIGRATION_6_7,
        MIGRATION_7_8,
        MIGRATION_8_9,
        MIGRATION_9_10,
    )
}
