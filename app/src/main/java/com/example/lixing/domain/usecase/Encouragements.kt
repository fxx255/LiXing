package com.example.lixing.domain.usecase

/**
 * 打卡后的随机激励文案。
 *
 * 写作原则：不喊口号、不用感叹号堆情绪。就事论事地肯定动作本身，
 * 迟到和补卡也给正向反馈——晚做了总比没做好，别把人推到「反正晚了不如放弃」。
 */
object Encouragements {

    private val onTime = listOf(
        "按时完成，这一格填上了。",
        "又推进一步。",
        "稳住节奏，就是这样。",
        "这一项拿下了。",
        "干净利落。",
        "今天的这块砖砌好了。",
        "计划正在被执行，而不只是被制定。",
        "不声不响地又前进一点。",
    )

    private val late = listOf(
        "晚了一点，但做完了，这才是重点。",
        "补上了。下次早一点会更轻松。",
        "过了时段也算完成，别为这个纠结。",
        "慢一步也是一步。",
    )

    private val makeup = listOf(
        "把昨天的坑填了，继续往前。",
        "补回来了。别让补卡变成习惯就好。",
        "昨天欠的今天还上了。",
    )

    private val fullDay = listOf(
        "全天满勤。这种日子多来几次，结果自然会变。",
        "今天一项没落。",
        "满勤达成，今天可以安心休息了。",
        "全部完成——这就是理想中的一天。",
    )

    private val streakHigh = listOf(
        "连续这么多天了，别在今天松手。",
        "这串数字比任何计划表都有说服力。",
        "坚持到这个份上，已经是少数人了。",
    )

    /**
     * 挑一条文案。优先级：满勤 > 长连续 > 补卡 > 迟到 > 按时。
     * 用随机而不是轮播，重复感低一些。
     */
    fun pick(
        isLate: Boolean,
        isMakeup: Boolean,
        isFullDay: Boolean,
        streak: Int,
    ): String = when {
        isFullDay -> fullDay.random()
        streak >= 14 -> streakHigh.random()
        isMakeup -> makeup.random()
        isLate -> late.random()
        else -> onTime.random()
    }

    /** 断签时的文案。刻意不指责，把注意力放在历史最长上。 */
    fun onStreakBroken(longestStreak: Int): String = when {
        longestStreak >= 30 -> "连续中断了。你最长坚持过 $longestStreak 天，那个能力还在。"
        longestStreak > 0 -> "断在这里了。历史最长 $longestStreak 天，从今天重新数。"
        else -> "从今天开始数第一天。"
    }

    /** 用掉补救卡时的文案。 */
    fun onRescueUsed(remaining: Int): String =
        "今天没达标，用掉一张补救卡保住了连续记录。本月还剩 $remaining 张。"
}
