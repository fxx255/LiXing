package com.example.lixing.domain.assistant

import com.example.lixing.domain.english.EnglishEntryType
import com.example.lixing.domain.model.RepeatRule
import com.example.lixing.domain.model.TargetType
import com.example.lixing.domain.model.TaskType
import java.time.LocalTime

/** 用户在向助手提问时可以选择附带的上下文种类。默认全部由用户手动勾选。 */
enum class AssistantContextKind(val label: String) {
    PLAN("当前计划"),
    TODAY("今日任务"),
    STATS("近期统计"),
    ENGLISH("英语积累"),
    WORDS("墨墨单词"),
}

/** 会话消息。role 只允许 user / assistant。 */
data class AssistantMessage(
    val role: String,
    val content: String,
    val imagePaths: List<String> = emptyList(),
    /** Optional compact text shown in the chat UI; [content] remains the model-only body. */
    val displayContent: String? = null,
)

/**
 * AI 可以返回的结构化「计划修改建议」。
 *
 * 原则：AI 只能给出建议，所有建议必须经过本地校验与用户逐条确认；
 * 不允许删除类动作，也不允许触碰历史任务、积分与成就。
 */
sealed class PlanAction {
    abstract val reason: String

    /** 调整现有时段的起止时间（任一为空表示不改）。 */
    data class UpdateTimeSlot(
        val slotId: String,
        val startTime: LocalTime?,
        val endTime: LocalTime?,
        override val reason: String,
    ) : PlanAction()

    /** 调整现有任务模板的若干字段（null 表示不改）。 */
    data class UpdateTaskTemplate(
        val templateId: String,
        val title: String?,
        val targetValue: Int?,
        val timeSlotId: String?,
        val repeatRule: RepeatRule?,
        val isKeystone: Boolean?,
        val isEnabled: Boolean?,
        override val reason: String,
    ) : PlanAction()

    /** 新增一个任务模板。 */
    data class InsertTaskTemplate(
        val subjectId: String,
        val timeSlotId: String,
        val title: String,
        val taskType: TaskType,
        val targetType: TargetType,
        val targetValue: Int,
        val repeatRule: RepeatRule,
        val isKeystone: Boolean,
        val note: String,
        override val reason: String,
    ) : PlanAction()

    /** 只调整今天尚未打卡的任务快照，不修改任务模板或未来日期。 */
    data class UpdateTodayTask(
        val taskId: String,
        val targetValue: Int?,
        val timeSlotId: String?,
        override val reason: String,
    ) : PlanAction()

    /** 仅跳过今天的待做任务；保留任务记录、模板以及未来日期安排。 */
    data class SkipTodayTask(
        val taskId: String,
        override val reason: String,
    ) : PlanAction()

    /** 整个学习日请假；本地负责校验月度额度并保留已完成记录。 */
    data class TakeTodayOff(
        override val reason: String,
    ) : PlanAction()

    /** 保留指定的今日任务，其余今日待做任务统一跳过。 */
    data class KeepTodayTasks(
        val keepTaskIds: List<String>,
        override val reason: String,
    ) : PlanAction()
}

/**
 * AI 可以返回的结构化「英语积累变更建议」。
 *
 * 与计划修改一致：AI 只给建议，本地负责校验长度与目标是否存在，
 * 用户逐条确认后才会真正写入本机数据库；删除也只在用户勾选后才执行。
 */
sealed class EnglishEntryAction {
    abstract val reason: String

    /** 新增一条英语积累。 */
    data class Add(
        val type: EnglishEntryType,
        val content: String,
        val meaning: String,
        override val reason: String,
    ) : EnglishEntryAction()

    /** 修改已有条目。null 字段表示不改。 */
    data class Update(
        val id: String,
        val type: EnglishEntryType? = null,
        val content: String? = null,
        val meaning: String? = null,
        override val reason: String,
    ) : EnglishEntryAction()

    /** 删除已有条目（只在用户确认后执行）。 */
    data class Delete(
        val id: String,
        override val reason: String,
    ) : EnglishEntryAction()
}
