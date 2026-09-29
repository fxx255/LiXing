package com.example.lixing.domain.planning

import com.example.lixing.data.local.entity.LearningGoalEntity
import com.example.lixing.data.local.entity.TaskContentProgressEntity

data class GoalContentStats(
    val goalId: String,
    val title: String,
    val subjectId: String,
    val targetCount: Int?,
    val knownCompleted: Int,
    val quantityOnly: Int,
    val remainingCount: Int?,
    val remainingRanges: List<ContentInterval>? = null,
)

object GoalProgressCalculator {
    fun calculate(goals: List<LearningGoalEntity>, records: List<TaskContentProgressEntity>): List<GoalContentStats> {
        val superseded = records.mapNotNull { it.supersedesId }.toSet()
        val effective = records.filter { it.id !in superseded }
        return goals.map { goal ->
            val scope = ContentSelectionCodec.decode(goal.scopeJson)
            val initial = ContentSelectionCodec.decode(goal.initialProgressJson)
            val confirmed = ArrayList<ContentInterval>()
            if (initial != null && initial.roundKey == goal.roundKey) confirmed += initial.intervals
            var quantityOnly = 0
            for (record in effective) {
                if (record.goalId != goal.id || record.roundKey != goal.roundKey) continue
                if (goal.resourceId != null && record.resourceId != goal.resourceId) continue
                if (record.mode == "COUNT") {
                    quantityOnly += record.quantity
                    continue
                }
                val content = ContentSelectionCodec.decode(record.completedJson) ?: continue
                if (scope != null && ((goal.resourceId == null && scope.resourceName.isNotBlank() &&
                    content.resourceName != scope.resourceName) ||
                    (scope.chapter.isNotBlank() && content.chapter != scope.chapter) ||
                    content.kind != scope.kind)) continue
                confirmed += content.intervals
            }
            val known = IntervalMath.merge(confirmed)
            val target = scope?.intervals?.takeIf { it.isNotEmpty() }
            val remaining = target?.let { IntervalMath.remaining(it, known) }
            GoalContentStats(
                goalId = goal.id, title = goal.title, subjectId = goal.subjectId,
                targetCount = target?.let { IntervalMath.merge(it).sumOf(ContentInterval::size) },
                knownCompleted = if (target == null) known.sumOf(ContentInterval::size)
                    else IntervalMath.intersect(target, known).sumOf(ContentInterval::size),
                quantityOnly = quantityOnly,
                remainingCount = remaining?.sumOf(ContentInterval::size),
                remainingRanges = remaining,
            )
        }
    }
}
