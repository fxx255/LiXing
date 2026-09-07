package com.example.lixing.ui.screen.today

import com.example.lixing.data.local.entity.DailyTaskEntity
import com.example.lixing.data.local.entity.PhaseEntity
import com.example.lixing.data.local.entity.StudyPlanEntity
import com.example.lixing.domain.settle.DayStats
import com.example.lixing.domain.time.SlotState
import com.example.lixing.domain.word.WordProgress
import java.time.LocalDate
import java.time.LocalTime

/** 首页整体状态。 */
data class TodayUiState(
    val isLoading: Boolean = true,
    /** 是否还没有任何计划（空态引导导入）。 */
    val hasNoPlan: Boolean = false,

    val plan: StudyPlanEntity? = null,
    val currentPhase: PhaseEntity? = null,
    val today: LocalDate = LocalDate.MIN,
    /** 距目标日剩余天数，负数表示已过。 */
    val daysToTarget: Int = 0,

    val stats: DayStats = DayStats.EMPTY,
    val currentStreak: Int = 0,
    val longestStreak: Int = 0,
    /** 本周（从周一算）每天是否达成。下标 0 = 周一。 */
    val weekAchieved: List<Boolean?> = List(7) { null },
    val rescueCardsLeft: Int = 0,

    /** 按时段分组好的任务区块，当前时段已排到前面。 */
    val slotSections: List<SlotSection> = emptyList(),

    val level: Int = 1,
    val totalPoints: Int = 0,
    val title: String = "初心者",

    // ---------- 墨墨背单词 ----------
    val maimemoEnabled: Boolean = false,
    val maimemoProgress: WordProgress? = null,
    val maimemoSyncing: Boolean = false,
    val maimemoMessage: String? = null,
) {
    /** 有没有任何待办任务，决定要不要展示空态。 */
    val hasTasks: Boolean get() = slotSections.isNotEmpty()
}

/** 一个时段下的任务集合。 */
data class SlotSection(
    val slotId: String,
    val slotName: String,
    val start: LocalTime,
    val end: LocalTime,
    val sortOrder: Int,
    val state: SlotState,
    /** 是否当前进行中的时段（用于置顶高亮）。 */
    val isCurrent: Boolean,
    val tasks: List<DailyTaskEntity>,
    val note: String = "",
    val requiredTaskCount: Int = 0,
) {
    val doneCount: Int get() = tasks.count { it.status.isEngaged }
    val totalCount: Int get() = tasks.size
    val allDone: Boolean get() = totalCount > 0 &&
        (if (requiredTaskCount > 0) doneCount >= requiredTaskCount else doneCount == totalCount)
}
