package com.example.lixing.data

import android.app.Application
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.example.lixing.data.local.LiXingDatabase
import com.example.lixing.data.local.Migrations
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class EnglishReviewMigrationTest {
    @Test fun `v12 migrates into Room v13 without resetting due dates or existing words`() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val name = "english-v12-migration.db"
        context.deleteDatabase(name)
        val schema = javaClass.getResourceAsStream("/com.example.lixing.data.local.LiXingDatabase/12.json")!!.bufferedReader().use { Json.parseToJsonElement(it.readText()) }.jsonObject["database"]!!.jsonObject
        val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context).name(name).callback(object : SupportSQLiteOpenHelper.Callback(12) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                schema["entities"]!!.jsonArray.forEach { item ->
                    val entity = item.jsonObject
                    val table = entity["tableName"]!!.jsonPrimitive.content
                    db.execSQL(entity["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                    (entity["indices"] as? JsonArray).orEmpty().forEach { index -> db.execSQL(index.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table)) }
                }
                db.execSQL("INSERT INTO english_entry (id,type,content,meaning,created_at,updated_at,review_due_at,review_last_at,review_reps,review_interval_days) VALUES ('legacy','WORD','abide','遵守',1,2,9999999,123456,3,6)")
            }
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }).build())
        helper.writableDatabase
        helper.close()
        val db = Room.databaseBuilder(context, LiXingDatabase::class.java, name).addMigrations(*Migrations.ALL).allowMainThreadQueries().build()
        try {
            val word = db.englishEntryDao().get("legacy")!!
            assertEquals("遵守", word.meaning)
            assertEquals(9999999L, word.reviewDueAt!!.toEpochMilli())
            assertEquals(3, word.reviewReps)
            assertTrue(db.englishEntryDao().history(word.id).isEmpty())
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
