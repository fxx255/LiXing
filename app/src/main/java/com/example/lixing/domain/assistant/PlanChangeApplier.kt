package com.example.lixing.domain.assistant

import com.example.lixing.data.local.entity.TaskTemplateEntity
import com.example.lixing.data.local.entity.TimeSlotEntity
import com.example.lixing.data.repository.PlanRepository
import com.example.lixing.data.repository.TaskRepository
import com.example.lixing.domain.model.TaskStatus
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/** 单条建议的应用结果。 */
data class PlanApplyResult(
    val action: PlanAction,
    val success: Boolean,
    val message: String,
)

/**
 * 把用户确认过的 AI 计划建议落到数据库。
 *
 * 边界（硬规则）：
 * - 只处理白名单动作，其它类型一律拒绝；
 * - 引用的时段 / 科目 / 模板 / 任务必须真实存在；
 * - 只允许临时修改或跳过今天仍处于 PENDING 的任务；
 * - 不删除任何数据，不触碰积分、成就与历史记录。
 */
@Singleton
class PlanChangeApplier @Inject constructor(
    private val planRepository: PlanRepository,
    private val taskRepository: TaskRepository,
) {

    suspend fun apply(
        actions: List<PlanAction>,
        today: LocalDate,
        dayOffLimit: Int = Int.MAX_VALUE,
    ): List<PlanApplyResult> =
        actions.map { action ->
            runCatching { applyOne(action, today, dayOffLimit) }
                .getOrElse { PlanApplyResult(action, false, it.message ?: "应用失败") }
        }

    private suspend fun applyOne(
        action: PlanAction,
        today: LocalDate,
        dayOffLimit: Int,
    ): PlanApplyResult = when (action) {
        is PlanAction.UpdateTimeSlot -> applyUpdateTimeSlot(action)
        is PlanAction.UpdateTaskTemplate -> applyUpdateTemplate(action)
        is PlanAction.InsertTaskTemplate -> applyInsertTemplate(action)
        is PlanAction.UpdateTodayTask -> applyUpdateTodayTask(action, today)
        is PlanAction.SkipTodayTask -> applySkipTodayTask(action, today)
        is PlanAction.TakeTodayOff -> applyTakeTodayOff(action, today, dayOffLimit)
        is PlanAction.KeepTodayTasks -> applyKeepTodayTasks(action, today)
    }

    private suspend fun applyUpdateTimeSlot(action: PlanAction.UpdateTimeSlot): PlanApplyResult {
        val slot = planRepository.getTimeSlot(action.slotId)
            ?: return fail(action, "时段不存在（id=${action.slotId}）")
        val newStart = action.startTime ?: slot.startTime
        val newEnd = action.endTime ?: slot.endTime
        if (newStart == newEnd) return fail(action, "开始时间与结束时间不能相同")
        planRepository.upsertTimeSlot(
            TimeSlotEntity(
                id = slot.id,
                planId = slot.planId,
                name = slot.name,
                startTime = newStart,
                endTime = newEnd,
                weekdayMask = slot.weekdayMask,
                sortOrder = slot.sortOrder,
                note = slot.note,
                requiredTaskCount = slot.requiredTaskCount,
                isEnabled = slot.isEnabled,
            ),
        )
        return ok(action, "时段「${slot.name}」已调整为 $newStart-$newEnd")
    }

    private suspend fun applyUpdateTemplate(action: PlanAction.UpdateTaskTemplate): PlanApplyResult {
        val template = planRepository.getTemplate(action.templateId)
            ?: return fail(action, "任务模板不存在（id=${action.templateId}）")
        if (action.timeSlotId != null && planRepository.getTimeSlot(action.timeSlotId) == null) {
            return fail(action, "目标时段不存在（id=${action.timeSlotId}）")
        }
        planRepository.upsertTemplate(
            TaskTemplateEntity(
                id = template.id,
                subjectId = template.subjectId,
                timeSlotId = action.timeSlotId ?: template.timeSlotId,
                title = action.title ?: template.title,
                taskType = template.taskType,
                targetType = template.targetType,
                targetValue = action.targetValue ?: template.targetValue,
                repeatRule = action.repeatRule ?: template.repeatRule,
                weekdayMask = template.weekdayMask,
                intervalDays = template.intervalDays,
                anchorDate = template.anchorDate,
                phaseId = template.phaseId,
                activeFrom = template.activeFrom,
                activeUntil = template.activeUntil,
                isKeystone = action.isKeystone ?: template.isKeystone,
                note = template.note,
                sortOrder = template.sortOrder,
                isEnabled = action.isEnabled ?: template.isEnabled,
            ),
        )
        return ok(action, "任务「${action.title ?: template.title}」已更新")
    }

    private suspend fun applyInsertTemplate(action: PlanAction.InsertTaskTemplate): PlanApplyResult {
        if (planRepository.getSubject(action.subjectId) == null) {
            return fail(action, "科目不存在（id=${action.subjectId}）")
        }
        if (planRepository.getTimeSlot(action.timeSlotId) == null) {
            return fail(action, "时段不存在（id=${action.timeSlotId}）")
        }
        val id = planRepository.upsertTemplate(
            TaskTemplateEntity(
                subjectId = action.subjectId,
                timeSlotId = action.timeSlotId,
                title = action.title,
                taskType = action.taskType,
                targetType = action.targetType,
                targetValue = if (action.targetType.isQuantified) action.targetValue else 1,
                repeatRule = action.repeatRule,
                isKeystone = action.isKeystone,
                note = action.note,
            ),
        )
        return ok(action, "已新增任务「${action.title}」（id=$id），明天起生效")
    }

    private suspend fun applyUpdateTodayTask(
        action: PlanAction.UpdateTodayTask,
        today: LocalDate,
    ): PlanApplyResult {
        val task = taskRepository.getTask(action.taskId)
            ?: return fail(action, "任务不存在（id=${action.taskId}）")
        if (task.date != today) {
            return fail(action, "只能临时调整今天（$today）的任务")
        }
        if (task.status != TaskStatus.PENDING) {
            return fail(action, "只能调整今天尚未打卡任务的目标（「${task.title}」已是 ${task.status.label}）")
        }
        if (action.targetValue != null && !task.targetType.isQuantified) {
            return fail(action, "「${task.title}」是完成型任务，不能调整目标量")
        }

        var updated = task.copy(targetValue = action.targetValue ?: task.targetValue)
        val changes = mutableListOf<String>()
        action.targetValue?.let { changes += "目标 ${task.targetValue} → $it${task.targetType.unit}" }
        action.timeSlotId?.let { slotId ->
            val slot = planRepository.getTimeSlot(slotId)
                ?: return fail(action, "目标时段不存在（id=$slotId）")
            val subject = planRepository.getSubject(task.subjectId)
                ?: return fail(action, "任务科目不存在（id=${task.subjectId}）")
            if (slot.planId != subject.planId) {
                return fail(action, "目标时段不属于当前任务的计划")
            }
            updated = updated.copy(
                timeSlotId = slot.id,
                slotName = slot.name,
                slotStart = slot.startTime,
                slotEnd = slot.endTime,
                slotSortOrder = slot.sortOrder,
                slotRequiredTaskCount = slot.requiredTaskCount,
            )
            changes += "时段 ${task.slotName} → ${slot.name}"
        }
        taskRepository.updateTask(updated)
        return ok(action, "「${task.title}」今日已临时调整：${changes.joinToString("，")}")
    }

    private suspend fun applySkipTodayTask(
        action: PlanAction.SkipTodayTask,
        today: LocalDate,
    ): PlanApplyResult {
        val task = taskRepository.getTask(action.taskId)
            ?: return fail(action, "任务不存在（id=${action.taskId}）")
        if (task.date != today) {
            return fail(action, "只能跳过今天（$today）的任务")
        }
        if (task.status != TaskStatus.PENDING) {
            return fail(action, "只能跳过今天尚未打卡的任务（「${task.title}」已是 ${task.status.label}）")
        }
        taskRepository.updateTask(task.copy(status = TaskStatus.SKIPPED))
        return ok(action, "「${task.title}」已仅在今天跳过，不影响之后的安排")
    }

    private suspend fun applyTakeTodayOff(
        action: PlanAction.TakeTodayOff,
        today: LocalDate,
        dayOffLimit: Int,
    ): PlanApplyResult {
        val record = taskRepository.getDayRecord(today)
        if (record?.isSettled == true) return fail(action, "今天已经结算，不能再申请请假")
        if (record?.isDayOff == true) return ok(action, "今天已经处于请假状态")
        val tasks = taskRepository.getTasksOfDay(today)
        if (tasks.none { it.status == TaskStatus.PENDING || it.status == TaskStatus.MISSED }) {
            return fail(action, "今天没有可请假的待做任务")
        }

        val monthStart = today.withDayOfMonth(1)
        val monthEnd = today.withDayOfMonth(today.lengthOfMonth())
        val used = taskRepository.getDayRecordsBetween(monthStart, monthEnd).count { it.isDayOff }
        if (used >= dayOffLimit) {
            return fail(action, "本月请假额度已用完（$dayOffLimit 天）")
        }

        taskRepository.markDayAsSkipped(today)
        taskRepository.setDayOff(today, true)
        return ok(action, "今天已请假，本月剩余 ${dayOffLimit - used - 1} 天")
    }

    private suspend fun applyKeepTodayTasks(
        action: PlanAction.KeepTodayTasks,
        today: LocalDate,
    ): PlanApplyResult {
        val record = taskRepository.getDayRecord(today)
        if (record?.isSettled == true) return fail(action, "今天已经结算，不能调整任务")
        if (record?.isDayOff == true) return fail(action, "今天已整日请假，请先取消请假后再保留部分任务")
        val tasks = taskRepository.getTasksOfDay(today)
        val byId = tasks.associateBy { it.id }
        val missingIds = action.keepTaskIds.filterNot(byId::containsKey)
        if (missingIds.isNotEmpty()) return fail(action, "要保留的任务不存在或不属于今天：${missingIds.joinToString()}")
        val keepTasks = action.keepTaskIds.mapNotNull(byId::get)
        if (keepTasks.any { it.status == TaskStatus.SKIPPED || it.status == TaskStatus.MISSED }) {
            return fail(action, "要保留的任务中包含已跳过或已漏卡任务")
        }
        val toSkip = tasks.filter { it.id !in action.keepTaskIds && it.status == TaskStatus.PENDING }
        if (toSkip.isEmpty()) return fail(action, "除保留任务外，没有其他今日待做任务")
        taskRepository.updateTasks(toSkip.map { it.copy(status = TaskStatus.SKIPPED) })
        return ok(action, "已保留 ${keepTasks.size} 项任务，其余 ${toSkip.size} 项仅在今天跳过")
    }

    private fun ok(action: PlanAction, message: String) = PlanApplyResult(action, true, message)

    private fun fail(action: PlanAction, message: String) = PlanApplyResult(action, false, message)
}
