package com.example.lixing.domain

import android.content.Context
import androidx.room.Room
import com.example.lixing.data.local.LiXingDatabase
import com.example.lixing.data.local.entity.DailyTaskEntity
import com.example.lixing.data.local.entity.DayRecordEntity
import com.example.lixing.data.local.entity.StudyPlanEntity
import com.example.lixing.data.local.entity.SubjectEntity
import com.example.lixing.data.local.entity.TaskTemplateEntity
import com.example.lixing.data.local.entity.TimeSlotEntity
import com.example.lixing.data.repository.PlanRepository
import com.example.lixing.data.repository.TaskRepository
import com.example.lixing.domain.assistant.PlanAction
import com.example.lixing.domain.assistant.PlanChangeApplier
import com.example.lixing.domain.model.TargetType
import com.example.lixing.domain.model.TaskStatus
import com.example.lixing.domain.model.TaskType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
class PlanChangeApplierTest {
    private val today: LocalDate = LocalDate.of(2026, 8, 9)
    private lateinit var database: LiXingDatabase
    private lateinit var planRepository: PlanRepository
    private lateinit var taskRepository: TaskRepository
    private lateinit var applier: PlanChangeApplier

    private lateinit var slot: TimeSlotEntity
    private lateinit var subject: SubjectEntity
    private lateinit var template: TaskTemplateEntity

    @Before
    fun setUp() = runTest {
        val context = RuntimeEnvironment.getApplication() as Context
        database = Room.inMemoryDatabaseBuilder(context, LiXingDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        planRepository = PlanRepository(
            database.planDao(),
            database.taskTemplateDao(),
            database.dailyTaskDao(),
            database.dayRecordDao(),
            database.focusSessionDao(),
            Dispatchers.Unconfined,
        )
        taskRepository = TaskRepository(
            database.dailyTaskDao(),
            database.dayRecordDao(),
            planRepository,
            Dispatchers.Unconfined,
        )
        applier = PlanChangeApplier(planRepository, taskRepository)

        val planId = planRepository.createPlan(
            StudyPlanEntity(name = "测试计划", startDate = LocalDate.of(2026, 8, 1), targetDate = LocalDate.of(2026, 12, 19)),
        )
        subject = SubjectEntity(planId = planId, name = "数学", colorArgb = 0xFF3366.toInt())
        val subjectId = planRepository.upsertSubject(subject)
        subject = subject.copy(id = subjectId)
        val slotId = planRepository.upsertTimeSlot(
            TimeSlotEntity(planId = planId, name = "上午", startTime = LocalTime.of(8, 0), endTime = LocalTime.of(11, 30)),
        )
        slot = planRepository.getTimeSlot(slotId)!!
        val templateId = planRepository.upsertTemplate(
            TaskTemplateEntity(subjectId = subject.id, timeSlotId = slot.id, title = "660 题", targetType = TargetType.MINUTES, targetValue = 120),
        )
        template = planRepository.getTemplate(templateId)!!
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun `update time slot changes start and end`() = runTest {
        val results = applier.apply(
            listOf(PlanAction.UpdateTimeSlot(slot.id, LocalTime.of(9, 0), LocalTime.of(11, 0), "缩短上午")),
            today,
        )
        assertTrue(results.single().success)
        val updated = planRepository.getTimeSlot(slot.id)!!
        assertEquals(LocalTime.of(9, 0), updated.startTime)
        assertEquals(LocalTime.of(11, 0), updated.endTime)
    }

    @Test
    fun `update time slot rejects missing slot and equal times`() = runTest {
        val missing = applier.apply(
            listOf(PlanAction.UpdateTimeSlot("999", LocalTime.of(9, 0), null, "")),
            today,
        )
        assertFalse(missing.single().success)

        val sameTimes = applier.apply(
            listOf(PlanAction.UpdateTimeSlot(slot.id, LocalTime.of(9, 0), LocalTime.of(9, 0), "")),
            today,
        )
        assertFalse(sameTimes.single().success)
    }

    @Test
    fun `update template changes only provided fields`() = runTest {
        val results = applier.apply(
            listOf(PlanAction.UpdateTaskTemplate(template.id, null, 90, null, null, true, null, "减量")),
            today,
        )
        assertTrue(results.single().success)
        val updated = planRepository.getTemplate(template.id)!!
        assertEquals("660 题", updated.title)
        assertEquals(90, updated.targetValue)
        assertTrue(updated.isKeystone)
    }

    @Test
    fun `insert template requires existing subject and slot`() = runTest {
        val ok = applier.apply(
            listOf(
                PlanAction.InsertTaskTemplate(
                    subject.id, slot.id, "错题复盘", TaskType.REVIEW, TargetType.MINUTES, 30,
                    com.example.lixing.domain.model.RepeatRule.DAILY, false, "", "补复盘",
                ),
            ),
            today,
        )
        assertTrue(ok.single().success)

        val badSubject = applier.apply(
            listOf(
                PlanAction.InsertTaskTemplate(
                    "999", slot.id, "不存在的科目", TaskType.CUSTOM, TargetType.BOOLEAN, 1,
                    com.example.lixing.domain.model.RepeatRule.DAILY, false, "", "",
                ),
            ),
            today,
        )
        assertFalse(badSubject.single().success)
    }

    @Test
    fun `today target only applies to pending tasks on current study day`() = runTest {
        val pendingId = insertTask(
            task(today, "背单词", TaskStatus.PENDING),
        )
        val doneId = insertTask(
            task(today, "已完成的任务", TaskStatus.DONE).copy(templateId = null),
        )

        val yesterdayId = insertTask(
            task(today.minusDays(1), "昨天未完成的任务", TaskStatus.PENDING).copy(templateId = null),
        )

        val okResult = applier.apply(
            listOf(PlanAction.UpdateTodayTask(pendingId, 60, null, "今天减量")),
            today,
        )
        assertTrue(okResult.single().success)
        assertEquals(60, taskRepository.getTask(pendingId)!!.targetValue)

        val doneResult = applier.apply(
            listOf(PlanAction.UpdateTodayTask(doneId, 60, null, "")),
            today,
        )
        assertFalse(doneResult.single().success)
        assertEquals(100, taskRepository.getTask(doneId)!!.targetValue)

        val wrongDateResult = applier.apply(
            listOf(PlanAction.UpdateTodayTask(yesterdayId, 60, null, "")),
            today,
        )
        assertFalse(wrongDateResult.single().success)
        assertEquals(100, taskRepository.getTask(yesterdayId)!!.targetValue)

        val missingResult = applier.apply(
            listOf(PlanAction.UpdateTodayTask("424242", 60, null, "")),
            today,
        )
        assertFalse(missingResult.single().success)
    }

    @Test
    fun `today task can move time slot without changing its template`() = runTest {
        val afternoonId = planRepository.upsertTimeSlot(
            TimeSlotEntity(
                planId = slot.planId,
                name = "下午",
                startTime = LocalTime.of(14, 0),
                endTime = LocalTime.of(17, 30),
                sortOrder = 2,
            ),
        )
        val taskId = insertTask(task(today, "660 题", TaskStatus.PENDING))

        val result = applier.apply(
            listOf(PlanAction.UpdateTodayTask(taskId, null, afternoonId, "今天下午再做")),
            today,
        )

        assertTrue(result.single().success)
        val updatedTask = taskRepository.getTask(taskId)!!
        assertEquals(afternoonId, updatedTask.timeSlotId)
        assertEquals("下午", updatedTask.slotName)
        assertEquals(slot.id, planRepository.getTemplate(template.id)!!.timeSlotId)
    }

    @Test
    fun `skip today task preserves record and future template`() = runTest {
        val taskId = insertTask(task(today, "强化听课", TaskStatus.PENDING))

        val result = applier.apply(
            listOf(PlanAction.SkipTodayTask(taskId, "今天只背单词")),
            today,
        )

        assertTrue(result.single().success)
        val skipped = taskRepository.getTask(taskId)!!
        assertEquals(TaskStatus.SKIPPED, skipped.status)
        assertEquals(template.id, skipped.templateId)
        assertTrue(planRepository.getTemplate(template.id)!!.isEnabled)
    }

    @Test
    fun `skip today task rejects completed and other date tasks`() = runTest {
        val doneId = insertTask(task(today, "已完成", TaskStatus.DONE).copy(templateId = null))
        val yesterdayId = insertTask(
            task(today.minusDays(1), "昨天待做", TaskStatus.PENDING).copy(templateId = null),
        )

        val results = applier.apply(
            listOf(
                PlanAction.SkipTodayTask(doneId, ""),
                PlanAction.SkipTodayTask(yesterdayId, ""),
            ),
            today,
        )

        assertTrue(results.none { it.success })
        assertEquals(TaskStatus.DONE, taskRepository.getTask(doneId)!!.status)
        assertEquals(TaskStatus.PENDING, taskRepository.getTask(yesterdayId)!!.status)
    }

    @Test
    fun `take today off skips pending tasks and preserves completed tasks`() = runTest {
        val pendingId = insertTask(task(today, "待做任务", TaskStatus.PENDING))
        val doneId = insertTask(task(today, "已完成任务", TaskStatus.DONE).copy(templateId = null))

        val result = applier.apply(listOf(PlanAction.TakeTodayOff("休息一天")), today, dayOffLimit = 4)

        assertTrue(result.single().success)
        assertEquals(TaskStatus.SKIPPED, taskRepository.getTask(pendingId)!!.status)
        assertEquals(TaskStatus.DONE, taskRepository.getTask(doneId)!!.status)
        assertTrue(taskRepository.getDayRecord(today)!!.isDayOff)
    }

    @Test
    fun `take today off respects monthly limit`() = runTest {
        repeat(4) { offset ->
            taskRepository.upsertDayRecord(
                DayRecordEntity(date = today.withDayOfMonth(offset + 1), isDayOff = true),
            )
        }
        val taskId = insertTask(task(today, "待做任务", TaskStatus.PENDING))

        val result = applier.apply(listOf(PlanAction.TakeTodayOff("休息一天")), today, dayOffLimit = 4)

        assertFalse(result.single().success)
        assertEquals(TaskStatus.PENDING, taskRepository.getTask(taskId)!!.status)
        assertFalse(taskRepository.getDayRecord(today)?.isDayOff == true)
    }

    @Test
    fun `keep today tasks skips only other pending tasks`() = runTest {
        val keepId = insertTask(task(today, "晨间背单词", TaskStatus.PENDING))
        val skipId = insertTask(task(today, "强化听课", TaskStatus.PENDING).copy(templateId = null))
        val doneId = insertTask(task(today, "已经打卡", TaskStatus.DONE).copy(templateId = null))

        val result = applier.apply(
            listOf(PlanAction.KeepTodayTasks(listOf(keepId), "只保留背单词")),
            today,
        )

        assertTrue(result.single().success)
        assertEquals(TaskStatus.PENDING, taskRepository.getTask(keepId)!!.status)
        assertEquals(TaskStatus.SKIPPED, taskRepository.getTask(skipId)!!.status)
        assertEquals(TaskStatus.DONE, taskRepository.getTask(doneId)!!.status)
        assertFalse(taskRepository.getDayRecord(today)?.isDayOff == true)
    }

    /** DAO.insert 不再返回 id；构造实体后由实体携带的 UUID 主键取回。 */
    private suspend fun insertTask(task: DailyTaskEntity): String {
        database.dailyTaskDao().insert(task)
        return task.id
    }

    private fun task(date: LocalDate, title: String, status: TaskStatus) = DailyTaskEntity(
        date = date,
        templateId = template.id,
        subjectId = subject.id,
        subjectName = subject.name,
        subjectColorArgb = subject.colorArgb,
        timeSlotId = slot.id,
        slotName = slot.name,
        slotStart = slot.startTime,
        slotEnd = slot.endTime,
        title = title,
        taskType = TaskType.MEMORIZE,
        targetType = TargetType.MINUTES,
        targetValue = 100,
        status = status,
    )
}
