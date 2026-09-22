package com.example.lixing.data

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.example.lixing.data.local.Migrations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 守卫 v13 → v14 迁移：新增**在途生成请求**表 `assistant_request`。
 *
 * 这张表解决的是「切后台/被查杀后回答凭空消失、也没有重试入口」：
 * 请求状态以前只活在 ViewModel 内存里，进程一没就什么都不剩。
 *
 * 迁移必须是**非破坏性**的（项目约定：不使用 destructive migration）：
 * 历史消息与待确认信封一个字都不能动，新表初始为空（= 没有在途请求）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class AssistantRequestMigrationTest {

    @Test
    fun `v13 to v14 creates request table, keeps messages and pending review`() {
        val context = RuntimeEnvironment.getApplication() as Context
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(null)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(13) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            // v13 时期的表：会话 + 消息（含 pending_review），还没有 assistant_request
                            db.execSQL(
                                "CREATE TABLE assistant_conversation (" +
                                    "`id` TEXT PRIMARY KEY NOT NULL, " +
                                    "`title` TEXT NOT NULL, " +
                                    "`created_at` INTEGER NOT NULL, " +
                                    "`updated_at` INTEGER NOT NULL, " +
                                    "`sync_modified_at` INTEGER NOT NULL DEFAULT 0)",
                            )
                            db.execSQL(
                                "CREATE TABLE assistant_message (" +
                                    "`id` TEXT PRIMARY KEY NOT NULL, " +
                                    "`conversation_id` TEXT NOT NULL, " +
                                    "`role` TEXT NOT NULL, " +
                                    "`content` TEXT NOT NULL, " +
                                    "`image_paths` TEXT NOT NULL DEFAULT '', " +
                                    "`display_content` TEXT, " +
                                    "`created_at` INTEGER NOT NULL, " +
                                    "`sync_modified_at` INTEGER NOT NULL DEFAULT 0, " +
                                    "`pending_review` TEXT NOT NULL DEFAULT '')",
                            )
                            db.execSQL("CREATE INDEX index_assistant_message_conversation_id ON assistant_message(conversation_id)")
                        }

                        override fun onUpgrade(
                            db: SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) = Unit
                    },
                )
                .build(),
        )

        try {
            val db = helper.writableDatabase
            db.execSQL(
                "INSERT INTO assistant_conversation (`id`, `title`, `created_at`, `updated_at`) " +
                    "VALUES ('c1', '原会话', 1000, 1000)",
            )
            db.execSQL(
                "INSERT INTO assistant_message " +
                    "(`id`, `conversation_id`, `role`, `content`, `created_at`, `pending_review`) " +
                    "VALUES ('m1', 'c1', 'assistant', '原有正文', 1000, '{\"actionsJson\":\"[]\"}')",
            )
            db.execSQL(
                "INSERT INTO assistant_conversation (`id`, `title`, `created_at`, `updated_at`) " +
                    "VALUES ('c2', '待级联会话', 1000, 1000)",
            )
            db.execSQL(
                "INSERT INTO assistant_message " +
                    "(`id`, `conversation_id`, `role`, `content`, `created_at`) " +
                    "VALUES ('m2', 'c2', 'user', '稍后要被级联删除', 1000)",
            )

            Migrations.ALL.first { it.startVersion == 13 && it.endVersion == 14 }.migrate(db)

            // 1) 新表存在，且带上了外键与索引
            val tables = db.query("SELECT name FROM sqlite_master WHERE type = 'table'").use { cursor ->
                buildList {
                    val nameIndex = cursor.getColumnIndexOrThrow("name")
                    while (cursor.moveToNext()) add(cursor.getString(nameIndex))
                }
            }
            assertTrue("迁移后应有 assistant_request 表，实际：$tables", "assistant_request" in tables)

            val indexes = db.query(
                "SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'assistant_request'",
            ).use { cursor ->
                buildList {
                    val nameIndex = cursor.getColumnIndexOrThrow("name")
                    while (cursor.moveToNext()) add(cursor.getString(nameIndex))
                }
            }
            assertTrue("应有 conversation_id 索引：$indexes", indexes.contains("index_assistant_request_conversation_id"))
            assertTrue("应有 status 索引：$indexes", indexes.contains("index_assistant_request_status"))

            // 2) 新表初始为空 = 没有在途请求，绝不会凭空产生待执行任务
            db.query("SELECT COUNT(*) FROM assistant_request").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("迁移不应凭空产生在途请求", 0, c.getInt(0))
            }

            // 3) 老数据原样保留：正文与待确认信封一个字都没动
            db.query("SELECT content, pending_review FROM assistant_message WHERE id = 'm1'").use { c ->
                assertTrue("原有消息不该在迁移中丢失", c.moveToFirst())
                assertEquals("原有正文", c.getString(0))
                assertEquals("{\"actionsJson\":\"[]\"}", c.getString(1))
            }

            // 4) 外键级联：删除会话要连带清掉它的请求记录（此处验证约束确实存在）
            val foreignKeys = db.query("PRAGMA foreign_key_list(`assistant_request`)").use { cursor ->
                buildList {
                    val tableIndex = cursor.getColumnIndexOrThrow("table")
                    val onDeleteIndex = cursor.getColumnIndexOrThrow("on_delete")
                    while (cursor.moveToNext()) {
                        add(cursor.getString(tableIndex) to cursor.getString(onDeleteIndex))
                    }
                }
            }
            assertTrue("应指向 assistant_conversation：$foreignKeys", foreignKeys.any { it.first == "assistant_conversation" })
            assertTrue("删除会话必须级联：$foreignKeys", foreignKeys.any { it.second == "CASCADE" })
        } finally {
            helper.close()
        }
    }

    @Test
    fun `migration list is contiguous from 1 to 14 with no destructive fallback`() {
        val versions = Migrations.ALL.map { it.startVersion to it.endVersion }
        assertEquals("迁移链必须从 1 连续到 14：$versions", 1, versions.first().first)
        assertEquals("迁移链终点必须是 14", 14, versions.last().second)
        versions.zipWithNext().forEach { (current, next) ->
            assertEquals("迁移链不能有断点：$versions", current.second, next.first)
        }
    }
}
