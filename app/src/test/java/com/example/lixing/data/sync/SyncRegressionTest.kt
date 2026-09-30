package com.example.lixing.data.sync

import android.content.Context
import androidx.room.Room
import com.example.lixing.data.local.LiXingDatabase
import com.example.lixing.data.local.entity.*
import com.example.lixing.domain.english.EnglishEntryType
import com.example.lixing.domain.model.*
import com.example.lixing.domain.rules.PointRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.first
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
import java.security.MessageDigest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

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
        suspend fun sync(studyToday: LocalDate? = null) = if (studyToday == null) {
            engine.sync(cloud, id)
        } else {
            engine.sync(cloud, id, 0.6f, studyToday, 1)
        }
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

    @Test fun `protocol upgrade rewrites the existing device snapshot`() = runTest {
        val phone = Device("phone")
        phone.db.englishEntryDao().upsert(word())
        assertTrue(phone.sync().isSuccess)
        val name = SyncFiles.snapshot("phone")
        val old = json.decodeFromString<SyncSnapshot>(cloud.files.getValue(name))
        cloud.files[name] = json.encodeToString(old.copy(format = 2))
        phone.store.saveCursor("__sync_format__", 2)

        val upgraded = phone.sync()
        assertTrue(upgraded.isSuccess)
        assertTrue(upgraded.snapshotWritten)
        assertEquals(SYNC_FORMAT, json.decodeFromString<SyncSnapshot>(cloud.files.getValue(name)).format)
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
        val batch = json.decodeFromString<SyncDeltaBatch>(log.trim())
        val rows = batch.rows.filter { it.table != "assistant_conversation" }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(json.encodeToString(rows).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        cloud.files[logName] = json.encodeToString(batch.copy(
            rowCount = rows.size, rowsSha256 = digest, rows = rows))
        val cursor = b.store.cursors()["A"]
        assertFalse(b.sync().isSuccess)
        assertEquals("failed rows must remain pending", cursor, b.store.cursors()["A"])
        cloud.files[logName] = log
        assertTrue(b.sync().isSuccess)
        assertEquals("question", b.db.assistantChatDao().getMessages("conv").single().content)
    }

    @Test fun `truncated incremental batch does not apply its valid prefix`() = runTest {
        val a = Device("A"); val b = Device("B")
        a.db.englishEntryDao().upsert(word())
        a.sync(); b.sync()
        a.db.assistantChatDao().insertConversation(AssistantConversationEntity(id = "conv", title = "题目"))
        a.db.assistantChatDao().insertMessage(AssistantMessageEntity(id = "msg", conversationId = "conv", role = "user", content = "question"))
        a.sync()
        val logName = SyncFiles.log("A")
        val full = cloud.files.getValue(logName)
        cloud.files[logName] = full.dropLast(30)
        val cursor = b.store.cursors()["A"]
        assertFalse(b.sync().isSuccess)
        assertEquals(cursor, b.store.cursors()["A"])
        assertNull(b.db.assistantChatDao().getConversation("conv"))
        cloud.files[logName] = full
        val recovered = b.sync()
        assertTrue(recovered.errors.joinToString(), recovered.isSuccess)
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

    @Test fun `concurrent dated edits retain both local candidates and report conflict`() = runTest {
        val a = Device("A"); val b = Device("B")
        seedPlan(a.db)
        val base = ScheduledTaskEntity(id = "dated", planId = "plan", studyDate = DATE,
            subjectId = "subject", timeSlotId = "slot", title = "初稿")
        a.db.planningDao().upsertScheduledTask(base)
        assertTrue(a.sync().isSuccess)
        assertTrue(b.sync().isSuccess)
        a.db.planningDao().upsertScheduledTask(base.copy(title = "甲修改", revision = 2))
        b.db.planningDao().upsertScheduledTask(base.copy(title = "乙修改", revision = 2))
        assertTrue(a.sync().isSuccess)
        val cursor = b.store.cursors()["A"]
        val report = b.sync()
        assertFalse(report.isSuccess)
        assertEquals(cursor, b.store.cursors()["A"])
        assertEquals("乙修改", b.db.planningDao().getScheduledTask("dated")?.title)
        assertEquals(1, b.db.planningDao().observeSyncConflicts().first().size)
        b.db.planningDao().upsertScheduledTask(base.copy(title = "甲修改", revision = 3))
        val retried = b.sync()
        assertTrue(retried.errors.joinToString(), retried.isSuccess)
        assertTrue(b.db.planningDao().observeSyncConflicts().first().isEmpty())
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

    @Test fun `offline checkin beats another devices later automatic settlement and restores summary`() = runTest {
        val phone = Device("phone"); val tablet = Device("tablet"); val third = Device("third")
        seedPlan(phone.db)
        val date = LocalDate.of(2026, 9, 30)
        phone.db.dailyTaskDao().insert(task("shared").copy(date = date, plannedMinutes = 480))
        phone.db.dailyTaskDao().insert(task("small").copy(date = date, templateId = null, plannedMinutes = 60))
        phone.db.dayRecordDao().upsert(DayRecordEntity(date = date))
        phone.db.gamificationDao().upsertStreak(CheckInStreakEntity())
        assertTrue(phone.sync().isSuccess)
        assertTrue(tablet.sync().isSuccess)

        val original = phone.db.dailyTaskDao().getTask("shared")!!
        phone.db.dailyTaskDao().update(original.copy(status = TaskStatus.DONE, actualValue = 20))
        phone.db.dayRecordDao().upsert(DayRecordEntity(date = date,
            totalTasks = 2, doneTasks = 1, completionRate = 480f / 540f))
        phone.db.gamificationDao().upsertStreak(CheckInStreakEntity(
            currentStreak = 1, lastAchievedDate = date, totalAchievedDays = 1,
            rescueCardsLeft = 1, rescueCardsMonth = 202609))

        tablet.db.dailyTaskDao().update(tablet.db.dailyTaskDao().getTask("shared")!!.copy(status = TaskStatus.MISSED))
        tablet.db.dailyTaskDao().update(tablet.db.dailyTaskDao().getTask("small")!!.copy(status = TaskStatus.MISSED))
        tablet.db.gamificationDao().insertLedger(PointLedgerEntity(id = "false-penalty", date = date,
            delta = -5, reason = PointReason.MISSED_PENALTY, dailyTaskId = "shared",
            dedupeKey = PointRules.keyMissed("shared")))
        tablet.db.dayRecordDao().upsert(DayRecordEntity(date = date,
            totalTasks = 2, missedTasks = 2, completionRate = 0f, pointsEarned = -5,
            isSettled = true, usedRescueCard = true))
        tablet.db.gamificationDao().upsertStreak(CheckInStreakEntity(
            currentStreak = 0, rescueCardsLeft = 0, rescueCardsMonth = 202609))
        val today = date.plusDays(1)
        assertTrue(tablet.sync(today).isSuccess)
        assertTrue(phone.sync(today).isSuccess)
        assertTrue(tablet.sync(today).isSuccess)
        assertTrue(third.sync(today).isSuccess)

        for (device in listOf(phone, tablet, third)) {
            assertEquals(TaskStatus.DONE, device.db.dailyTaskDao().getTask("shared")!!.status)
            val record = device.db.dayRecordDao().getRecord(date)!!
            assertEquals(1, record.doneTasks)
            assertEquals(1, record.missedTasks)
            assertEquals(480f / 540f, record.completionRate, 0.001f)
            assertTrue(record.isAchieved)
            assertFalse(record.usedRescueCard)
            assertEquals(0, record.pointsEarned)
            assertTrue(device.db.gamificationDao().getLedgerOfDay(date).isEmpty())
            assertEquals(1, device.db.gamificationDao().getStreak()!!.currentStreak)
            assertEquals(1, device.db.gamificationDao().getStreak()!!.rescueCardsLeft)
        }
    }

    @Test fun `explicit revoke is not mistaken for automatic missed status`() = runTest {
        val phone = Device("phone"); val tablet = Device("tablet")
        seedPlan(phone.db)
        phone.db.dailyTaskDao().insert(task("shared"))
        assertTrue(phone.sync().isSuccess)
        assertTrue(tablet.sync().isSuccess)
        phone.db.dailyTaskDao().update(phone.db.dailyTaskDao().getTask("shared")!!
            .copy(status = TaskStatus.PENDING, skipReason = "USER_REVOKED"))
        tablet.db.dailyTaskDao().update(tablet.db.dailyTaskDao().getTask("shared")!!
            .copy(status = TaskStatus.MISSED))
        assertTrue(tablet.sync().isSuccess)
        assertTrue(phone.sync().isSuccess)
        assertTrue(tablet.sync().isSuccess)
        assertEquals("USER_REVOKED", phone.db.dailyTaskDao().getTask("shared")!!.skipReason)
        assertEquals("USER_REVOKED", tablet.db.dailyTaskDao().getTask("shared")!!.skipReason)
    }

    @Test fun `next day makeup keeps the original missed penalty`() = runTest {
        val phone = Device("phone"); val tablet = Device("tablet")
        seedPlan(phone.db)
        phone.db.dailyTaskDao().insert(task("shared"))
        assertTrue(phone.sync().isSuccess)
        assertTrue(tablet.sync().isSuccess)
        phone.db.dailyTaskDao().update(phone.db.dailyTaskDao().getTask("shared")!!
            .copy(status = TaskStatus.DONE, actualValue = 20, isMakeup = true))
        tablet.db.dailyTaskDao().update(tablet.db.dailyTaskDao().getTask("shared")!!
            .copy(status = TaskStatus.MISSED))
        tablet.db.gamificationDao().insertLedger(PointLedgerEntity(id = "real-penalty", date = DATE,
            delta = -5, reason = PointReason.MISSED_PENALTY, dailyTaskId = "shared",
            dedupeKey = PointRules.keyMissed("shared")))
        tablet.db.dayRecordDao().upsert(DayRecordEntity(date = DATE, isSettled = true, usedRescueCard = true))
        tablet.db.gamificationDao().upsertStreak(CheckInStreakEntity(
            rescueCardsLeft = 0, rescueCardsMonth = 202609))
        assertTrue(tablet.sync().isSuccess)
        assertTrue(phone.sync().isSuccess)
        assertTrue(tablet.sync().isSuccess)
        assertEquals(TaskStatus.DONE, tablet.db.dailyTaskDao().getTask("shared")!!.status)
        assertEquals(-5, tablet.db.gamificationDao().getPointsOfDay(DATE))
        assertTrue(tablet.db.dayRecordDao().getRecord(DATE)!!.usedRescueCard)
        assertEquals(0, tablet.db.gamificationDao().getStreak()!!.rescueCardsLeft)
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
