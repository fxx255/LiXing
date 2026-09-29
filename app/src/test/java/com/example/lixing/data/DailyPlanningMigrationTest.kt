package com.example.lixing.data

import android.app.Application
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.example.lixing.data.local.LiXingDatabase
import com.example.lixing.data.local.Migrations
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Open a real v14 schema through Room v15 so the migration and entity definitions are compared. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class DailyPlanningMigrationTest {
    @Test
    fun `v14 data survives dated planning migration`() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val name = "planning-v14-migration.db"
        context.deleteDatabase(name)
        val schema = javaClass.getResourceAsStream(
            "/com.example.lixing.data.local.LiXingDatabase/14.json",
        )!!.bufferedReader().use {
            Json.parseToJsonElement(it.readText()).jsonObject["database"]!!.jsonObject
        }
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(14) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        schema["entities"]!!.jsonArray.forEach { item ->
                            val entity = item.jsonObject
                            val table = entity["tableName"]!!.jsonPrimitive.content
                            db.execSQL(entity["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                            (entity["indices"] as? JsonArray).orEmpty().forEach { index ->
                                db.execSQL(index.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                            }
                        }
                        db.execSQL("INSERT INTO study_plan (id,name,start_date,target_date,is_active,note,created_at,sync_modified_at) VALUES ('legacy-plan','旧计划',0,1000,1,'',0,0)")
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }).build(),
        )
        helper.writableDatabase
        helper.close()
        val db = Room.databaseBuilder(context, LiXingDatabase::class.java, name)
            .addMigrations(*Migrations.ALL).allowMainThreadQueries().build()
        try {
            assertEquals("旧计划", db.planDao().getPlan("legacy-plan")?.name)
            assertTrue(db.planningDao().getScheduledTasks("legacy-plan", java.time.LocalDate.ofEpochDay(0)).isEmpty())
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }
}
