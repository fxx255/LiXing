package com.example.lixing.domain.seed

import com.example.lixing.domain.model.RepeatRule
import com.example.lixing.domain.model.TargetType
import com.example.lixing.domain.model.TaskType
import java.time.DayOfWeek
import java.time.LocalTime

/**
 * 种子模板的中间表示。
 *
 * 用「阶段名 / 时段名 / 科目名」这类字符串引用而不是 id，因为写模板时还没有 id。
 * 导入器（TemplateImporter）负责建表后回填 id。
 */

/** 一个完整的可导入计划模板。 */
data class SeedPlan(
    val name: String,
    /** 计划说明，展示在导入确认页。 */
    val description: String,
    val phases: List<SeedPhase>,
    val timeSlots: List<SeedTimeSlot>,
    val subjects: List<SeedSubject>,
    val tasks: List<SeedTask>,
)

/**
 * 阶段。用「月-日」表示边界而不是完整日期，因为具体年份取决于用户设定的目标日。
 * [startMonthDay] 为 null 表示「从计划开始日算」，[endMonthDay] 为 null 表示「到目标日」。
 */
data class SeedPhase(
    val name: String,
    val startMonthDay: MonthDaySpec?,
    val endMonthDay: MonthDaySpec?,
    val description: String,
    val sortOrder: Int,
)

/** 月-日，外加「相对目标日所在年」的偏移。备考周期跨年时用得上。 */
data class MonthDaySpec(val month: Int, val day: Int, val yearOffsetFromTarget: Int = 0)

data class SeedTimeSlot(
    val name: String,
    val start: LocalTime,
    val end: LocalTime,
    val note: String,
    val sortOrder: Int,
    val days: Set<DayOfWeek> = DayOfWeek.entries.toSet(),
)

data class SeedSubject(
    val name: String,
    /** 调色板索引，导入时映射到实际颜色。 */
    val paletteIndex: Int,
    val iconKey: String,
    /** 目标总时长（小时）。0 = 不设。 */
    val targetHours: Int = 0,
    val sortOrder: Int,
)

data class SeedTask(
    val subjectName: String,
    val slotName: String,
    val title: String,
    val taskType: TaskType,
    val targetType: TargetType,
    val targetValue: Int,
    /** 绑定的阶段名。null = 全程有效。 */
    val phaseName: String? = null,
    val repeatRule: RepeatRule = RepeatRule.DAILY,
    val days: Set<DayOfWeek> = DayOfWeek.entries.toSet(),
    val intervalDays: Int = 1,
    val isKeystone: Boolean = false,
    val note: String = "",
    val sortOrder: Int = 0,
)
