package com.example.lixing.ui.screen.assistant

import com.example.lixing.data.prefs.UserPreferencesRepository
import com.example.lixing.data.repository.EnglishEntryRepository
import com.example.lixing.data.repository.PlanRepository
import com.example.lixing.data.repository.TaskRepository
import com.example.lixing.domain.assistant.EnglishEntryAction
import com.example.lixing.domain.assistant.PlanAction
import com.example.lixing.domain.english.EnglishEntryType
import com.example.lixing.domain.model.TaskStatus
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/** Builds review cards from stored data before the user confirms plan or English changes. */
class AssistantReviewPreviewBuilder @Inject constructor(
    private val prefsRepository: UserPreferencesRepository,
    private val planRepository: PlanRepository,
    private val taskRepository: TaskRepository,
    private val englishEntryRepository: EnglishEntryRepository,
) {
    // ---------------- 预览构建 ----------------

    private val timeFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    suspend fun buildPreviews(
        actions: List<PlanAction>,
        today: LocalDate,
    ): List<PendingPlanAction> =
        actions.map { action ->
            when (action) {
                is PlanAction.UpdateTimeSlot -> previewUpdateTimeSlot(action)
                is PlanAction.UpdateTaskTemplate -> previewUpdateTemplate(action)
                is PlanAction.InsertTaskTemplate -> previewInsertTemplate(action)
                is PlanAction.UpdateTodayTask -> previewUpdateTodayTask(action, today)
                is PlanAction.SkipTodayTask -> previewSkipTodayTask(action, today)
                is PlanAction.TakeTodayOff -> previewTakeTodayOff(action, today)
                is PlanAction.KeepTodayTasks -> previewKeepTodayTasks(action, today)
            }
        }

    private suspend fun previewUpdateTimeSlot(action: PlanAction.UpdateTimeSlot): PendingPlanAction {
        val slot = planRepository.getTimeSlot(action.slotId)
            ?: return invalidPreview(action, "调整时段（id=${action.slotId}）", "时段不存在")
        val newStart = action.startTime ?: slot.startTime
        val newEnd = action.endTime ?: slot.endTime
        val newRequired = action.requiredTaskCount
        val before = "${slot.startTime.format(timeFmt)}-${slot.endTime.format(timeFmt)}" +
            requiredSuffix(slot.requiredTaskCount)
        val after = "${newStart.format(timeFmt)}-${newEnd.format(timeFmt)}" +
            requiredSuffix(newRequired ?: slot.requiredTaskCount)
        val problem = when {
            newStart == newEnd -> "开始时间与结束时间相同"
            before == after -> "方案与当前时段相同"
            else -> null
        }
        return PendingPlanAction(
            action,
            title = "调整时段「${slot.name}」",
            before = before,
            after = after,
            scope = PlanChangeScope.LONG_TERM,
            problem = problem,
        )
    }

    /** 时段预览里「每时段至少完成几项」的后缀；0 = 全部都要完成。 */
    private fun requiredSuffix(count: Int): String =
        if (count > 0) " · 至少${count}项" else " · 全部"

    private suspend fun previewUpdateTemplate(action: PlanAction.UpdateTaskTemplate): PendingPlanAction {
        val template = planRepository.getTemplate(action.templateId)
            ?: return invalidPreview(action, "调整任务（id=${action.templateId}）", "任务模板不存在")
        val currentSlot = planRepository.getTimeSlot(template.timeSlotId)
            ?: return invalidPreview(action, "调整任务「${template.title}」", "当前时段不存在")
        val targetSlot = action.timeSlotId?.let { planRepository.getTimeSlot(it) }
        if (action.timeSlotId != null && targetSlot == null) {
            return invalidPreview(action, "调整任务「${template.title}」", "目标时段不存在")
        }
        val before = templateSummary(
            title = template.title,
            slotName = currentSlot.name,
            target = targetText(template.targetValue, template.targetType.unit, template.targetType.isQuantified),
            repeat = template.repeatRule.label,
            isKeystone = template.isKeystone,
            isEnabled = template.isEnabled,
        )
        val after = templateSummary(
            title = action.title ?: template.title,
            slotName = targetSlot?.name ?: currentSlot.name,
            target = targetText(
                action.targetValue ?: template.targetValue,
                template.targetType.unit,
                template.targetType.isQuantified,
            ),
            repeat = (action.repeatRule ?: template.repeatRule).label,
            isKeystone = action.isKeystone ?: template.isKeystone,
            isEnabled = action.isEnabled ?: template.isEnabled,
        )
        return PendingPlanAction(
            action = action,
            title = "调整任务「${template.title}」",
            before = before,
            after = after,
            scope = PlanChangeScope.LONG_TERM,
            problem = "方案与当前任务相同".takeIf { before == after },
        )
    }

    private suspend fun previewInsertTemplate(action: PlanAction.InsertTaskTemplate): PendingPlanAction {
        val subject = planRepository.getSubject(action.subjectId)
        val slot = planRepository.getTimeSlot(action.timeSlotId)
        if (subject == null || slot == null) {
            return invalidPreview(action, "新增任务「${action.title}」", "科目或时段不存在")
        }
        return PendingPlanAction(
            action = action,
            title = "新增任务「${action.title}」",
            before = "当前计划中无此任务",
            after = "${subject.name} · ${slot.name} · " +
                "${targetText(action.targetValue, action.targetType.unit, action.targetType.isQuantified)} · " +
                action.repeatRule.label,
            scope = PlanChangeScope.LONG_TERM,
        )
    }

    private suspend fun previewUpdateTodayTask(
        action: PlanAction.UpdateTodayTask,
        today: LocalDate,
    ): PendingPlanAction {
        val task = taskRepository.getTask(action.taskId)
            ?: return invalidPreview(action, "调整今日任务（id=${action.taskId}）", "任务不存在", PlanChangeScope.TODAY)
        val targetSlot = action.timeSlotId?.let { planRepository.getTimeSlot(it) }
        val subject = planRepository.getSubject(task.subjectId)
        val before = "${task.slotName} · ${targetText(task.targetValue, task.targetType.unit, task.targetType.isQuantified)}"
        val after = "${targetSlot?.name ?: task.slotName} · " + targetText(
            action.targetValue ?: task.targetValue,
            task.targetType.unit,
            task.targetType.isQuantified,
        )
        val problem = when {
            task.date != today -> "该任务不属于今天（${task.date}）"
            task.status != TaskStatus.PENDING -> "只能调整尚未打卡的任务（当前：${task.status.label}）"
            action.targetValue != null && !task.targetType.isQuantified -> "完成型任务不能调整目标量"
            action.timeSlotId != null && targetSlot == null -> "目标时段不存在"
            targetSlot != null && subject?.planId != targetSlot.planId -> "目标时段不属于当前计划"
            before == after -> "方案与当前任务相同"
            else -> null
        }
        return PendingPlanAction(
            action = action,
            title = "临时调整「${task.title}」",
            before = before,
            after = after,
            scope = PlanChangeScope.TODAY,
            problem = problem,
        )
    }

    private suspend fun previewSkipTodayTask(
        action: PlanAction.SkipTodayTask,
        today: LocalDate,
    ): PendingPlanAction {
        val task = taskRepository.getTask(action.taskId)
            ?: return invalidPreview(action, "仅今日跳过任务（id=${action.taskId}）", "任务不存在", PlanChangeScope.TODAY)
        val before = "${task.slotName} · ${task.status.label} · " +
            targetText(task.targetValue, task.targetType.unit, task.targetType.isQuantified)
        val problem = when {
            task.date != today -> "该任务不属于今天（${task.date}）"
            task.status != TaskStatus.PENDING -> "只能跳过尚未打卡的任务（当前：${task.status.label}）"
            else -> null
        }
        return PendingPlanAction(
            action = action,
            title = "仅今日跳过「${task.title}」",
            before = before,
            after = "仅今日跳过 · 不计入完成率和漏卡扣分 · 明天照常安排",
            scope = PlanChangeScope.TODAY,
            problem = problem,
        )
    }

    private suspend fun previewTakeTodayOff(
        action: PlanAction.TakeTodayOff,
        today: LocalDate,
    ): PendingPlanAction {
        val tasks = taskRepository.getTasksOfDay(today)
        val record = taskRepository.getDayRecord(today)
        val affected = tasks.count { it.status == TaskStatus.PENDING || it.status == TaskStatus.MISSED }
        val prefs = prefsRepository.current()
        val used = taskRepository.getDayRecordsBetween(
            today.withDayOfMonth(1),
            today.withDayOfMonth(today.lengthOfMonth()),
        ).count { it.isDayOff }
        val problem = when {
            record?.isSettled == true -> "今天已经结算"
            record?.isDayOff == true -> "今天已经处于请假状态"
            affected == 0 -> "今天没有可请假的待做任务"
            used >= prefs.dayOffPerMonth -> "本月请假额度已用完（${prefs.dayOffPerMonth} 天）"
            else -> null
        }
        return PendingPlanAction(
            action = action,
            title = "今日整天请假",
            before = "今日 ${tasks.count { it.status != TaskStatus.SKIPPED }} 项有效任务，其中 $affected 项可请假",
            after = "今日整天请假 · 待做任务全部跳过 · 不计完成率且不打断连续记录 · " +
                "本月剩余 ${maxOf(0, prefs.dayOffPerMonth - used - 1)} 天",
            scope = PlanChangeScope.TODAY,
            problem = problem,
        )
    }

    private suspend fun previewKeepTodayTasks(
        action: PlanAction.KeepTodayTasks,
        today: LocalDate,
    ): PendingPlanAction {
        val tasks = taskRepository.getTasksOfDay(today)
        val record = taskRepository.getDayRecord(today)
        val byId = tasks.associateBy { it.id }
        val keepTasks = action.keepTaskIds.mapNotNull(byId::get)
        val missing = action.keepTaskIds.filterNot(byId::containsKey)
        val skippedCount = tasks.count { it.id !in action.keepTaskIds && it.status == TaskStatus.PENDING }
        val problem = when {
            record?.isSettled == true -> "今天已经结算"
            record?.isDayOff == true -> "今天已整日请假"
            missing.isNotEmpty() -> "要保留的任务不存在或不属于今天：${missing.joinToString()}"
            keepTasks.any { it.status == TaskStatus.SKIPPED || it.status == TaskStatus.MISSED } ->
                "要保留的任务中包含已跳过或已漏卡任务"
            skippedCount == 0 -> "除保留任务外，没有其他今日待做任务"
            else -> null
        }
        return PendingPlanAction(
            action = action,
            title = "今日仅保留 ${keepTasks.size} 项任务",
            before = "今日 ${tasks.count { it.status != TaskStatus.SKIPPED }} 项有效任务",
            after = "保留：${keepTasks.joinToString("、") { it.title }}；其余 $skippedCount 项仅今日跳过",
            scope = PlanChangeScope.TODAY,
            problem = problem,
        )
    }

    // ---------------- 英语积累预览构建 ----------------

    /** 返回 null 表示本轮没有英语积累变更，保留界面上原有的待确认项。 */
    suspend fun buildEnglishPreviews(
        actions: List<EnglishEntryAction>,
    ): List<PendingEnglishAction>? {
        if (actions.isEmpty()) return null
        return actions.take(MAX_ENGLISH_ACTIONS).map { action ->
            when (action) {
                is EnglishEntryAction.Add -> previewAddEnglish(action)
                is EnglishEntryAction.Update -> previewUpdateEnglish(action)
                is EnglishEntryAction.Delete -> previewDeleteEnglish(action)
            }
        }
    }

    private fun previewAddEnglish(action: EnglishEntryAction.Add): PendingEnglishAction {
        val content = action.content.trim()
        val meaning = action.meaning.trim()
        val problem = when {
            content.isEmpty() -> "英文内容为空"
            meaning.isEmpty() -> "释义为空"
            content.length > EnglishEntryRepository.MAX_CONTENT_LENGTH ->
                "英文内容超过 ${EnglishEntryRepository.MAX_CONTENT_LENGTH} 个字符"
            meaning.length > EnglishEntryRepository.MAX_MEANING_LENGTH ->
                "释义超过 ${EnglishEntryRepository.MAX_MEANING_LENGTH} 个字符"
            else -> null
        }
        return PendingEnglishAction(
            action = action,
            title = "新增${englishTypeLabel(action.type)}「${content.take(30)}」",
            before = "当前英语积累中没有这一条",
            after = "${englishTypeLabel(action.type)} · $content —— $meaning",
            problem = problem,
        )
    }

    private suspend fun previewUpdateEnglish(action: EnglishEntryAction.Update): PendingEnglishAction {
        val existing = englishEntryRepository.get(action.id)
            ?: return invalidEnglishPreview(action, "修改英语积累（id=${action.id}）", "要修改的条目不存在")
        val newType = action.type ?: existing.type
        val newContent = action.content?.trim() ?: existing.content
        val newMeaning = action.meaning?.trim() ?: existing.meaning
        val before = "${englishTypeLabel(existing.type)} · ${existing.content} —— ${existing.meaning}"
        val after = "${englishTypeLabel(newType)} · $newContent —— $newMeaning"
        val problem = when {
            newContent.isBlank() -> "英文内容为空"
            newMeaning.isBlank() -> "释义为空"
            newContent.length > EnglishEntryRepository.MAX_CONTENT_LENGTH ->
                "英文内容超过 ${EnglishEntryRepository.MAX_CONTENT_LENGTH} 个字符"
            newMeaning.length > EnglishEntryRepository.MAX_MEANING_LENGTH ->
                "释义超过 ${EnglishEntryRepository.MAX_MEANING_LENGTH} 个字符"
            before == after -> "内容与当前条目相同"
            else -> null
        }
        return PendingEnglishAction(
            action = action,
            title = "修改英语积累「${existing.content.take(30)}」",
            before = before,
            after = after,
            problem = problem,
        )
    }

    private suspend fun previewDeleteEnglish(action: EnglishEntryAction.Delete): PendingEnglishAction {
        val existing = englishEntryRepository.get(action.id)
            ?: return invalidEnglishPreview(action, "删除英语积累（id=${action.id}）", "要删除的条目不存在")
        return PendingEnglishAction(
            action = action,
            title = "删除英语积累「${existing.content.take(30)}」",
            before = "${englishTypeLabel(existing.type)} · ${existing.content} —— ${existing.meaning}",
            after = "删除后本机与之后的新备份中都不再保留",
        )
    }

    private fun invalidEnglishPreview(
        action: EnglishEntryAction,
        title: String,
        problem: String,
    ) = PendingEnglishAction(
        action = action,
        title = title,
        before = "无法读取当前内容",
        after = "不会应用",
        problem = problem,
    )

    private fun englishTypeLabel(type: EnglishEntryType): String = when (type) {
        EnglishEntryType.WORD -> "单词"
        EnglishEntryType.PHRASE -> "短语"
        EnglishEntryType.SENTENCE -> "句子"
    }

    private fun invalidPreview(
        action: PlanAction,
        title: String,
        problem: String,
        scope: PlanChangeScope = PlanChangeScope.LONG_TERM,
    ) = PendingPlanAction(
        action = action,
        title = title,
        before = "无法读取当前内容",
        after = "不会应用",
        scope = scope,
        problem = problem,
    )

    private fun targetText(value: Int, unit: String, quantified: Boolean): String =
        if (quantified) "$value$unit" else "完成即可"

    private fun templateSummary(
        title: String,
        slotName: String,
        target: String,
        repeat: String,
        isKeystone: Boolean,
        isEnabled: Boolean,
    ): String = buildList {
        add(title)
        add(slotName)
        add(target)
        add(repeat)
        if (isKeystone) add("关键任务")
        add(if (isEnabled) "启用" else "停用")
    }.joinToString(" · ")

    private companion object {
        const val MAX_ENGLISH_ACTIONS = 20
    }
}
