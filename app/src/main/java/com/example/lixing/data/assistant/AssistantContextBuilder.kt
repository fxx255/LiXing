package com.example.lixing.data.assistant

import com.example.lixing.data.repository.EnglishEntryRepository
import com.example.lixing.data.repository.PlanRepository
import com.example.lixing.data.repository.TaskRepository
import com.example.lixing.domain.assistant.AssistantContextKind
import com.example.lixing.domain.word.WordSource
import android.util.Log
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 把用户勾选的本机学习数据裁剪成一段紧凑文本，作为助手的上下文。
 *
 * 原则：
 * - 只包含用户主动勾选的种类；
 * - 只取「AI 需要知道的最小集合」：ID、名称、时间、目标、状态；
 * - 不包含照片、打卡备注原文、饮食记录、任何 Token；
 * - 总长度截断到 [MAX_CONTEXT_CHARS]，避免费用失控。
 */
@Singleton
class AssistantContextBuilder @Inject constructor(
    private val planRepository: PlanRepository,
    private val taskRepository: TaskRepository,
    private val englishEntryRepository: EnglishEntryRepository,
    private val wordSource: WordSource,
) {
    private val timeFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    suspend fun build(kinds: Set<AssistantContextKind>, today: LocalDate): String {
        val sections = mutableListOf<String>()
        if (AssistantContextKind.PLAN in kinds) sections += buildPlanSection()
        if (AssistantContextKind.TODAY in kinds) sections += buildTodaySection(today)
        if (AssistantContextKind.STATS in kinds) sections += buildStatsSection(today)
        if (AssistantContextKind.ENGLISH in kinds) sections += buildEnglishSection()
        if (AssistantContextKind.WORDS in kinds) sections += buildWordsSection()
        if (sections.isEmpty()) return ""
        return sections.joinToString("\n\n").take(MAX_CONTEXT_CHARS)
    }

    private suspend fun buildPlanSection(): String = runCatching {
        val plan = planRepository.getActivePlan() ?: return "## 当前计划\n（还没有学习计划）"
        val subjects = planRepository.getSubjects(plan.id)
        val slots = planRepository.getTimeSlots(plan.id)
        val templates = planRepository.getEnabledTemplates(plan.id)
        buildString {
            appendLine("## 当前计划")
            appendLine("- 计划：${plan.name}（${plan.startDate} ~ 目标日 ${plan.targetDate}）")
            appendLine("- 科目：")
            subjects.forEach { appendLine("  - [id=${it.id}] ${it.name}") }
            appendLine("- 时段：")
            slots.forEach {
                appendLine(
                    "  - [id=${it.id}] ${it.name} ${it.startTime.format(timeFmt)}-${it.endTime.format(timeFmt)}" +
                        if (it.isEnabled) "" else "（已停用）",
                )
            }
            appendLine("- 启用的任务模板：")
            templates.forEach { t ->
                val subject = subjects.firstOrNull { it.id == t.subjectId }?.name ?: "?"
                val slot = slots.firstOrNull { it.id == t.timeSlotId }?.name ?: "?"
                val target = when {
                    t.targetValue > 1 -> "目标 ${t.targetValue}"
                    else -> "完成即可"
                }
                appendLine(
                    "  - [id=${t.id}] ${t.title}（科目:$subject，时段:$slot，$target，重复:${t.repeatRule.name}" +
                        if (t.isKeystone) "，关键任务）" else "）",
                )
            }
        }.trimEnd()
    }.getOrElse { e ->
        Log.e(TAG, "plan section failed", e)
        "## 当前计划\n（读取失败：${describe(e)}）"
    }

    private suspend fun buildTodaySection(today: LocalDate): String = runCatching {
        val tasks = taskRepository.getTasksOfDay(today)
        if (tasks.isEmpty()) return "## 今日任务（$today）\n（今天没有任务）"
        buildString {
            appendLine("## 今日任务（$today）")
            tasks.sortedBy { it.slotSortOrder }.forEach { t ->
                val target = if (t.targetType.isQuantified) {
                    "目标:${t.targetValue}${t.targetType.unit}，进度:${t.actualValue}/${t.targetValue}"
                } else {
                    "目标:完成即可"
                }
                appendLine(
                    "  - [id=${t.id}] ${t.title}（${t.subjectName}，" +
                        "时段:${t.slotName}[id=${t.timeSlotId}]，$target，状态:${t.status.name}）",
                )
            }
        }.trimEnd()
    }.getOrElse { e ->
        Log.e(TAG, "today section failed", e)
        "## 今日任务\n（读取失败：${describe(e)}）"
    }

    private suspend fun buildStatsSection(today: LocalDate): String = runCatching {
        val from = today.minusDays(6)
        val focus = taskRepository.getTotalFocusMinutes(from, today)
        val subjectMinutes = taskRepository.getSubjectMinutes(from, today)
        val slotCompletion = taskRepository.getSlotCompletion(from, today)
        buildString {
            appendLine("## 近 7 天统计（$from ~ $today）")
            appendLine("- 累计专注：$focus 分钟")
            if (subjectMinutes.isNotEmpty()) {
                appendLine("- 各科目投入：")
                subjectMinutes.sortedByDescending { it.minutes }.forEach {
                    appendLine("  - ${it.subjectName}：${it.minutes} 分钟")
                }
            }
            if (slotCompletion.isNotEmpty()) {
                appendLine("- 各时段完成率：")
                slotCompletion.forEach {
                    appendLine("  - ${it.slotName}：${(it.rate * 100).toInt()}%（${it.done}/${it.total}）")
                }
            }
        }.trimEnd()
    }.getOrElse { e ->
        Log.e(TAG, "stats section failed", e)
        "## 近 7 天统计\n（读取失败：${describe(e)}）"
    }

    private suspend fun buildEnglishSection(): String = runCatching {
        val entries = englishEntryRepository.observe("", null).first().take(50)
        if (entries.isEmpty()) return "## 英语积累\n（还没有积累内容）"
        buildString {
            appendLine("## 英语积累（最近 ${entries.size} 条，id 可用于修改或删除）")
            entries.forEach {
                appendLine("  - [id=${it.id}] [${it.type.name}] ${it.content} —— ${it.meaning.take(80)}")
            }
        }.trimEnd()
    }.getOrElse { e ->
        Log.e(TAG, "english section failed", e)
        "## 英语积累\n（读取失败：${describe(e)}）"
    }

    private suspend fun buildWordsSection(): String = runCatching {
        if (!wordSource.isConfigured()) return "## 墨墨今日单词\n（未连接墨墨）"
        val words = wordSource.todayWords(limit = 100)
        if (words.isEmpty()) return "## 墨墨今日单词\n（今天没有单词安排）"
        "## 墨墨今日单词\n" + words.joinToString("、") { it.spelling }
    }.getOrElse { e ->
        Log.e(TAG, "words section failed", e)
        "## 墨墨今日单词\n（读取失败：${describe(e)}）"
    }

    private fun describe(e: Throwable): String =
        "${e::class.java.simpleName}: ${e.message ?: "无详情"}"

    private companion object {
        const val TAG = "AssistantContext"
        const val MAX_CONTEXT_CHARS = 8_000
    }
}
