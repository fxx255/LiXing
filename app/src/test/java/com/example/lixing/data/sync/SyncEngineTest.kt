package com.example.lixing.data.sync

import android.content.Context
import androidx.room.Room
import com.example.lixing.data.local.LiXingDatabase
import com.example.lixing.data.local.entity.AssistantConversationEntity
import com.example.lixing.data.local.entity.AssistantMessageEntity
import com.example.lixing.data.local.entity.DailyTaskEntity
import com.example.lixing.data.local.entity.DayRecordEntity
import com.example.lixing.data.local.entity.EnglishEntryEntity
import com.example.lixing.data.local.entity.PhaseEntity
import com.example.lixing.data.local.entity.StudyPlanEntity
import com.example.lixing.data.local.entity.SubjectEntity
import com.example.lixing.data.local.entity.TaskTemplateEntity
import com.example.lixing.data.local.entity.TimeSlotEntity
import com.example.lixing.domain.english.EnglishEntryType
import com.example.lixing.domain.model.TargetType
import com.example.lixing.domain.model.TaskStatus
import com.example.lixing.domain.model.TaskType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalTime
import kotlin.time.Duration.Companion.seconds

/** 内存版传输：两个"设备"共享同一份"云"文件。 */
class FakeTransport : SyncTransport {
    val files = mutableMapOf<String, String>()

    override suspend fun list(): List<SyncRemoteFile> =
        files.map { SyncRemoteFile(it.key, it.value.toByteArray(Charsets.UTF_8).size.toLong()) }

    override suspend fun read(name: String): String? = files[name]

    override suspend fun write(name: String, content: String) {
        files[name] = content
    }

    override suspend fun append(name: String, lines: List<String>) {
        files[name] = files[name].orEmpty() + lines.joinToString("\n", postfix = "\n")
    }

    override suspend fun delete(name: String) {
        files.remove(name)
    }
}

/**
 * 同步内核端到端测试：Lamport 触发器、快照基线、增量日志、
 * 双设备收敛、墓碑删除、照片列隔离。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class SyncEngineTest {

    private lateinit var context: Context
    private lateinit var cloud: FakeTransport
    private lateinit var dbA: LiXingDatabase
    private lateinit var dbB: LiXingDatabase
    private lateinit var storeA: SyncLocalStore
    private lateinit var storeB: SyncLocalStore
    private lateinit var engineA: SyncEngine
    private lateinit var engineB: SyncEngine

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication() as Context
        cloud = FakeTransport()
        dbA = buildDatabase()
        dbB = buildDatabase()
        storeA = SyncLocalStore(dbA, Dispatchers.IO)
        storeB = SyncLocalStore(dbB, Dispatchers.IO)
        val identity = SyncIdentity(context)
        engineA = SyncEngine(storeA, identity, Dispatchers.IO)
        engineB = SyncEngine(storeB, identity, Dispatchers.IO)
    }

    @After
    fun tearDown() {
        dbA.close()
        dbB.close()
    }

    private fun buildDatabase(): LiXingDatabase =
        Room.inMemoryDatabaseBuilder(context, LiXingDatabase::class.java)
            .allowMainThreadQueries()
            .build()
            .also { db ->
                // 生产环境由 RoomDatabase.Callback.onOpen 安装；测试库手动装一遍
                db.openHelper.writableDatabase
                SyncTriggerInstaller().install(db.openHelper.writableDatabase)
            }

    // ---------------- 触发器与时钟 ----------------

    @Test
    fun `triggers bump lamport clock and keep tombstones on delete`() = runTest(timeout = 120.seconds) {
        val dao = dbA.englishEntryDao()
        // 注意：Room @Upsert 的实现依 SQLite 版本可能是「INSERT OR IGNORE + UPDATE」或
        // 「OR REPLACE」，新建一行可能触发一次或两次触发器。因此只断言单调递增，
        // 以及 sync_modified_at 与最终时钟一致——不数具体的步数。
        dao.upsert(EnglishEntryEntity(id = "en-1", type = EnglishEntryType.WORD, content = "resilient", meaning = "有韧性的"))
        val clockAfterInsert = storeA.clock()
        assertTrue("新行应推进时钟", clockAfterInsert >= 1)
        assertEquals(clockAfterInsert, dao.get("en-1")!!.syncModifiedAt)

        val beforeUpdate = storeA.clock()
        dao.upsert(dao.get("en-1")!!.copy(content = "resiliently"))
        val clockAfterUpdate = storeA.clock()
        assertTrue("更新应推进时钟", clockAfterUpdate > beforeUpdate)
        assertEquals(clockAfterUpdate, dao.get("en-1")!!.syncModifiedAt)

        val beforeDelete = storeA.clock()
        dao.delete(dao.get("en-1")!!)
        val clockAfterDelete = storeA.clock()
        assertNull("删除后行不存在", dao.get("en-1"))
        assertTrue("删除应推进时钟", clockAfterDelete > beforeDelete)

        val tombstones = dbA.openHelper.writableDatabase.query(
            "SELECT `row_id`, `deleted_at` FROM `sync_tombstone` WHERE `table_name` = 'english_entry'",
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(c.getString(0) to c.getLong(1))
            }
        }
        assertEquals("墓碑应记下删除", listOf("en-1" to clockAfterDelete), tombstones)
    }

    // ---------------- 双设备收敛 ----------------

    @Test
    fun `two devices converge plans edits and tombstones without touching photos`() = runTest(timeout = 300.seconds) {
        seedDeviceA(dbA)

        // A 首次同步：写快照基线
        val reportA = engineA.sync(cloud, DEVICE_A)
        assertTrue("A 首次应写快照", reportA.snapshotWritten)
        assertTrue("A 首次应无错误", reportA.errors.isEmpty())

        // B 首次同步：拉 A 基线
        val reportB = engineB.sync(cloud, DEVICE_B)
        assertTrue(reportB.errors.isEmpty())
        assertEquals(listOf(DEVICE_A), reportB.peers)
        assertTrue("B 应应用 A 的基线行", reportB.appliedRows > 0)

        assertNotNull("B 有了计划", dbB.planDao().getPlan(PLAN_A_ID))
        assertNotNull("B 有了科目", dbB.planDao().getSubject(SUBJECT_A_ID))
        assertNotNull("B 有了时段", dbB.planDao().getTimeSlot(SLOT_A_ID))
        assertNotNull("B 有了模板", dbB.taskTemplateDao().getTemplate(TEMPLATE_A_ID))
        val taskOnB = dbB.dailyTaskDao().getTask(TASK_A_ID)
        assertNotNull("B 应有任务", taskOnB)
        assertEquals("B 的任务照片列不搬运", null, taskOnB!!.checkinPhoto)
        assertNotNull("B 有了英语积累", dbB.englishEntryDao().get("a-en"))
        assertNotNull("B 有了对话", dbB.assistantChatDao().getConversation("a-conv"))
        val msgOnB = dbB.assistantChatDao().getMessages("a-conv").first()
        assertEquals("B 的消息图片路径不搬运", "", msgOnB.imagePaths)
        assertNotNull("B 有了 day_record（date 主键）", dbB.dayRecordDao().getRecord(DATE))

        // B 修改 A 的英语积累，并新建自己的对话
        dbB.englishEntryDao().upsert(
            dbB.englishEntryDao().get("a-en")!!.copy(meaning = "修订后的释义"),
        )
        val convB = AssistantConversationEntity(id = "b-conv", title = "B 的对话")
        dbB.assistantChatDao().insertConversation(convB)
        dbB.assistantChatDao().insertMessage(
            AssistantMessageEntity(
                id = "b-msg",
                conversationId = convB.id,
                role = "user",
                content = "B 的问题",
            ),
        )
        // B 也写一条本地计划，测双向合并
        dbB.planDao().insertPlan(
            StudyPlanEntity(id = "b-plan", name = "B 的考证计划", startDate = DATE, targetDate = DATE.plusMonths(3)),
        )

        engineB.sync(cloud, DEVICE_B)
        engineA.sync(cloud, DEVICE_A)

        assertEquals("A 收到 B 的释义修订", "修订后的释义", dbA.englishEntryDao().get("a-en")!!.meaning)
        assertNotNull("A 收到 B 的对话", dbA.assistantChatDao().getConversation("b-conv"))
        assertNotNull("A 收到 B 的计划", dbA.planDao().getPlan("b-plan"))
        // A 的照片路径不能被对端空值清空（A 从未收到 checkin_photo 列）
        assertEquals("file:///a-task.jpg", dbA.dailyTaskDao().getTask(TASK_A_ID)!!.checkinPhoto)

        // B 删除任务 → 墓碑 → A 删掉
        dbB.dailyTaskDao().deleteById(TASK_A_ID)
        engineB.sync(cloud, DEVICE_B)
        engineA.sync(cloud, DEVICE_A)
        assertNull("A 收到墓碑后删除任务", dbA.dailyTaskDao().getTask(TASK_A_ID))
        assertNull("B 已删除任务", dbB.dailyTaskDao().getTask(TASK_A_ID))
    }

    // ---------------- 工具 ----------------

    private suspend fun seedDeviceA(db: LiXingDatabase) {
        val planDao = db.planDao()
        planDao.insertPlan(
            StudyPlanEntity(id = PLAN_A_ID, name = "A 的考研计划", startDate = DATE, targetDate = DATE.plusMonths(4)),
        )
        planDao.upsertPhase(
            PhaseEntity(id = "a-phase", planId = PLAN_A_ID, name = "强化", startDate = DATE, endDate = DATE.plusDays(30)),
        )
        planDao.upsertSubject(
            SubjectEntity(id = SUBJECT_A_ID, planId = PLAN_A_ID, name = "数学", colorArgb = 0xFF3366.toInt()),
        )
        val slot = TimeSlotEntity(
            id = SLOT_A_ID,
            planId = PLAN_A_ID,
            name = "上午",
            startTime = LocalTime.of(8, 0),
            endTime = LocalTime.of(11, 30),
        )
        planDao.upsertTimeSlot(slot)
        db.taskTemplateDao().upsertTemplate(
            TaskTemplateEntity(
                id = TEMPLATE_A_ID,
                subjectId = SUBJECT_A_ID,
                timeSlotId = SLOT_A_ID,
                title = "660 题",
                targetType = TargetType.MINUTES,
                targetValue = 120,
            ),
        )
        db.dailyTaskDao().insert(
            DailyTaskEntity(
                id = TASK_A_ID,
                date = DATE,
                templateId = TEMPLATE_A_ID,
                subjectId = SUBJECT_A_ID,
                subjectName = "数学",
                subjectColorArgb = 0xFF3366.toInt(),
                timeSlotId = SLOT_A_ID,
                slotName = "上午",
                slotStart = slot.startTime,
                slotEnd = slot.endTime,
                title = "背单词",
                taskType = TaskType.MEMORIZE,
                targetType = TargetType.COUNT,
                targetValue = 20,
                status = TaskStatus.PENDING,
                checkinPhoto = "file:///a-task.jpg",
            ),
        )
        db.englishEntryDao().upsert(
            EnglishEntryEntity(id = "a-en", type = EnglishEntryType.WORD, content = "resilient", meaning = "有韧性的"),
        )
        val conv = AssistantConversationEntity(id = "a-conv", title = "A 的对话")
        db.assistantChatDao().insertConversation(conv)
        db.assistantChatDao().insertMessage(
            AssistantMessageEntity(
                id = "a-msg",
                conversationId = conv.id,
                role = "user",
                content = "如何安排时间",
                imagePaths = """["/local/a.jpg"]""",
            ),
        )
        db.dayRecordDao().upsert(DayRecordEntity(date = DATE))
    }

    private companion object {
        const val DEVICE_A = "device-a-1111"
        const val DEVICE_B = "device-b-2222"
        const val PLAN_A_ID = "a-plan"
        const val SUBJECT_A_ID = "a-subject"
        const val SLOT_A_ID = "a-slot"
        const val TEMPLATE_A_ID = "a-template"
        const val TASK_A_ID = "a-task"
        val DATE: LocalDate = LocalDate.of(2026, 8, 9)
    }
}
