package com.example.lixing.domain

import com.example.lixing.domain.model.TargetType
import com.example.lixing.domain.rules.LevelRules
import com.example.lixing.domain.rules.PointRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PointRulesTest {

    // ---------- 基础打卡 ----------

    @Test
    fun `on time boolean task gives base points`() {
        val points = PointRules.checkInPoints(
            actualValue = 1,
            targetValue = 1,
            targetType = TargetType.BOOLEAN,
            isKeystone = false,
            isLate = false,
            isMakeup = false,
        )
        assertEquals(PointRules.BASE_ON_TIME, points)
    }

    @Test
    fun `keystone doubles base points`() {
        val normal = PointRules.checkInPoints(1, 1, TargetType.BOOLEAN, false, false, false)
        val keystone = PointRules.checkInPoints(1, 1, TargetType.BOOLEAN, true, false, false)
        assertEquals(normal * 2, keystone)
    }

    @Test
    fun `late check-in halves points`() {
        val onTime = PointRules.checkInPoints(1, 1, TargetType.BOOLEAN, false, false, false)
        val late = PointRules.checkInPoints(1, 1, TargetType.BOOLEAN, false, isLate = true, false)
        assertEquals(onTime / 2, late)
    }

    @Test
    fun `makeup check-in gives thirty percent`() {
        val onTime = PointRules.checkInPoints(1, 1, TargetType.BOOLEAN, false, false, false)
        val makeup = PointRules.checkInPoints(1, 1, TargetType.BOOLEAN, false, false, isMakeup = true)
        assertEquals((onTime * PointRules.MAKEUP_MULTIPLIER).toInt(), makeup)
    }

    @Test
    fun `partial completion scales base points`() {
        // 只完成一半，基础分打折
        val half = PointRules.checkInPoints(50, 100, TargetType.COUNT, false, false, false)
        assertTrue(half < PointRules.BASE_ON_TIME)
        assertTrue(half >= 1)
    }

    @Test
    fun `zero actual value gives zero points`() {
        val points = PointRules.checkInPoints(0, 100, TargetType.COUNT, false, false, false)
        assertEquals(0, points)
    }

    // ---------- 超额奖励 ----------

    @Test
    fun `over target gives bonus`() {
        val bonus = PointRules.overTargetBonus(120, 100, TargetType.COUNT, false)
        assertEquals(PointRules.OVER_TARGET_BONUS, bonus)
    }

    @Test
    fun `over target keystone doubles bonus`() {
        val bonus = PointRules.overTargetBonus(120, 100, TargetType.COUNT, true)
        assertEquals(PointRules.OVER_TARGET_BONUS * PointRules.KEYSTONE_MULTIPLIER, bonus)
    }

    @Test
    fun `no bonus when not exceeding target`() {
        assertEquals(0, PointRules.overTargetBonus(100, 100, TargetType.COUNT, false))
        assertEquals(0, PointRules.overTargetBonus(50, 100, TargetType.COUNT, false))
    }

    @Test
    fun `boolean task never gives over bonus`() {
        assertEquals(0, PointRules.overTargetBonus(5, 1, TargetType.BOOLEAN, false))
    }

    // ---------- 漏卡与专注 ----------

    @Test
    fun `missed penalty is negative and doubled for keystone`() {
        val normal = PointRules.missedPenalty(true, false)
        val keystone = PointRules.missedPenalty(true, true)
        assertTrue(normal < 0)
        assertEquals(-PointRules.MISSED_PENALTY, normal)
        assertEquals(normal * 2, keystone)
    }

    @Test
    fun `missed penalty disabled gives zero`() {
        assertEquals(0, PointRules.missedPenalty(false, true))
    }

    @Test
    fun `focus points convert minutes to points`() {
        assertEquals(0, PointRules.focusPoints(24))
        assertEquals(1, PointRules.focusPoints(25))
        assertEquals(4, PointRules.focusPoints(100))
    }

    // ---------- 连续记录里程碑 ----------

    @Test
    fun `streak bonus only on exact milestone`() {
        assertEquals(50, PointRules.streakBonus(7))
        assertEquals(200, PointRules.streakBonus(30))
        assertEquals(0, PointRules.streakBonus(8))
        assertEquals(0, PointRules.streakBonus(0))
    }

    // ---------- 完成比例 ----------

    @Test
    fun `completion ratio for quantified task`() {
        assertEquals(0.5f, PointRules.completionRatio(50, 100, TargetType.COUNT))
        assertEquals(1f, PointRules.completionRatio(100, 100, TargetType.COUNT))
        assertEquals(2f, PointRules.completionRatio(200, 100, TargetType.COUNT))
    }

    @Test
    fun `completion ratio for boolean task`() {
        assertEquals(1f, PointRules.completionRatio(1, 1, TargetType.BOOLEAN))
        assertEquals(0f, PointRules.completionRatio(0, 1, TargetType.BOOLEAN))
    }

    @Test
    fun `completion ratio handles zero target`() {
        assertEquals(1f, PointRules.completionRatio(5, 0, TargetType.COUNT))
        assertEquals(0f, PointRules.completionRatio(0, 0, TargetType.COUNT))
    }

    // ---------- 幂等键 ----------

    @Test
    fun `dedupe keys are distinct per task and reason`() {
        val a = PointRules.keyCheckIn("1")
        val b = PointRules.keyCheckIn("2")
        val c = PointRules.keyMissed("1")
        assertTrue(a != b)
        assertTrue(a != c)
        assertEquals(a, PointRules.keyCheckIn("1"))
    }
}
