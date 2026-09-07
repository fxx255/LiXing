package com.example.lixing.domain.schedule

import com.example.lixing.data.local.entity.TimeSlotEntity
import java.time.Duration

/** 一天的排程分析结果。 */
data class ScheduleLoad(
    /** 所有时段合计的学习分钟数。 */
    val scheduledMinutes: Int,
    /** 相邻时段之间的空档（休息）合计分钟数。 */
    val restMinutes: Int,
    /** 是否存在过长的连续学习块（≥3.5 小时中间无空档）。 */
    val hasLongBlock: Boolean,
    /** 建议文案。为空表示排程健康。 */
    val warnings: List<String>,
) {
    val isOverPacked: Boolean get() = warnings.isNotEmpty()
}

/**
 * 排程健康度分析。
 *
 * 理念：排得太满的计划执行不下去。这里给出可量化的提醒：
 * - 一天学习总量超过阈值（默认 10 小时）→ 提醒减量；
 * - 相邻时段之间没有空档（连着干）→ 提醒插休息；
 * - 单个时段过长（≥3.5h）→ 提醒中途休息。
 *
 * 纯函数，不碰库、不读时钟，可单测。
 */
object ScheduleLoadAnalyzer {

    /** 一天建议的学习上限（分钟）。超过就提醒。 */
    const val MAX_SCHEDULED_MINUTES = 10 * 60

    /** 相邻时段之间建议的最小空档（分钟）。 */
    const val MIN_GAP_MINUTES = 20

    /** 单个时段超过这个长度就提醒中途休息。3.5h 的常规学习块不报警，4h 才报。 */
    const val LONG_BLOCK_MINUTES = 240

    fun analyze(slots: List<TimeSlotEntity>): ScheduleLoad {
        val enabled = slots.filter { it.isEnabled }
            .sortedBy { it.startTime }

        var scheduled = 0
        var rest = 0
        var hasLongBlock = false
        val warnings = ArrayList<String>()

        enabled.forEachIndexed { index, slot ->
            val len = slotLengthMinutes(slot)
            scheduled += len
            if (len >= LONG_BLOCK_MINUTES) hasLongBlock = true

            // 与上一个时段的空档
            if (index > 0) {
                val prev = enabled[index - 1]
                val prevEnd = prev.endTime
                val gap = Duration.between(prevEnd, slot.startTime).toMinutes()
                if (gap in 0 until MIN_GAP_MINUTES) {
                    warnings.add("「${prev.name}」和「${slot.name}」之间几乎没有空隙，留 ${MIN_GAP_MINUTES} 分钟以上休息。")
                } else if (gap > 0) {
                    rest += gap.toInt()
                }
            }
        }

        if (scheduled > MAX_SCHEDULED_MINUTES) {
            warnings.add(
                "一天排了 ${scheduled / 60} 小时学习，超过 ${MAX_SCHEDULED_MINUTES / 60} 小时。" +
                    "砍掉一两件，留出喘息的余地，坚持比强度重要。",
            )
        }

        if (hasLongBlock) {
            warnings.add("有超过 ${LONG_BLOCK_MINUTES / 60} 小时的连续时段，中间安排一次休息效率更高。")
        }

        return ScheduleLoad(
            scheduledMinutes = scheduled,
            restMinutes = rest,
            hasLongBlock = hasLongBlock,
            warnings = warnings,
        )
    }

    /** 时段长度（分钟），处理跨夜。 */
    private fun slotLengthMinutes(slot: TimeSlotEntity): Int {
        val start = slot.startTime
        val end = slot.endTime
        val minutes = Duration.between(start, end).toMinutes().toInt()
        return if (minutes <= 0) minutes + 24 * 60 else minutes
    }
}
