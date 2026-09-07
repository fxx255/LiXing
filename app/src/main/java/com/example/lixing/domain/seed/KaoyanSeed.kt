package com.example.lixing.domain.seed

import com.example.lixing.domain.model.RepeatRule
import com.example.lixing.domain.model.TargetType
import com.example.lixing.domain.model.TaskType
import java.time.DayOfWeek
import java.time.LocalTime

/**
 * 内置「考研全程计划」模板（v2 排程）。
 *
 * 作息：8:00 开始、21:30 结束，中间 11:30–14:00 与 17:00–19:00 两段休息，
 * 不排早读、不熬夜。每天覆盖 英语 + 数学 + 专业课；政治隔天才学（EVERY_N_DAYS=2），
 * 把晚上的精力留给专业课和休息。
 */
object KaoyanSeed {

    const val PHASE_BASE = "基础阶段"
    const val PHASE_INTENSIVE = "强化阶段"
    const val PHASE_SPRINT = "冲刺阶段"

    const val SLOT_WORD_AM = "晨间单词"
    const val SLOT_AM = "上午"
    const val SLOT_PM = "下午"
    const val SLOT_EVE = "晚间"

    const val SUBJECT_MATH = "数学"
    const val SUBJECT_ENGLISH = "英语"
    const val SUBJECT_POLITICS = "政治"
    const val SUBJECT_MAJOR = "专业课"

    val plan: SeedPlan = SeedPlan(
        name = "考研全程计划",
        description = "8:00–21:30，每天英语+数学+专业课，政治隔天学，两段休息不熬夜。" +
            "按基础/强化/冲刺推进，导入后都能改。",
        phases = phases(),
        timeSlots = timeSlots(),
        subjects = subjects(),
        tasks = englishTasks() + mathTasks() + majorTasks() + politicsTasks(),
    )

    // ---------------- 阶段 ----------------

    private fun phases() = listOf(
        SeedPhase(PHASE_BASE, null, MonthDaySpec(7, 31), "重理解，不求快。教材与基础课过一遍。", 0),
        SeedPhase(PHASE_INTENSIVE, MonthDaySpec(8, 1), MonthDaySpec(10, 31), "刷题为主，数学是提分主战场。", 1),
        SeedPhase(PHASE_SPRINT, MonthDaySpec(11, 1), null, "真题与模拟卷限时训练，保持作息稳定。", 2),
    )

    // ---------------- 时段（含休息空档） ----------------

    private fun timeSlots() = listOf(
        SeedTimeSlot(SLOT_WORD_AM, LocalTime.of(8, 0), LocalTime.of(8, 40), "单词新词，一天第一件", 0),
        SeedTimeSlot(SLOT_AM, LocalTime.of(8, 40), LocalTime.of(11, 30), "数学（听课/刷题）", 1),
        // 11:30–14:00 休息（午饭+午休），不排任务
        SeedTimeSlot(SLOT_PM, LocalTime.of(14, 0), LocalTime.of(17, 0), "英语 + 专业课", 2),
        // 17:00–19:00 休息（晚饭+散步），不排任务
        SeedTimeSlot(SLOT_EVE, LocalTime.of(19, 0), LocalTime.of(21, 30), "专业课 + 政治(隔天) + 复盘", 3),
    )

    // ---------------- 科目 ----------------

    private fun subjects() = listOf(
        SeedSubject(SUBJECT_MATH, paletteIndex = 0, iconKey = "function", targetHours = 900, sortOrder = 0),
        SeedSubject(SUBJECT_ENGLISH, paletteIndex = 1, iconKey = "translate", targetHours = 500, sortOrder = 1),
        SeedSubject(SUBJECT_MAJOR, paletteIndex = 3, iconKey = "memory", targetHours = 700, sortOrder = 2),
        SeedSubject(SUBJECT_POLITICS, paletteIndex = 2, iconKey = "gavel", targetHours = 300, sortOrder = 3),
    )

    // ---------------- 英语：每天 ----------------

    private fun englishTasks() = listOf(
        SeedTask(
            subjectName = SUBJECT_ENGLISH, slotName = SLOT_WORD_AM,
            title = "背单词（新词）", taskType = TaskType.MEMORIZE,
            targetType = TargetType.COUNT, targetValue = 80, isKeystone = true,
            note = "早上记新词效率最高，复习+新词合计别贪多。", sortOrder = 0,
        ),
        SeedTask(
            subjectName = SUBJECT_ENGLISH, slotName = SLOT_EVE,
            title = "背单词（复习）", taskType = TaskType.MEMORIZE,
            targetType = TargetType.COUNT, targetValue = 120, isKeystone = true,
            note = "晚上把早上和新词一起过一遍，符合记忆曲线。", sortOrder = 3,
        ),
        SeedTask(
            subjectName = SUBJECT_ENGLISH, slotName = SLOT_PM,
            title = "语法长难句（速刷）", taskType = TaskType.LECTURE,
            targetType = TargetType.COUNT, targetValue = 1, phaseName = PHASE_BASE,
            note = "语法不过关先速刷，过关就停用这条。", sortOrder = 1,
        ),
        SeedTask(
            subjectName = SUBJECT_ENGLISH, slotName = SLOT_PM,
            title = "真题阅读 2 篇", taskType = TaskType.PRACTICE,
            targetType = TargetType.COUNT, targetValue = 2, phaseName = PHASE_BASE,
            note = "只刷真题，看不懂的找老师讲解。", sortOrder = 2,
        ),
        SeedTask(
            subjectName = SUBJECT_ENGLISH, slotName = SLOT_PM,
            title = "真题阅读 2 篇", taskType = TaskType.PRACTICE,
            targetType = TargetType.COUNT, targetValue = 2, phaseName = PHASE_INTENSIVE,
            note = "继续刷阅读，视情况上整套。", sortOrder = 2,
        ),
        SeedTask(
            subjectName = SUBJECT_ENGLISH, slotName = SLOT_PM,
            title = "新题型 / 作文听课", taskType = TaskType.LECTURE,
            targetType = TargetType.COUNT, targetValue = 1, phaseName = PHASE_INTENSIVE,
            note = "新题型和作文有套路，跟课比自己摸索快。", sortOrder = 3,
        ),
        SeedTask(
            subjectName = SUBJECT_ENGLISH, slotName = SLOT_PM,
            title = "整套真题（限时）", taskType = TaskType.PRACTICE,
            targetType = TargetType.MINUTES, targetValue = 180, phaseName = PHASE_INTENSIVE,
            repeatRule = RepeatRule.WEEKLY_DAYS, days = setOf(DayOfWeek.SUNDAY),
            note = "严格限时 3 小时，模拟考场节奏。", sortOrder = 4,
        ),
        SeedTask(
            subjectName = SUBJECT_ENGLISH, slotName = SLOT_PM,
            title = "整套真题 / 模拟（限时）", taskType = TaskType.PRACTICE,
            targetType = TargetType.MINUTES, targetValue = 180, phaseName = PHASE_SPRINT,
            repeatRule = RepeatRule.WEEKLY_DAYS, days = setOf(DayOfWeek.SUNDAY),
            note = "保持手感，考前两周换看错题和作文模板。", sortOrder = 4,
        ),
    )

    // ---------------- 数学：每天上午 ----------------

    private fun mathTasks() = listOf(
        SeedTask(
            subjectName = SUBJECT_MATH, slotName = SLOT_AM,
            title = "高数听课 2 讲", taskType = TaskType.LECTURE,
            targetType = TargetType.COUNT, targetValue = 2, phaseName = PHASE_BASE,
            isKeystone = true, note = "重理解，听完当场把例题自己推一遍。", sortOrder = 0,
        ),
        SeedTask(
            subjectName = SUBJECT_MATH, slotName = SLOT_AM,
            title = "线代 / 概率听课 1 讲", taskType = TaskType.LECTURE,
            targetType = TargetType.COUNT, targetValue = 1, phaseName = PHASE_BASE,
            note = "高数占大头，线代概率交替推进。", sortOrder = 1,
        ),
        SeedTask(
            subjectName = SUBJECT_MATH, slotName = SLOT_AM,
            title = "刷题（660 / 1000 题）1 节", taskType = TaskType.PRACTICE,
            targetType = TargetType.COUNT, targetValue = 1, phaseName = PHASE_BASE,
            isKeystone = true, note = "重在理解，听一节课配一节题。", sortOrder = 2,
        ),
        SeedTask(
            subjectName = SUBJECT_MATH, slotName = SLOT_AM,
            title = "强化听课 1 讲", taskType = TaskType.LECTURE,
            targetType = TargetType.COUNT, targetValue = 1, phaseName = PHASE_INTENSIVE,
            note = "以知识点真的学会为准。", sortOrder = 0,
        ),
        SeedTask(
            subjectName = SUBJECT_MATH, slotName = SLOT_AM,
            title = "刷题 90 分钟（1000 / 880 题）", taskType = TaskType.PRACTICE,
            targetType = TargetType.MINUTES, targetValue = 90, phaseName = PHASE_INTENSIVE,
            isKeystone = true, note = "单题 10 分钟没思路就跳过，不死抠难题。", sortOrder = 1,
        ),
        SeedTask(
            subjectName = SUBJECT_MATH, slotName = SLOT_AM,
            title = "真题 / 模拟卷 1 套（限时）", taskType = TaskType.PRACTICE,
            targetType = TargetType.MINUTES, targetValue = 180, phaseName = PHASE_SPRINT,
            repeatRule = RepeatRule.EVERY_N_DAYS, intervalDays = 2, isKeystone = true,
            note = "李林 6+4 等，上午做对齐考试时间。", sortOrder = 0,
        ),
        SeedTask(
            subjectName = SUBJECT_MATH, slotName = SLOT_AM,
            title = "批改与错题整理", taskType = TaskType.REVIEW,
            targetType = TargetType.MINUTES, targetValue = 30, phaseName = PHASE_SPRINT,
            repeatRule = RepeatRule.EVERY_N_DAYS, intervalDays = 2,
            note = "做完当天就批改。", sortOrder = 1,
        ),
    )

    // ---------------- 专业课：每天 ----------------

    private fun majorTasks() = listOf(
        SeedTask(
            subjectName = SUBJECT_MAJOR, slotName = SLOT_PM,
            title = "讲义精读 60 分钟", taskType = TaskType.LECTURE,
            targetType = TargetType.MINUTES, targetValue = 60, isKeystone = true,
            note = "专业课分值高，全程不能断。", sortOrder = 5,
        ),
        SeedTask(
            subjectName = SUBJECT_MAJOR, slotName = SLOT_EVE,
            title = "习题汇编 1 节", taskType = TaskType.PRACTICE,
            targetType = TargetType.COUNT, targetValue = 1,
            note = "看完一章立刻做对应习题。", sortOrder = 0,
        ),
        SeedTask(
            subjectName = SUBJECT_MAJOR, slotName = SLOT_EVE,
            title = "章节总结", taskType = TaskType.REVIEW,
            targetType = TargetType.MINUTES, targetValue = 30, phaseName = PHASE_INTENSIVE,
            repeatRule = RepeatRule.WEEKLY_DAYS, days = setOf(DayOfWeek.SATURDAY),
            note = "自己画知识框架，比重读有用。", sortOrder = 2,
        ),
        SeedTask(
            subjectName = SUBJECT_MAJOR, slotName = SLOT_EVE,
            title = "专业课真题", taskType = TaskType.PRACTICE,
            targetType = TargetType.MINUTES, targetValue = 60, phaseName = PHASE_SPRINT,
            isKeystone = true, note = "真题至少过两轮。", sortOrder = 0,
        ),
        SeedTask(
            subjectName = SUBJECT_MAJOR, slotName = SLOT_EVE,
            title = "公式默写", taskType = TaskType.MEMORIZE,
            targetType = TargetType.MINUTES, targetValue = 20, phaseName = PHASE_SPRINT,
            note = "合上书默写，写不出才是没记住。", sortOrder = 3,
        ),
    )

    // ---------------- 政治：隔天才学 ----------------

    private fun politicsTasks() = listOf(
        SeedTask(
            subjectName = SUBJECT_POLITICS, slotName = SLOT_EVE,
            title = "听课 1 讲（马原精听）", taskType = TaskType.LECTURE,
            targetType = TargetType.COUNT, targetValue = 1, phaseName = PHASE_INTENSIVE,
            repeatRule = RepeatRule.EVERY_N_DAYS, intervalDays = 2,
            note = "隔天学，马原仔细听。", sortOrder = 4,
        ),
        SeedTask(
            subjectName = SUBJECT_POLITICS, slotName = SLOT_EVE,
            title = "刷 1000 题（听一节做一节）", taskType = TaskType.PRACTICE,
            targetType = TargetType.COUNT, targetValue = 1, phaseName = PHASE_INTENSIVE,
            repeatRule = RepeatRule.EVERY_N_DAYS, intervalDays = 2,
            note = "听完立刻做，别攒着。", sortOrder = 5,
        ),
        SeedTask(
            subjectName = SUBJECT_POLITICS, slotName = SLOT_EVE,
            title = "模拟卷选择题 1 套", taskType = TaskType.PRACTICE,
            targetType = TargetType.COUNT, targetValue = 1, phaseName = PHASE_SPRINT,
            repeatRule = RepeatRule.EVERY_N_DAYS, intervalDays = 2,
            note = "肖八/徐涛/腿姐选择题都刷。", sortOrder = 4,
        ),
        SeedTask(
            subjectName = SUBJECT_POLITICS, slotName = SLOT_EVE,
            title = "背大题 1 道", taskType = TaskType.MEMORIZE,
            targetType = TargetType.COUNT, targetValue = 1, phaseName = PHASE_SPRINT,
            repeatRule = RepeatRule.EVERY_N_DAYS, intervalDays = 2,
            note = "肖四出来后背大题，时间紧背大纲。", sortOrder = 5,
        ),
    )
}
