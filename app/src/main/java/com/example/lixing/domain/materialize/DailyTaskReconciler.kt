package com.example.lixing.domain.materialize

import com.example.lixing.data.local.entity.DailyTaskEntity
import com.example.lixing.domain.model.TaskStatus

/**
 * 把某个尚未结算的学习日刷新为最新计划快照。
 *
 * 历史日不应随模板变化，但“今天”仍处于执行中，计划编辑后应立即反映到今日页。
 * 已有任务的执行字段（打卡、专注、复盘）必须保留；不再符合计划的待办可以移除，
 * 已经有进度的任务则留下，避免计划编辑导致成绩和积分失去对应记录。
 */
object DailyTaskReconciler {

    data class Result(
        val inserts: List<DailyTaskEntity>,
        val updates: List<DailyTaskEntity>,
        val deleteIds: List<String>,
    )

    fun reconcile(
        existing: List<DailyTaskEntity>,
        expected: List<DailyTaskEntity>,
    ): Result {
        val existingByTemplate = existing.mapNotNull { task ->
            task.templateId?.let { it to task }
        }.toMap()
        val expectedByTemplate = expected.mapNotNull { task ->
            task.templateId?.let { it to task }
        }.toMap()

        val inserts = expected.filter { task ->
            task.templateId == null || task.templateId !in existingByTemplate
        }
        val updates = expected.mapNotNull { fresh ->
            val old = fresh.templateId?.let(existingByTemplate::get) ?: return@mapNotNull null
            val merged = fresh.copy(
                id = old.id,
                status = old.status,
                actualValue = old.actualValue,
                checkedAt = old.checkedAt,
                isLate = old.isLate,
                isMakeup = old.isMakeup,
                makeupReason = old.makeupReason,
                focusedMinutes = old.focusedMinutes,
                checkinNote = old.checkinNote,
                checkinPhoto = old.checkinPhoto,
                mood = old.mood,
                reflection = old.reflection,
            )
            merged.takeIf { it != old }
        }
        val deleteIds = existing.mapNotNull { old ->
            val noLongerExpected = old.templateId != null && old.templateId !in expectedByTemplate
            old.id.takeIf { noLongerExpected && old.status == TaskStatus.PENDING && old.focusedMinutes == 0 }
        }

        return Result(inserts = inserts, updates = updates, deleteIds = deleteIds)
    }
}
