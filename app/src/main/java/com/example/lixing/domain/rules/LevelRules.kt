package com.example.lixing.domain.rules

import kotlin.math.pow

/**
 * 等级与称号。
 *
 * 曲线：升到 level 所需累计积分 = 100 * (level-1)^1.5。
 * 1 级 0 分、2 级 100 分、3 级约 283 分、10 级约 2700 分、20 级约 8300 分。
 * 前期升得快（正反馈密），后期拉长（不至于几个月就满级）。
 */
object LevelRules {

    const val MAX_LEVEL = 60

    /** 升到 [level] 所需的累计总积分。 */
    fun requiredPointsFor(level: Int): Int {
        if (level <= 1) return 0
        return (100.0 * (level - 1).toDouble().pow(1.5)).toInt()
    }

    /** 由累计积分反算等级。 */
    fun levelOf(totalPoints: Int): Int {
        if (totalPoints <= 0) return 1
        var level = 1
        while (level < MAX_LEVEL && totalPoints >= requiredPointsFor(level + 1)) {
            level++
        }
        return level
    }

    /** 当前等级内的进度 0f~1f。满级恒为 1f。 */
    fun progressInLevel(totalPoints: Int): Float {
        val level = levelOf(totalPoints)
        if (level >= MAX_LEVEL) return 1f
        val floor = requiredPointsFor(level)
        val ceil = requiredPointsFor(level + 1)
        if (ceil <= floor) return 1f
        return ((totalPoints - floor).toFloat() / (ceil - floor)).coerceIn(0f, 1f)
    }

    /** 距下一级还差多少分。满级返回 0。 */
    fun pointsToNextLevel(totalPoints: Int): Int {
        val level = levelOf(totalPoints)
        if (level >= MAX_LEVEL) return 0
        return (requiredPointsFor(level + 1) - totalPoints).coerceAtLeast(0)
    }

    /**
     * 称号。每 5 级换一个，用备考语境里的说法，避免游戏化过头显得轻浮。
     */
    fun titleOf(level: Int): String = when {
        level >= 55 -> "登顶者"
        level >= 50 -> "破晓人"
        level >= 45 -> "长跑健将"
        level >= 40 -> "厚积者"
        level >= 35 -> "稳如磐石"
        level >= 30 -> "笃行不倦"
        level >= 25 -> "百炼成钢"
        level >= 20 -> "渐入佳境"
        level >= 15 -> "步履不停"
        level >= 10 -> "小有所成"
        level >= 5 -> "初见门径"
        else -> "初心者"
    }

    /** 升级时是否解锁了新称号（用于弹窗文案区分「升级」和「升级 + 新称号」）。 */
    fun unlockedNewTitle(oldLevel: Int, newLevel: Int): Boolean =
        titleOf(oldLevel) != titleOf(newLevel)
}
