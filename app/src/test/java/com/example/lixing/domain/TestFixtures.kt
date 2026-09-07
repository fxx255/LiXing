package com.example.lixing.domain

import com.example.lixing.data.local.entity.CheckInStreakEntity
import com.example.lixing.data.local.entity.CommitmentEntity
import com.example.lixing.data.local.entity.DailyTaskEntity
import com.example.lixing.domain.model.CommitmentStatus
import com.example.lixing.domain.model.TargetType
import com.example.lixing.domain.model.TaskStatus
import com.example.lixing.domain.model.TaskType
import java.time.LocalDate
import java.time.LocalTime

/** 测试数据工厂。让用例保持简洁，不重复一大坨构造参数。 */
object TestFixtures {

    private var nextTaskSeq = 0

    fun task(
        date: LocalDate = LocalDate.of(2026, 8, 3),
        title: String = "背单词",
        targetType: TargetType = TargetType.BOOLEAN,
        targetValue: Int = 1,
        status: TaskStatus = TaskStatus.PENDING,
        actualValue: Int = 0,
        isKeystone: Boolean = false,
        slotStart: LocalTime = LocalTime.of(6, 40),
        slotEnd: LocalTime = LocalTime.of(7, 30),
        slotName: String = "早读",
    ): DailyTaskEntity = DailyTaskEntity(
        id = "task-${nextTaskSeq++}",
        date = date,
        templateId = "1",
        subjectId = "1",
        subjectName = "英语",
        subjectColorArgb = -16711936,
        timeSlotId = "1",
        slotName = slotName,
        slotStart = slotStart,
        slotEnd = slotEnd,
        title = title,
        taskType = TaskType.MEMORIZE,
        targetType = targetType,
        targetValue = targetValue,
        isKeystone = isKeystone,
        status = status,
        actualValue = actualValue,
    )

    fun streak(
        current: Int = 0,
        longest: Int = current,
        lastAchieved: LocalDate? = null,
        rescueLeft: Int = 1,
        rescueMonth: Int = 0,
    ): CheckInStreakEntity = CheckInStreakEntity(
        currentStreak = current,
        longestStreak = longest,
        lastAchievedDate = lastAchieved,
        rescueCardsLeft = rescueLeft,
        rescueCardsMonth = rescueMonth,
    )

    fun commitment(
        id: String = "1",
        title: String = "强化阶段完成率不低于 85%",
        start: LocalDate = LocalDate.of(2026, 7, 1),
        end: LocalDate = LocalDate.of(2026, 7, 30),
        targetPercent: Int = 85,
        reward: Int = 100,
        status: CommitmentStatus = CommitmentStatus.ACTIVE,
        note: String = "",
    ): CommitmentEntity = CommitmentEntity(
        id = id,
        title = title,
        startDate = start,
        endDate = end,
        targetRatePercent = targetPercent,
        rewardPoints = reward,
        status = status,
        note = note,
    )

    fun resetIds() {
        nextTaskSeq = 0
    }
}
