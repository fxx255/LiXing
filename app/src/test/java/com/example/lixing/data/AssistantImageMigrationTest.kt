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

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class AssistantImageMigrationTest {
    @Test
    fun `v7 to v8 keeps messages and adds empty image paths`() {
        val context = RuntimeEnvironment.getApplication() as Context
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(null)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(7) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            db.execSQL(
                                "CREATE TABLE assistant_conversation (" +
                                    "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                                    "title TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)",
                            )
                            db.execSQL(
                                "CREATE TABLE assistant_message (" +
                                    "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                                    "conversation_id INTEGER NOT NULL, role TEXT NOT NULL, " +
                                    "content TEXT NOT NULL, created_at INTEGER NOT NULL, " +
                                    "FOREIGN KEY(conversation_id) REFERENCES assistant_conversation(id) ON DELETE CASCADE)",
                            )
                        }

                        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                    },
                )
                .build(),
        )

        try {
            val db = helper.writableDatabase
            db.execSQL("INSERT INTO assistant_conversation VALUES (1, 'kept', 1, 1)")
            db.execSQL("INSERT INTO assistant_message VALUES (1, 1, 'user', 'hello', 1)")

            Migrations.ALL.first { it.startVersion == 7 && it.endVersion == 8 }.migrate(db)

            val columns = db.query("PRAGMA table_info(assistant_message)").use { cursor ->
                buildList {
                    val nameIndex = cursor.getColumnIndexOrThrow("name")
                    while (cursor.moveToNext()) add(cursor.getString(nameIndex))
                }
            }
            assertTrue("image_paths" in columns)
            val row = db.query("SELECT content, image_paths FROM assistant_message WHERE id = 1").use { cursor ->
                cursor.moveToFirst()
                cursor.getString(0) to cursor.getString(1)
            }
            assertEquals("hello", row.first)
            assertEquals("", row.second)
        } finally {
            helper.close()
        }
    }

    @Test
    fun `v8 to v9 keeps messages and adds nullable display content`() {
        val context = RuntimeEnvironment.getApplication() as Context
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(null)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(8) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            db.execSQL(
                                "CREATE TABLE assistant_message (" +
                                    "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                                    "conversation_id INTEGER NOT NULL, role TEXT NOT NULL, " +
                                    "content TEXT NOT NULL, image_paths TEXT NOT NULL DEFAULT '', " +
                                    "created_at INTEGER NOT NULL)",
                            )
                        }

                        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                    },
                )
                .build(),
        )

        try {
            val db = helper.writableDatabase
            db.execSQL("INSERT INTO assistant_message VALUES (1, 1, 'user', 'ocr text', '[]', 1)")

            Migrations.ALL.first { it.startVersion == 8 && it.endVersion == 9 }.migrate(db)

            val row = db.query("SELECT content, image_paths, display_content FROM assistant_message").use { cursor ->
                cursor.moveToFirst()
                Triple(cursor.getString(0), cursor.getString(1), cursor.getString(2))
            }
            assertEquals("ocr text", row.first)
            assertEquals("[]", row.second)
            assertEquals(null, row.third)
        } finally {
            helper.close()
        }
    }
}
