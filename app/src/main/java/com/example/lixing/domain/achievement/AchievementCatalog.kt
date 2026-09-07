package com.example.lixing.domain.achievement

import com.example.lixing.data.local.entity.AchievementEntity
import com.example.lixing.domain.model.AchievementCondition

/**
 * 成就目录。首次启动时按 code 预置入库（已存在的不覆盖，保住已解锁的时间戳）。
 *
 * 分档：tier 1 铜 / 2 银 / 3 金。
 * 设计原则——前几个必须很容易拿到（第一天就能解锁 2~3 个），
 * 让人立刻看到反馈；金档留给真正需要长期坚持的。
 */
object AchievementCatalog {

    fun all(): List<AchievementEntity> = buildList {
        var order = 0
        fun add(
            code: String,
            title: String,
            desc: String,
            condition: AchievementCondition,
            threshold: Int = 0,
            extraKey: String? = null,
            reward: Int = 20,
            tier: Int = 1,
            icon: String = "trophy",
        ) {
            add(
                AchievementEntity(
                    code = code,
                    title = title,
                    description = desc,
                    iconKey = icon,
                    condition = condition,
                    threshold = threshold,
                    extraKey = extraKey,
                    rewardPoints = reward,
                    tier = tier,
                    sortOrder = order++,
                ),
            )
        }

        // ---- 入门：第一天就该拿到 ----
        add(
            "FIRST_CHECK_IN", "第一次打卡", "完成人生第一次打卡",
            AchievementCondition.FIRST_CHECK_IN, reward = 10, icon = "flag",
        )
        add(
            "CHECK_IN_10", "小试牛刀", "累计打卡 10 次",
            AchievementCondition.TOTAL_CHECK_INS, threshold = 10, reward = 20, icon = "check",
        )
        add(
            "CHECK_IN_100", "百次锤炼", "累计打卡 100 次",
            AchievementCondition.TOTAL_CHECK_INS, threshold = 100, reward = 80, tier = 2, icon = "check",
        )
        add(
            "CHECK_IN_500", "五百次不动摇", "累计打卡 500 次",
            AchievementCondition.TOTAL_CHECK_INS, threshold = 500, reward = 300, tier = 3, icon = "check",
        )

        // ---- 连续记录：这个 App 的核心指标 ----
        add(
            "STREAK_3", "站稳脚跟", "连续达成 3 天",
            AchievementCondition.STREAK_DAYS, threshold = 3, reward = 20, icon = "flame",
        )
        add(
            "STREAK_7", "一周不断", "连续达成 7 天",
            AchievementCondition.STREAK_DAYS, threshold = 7, reward = 50, icon = "flame",
        )
        add(
            "STREAK_30", "月度铁人", "连续达成 30 天",
            AchievementCondition.STREAK_DAYS, threshold = 30, reward = 200, tier = 2, icon = "flame",
        )
        add(
            "STREAK_60", "两月长跑", "连续达成 60 天",
            AchievementCondition.STREAK_DAYS, threshold = 60, reward = 350, tier = 2, icon = "flame",
        )
        add(
            "STREAK_100", "百日不辍", "连续达成 100 天",
            AchievementCondition.STREAK_DAYS, threshold = 100, reward = 600, tier = 3, icon = "flame",
        )
        add(
            "STREAK_200", "两百日之约", "连续达成 200 天",
            AchievementCondition.STREAK_DAYS, threshold = 200, reward = 1200, tier = 3, icon = "flame",
        )

        // ---- 满勤 ----
        add(
            "FULL_DAY_1", "完美一天", "单日完成全部任务",
            AchievementCondition.FULL_DAY_COUNT, threshold = 1, reward = 30, icon = "star",
        )
        add(
            "FULL_DAY_10", "十全十美", "累计 10 个满勤日",
            AchievementCondition.FULL_DAY_COUNT, threshold = 10, reward = 100, tier = 2, icon = "star",
        )
        add(
            "FULL_DAY_50", "五十次无懈怠", "累计 50 个满勤日",
            AchievementCondition.FULL_DAY_COUNT, threshold = 50, reward = 400, tier = 3, icon = "star",
        )

        // ---- 早读：最难坚持的时段 ----
        add(
            "EARLY_7", "晨光七日", "早读时段按时打卡 7 次",
            AchievementCondition.SLOT_COUNT, threshold = 7, extraKey = "早读",
            reward = 40, icon = "sunrise",
        )
        add(
            "EARLY_30", "早起的人", "早读时段按时打卡 30 次",
            AchievementCondition.SLOT_COUNT, threshold = 30, extraKey = "早读",
            reward = 150, tier = 2, icon = "sunrise",
        )
        add(
            "EARLY_BIRD_STREAK_14", "连续两周早起", "连续 14 天完成早读",
            AchievementCondition.EARLY_BIRD_STREAK, threshold = 14, extraKey = "早读",
            reward = 200, tier = 3, icon = "sunrise",
        )

        // ---- 专注时长 ----
        add(
            "FOCUS_DAY_120", "两小时深潜", "单日专注满 2 小时",
            AchievementCondition.SINGLE_DAY_FOCUS_MINUTES, threshold = 120, reward = 30, icon = "timer",
        )
        add(
            "FOCUS_DAY_240", "四小时不抬头", "单日专注满 4 小时",
            AchievementCondition.SINGLE_DAY_FOCUS_MINUTES, threshold = 240,
            reward = 80, tier = 2, icon = "timer",
        )
        add(
            "FOCUS_DAY_480", "八小时苦功", "单日专注满 8 小时",
            AchievementCondition.SINGLE_DAY_FOCUS_MINUTES, threshold = 480,
            reward = 200, tier = 3, icon = "timer",
        )

        // ---- 单科累计（按科目名匹配，用户改名后旧成就仍保留已解锁状态） ----
        listOf(
            Triple("数学", 20, 1), Triple("数学", 50, 2), Triple("数学", 100, 3),
            Triple("英语", 20, 1), Triple("英语", 50, 2), Triple("英语", 100, 3),
            Triple("政治", 20, 1), Triple("政治", 50, 2),
            Triple("专业课", 20, 1), Triple("专业课", 50, 2), Triple("专业课", 100, 3),
        ).forEach { (subject, hours, tier) ->
            add(
                "SUBJECT_${subject}_$hours", "$subject $hours 小时", "$subject 累计投入 $hours 小时",
                AchievementCondition.SUBJECT_TOTAL_MINUTES, threshold = hours * 60,
                extraKey = subject, reward = hours * 3, tier = tier, icon = "book",
            )
        }

        // ---- 月完成率 ----
        add(
            "MONTH_RATE_80", "八成之上", "完整自然月平均完成率达到 80%",
            AchievementCondition.MONTH_COMPLETION_RATE, threshold = 80, reward = 120, tier = 2, icon = "chart",
        )
        add(
            "MONTH_RATE_90", "九成之上", "完整自然月平均完成率达到 90%",
            AchievementCondition.MONTH_COMPLETION_RATE, threshold = 90, reward = 250, tier = 3, icon = "chart",
        )

        // ---- 阶段与里程碑 ----
        add(
            "PHASE_FINISHED", "阶段收官", "完整走完一个备考阶段",
            AchievementCondition.PHASE_FINISHED, threshold = 1, reward = 150, tier = 2, icon = "milestone",
        )
        add(
            "DDAY_100", "百日冲刺", "进入考前 100 天",
            AchievementCondition.DDAY_MILESTONE, threshold = 100, reward = 100, tier = 2, icon = "rocket",
        )
        add(
            "DDAY_30", "最后一个月", "进入考前 30 天",
            AchievementCondition.DDAY_MILESTONE, threshold = 30, reward = 200, tier = 3, icon = "rocket",
        )

        // ---- 心态类：断签后回来，比连续更难 ----
        add(
            "COMEBACK", "重新出发", "断签后重新连续达成 3 天",
            AchievementCondition.COMEBACK, threshold = 3, reward = 60, tier = 2, icon = "restart",
        )
        add(
            "NIGHT_OWL_FIX", "扭转作息", "连续 7 天在睡前时段按时完成复盘",
            AchievementCondition.NIGHT_OWL_FIX, threshold = 7, extraKey = "睡前",
            reward = 80, tier = 2, icon = "moon",
        )
    }
}
