package com.example.lixing.domain

import com.example.lixing.data.local.entity.FocusSessionEntity
import com.example.lixing.data.local.entity.LearningGoalEntity
import com.example.lixing.data.local.entity.ManualStudyTimeEntity
import com.example.lixing.data.local.entity.TaskContentProgressEntity
import com.example.lixing.domain.model.TargetType
import com.example.lixing.domain.model.TaskStatus
import com.example.lixing.domain.planning.ContentInterval
import com.example.lixing.domain.planning.ContentRangeText
import com.example.lixing.domain.planning.ContentSelection
import com.example.lixing.domain.planning.ContentSelectionCodec
import com.example.lixing.domain.planning.GoalProgressCalculator
import com.example.lixing.domain.study.StudyStatsCalculator
import com.example.lixing.domain.time.StudyDayWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

class DailyPlanningExecutionTest {
    private val day = LocalDate.of(2026, 9, 30)

    @Test fun `early morning interval belongs to previous study date`() {
        val window = StudyDayWindow.of(day, LocalTime.of(1, 0), LocalTime.of(3, 0), LocalTime.of(4, 0))
        assertEquals(day.plusDays(1).atTime(1, 0), window.start)
        assertEquals(day.plusDays(1).atTime(3, 0), window.end)
    }

    @Test fun `noncontinuous ranges preserve exact quantity and reject malformed input`() {
        assertEquals(listOf(ContentInterval(190, 198), ContentInterval(200, 203)),
            ContentRangeText.parse("190 - 198、200–203"))
        assertNull(ContentRangeText.parse("190-abc"))
        assertNull(ContentRangeText.parse("203-190"))
    }

    @Test fun `goal uses latest range snapshot and keeps count only reports separate`() {
        val scope = ContentSelection(resourceName = "题册", chapter = "第 2 章", kind = "QUESTION",
            intervals = listOf(ContentInterval(190, 210)))
        val goal = LearningGoalEntity(id = "goal", planId = "plan", subjectId = "math", resourceId = null,
            title = "完成第 2 章", scopeJson = ContentSelectionCodec.encode(scope))
        val old = TaskContentProgressEntity(id = "old", dailyTaskId = "task", goalId = "goal",
            mode = "COUNT", quantity = 14)
        val exact = TaskContentProgressEntity(id = "exact", dailyTaskId = "task", goalId = "goal",
            mode = "RANGE", completedJson = ContentSelectionCodec.encode(scope.copy(intervals = listOf(
                ContentInterval(190, 198), ContentInterval(200, 203)))),
            quantity = 13, supersedesId = "old")
        val stats = GoalProgressCalculator.calculate(listOf(goal), listOf(old, exact)).single()
        assertEquals(21, stats.targetCount)
        assertEquals(13, stats.knownCompleted)
        assertEquals(8, stats.remainingCount)
        assertEquals(listOf(ContentInterval(199, 199), ContentInterval(204, 210)), stats.remainingRanges)
        assertEquals(0, stats.quantityOnly)
    }

    @Test fun `goal progress follows resource id after its display name changes`() {
        val scope = ContentSelection(resourceName = "旧书名", kind = "QUESTION",
            intervals = listOf(ContentInterval(190, 210)))
        val goal = LearningGoalEntity(id = "goal", planId = "plan", subjectId = "math",
            resourceId = "book", title = "刷题", scopeJson = ContentSelectionCodec.encode(scope))
        val completed = TaskContentProgressEntity(id = "correct", dailyTaskId = "task",
            goalId = "goal", resourceId = "book", mode = "RANGE", quantity = 11,
            completedJson = ContentSelectionCodec.encode(scope.copy(resourceName = "新书名",
                chapter = "第 2 章", intervals = listOf(ContentInterval(190, 200)))))
        val otherBook = completed.copy(id = "wrong", resourceId = "other", quantity = 10,
            completedJson = ContentSelectionCodec.encode(scope.copy(intervals =
                listOf(ContentInterval(201, 210)))))
        val stats = GoalProgressCalculator.calculate(listOf(goal), listOf(completed, otherBook)).single()
        assertEquals(11, stats.knownCompleted)
        assertEquals(10, stats.remainingCount)
    }

    @Test fun `new task counts session and untimed entry once while old cache stays compatible`() {
        val newTask = TestFixtures.task(date = day, targetType = TargetType.COUNT, targetValue = 21)
            .copy(id = "new", subjectId = "math", subjectName = "数学", plannedMinutes = 60,
                focusedMinutes = 40, actualValue = 13, status = TaskStatus.PARTIAL, timeAccountingVersion = 2)
        val legacy = TestFixtures.task(date = day, targetType = TargetType.MINUTES, targetValue = 60)
            .copy(id = "old", subjectId = "math", subjectName = "数学", actualValue = 30,
                focusedMinutes = 20, timeAccountingVersion = 1)
        val sessions = listOf(
            FocusSessionEntity(date = day, dailyTaskId = "new", subjectId = "math",
                startedAt = Instant.EPOCH, endedAt = Instant.EPOCH.plusSeconds(2400), effectiveMinutes = 40),
            FocusSessionEntity(date = day, dailyTaskId = "old", subjectId = "math",
                startedAt = Instant.EPOCH, endedAt = Instant.EPOCH.plusSeconds(1200), effectiveMinutes = 20,
                timeAccountingVersion = 1),
        )
        val manual = listOf(ManualStudyTimeEntity(dailyTaskId = "new", subjectId = "math",
            subjectName = "数学", studyDate = day, minutes = 10))
        val stats = StudyStatsCalculator.calculate(listOf(newTask, legacy), sessions, manual).single()
        assertEquals(100, stats.actualMinutes)
        assertEquals(60, stats.plannedMinutes)
        assertEquals(1, stats.unestimatedTasks)
        assertEquals(13, stats.reportedCount)
    }
}
