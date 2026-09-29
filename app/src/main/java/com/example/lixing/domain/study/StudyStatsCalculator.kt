package com.example.lixing.domain.study

import com.example.lixing.data.local.entity.DailyTaskEntity
import com.example.lixing.data.local.entity.FocusSessionEntity
import com.example.lixing.data.local.entity.ManualStudyTimeEntity
import com.example.lixing.domain.model.TargetType
import com.example.lixing.domain.model.TaskStatus

data class SubjectStudyStats(
    val subjectId: String,
    val subjectName: String,
    val plannedMinutes: Int,
    val unestimatedTasks: Int,
    val actualMinutes: Int,
    /** Reported completed count, including review rounds; not a unique-question union. */
    val reportedCount: Int,
)

/** Distinguishes legacy cached totals from sessions and manual records on new tasks. */
object StudyStatsCalculator {
    fun calculate(
        tasks: List<DailyTaskEntity>,
        sessions: List<FocusSessionEntity>,
        manualTimes: List<ManualStudyTimeEntity>,
    ): List<SubjectStudyStats> {
        val tasksById = tasks.associateBy { it.id }
        val minutes = HashMap<String, Int>()
        val names = HashMap<String, String>()
        val plans = HashMap<String, Int>()
        val unknown = HashMap<String, Int>()
        val counts = HashMap<String, Int>()
        for (task in tasks) {
            names.putIfAbsent(task.subjectId, task.subjectName)
            if (task.status != TaskStatus.SKIPPED) {
                if (task.plannedMinutes != null) plans.merge(task.subjectId, task.plannedMinutes, Int::plus)
                else unknown.merge(task.subjectId, 1, Int::plus)
            }
            if (task.targetType == TargetType.COUNT && task.status.isEngaged) {
                counts.merge(task.subjectId, task.actualValue, Int::plus)
            }
            if (task.timeAccountingVersion == 1 && task.status != TaskStatus.SKIPPED) {
                val legacy = (if (task.targetType == TargetType.MINUTES) task.actualValue else 0) + task.focusedMinutes
                minutes.merge(task.subjectId, legacy, Int::plus)
            }
        }
        for (session in sessions) {
            if (session.endedAt == null || session.effectiveMinutes <= 0) continue
            val task = session.dailyTaskId?.let(tasksById::get)
            val alreadyInLegacyCache = task?.timeAccountingVersion == 1 && session.countsTowardTask
            val isLegacyFree = task == null && session.timeAccountingVersion == 1
            if (alreadyInLegacyCache || isLegacyFree) continue
            val subjectId = task?.subjectId ?: session.subjectId ?: ""
            names.putIfAbsent(subjectId, task?.subjectName ?: if (subjectId.isBlank()) "未分类" else "其他科目")
            minutes.merge(subjectId, session.effectiveMinutes, Int::plus)
        }
        val superseded = manualTimes.mapNotNull { it.supersedesId }.toSet()
        for (time in manualTimes) {
            if (time.id in superseded || time.minutes <= 0) continue
            val task = time.dailyTaskId?.let(tasksById::get)
            val subjectId = task?.subjectId ?: time.subjectId ?: ""
            names.putIfAbsent(subjectId, task?.subjectName ?: time.subjectName.ifBlank { "未分类" })
            minutes.merge(subjectId, time.minutes, Int::plus)
        }
        return names.keys.map { id ->
            SubjectStudyStats(id, names.getValue(id), plans[id] ?: 0, unknown[id] ?: 0,
                minutes[id] ?: 0, counts[id] ?: 0)
        }.sortedByDescending { it.actualMinutes }
    }
}
