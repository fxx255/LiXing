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
 * 守卫 v11 → v12 迁移：助手消息加 `pending_review` 列。
 *
 * 这一列用来让**待确认方案跨进程存活** —— 以前它只是 ViewModel 的内存状态，
 * 而 `openConversation` 又会主动清空，于是用户「还没点确认就锁屏 / 切走再回来」，
 * 方案和按钮一起消失，界面上连痕迹都不留。
 *
 * 迁移必须是**非破坏性**的（项目约定：不使用 destructive migration）：
 * 老消息的正文一个字都不能动，新列取空串默认值（= 没有待确认项）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class AssistantPendingReviewMigrationTest {

    @Test
    fun `v11 to v12 adds pending_review as empty default and keeps existing messages`() {
        val context = RuntimeEnvironment.getApplication() as Context
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(null)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(11) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            // v11 时期的 assistant_message：还没有 pending_review
                            db.execSQL(
                                "CREATE TABLE assistant_message (" +
                                    "`id` TEXT PRIMARY KEY NOT NULL, " +
                                    "`conversation_id` TEXT NOT NULL, " +
                                    "`role` TEXT NOT NULL, " +
                                    "`content` TEXT NOT NULL, " +
                                    "`image_paths` TEXT NOT NULL DEFAULT '', " +
                                    "`display_content` TEXT, " +
                                    "`created_at` INTEGER NOT NULL, " +
                                    "`sync_modified_at` INTEGER NOT NULL DEFAULT 0)",
                            )
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
                "INSERT INTO assistant_message " +
                    "(`id`, `conversation_id`, `role`, `content`, `created_at`) " +
                    "VALUES ('m1', 'c1', 'assistant', '原有正文', 1000)",
            )

            Migrations.ALL.first { it.startVersion == 11 && it.endVersion == 12 }.migrate(db)

            val columns = db.query("PRAGMA table_info(`assistant_message`)").use { cursor ->
                buildList {
                    val nameIndex = cursor.getColumnIndexOrThrow("name")
                    while (cursor.moveToNext()) add(cursor.getString(nameIndex))
                }
            }
            assertTrue("迁移后应有 pending_review 列，实际：$columns", "pending_review" in columns)

            // 老数据原样保留；新列是空串 = 「没有待确认项」
            db.query("SELECT content, pending_review FROM assistant_message WHERE id = 'm1'").use { c ->
                assertTrue("原有消息不该在迁移中丢失", c.moveToFirst())
                assertEquals("原有正文", c.getString(0))
                assertEquals("", c.getString(1))
            }
        } finally {
            helper.close()
        }
    }
}
