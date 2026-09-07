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
class EnglishEntryMigrationTest {
    @Test
    fun `v5 to v6 creates usable english table without touching existing rows`() {
        val context = RuntimeEnvironment.getApplication() as Context
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(null)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(5) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            db.execSQL("CREATE TABLE legacy_sentinel (`id` INTEGER PRIMARY KEY NOT NULL, `value` TEXT NOT NULL)")
                        }

                        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                    },
                )
                .build(),
        )

        try {
            val db = helper.writableDatabase
            db.execSQL("INSERT INTO legacy_sentinel (`id`, `value`) VALUES (1, 'kept')")
            Migrations.ALL.first { it.startVersion == 5 && it.endVersion == 6 }.migrate(db)

            val columns = db.query("PRAGMA table_info(`english_entry`)").use { cursor ->
                buildList {
                    val nameIndex = cursor.getColumnIndexOrThrow("name")
                    while (cursor.moveToNext()) add(cursor.getString(nameIndex))
                }
            }
            assertEquals(listOf("id", "type", "content", "meaning", "created_at", "updated_at"), columns)

            val indices = db.query("PRAGMA index_list(`english_entry`)").use { cursor ->
                buildList {
                    val nameIndex = cursor.getColumnIndexOrThrow("name")
                    while (cursor.moveToNext()) add(cursor.getString(nameIndex))
                }
            }
            assertTrue("index_english_entry_type" in indices)
            assertTrue("index_english_entry_updated_at" in indices)

            db.execSQL(
                "INSERT INTO english_entry (`type`, `content`, `meaning`, `created_at`, `updated_at`) " +
                    "VALUES ('WORD', 'resilient', '有韧性的', 1, 1)",
            )
            assertEquals(
                "resilient",
                db.query("SELECT content FROM english_entry").use { cursor ->
                    cursor.moveToFirst()
                    cursor.getString(0)
                },
            )
            assertEquals(
                "kept",
                db.query("SELECT value FROM legacy_sentinel WHERE id = 1").use { cursor ->
                    cursor.moveToFirst()
                    cursor.getString(0)
                },
            )
        } finally {
            helper.close()
        }
    }
}
