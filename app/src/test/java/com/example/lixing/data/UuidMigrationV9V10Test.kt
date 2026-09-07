package com.example.lixing.data

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.example.lixing.data.local.LiXingDatabase
import com.example.lixing.data.local.Migrations
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * v9 -> v10 迁移回归：UUID 主键 + sync_modified_at + 同步元表。
 *
 * 步骤：
 * 1. 用 Room 导出的 9.json 重建一个 v9 数据库（含索引），并灌入少量带外键引用关系的种子行；
 * 2. 用 Room v10（Migrations.ALL）打开同一文件 —— 触发 MIGRATION_9_10；
 * 3. Room 打开时的 schema 校验通过，才说明迁移 DDL 与实体定义一致；
 * 4. 再校验：行数不丢、外键引用指向新 UUID、单例行 id=1 仍可读、同步元表就位。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class UuidMigrationV9V10Test {

    private val dbName = "lixing_migration_v9_v10.db"
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication() as Context
        context.deleteDatabase(dbName)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    // ---------------- helpers ----------------

    private fun schemaEntityList(version: Int): JsonArray {
        // 注意：Room 的 schemaLocation 目录名是「点号连写的包名」，即
        // app/schemas/com.example.lixing.data.local.LiXingDatabase/ 是单层文件夹名。
        val dirName = "com.example.lixing.data.local.LiXingDatabase"
        val text = run {
            val resource = "/$dirName/$version.json"
            val stream = javaClass.getResourceAsStream(resource)
            if (stream != null) {
                stream.bufferedReader().use { it.readText() }
            } else {
                // 兜底：直接读工程目录下的 schemas 文件夹（类路径未打包时）
                val candidates = listOf(
                    java.io.File("schemas/$dirName/$version.json"),
                    java.io.File("app/schemas/$dirName/$version.json"),
                )
                candidates.firstOrNull { it.isFile }?.readText()
                    ?: error("schema 资源不存在: $resource")
            }
        }
        return Json.parseToJsonElement(text).jsonObject["database"]!!.jsonObject["entities"]!!.jsonArray
    }

    private fun entityOf(list: JsonArray, table: String): JsonObject =
        list.first { it.jsonObject["tableName"]!!.jsonPrimitive.content == table }.jsonObject

    private fun defaultLiteral(field: JsonObject): String {
        val affinity = field["affinity"]!!.jsonPrimitive.content
        val notNull = field["notNull"]!!.jsonPrimitive.content == "true"
        return when {
            !notNull -> "NULL"
            affinity == "INTEGER" -> "0"
            affinity == "REAL" -> "0"
            else -> "''"
        }
    }

    /** 按 9.json 的字段顺序拼一条 INSERT，overrides 用「列名 -> 可直接执行的 SQL 字面量」。 */
    private fun seed(db: SupportSQLiteDatabase, list: JsonArray, table: String, vararg overrides: Pair<String, String>) {
        val entity = entityOf(list, table)
        val fields = entity["fields"]!!.jsonArray
        val overrideMap = overrides.toMap()
        val cols = mutableListOf<String>()
        val vals = mutableListOf<String>()
        for (f in fields) {
            val name = f.jsonObject["columnName"]!!.jsonPrimitive.content
            cols.add("`$name`")
            vals.add(overrideMap[name] ?: defaultLiteral(f.jsonObject))
        }
        db.execSQL(
            "INSERT INTO `$table` (${cols.joinToString(", ")}) VALUES (${vals.joinToString(", ")})",
        )
    }

    private fun count(db: SupportSQLiteDatabase, table: String): Long =
        db.query("SELECT COUNT(*) FROM `$table`").use { c ->
            c.moveToFirst()
            c.getLong(0)
        }

    // ---------------- 测试 ----------------

    @Test
    fun `v9 to v10 keeps rows remaps fks and passes room schema validation`() {
        val list9 = schemaEntityList(9)

        // 1) 重建 v9 库
        val factory = FrameworkSQLiteOpenHelperFactory()
        val helper = factory.create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(9) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            for (e in list9) {
                                val name = e.jsonObject["tableName"]!!.jsonPrimitive.content
                                val sql = e.jsonObject["createSql"]!!.jsonPrimitive.content
                                    .replace("\${TABLE_NAME}", name)
                                db.execSQL(sql)
                            }
                            for (e in list9) {
                                val name = e.jsonObject["tableName"]!!.jsonPrimitive.content
                                for (ix in e.jsonObject.getOrDefault("indices", JsonArray(emptyList())).jsonArray) {
                                    val sql = ix.jsonObject["createSql"]!!.jsonPrimitive.content
                                        .replace("\${TABLE_NAME}", name)
                                    db.execSQL(sql)
                                }
                            }
                        }

                        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                    },
                )
                .build(),
        )

        try {
            val db = helper.writableDatabase
            // 种子数据（父表在前，参照关系与真实 v9 一致）
            seed(db, list9, "study_plan", "id" to "1", "name" to "'考研冲刺'", "start_date" to "100", "target_date" to "200", "is_active" to "1", "note" to "''", "created_at" to "123")
            seed(db, list9, "phase", "id" to "1", "plan_id" to "1", "name" to "'强化'", "start_date" to "100", "end_date" to "180", "description" to "''", "sort_order" to "1")
            seed(db, list9, "subject", "id" to "1", "plan_id" to "1", "name" to "'数学'", "color_argb" to "-1234567", "target_total_minutes" to "0", "icon_key" to "'math'", "sort_order" to "1", "is_archived" to "0")
            seed(db, list9, "time_slot", "id" to "1", "plan_id" to "1", "name" to "'早读'", "start_time" to "28800", "end_time" to "32400", "weekday_mask" to "127", "sort_order" to "1", "note" to "''", "required_task_count" to "1", "is_enabled" to "1")
            seed(db, list9, "task_template", "id" to "1", "subject_id" to "1", "time_slot_id" to "1", "title" to "'背单词'", "task_type" to "'STUDY'", "target_type" to "'COUNT'", "target_value" to "20", "repeat_rule" to "'DAILY'", "weekday_mask" to "127", "interval_days" to "1", "anchor_date" to "NULL", "phase_id" to "NULL", "active_from" to "100", "active_until" to "200", "is_keystone" to "0", "note" to "''", "sort_order" to "1", "is_enabled" to "1")
            seed(db, list9, "daily_task", "id" to "1", "date" to "100", "template_id" to "1", "subject_id" to "1", "subject_name" to "'数学'", "subject_color_argb" to "-1234567", "time_slot_id" to "1", "slot_name" to "'早读'", "slot_start" to "28800", "slot_end" to "32400", "slot_sort_order" to "1", "slot_required_task_count" to "1", "title" to "'背单词'", "task_type" to "'STUDY'", "target_type" to "'COUNT'", "target_value" to "20", "is_keystone" to "0", "note" to "''", "sort_order" to "1", "status" to "'DONE'", "actual_value" to "20", "checked_at" to "456", "is_late" to "0", "is_makeup" to "0", "makeup_reason" to "NULL", "focused_minutes" to "0", "checkin_note" to "NULL", "checkin_photo" to "NULL", "mood" to "NULL", "reflection" to "NULL")
            seed(db, list9, "daily_task", "id" to "2", "date" to "101", "template_id" to "1", "subject_id" to "1", "subject_name" to "'数学'", "subject_color_argb" to "-1234567", "time_slot_id" to "1", "slot_name" to "'早读'", "slot_start" to "28800", "slot_end" to "32400", "slot_sort_order" to "1", "slot_required_task_count" to "1", "title" to "'背单词'", "task_type" to "'STUDY'", "target_type" to "'COUNT'", "target_value" to "20", "is_keystone" to "0", "note" to "''", "sort_order" to "1", "status" to "'PENDING'", "actual_value" to "0", "checked_at" to "NULL", "is_late" to "0", "is_makeup" to "0", "makeup_reason" to "NULL", "focused_minutes" to "0", "checkin_note" to "NULL", "checkin_photo" to "NULL", "mood" to "NULL", "reflection" to "NULL")
            seed(db, list9, "day_record", "date" to "100", "total_tasks" to "1", "done_tasks" to "1", "partial_tasks" to "0", "missed_tasks" to "0", "skipped_tasks" to "0", "completion_rate" to "1.0", "focus_minutes" to "0", "points_earned" to "10", "is_achieved" to "1", "is_full_day" to "0", "is_day_off" to "0", "used_rescue_card" to "0", "mood" to "NULL", "reflection" to "NULL", "is_settled" to "1")
            seed(db, list9, "focus_session", "id" to "1", "date" to "100", "daily_task_id" to "1", "subject_id" to "1", "started_at" to "500", "ended_at" to "NULL", "mode" to "'POMO'", "planned_minutes" to "25", "effective_minutes" to "0", "interruption_count" to "0", "counts_toward_task" to "1", "is_completed" to "0")
            seed(db, list9, "check_in_streak", "id" to "1", "current_streak" to "3", "longest_streak" to "5", "last_achieved_date" to "100", "rescue_cards_left" to "1", "rescue_cards_month" to "202609", "total_achieved_days" to "12", "makeups_used_this_week" to "0", "makeup_week_key" to "202636", "day_offs_used_this_month" to "0", "day_off_month_key" to "202609")
            seed(db, list9, "point_ledger", "id" to "1", "date" to "100", "delta" to "10", "reason" to "'TASK_DONE'", "daily_task_id" to "1", "detail" to "'完成一项'", "dedupe_key" to "'seed-1'", "created_at" to "123")
            seed(db, list9, "achievement", "id" to "1", "code" to "'FIRST_BLOOD'", "title" to "'初试锋芒'", "description" to "'完成第一项任务'", "icon_key" to "'x'", "condition" to "'none'", "threshold" to "1", "extra_key" to "NULL", "reward_points" to "10", "tier" to "1", "sort_order" to "1", "unlocked_at" to "NULL", "unlocked_detail" to "NULL", "progress" to "1")
            seed(db, list9, "user_profile", "id" to "1", "nickname" to "''", "total_points" to "10", "level" to "1", "title" to "'初心者'", "total_focus_minutes" to "0", "total_check_ins" to "1", "joined_date" to "100")
            seed(db, list9, "commitment", "id" to "1", "title" to "'立约'", "phase_id" to "NULL", "start_date" to "100", "end_date" to "180", "target_rate_percent" to "85", "metric" to "'COMPLETION_RATE'", "reward_points" to "100", "custom_reward" to "''", "status" to "'ACTIVE'", "actual_rate_percent" to "NULL", "settled_date" to "NULL", "note" to "''")
            seed(db, list9, "meal_record", "id" to "1", "date" to "100", "meal_type" to "'BREAKFAST'", "photo_path" to "''", "food_name" to "'燕麦'", "serving_grams" to "50", "calories_kcal" to "190", "protein_grams" to "6.0", "carbs_grams" to "33.0", "fat_grams" to "3.5", "fiber_grams" to "2.0", "model_label" to "'oats'", "confidence" to "0.95", "advice" to "''", "is_manually_edited" to "0", "analyzed_at" to "123")
            seed(db, list9, "english_entry", "id" to "1", "type" to "'WORD'", "content" to "'resilient'", "meaning" to "'有韧性的'", "created_at" to "123", "updated_at" to "123")
            seed(db, list9, "assistant_conversation", "id" to "1", "title" to "'第一问'", "created_at" to "123", "updated_at" to "123")
            seed(db, list9, "assistant_message", "id" to "1", "conversation_id" to "1", "role" to "'user'", "content" to "'如何安排时间'", "image_paths" to "''", "display_content" to "NULL", "created_at" to "123")

            val expectedCounts = mapOf(
                "study_plan" to 1L, "phase" to 1L, "subject" to 1L, "time_slot" to 1L,
                "task_template" to 1L, "daily_task" to 2L, "day_record" to 1L,
                "focus_session" to 1L, "check_in_streak" to 1L, "point_ledger" to 1L,
                "achievement" to 1L, "user_profile" to 1L, "commitment" to 1L,
                "meal_record" to 1L, "english_entry" to 1L,
                "assistant_conversation" to 1L, "assistant_message" to 1L,
            )
            for ((t, n) in expectedCounts) {
                assertEquals("v9 种子行数 $t", n, count(db, t))
            }
            // 记住 v9 里受外键约束的引用是否完整（应 0 孤儿）
            db.execSQL("PRAGMA user_version = 9")
            helper.close()
        } finally {
            helper.close()
        }

        // 2) 用 Room v10 打开：自动跑 MIGRATION_9_10，Room 自己校验 schema 是否与实体一致
        val roomDb = Room.databaseBuilder(context, LiXingDatabase::class.java, dbName)
            .addMigrations(*Migrations.ALL)
            .allowMainThreadQueries()
            .build()
        try {
            val db = roomDb.openHelper.writableDatabase

            // 3) 行数零丢失
            for ((t, n) in mapOf(
                "study_plan" to 1L, "phase" to 1L, "subject" to 1L, "time_slot" to 1L,
                "task_template" to 1L, "daily_task" to 2L, "day_record" to 1L,
                "focus_session" to 1L, "check_in_streak" to 1L, "point_ledger" to 1L,
                "achievement" to 1L, "user_profile" to 1L, "commitment" to 1L,
                "meal_record" to 1L, "english_entry" to 1L,
                "assistant_conversation" to 1L, "assistant_message" to 1L,
            )) {
                assertEquals("迁移后行数 $t", n, count(db, t))
            }

            // 4) 14 张 UUID 表：主键非空、互异、形如 UUID
            val uuidTables = listOf(
                "study_plan", "phase", "subject", "time_slot", "task_template",
                "daily_task", "focus_session", "point_ledger", "achievement",
                "commitment", "meal_record", "english_entry",
                "assistant_conversation", "assistant_message",
            )
            val uuidLike = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
            for (t in uuidTables) {
                val ids = db.query("SELECT `id` FROM `$t`").use { c ->
                    buildList {
                        while (c.moveToNext()) add(c.getString(0))
                    }
                }
                assertTrue("$t 应有数据", ids.isNotEmpty())
                assertEquals("$t id 互异", ids.size, ids.distinct().size)
                ids.forEach { id ->
                    assertTrue("$t id=$id 不是 UUID", uuidLike.matches(id))
                }
            }

            // 5) 外键引用翻译：新表引用必须指向新 UUID 且零孤儿
            fun orphanCount(child: String, fkCol: String, parent: String): Long =
                db.query(
                    "SELECT COUNT(*) FROM `$child` c WHERE c.`$fkCol` IS NOT NULL " +
                        "AND NOT EXISTS (SELECT 1 FROM `$parent` p WHERE p.`id` = c.`$fkCol`)",
                ).use { c -> c.moveToFirst(); c.getLong(0) }

            assertEquals("daily_task.template_id 孤儿", 0L, orphanCount("daily_task", "template_id", "task_template"))
            assertEquals("daily_task.subject_id 孤儿", 0L, orphanCount("daily_task", "subject_id", "subject"))
            assertEquals("daily_task.time_slot_id 孤儿", 0L, orphanCount("daily_task", "time_slot_id", "time_slot"))
            assertEquals("focus_session.daily_task_id 孤儿", 0L, orphanCount("focus_session", "daily_task_id", "daily_task"))
            assertEquals("point_ledger.daily_task_id 孤儿", 0L, orphanCount("point_ledger", "daily_task_id", "daily_task"))
            assertEquals("assistant_message.conversation_id 孤儿", 0L, orphanCount("assistant_message", "conversation_id", "assistant_conversation"))

            // 6) 单例行 id=1 依旧可读（它们保持 INTEGER 主键，不做 UUID）
            assertEquals("check_in_streak 单例行", 1L, count(db, "check_in_streak"))
            db.query("SELECT `id` FROM `check_in_streak` WHERE `id` = 1").use { c ->
                assertTrue(c.moveToFirst())
            }
            db.query("SELECT `id` FROM `user_profile` WHERE `id` = 1").use { c ->
                assertTrue(c.moveToFirst())
            }
            // 数据抽样：连续天数与昵称侧的积分仍在
            db.query("SELECT `current_streak` FROM `check_in_streak` WHERE `id` = 1").use { c ->
                c.moveToFirst(); assertEquals(3, c.getInt(0))
            }

            // 7) 幂等键与内容保留
            db.query("SELECT `dedupe_key` FROM `point_ledger`").use { c ->
                c.moveToFirst(); assertEquals("seed-1", c.getString(0))
            }
            db.query("SELECT `content` FROM `english_entry`").use { c ->
                c.moveToFirst(); assertEquals("resilient", c.getString(0))
            }
            db.query("SELECT `title` FROM `assistant_conversation`").use { c ->
                c.moveToFirst(); assertEquals("第一问", c.getString(0))
            }

            // 8) sync_modified_at 已就位且初值 0
            db.query("SELECT `sync_modified_at` FROM `daily_task`").use { c ->
                c.moveToFirst(); assertEquals(0, c.getInt(0))
            }

            // 9) 同步元表存在，时钟初值 1
            for (t in listOf("sync_clock", "sync_tombstone", "sync_peer")) {
                val exists = db.query(
                    "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='$t'",
                ).use { c -> c.moveToFirst(); c.getLong(0) }
                assertEquals("同步元表 $t", 1L, exists)
            }
            db.query("SELECT `value` FROM `sync_clock` WHERE `id` = 1").use { c ->
                assertTrue("sync_clock 应有初值行", c.moveToFirst())
                assertEquals(1, c.getInt(0))
            }
        } finally {
            roomDb.close()
        }
    }
}
