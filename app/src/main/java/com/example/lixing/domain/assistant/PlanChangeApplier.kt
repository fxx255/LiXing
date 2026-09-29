package com.example.lixing.domain.assistant

import com.example.lixing.data.local.entity.TaskTemplateEntity
import com.example.lixing.data.local.entity.TimeSlotEntity
import com.example.lixing.data.repository.PlanRepository
import com.example.lixing.data.repository.TaskRepository
import com.example.lixing.data.repository.PlanningRepository
import com.example.lixing.data.local.entity.ScheduledTaskEntity
import com.example.lixing.domain.model.TaskStatus
import com.example.lixing.domain.model.TargetType
import com.example.lixing.domain.model.TaskType
import com.example.lixing.domain.planning.ContentInterval
import com.example.lixing.domain.planning.ContentSelection
import com.example.lixing.domain.planning.ContentSelectionCodec
import com.example.lixing.domain.time.SlotWindow
import kotlinx.coroutines.CancellationException
import java.time.LocalDate
import java.util.UUID
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
 * 业务校验**不是异常**：[PlanChangeApplier.applyOne] 里所有「引用的对象不存在、
 * 任务不属于今天、请假额度用完」之类，都作为 `PlanApplyResult(success=false)`
 * 返回，让界面逐条提示、其余条照常生效。这条分工不能改。
 *
 * 但 `apply` 用 `runCatching` 把**所有**异常都吞成"应用失败"，这会让两种必须
 * 穿透的情况被吃掉：
 * - 意外数据库异常（正在 Room 事务里 ⇒ 事务必须整体回滚，而不是"其中一条失败"）；
 * - `CancellationException`（协程取消 ⇒ 必须原样向上传播）。
 *
 * 因此这里额外提供 [applyStrict]：直接调用逐条应用，**不捕获任何异常**。
 * 校验失败照常返回 `success=false` 的结果；意外异常与取消直接抛出。
 *
 * 边界（硬规则）：
 * - 只处理白名单动作，其它类型一律拒绝；
 * - 引用的时段 / 科目 / 模板 / 任务必须真实存在；
 * - 只允许临时修改或跳过今天仍处于 PENDING 的任务；
 * - 不删除任何数据，不触碰积分、成就与历史记录。
 */
@Singleton
open class PlanChangeApplier @Inject constructor(
    private val planRepository: PlanRepository,
    private val taskRepository: TaskRepository,
    private val planningRepository: PlanningRepository? = null,
) {

    suspend fun apply(
        actions: List<PlanAction>,
        today: LocalDate,
        dayOffLimit: Int = Int.MAX_VALUE,
    ): List<PlanApplyResult> =
        actions.map { action ->
            runCatching { applyOne(action, today, dayOffLimit) }
                .getOrElse { e ->
                    if (e is CancellationException) throw e
                    // 取消之外：保持历史行为（整体兼容旧调用方），业务/意外失败都作为一条失败。
                    PlanApplyResult(action, false, e.message ?: "应用失败")
                }
        }

    /**
     * **严格模式**：用于「整个确认批次在一个 Room 事务里执行」的路径。
     *
     * 语义：
     * - 每条的正常业务校验 ⇒ 返回 `PlanApplyResult(success=false)`（逐条反馈，其余生效）；
     * - 意外异常（IO/DB 等）⇒ **抛出**，由外层 `withTransaction` 回滚整批；
     * - `CancellationException` ⇒ 抛出，回滚整批并让取消语义生效。
     */
    open suspend fun applyStrict(
        actions: List<PlanAction>,
        today: LocalDate,
        dayOffLimit: Int = Int.MAX_VALUE,
    ): List<PlanApplyResult> = actions.map { action -> applyOne(action, today, dayOffLimit) }

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
        is PlanAction.AddDatedTask -> applyDatedTask(action)
    }

    private suspend fun applyDatedTask(action: PlanAction.AddDatedTask): PlanApplyResult {
        val planning = planningRepository ?: return fail(action, "按日规划服务不可用")
        val plan = planRepository.getActivePlan() ?: return fail(action, "当前没有学习计划")
        val goal = action.goalId?.let { id -> planning.getGoals(plan.id).firstOrNull { it.id == id } }
        if (action.goalId != null && (goal == null || goal.subjectId != action.subjectId)) {
            return fail(action, "学习目标不存在或不属于该科目")
        }
        val resourceId = action.resourceId ?: goal?.resourceId
        val resource = resourceId?.let { id -> planning.getResources(plan.id).firstOrNull { it.id == id } }
        if (resourceId != null && (resource == null || resource.subjectId != action.subjectId)) {
            return fail(action, "学习资料不存在或不属于该科目")
        }
        if (goal?.resourceId != null && resource?.id != goal.resourceId) {
            return fail(action, "资料与学习目标不一致")
        }
        if (resource != null && action.resourceName.isNotBlank() && action.resourceName != resource.name) {
            return fail(action, "资料名称与所选资料 id 不一致")
        }
        val content = if (action.resourceName.isNotBlank() || action.chapter.isNotBlank() ||
            action.questionFirst != null || resource != null) ContentSelection(
            resourceName = resource?.name ?: action.resourceName,
            edition = resource?.edition.orEmpty(), chapter = action.chapter,
            kind = if (action.questionFirst == null) "UNIT" else "QUESTION",
            intervals = if (action.questionFirst == null) emptyList() else
                listOf(ContentInterval(action.questionFirst, requireNotNull(action.questionLast))),
            roundKey = goal?.roundKey ?: "FIRST",
        ) else null
        val id = if (action.sourceTemplateId != null) {
            UUID.nameUUIDFromBytes("lixing:override:${plan.id}:${action.date}:${action.sourceTemplateId}"
                .toByteArray()).toString()
        } else {
            UUID.nameUUIDFromBytes("lixing:ai-dated:${plan.id}:${action.date}:${action.ordinal}:$action"
                .toByteArray()).toString()
        }
        if (planning.getSchedules(plan.id, action.date, action.date).any { it.id == id &&
                it.state in setOf("ACTIVE", "OVERRIDE") }) {
            return fail(action, "该日期安排已存在，请重新生成方案")
        }
        val minutes = action.plannedMinutes ?: if (action.startTime != null && action.endTime != null) {
            SlotWindow.of(action.date, action.startTime, action.endTime).duration.toMinutes().toInt()
        } else null
        val entry = ScheduledTaskEntity(
            id = id, planId = plan.id, studyDate = action.date,
            sourceTemplateId = action.sourceTemplateId,
            subjectId = action.subjectId, timeSlotId = action.timeSlotId,
            resourceId = resource?.id, goalId = goal?.id, roundKey = goal?.roundKey ?: "FIRST",
            title = action.title,
            taskType = if (action.questionFirst == null) TaskType.CUSTOM else TaskType.PRACTICE,
            targetType = if (action.questionFirst == null) TargetType.BOOLEAN else TargetType.COUNT,
            targetValue = content?.quantity()?.takeIf { action.questionFirst != null } ?: 1,
            contentJson = content?.let(ContentSelectionCodec::encode).orEmpty(),
            plannedMinutes = minutes, startTime = action.startTime, endTime = action.endTime,
            state = if (action.sourceTemplateId == null) "ACTIVE" else "OVERRIDE",
        )
        return try {
            planning.saveSchedule(entry, recordManualChange = false)
            ok(action, "已安排 ${action.date}「${action.title}」")
        } catch (error: IllegalArgumentException) {
            fail(action, error.message ?: "日期安排无效")
        } catch (error: IllegalStateException) {
            fail(action, error.message ?: "日期安排冲突")
        }
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
                requiredTaskCount = action.requiredTaskCount ?: slot.requiredTaskCount,
                isEnabled = slot.isEnabled,
            ),
        )
        val detail = buildString {
            if (action.startTime != null || action.endTime != null) {
                append("时间调整为 $newStart-$newEnd")
            }
            if (action.requiredTaskCount != null) {
                if (isNotEmpty()) append("，")
                append(
                    if (action.requiredTaskCount == 0) "改为全部任务都要完成"
                    else "改为至少完成 ${action.requiredTaskCount} 项",
                )
            }
        }
        return ok(action, "时段「${slot.name}」$detail")
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
