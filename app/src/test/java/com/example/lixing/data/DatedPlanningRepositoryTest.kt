package com.example.lixing.data

import android.app.Application
import androidx.room.Room
import com.example.lixing.data.local.LiXingDatabase
import com.example.lixing.data.local.entity.ScheduledTaskEntity
import com.example.lixing.data.local.entity.PlanDayPolicyEntity
import com.example.lixing.data.local.entity.StudyResourceEntity
import com.example.lixing.data.local.entity.LearningGoalEntity
import com.example.lixing.data.local.entity.FocusSessionEntity
import com.example.lixing.domain.model.FocusMode
import com.example.lixing.data.local.entity.StudyPlanEntity
import com.example.lixing.data.local.entity.TaskTemplateEntity
import com.example.lixing.data.local.entity.SubjectEntity
import com.example.lixing.data.local.entity.TimeSlotEntity
import com.example.lixing.data.prefs.UserPreferencesRepository
import com.example.lixing.data.repository.PlanRepository
import com.example.lixing.data.repository.PlanningRepository
import com.example.lixing.data.repository.TaskRepository
import com.example.lixing.domain.model.TargetType
import com.example.lixing.domain.model.TaskStatus
import com.example.lixing.domain.planning.ContentInterval
import com.example.lixing.domain.planning.ContentSelection
import com.example.lixing.domain.planning.ContentSelectionCodec
import com.example.lixing.domain.planning.AvailabilityCodec
import com.example.lixing.domain.planning.AvailabilityWindow
import com.example.lixing.domain.time.StudyClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalTime
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class DatedPlanningRepositoryTest {
    private lateinit var database: LiXingDatabase
    private lateinit var planning: PlanningRepository
    private lateinit var plans: PlanRepository
    private lateinit var prefs: UserPreferencesRepository

    @Before fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        database = Room.inMemoryDatabaseBuilder(context, LiXingDatabase::class.java)
            .allowMainThreadQueries().build()
        prefs = UserPreferencesRepository(context)
        plans = PlanRepository(database.planDao(), database.taskTemplateDao(), database.dailyTaskDao(),
            database.dayRecordDao(), database.focusSessionDao(), Dispatchers.Unconfined)
        val tasks = TaskRepository(database, database.dailyTaskDao(), database.dayRecordDao(), plans,
            database.planningDao(), database.focusSessionDao(), Dispatchers.Unconfined)
        planning = PlanningRepository(database, database.planningDao(), plans, tasks,
            database.focusSessionDao(), prefs, Dispatchers.Unconfined)
    }

    @After fun tearDown() = database.close()

    @Test fun `dated task previews once and overlapping blocks are rejected`() = runTest {
        val date = StudyClock(dayStart = prefs.current().dayStartTime).today().plusDays(1)
        val planId = plans.createPlan(StudyPlanEntity(name = "测试", startDate = date,
            targetDate = date.plusDays(30)))
        val subjectId = plans.upsertSubject(SubjectEntity(planId = planId, name = "数学", colorArgb = 0xff3366ff.toInt()))
        val slotId = plans.upsertTimeSlot(TimeSlotEntity(planId = planId, name = "上午",
            startTime = LocalTime.of(8, 0), endTime = LocalTime.of(12, 0)))
        val content = ContentSelection(resourceName = "题册", chapter = "第 2 章", kind = "QUESTION",
            intervals = listOf(ContentInterval(190, 210)))
        val first = ScheduledTaskEntity(id = "first", planId = planId, studyDate = date,
            subjectId = subjectId, timeSlotId = slotId, title = "第 190–210 题",
            targetType = TargetType.COUNT, targetValue = 21,
            contentJson = ContentSelectionCodec.encode(content), plannedMinutes = 60,
            startTime = LocalTime.of(9, 0), endTime = LocalTime.of(10, 0))
        planning.saveSchedule(first)
        val preview = planning.previewWeek(planId, date)[date].orEmpty()
        assertEquals(1, preview.size)
        assertEquals(21, preview.single().targetValue)
        assertEquals("first", preview.single().scheduleId)

        val overlap = runCatching { planning.saveSchedule(first.copy(id = "second", title = "重叠任务",
            startTime = LocalTime.of(9, 30), endTime = LocalTime.of(10, 30))) }
        assertTrue(overlap.isFailure)
        assertEquals(1, planning.getSchedules(planId, date, date).size)

        planning.cancelSchedule("first")
        assertTrue(planning.previewWeek(planId, date)[date].orEmpty().isEmpty())
        assertEquals("CANCELLED", planning.getSchedules(planId, date, date).single().state)
    }

    @Test fun `day override constrains capacity and AI placement uses free window`() = runTest {
        val date = StudyClock(dayStart = prefs.current().dayStartTime).today().plusDays(1)
        val planId = plans.createPlan(StudyPlanEntity(name = "测试", startDate = date,
            targetDate = date.plusDays(30)))
        val subjectId = plans.upsertSubject(SubjectEntity(planId = planId, name = "数学", colorArgb = 0xff3366ff.toInt()))
        val slotId = plans.upsertTimeSlot(TimeSlotEntity(planId = planId, name = "上午",
            startTime = LocalTime.of(8, 0), endTime = LocalTime.of(12, 0)))
        val first = ScheduledTaskEntity(id = "first", planId = planId, studyDate = date,
            subjectId = subjectId, timeSlotId = slotId, title = "数学", plannedMinutes = 60,
            startTime = LocalTime.of(9, 0), endTime = LocalTime.of(10, 0))
        planning.saveSchedule(first)
        val before = planning.snapshotFingerprint(planId, listOf(date))
        planning.saveDayPolicy(PlanDayPolicyEntity(planId = planId, studyDate = date,
            windowsJson = AvailabilityCodec.encode(listOf(AvailabilityWindow("09:00", "11:00"))),
            maxPlannedMinutes = 90))
        assertTrue(before != planning.snapshotFingerprint(planId, listOf(date)))
        val placed = planning.proposePlacement(planId, date, slotId, 30, emptyList())
        assertEquals(LocalTime.of(10, 0), placed?.start?.toLocalTime())
        assertEquals(LocalTime.of(10, 30), placed?.end?.toLocalTime())
        val overCapacity = runCatching { planning.saveSchedule(first.copy(id = "second", title = "英语",
            plannedMinutes = 45, startTime = LocalTime.of(10, 0), endTime = LocalTime.of(10, 45))) }
        assertTrue(overCapacity.isFailure)
        assertEquals(1, planning.getSchedules(planId, date, date).size)
    }

    @Test fun `plan replacement cleanup keeps future task with a focus record`() = runTest {
        val date = StudyClock(dayStart = prefs.current().dayStartTime).today().plusDays(1)
        val planId = plans.createPlan(StudyPlanEntity(name = "测试", startDate = date,
            targetDate = date.plusDays(30)))
        val subjectId = plans.upsertSubject(SubjectEntity(planId = planId, name = "数学", colorArgb = 0xff3366ff.toInt()))
        val slotId = plans.upsertTimeSlot(TimeSlotEntity(planId = planId, name = "上午",
            startTime = LocalTime.of(8, 0), endTime = LocalTime.of(12, 0)))
        planning.saveSchedule(ScheduledTaskEntity(id = "keep", planId = planId, studyDate = date,
            subjectId = subjectId, timeSlotId = slotId, title = "已开始"))
        planning.saveSchedule(ScheduledTaskEntity(id = "drop", planId = planId, studyDate = date,
            subjectId = subjectId, timeSlotId = slotId, title = "未开始"))
        val tasks = TaskRepository(database, database.dailyTaskDao(), database.dayRecordDao(), plans,
            database.planningDao(), database.focusSessionDao(), Dispatchers.Unconfined)
        tasks.materializeDay(date)
        val keep = tasks.getTasksOfDay(date).first { it.scheduleId == "keep" }
        database.focusSessionDao().insert(FocusSessionEntity(date = date, dailyTaskId = keep.id,
            subjectId = subjectId, startedAt = Instant.now(), mode = FocusMode.COUNT_UP))
        database.dailyTaskDao().deleteUnstartedFrom(date)
        assertEquals(keep.id, tasks.getTasksOfDay(date).single().id)
    }

    @Test fun `goal-linked question range cannot exceed its registered scope`() = runTest {
        val date = StudyClock(dayStart = prefs.current().dayStartTime).today().plusDays(1)
        val planId = plans.createPlan(StudyPlanEntity(name = "测试", startDate = date,
            targetDate = date.plusDays(30)))
        val subjectId = plans.upsertSubject(SubjectEntity(planId = planId, name = "数学", colorArgb = 0xff3366ff.toInt()))
        val slotId = plans.upsertTimeSlot(TimeSlotEntity(planId = planId, name = "上午",
            startTime = LocalTime.of(8, 0), endTime = LocalTime.of(12, 0)))
        planning.saveResource(StudyResourceEntity(id = "book", planId = planId,
            subjectId = subjectId, name = "题册"))
        planning.saveGoal(LearningGoalEntity(id = "goal", planId = planId, subjectId = subjectId,
            resourceId = "book", title = "完成第 190–210 题",
            scopeJson = ContentSelectionCodec.encode(ContentSelection(kind = "QUESTION",
                intervals = listOf(ContentInterval(190, 210))))))
        val outside = ScheduledTaskEntity(id = "outside", planId = planId, studyDate = date,
            subjectId = subjectId, timeSlotId = slotId, resourceId = "book", goalId = "goal",
            title = "超出范围", targetType = TargetType.COUNT, targetValue = 10,
            contentJson = ContentSelectionCodec.encode(ContentSelection(kind = "QUESTION",
                intervals = listOf(ContentInterval(211, 220)))))
        assertTrue(runCatching { planning.saveSchedule(outside) }.isFailure)
        assertTrue(runCatching { planning.saveSchedule(outside.copy(id = "missing-book",
            resourceId = null)) }.isFailure)
        val inside = outside.copy(id = "inside", title = "第 190–200 题", targetValue = 11,
            contentJson = ContentSelectionCodec.encode(ContentSelection(kind = "QUESTION",
                intervals = listOf(ContentInterval(190, 200)))))
        planning.saveSchedule(inside)
        assertEquals("goal", planning.getSchedules(planId, date, date).single().goalId)
    }

    @Test fun `today override can be cancelled restored and refined again`() = runTest {
        val date = StudyClock(dayStart = prefs.current().dayStartTime).today()
        val planId = plans.createPlan(StudyPlanEntity(name = "测试", startDate = date,
            targetDate = date.plusDays(30)))
        val subjectId = plans.upsertSubject(SubjectEntity(planId = planId, name = "数学",
            colorArgb = 0xff3366ff.toInt()))
        val slotId = plans.upsertTimeSlot(TimeSlotEntity(planId = planId, name = "上午",
            startTime = LocalTime.of(8, 0), endTime = LocalTime.of(12, 0)))
        val templateId = plans.upsertTemplate(TaskTemplateEntity(subjectId = subjectId,
            timeSlotId = slotId, title = "刷题"))
        val tasks = TaskRepository(database, database.dailyTaskDao(), database.dayRecordDao(), plans,
            database.planningDao(), database.focusSessionDao(), Dispatchers.Unconfined)
        tasks.materializeDay(date)
        val override = ScheduledTaskEntity(id = "override", planId = planId, studyDate = date,
            sourceTemplateId = templateId, subjectId = subjectId, timeSlotId = slotId,
            title = "第 190–210 题", state = "OVERRIDE")

        planning.saveSchedule(override)
        assertEquals("override", tasks.getTasksOfDay(date).single().scheduleId)
        planning.cancelSchedule(override.id)
        assertEquals(TaskStatus.SKIPPED, tasks.getTasksOfDay(date).single().status)
        planning.restoreTemplateOccurrence(override.id)
        assertEquals(TaskStatus.PENDING, tasks.getTasksOfDay(date).single().status)
        assertEquals("刷题", tasks.getTasksOfDay(date).single().title)
        planning.saveSchedule(override.copy(title = "第 211–230 题"))
        assertEquals(1, tasks.getTasksOfDay(date).size)
        assertEquals("第 211–230 题", tasks.getTasksOfDay(date).single().title)
    }
}
