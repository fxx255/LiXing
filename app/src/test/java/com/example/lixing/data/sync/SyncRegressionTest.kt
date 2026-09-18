package com.example.lixing.data.sync

import android.content.Context
import androidx.room.Room
import com.example.lixing.data.local.LiXingDatabase
import com.example.lixing.data.local.entity.*
import com.example.lixing.domain.english.EnglishEntryType
import com.example.lixing.domain.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalTime

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class SyncRegressionTest {
    private val databases = mutableListOf<LiXingDatabase>()
    private lateinit var context: Context
    private val cloud = FakeTransport()
    private val json = Json { encodeDefaults = true }

    @Before fun setUp() { context = RuntimeEnvironment.getApplication() }
    @After fun tearDown() { databases.forEach { it.close() } }

    private inner class Device(val id: String) {
        val db = Room.inMemoryDatabaseBuilder(context, LiXingDatabase::class.java)
            .allowMainThreadQueries().build().also {
                databases += it
                SyncTriggerInstaller().install(it.openHelper.writableDatabase)
            }
        val store = SyncLocalStore(db, Dispatchers.IO)
        val engine = SyncEngine(store, SyncIdentity(context), Dispatchers.IO)
        suspend fun sync() = engine.sync(cloud, id)
        suspend fun word() = db.englishEntryDao().get("word")!!
    }

    private fun word(meaning: String = "initial") = EnglishEntryEntity(
        id = "word", type = EnglishEntryType.WORD, content = "abide", meaning = meaning,
    )

    @Test fun `meal records sync without requiring a local photo on the other device`() = runTest {
        val a = Device("A"); val b = Device("B")
        a.db.mealRecordDao().upsert(MealRecordEntity(
            id = "meal", date = DATE, mealType = "BREAKFAST", photoPath = "/local/breakfast.jpg",
            foodName = "早餐", servingGrams = 100, caloriesKcal = 100, proteinGrams = 1f,
            carbsGrams = 1f, fatGrams = 1f, fiberGrams = 1f, modelLabel = "test", confidence = 1f, advice = "",
        ))
        assertTrue(a.sync().isSuccess)
        val report = b.sync()
        assertTrue(report.errors.joinToString(), report.isSuccess)
        val incoming = b.store.readChangesSince(-1).single { it.table == "meal_record" }
        assertEquals("早餐", incoming.columns["food_name"]?.value)
        assertFalse(incoming.columns.containsKey("photo_path"))
    }

    @Test fun `editing from a stale entity still advances clock and syncs`() = runTest {
        val a = Device("A"); val b = Device("B")
        val original = word()
        a.db.englishEntryDao().upsert(original)
        assertTrue(a.sync().isSuccess); assertTrue(b.sync().isSuccess)
        val before = a.store.clock()
        // UI may still hold the object from before SQLite added its sync clock.
        a.db.englishEntryDao().upsert(original.copy(meaning = "edited"))
        assertTrue("stale DAO entities must not reset the row clock", a.store.clock() > before)
        assertTrue(a.sync().isSuccess); assertTrue(b.sync().isSuccess)
        assertEquals("edited", b.word().meaning)
    }

    @Test fun `legacy clock zero rows are imported regardless of device id`() = runTest {
        val a = Device("A"); val z = Device("Z")
        a.store.withApplyingGuard { a.db.englishEntryDao().upsert(word()) }
        assertTrue(a.sync().isSuccess); assertTrue(z.sync().isSuccess)
        assertNotNull("migrated rows at clock zero are not older than absence", z.db.englishEntryDao().get("word"))
    }

    @Test fun `legacy plan and historical checkin skipped by old versions are repaired`() = runTest {
        val a = Device("A"); val z = Device("Z")
        a.store.withApplyingGuard {
            seedPlan(a.db)
            a.db.dailyTaskDao().insert(task("history").copy(status = TaskStatus.DONE, actualValue = 20))
        }
        a.sync()
        // Simulate the old client marking the baseline consumed without importing its clock-zero rows.
        z.store.saveCursor("A", a.store.clock())
        z.store.saveCursor(SELF_CURSOR_KEY, 100)
        val repaired = z.sync()
        assertTrue(repaired.errors.joinToString(), repaired.isSuccess)
        assertNotNull(z.db.planDao().getPlan("plan"))
        assertNotNull(z.db.taskTemplateDao().getTemplate("template"))
        assertEquals(TaskStatus.DONE, z.db.dailyTaskDao().getTask("history")!!.status)
    }

    @Test fun `active plan selection converges even when insertion order differs`() = runTest {
        val a = Device("A"); val b = Device("B")
        a.db.planDao().insertPlan(StudyPlanEntity(id = "plan-a", name = "A", startDate = DATE, targetDate = DATE.plusMonths(1)))
        b.db.planDao().insertPlan(StudyPlanEntity(id = "plan-b", name = "B", startDate = DATE, targetDate = DATE.plusMonths(1)))
        a.sync(); b.sync(); a.sync()
        assertEquals(a.db.planDao().getActivePlan()!!.id, b.db.planDao().getActivePlan()!!.id)
    }

    @Test fun `failed import does not consume peer cursor and can be retried`() = runTest {
        val a = Device("A"); val b = Device("B")
        a.db.englishEntryDao().upsert(word())
        a.sync(); b.sync()
        a.db.assistantChatDao().insertConversation(AssistantConversationEntity(id = "conv", title = "题目"))
        a.db.assistantChatDao().insertMessage(AssistantMessageEntity(id = "msg", conversationId = "conv", role = "user", content = "question"))
        a.sync()
        val logName = SyncFiles.log("A")
        val log = cloud.files.getValue(logName)
        val rows = log.lineSequence().filter { it.isNotBlank() }.map { json.decodeFromString<SyncRow>(it) }.toList()
        cloud.files[logName] = rows.filter { it.table != "assistant_conversation" }
            .joinToString("\n") { json.encodeToString(SyncRow.serializer(), it) }
        val cursor = b.store.cursors()["A"]
        assertFalse(b.sync().isSuccess)
        assertEquals("failed rows must remain pending", cursor, b.store.cursors()["A"])
        cloud.files[logName] = log
        assertTrue(b.sync().isSuccess)
        assertEquals("question", b.db.assistantChatDao().getMessages("conv").single().content)
    }

    @Test fun `third device forwarding an equal clock never changes the winner`() = runTest {
        val a = Device("A"); val b = Device("B"); val c = Device("C")
        a.db.englishEntryDao().upsert(word("A wins until B is seen"))
        b.db.englishEntryDao().upsert(word("B wins"))
        a.sync(); c.sync() // C now holds A's row and republishes it under C's file.
        b.sync(); a.sync(); c.sync(); b.sync(); a.sync()
        assertEquals("B wins", a.word().meaning)
        assertEquals(a.word().meaning, b.word().meaning)
        assertEquals(a.word().meaning, c.word().meaning)
    }

    @Test fun `independently created daily tasks merge checkins instead of colliding`() = runTest {
        val a = Device("A"); val b = Device("B")
        seedPlan(a.db)
        a.sync(); b.sync()
        a.db.dailyTaskDao().insert(task("task-a").copy(checkinPhoto = "/local/a.jpg"))
        b.db.dailyTaskDao().insert(task("task-b"))
        a.sync()
        b.db.dailyTaskDao().update(task("task-b").copy(status = TaskStatus.DONE, actualValue = 20))
        b.db.focusSessionDao().insert(FocusSessionEntity(id = "focus", date = DATE, dailyTaskId = "task-b",
            subjectId = "subject", startedAt = java.time.Instant.parse("2026-09-20T08:00:00Z")))
        b.db.gamificationDao().insertLedger(PointLedgerEntity(id = "points", date = DATE, delta = 10,
            reason = PointReason.entries.first(), dailyTaskId = "task-b", dedupeKey = "checkin:task-b"))
        val onB = b.sync()
        assertTrue(onB.errors.joinToString(), onB.isSuccess)
        val onA = a.sync()
        assertTrue(onA.errors.joinToString(), onA.isSuccess)
        assertEquals(TaskStatus.DONE, a.db.dailyTaskDao().getTask("task-a")!!.status)
        assertEquals(TaskStatus.DONE, b.db.dailyTaskDao().getTask("task-b")!!.status)
        assertEquals("/local/a.jpg", a.db.dailyTaskDao().getTask("task-a")!!.checkinPhoto)
        assertEquals("task-a", a.db.focusSessionDao().getSession("focus")!!.dailyTaskId)
        assertEquals("task-a", a.db.gamificationDao().getLedgerOfDay(DATE).single().dailyTaskId)
        assertEquals("checkin:task-a", a.db.gamificationDao().getLedgerOfDay(DATE).single().dedupeKey)
        b.db.dailyTaskDao().deleteById("task-b")
        assertTrue(b.sync().isSuccess); assertTrue(a.sync().isSuccess)
        assertNull(a.db.dailyTaskDao().getTask("task-a"))
    }

    @Test fun `restoring data resets old tombstones and clock before the next edit`() = runTest {
        val a = Device("A")
        a.db.englishEntryDao().upsert(word())
        a.db.englishEntryDao().delete(a.word())
        a.store.withApplyingGuard { a.db.englishEntryDao().upsert(word().copy(syncModifiedAt = 10_000)) }
        a.store.resetAfterRestore()
        assertTrue(a.store.clock() >= 10_000)
        assertTrue(a.store.readChangesSince(-1).none { it.deleted })
        a.db.englishEntryDao().upsert(a.word().copy(meaning = "after restore"))
        assertTrue(a.word().syncModifiedAt > 10_000)
    }

    private suspend fun seedPlan(db: LiXingDatabase) {
        db.planDao().insertPlan(StudyPlanEntity(id = "plan", name = "plan", startDate = DATE, targetDate = DATE.plusMonths(1)))
        db.planDao().upsertSubject(SubjectEntity(id = "subject", planId = "plan", name = "英语", colorArgb = 0))
        db.planDao().upsertTimeSlot(TimeSlotEntity(id = "slot", planId = "plan", name = "上午", startTime = LocalTime.of(8, 0), endTime = LocalTime.of(10, 0)))
        db.taskTemplateDao().upsertTemplate(TaskTemplateEntity(id = "template", subjectId = "subject", timeSlotId = "slot", title = "单词", targetType = TargetType.COUNT, targetValue = 20))
    }

    private fun task(id: String) = DailyTaskEntity(
        id = id, date = DATE, templateId = "template", subjectId = "subject", subjectName = "英语",
        subjectColorArgb = 0, timeSlotId = "slot", slotName = "上午", slotStart = LocalTime.of(8, 0),
        slotEnd = LocalTime.of(10, 0), title = "单词", taskType = TaskType.MEMORIZE,
        targetType = TargetType.COUNT, targetValue = 20,
    )

    companion object { val DATE: LocalDate = LocalDate.of(2026, 9, 20) }
}
