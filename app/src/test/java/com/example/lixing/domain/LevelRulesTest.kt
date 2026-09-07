package com.example.lixing.domain

import com.example.lixing.domain.rules.LevelRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LevelRulesTest {

    @Test
    fun `zero points is level 1`() {
        assertEquals(1, LevelRules.levelOf(0))
        assertEquals(1, LevelRules.levelOf(-5))
    }

    @Test
    fun `level thresholds are monotonically increasing`() {
        var prev = 0
        for (level in 2..20) {
            val req = LevelRules.requiredPointsFor(level)
            assertTrue("level $level should need more than previous", req > prev)
            prev = req
        }
    }

    @Test
    fun `level of matches required points`() {
        assertEquals(2, LevelRules.levelOf(LevelRules.requiredPointsFor(2)))
        assertEquals(2, LevelRules.levelOf(LevelRules.requiredPointsFor(3) - 1))
        assertEquals(3, LevelRules.levelOf(LevelRules.requiredPointsFor(3)))
    }

    @Test
    fun `progress in level is between 0 and 1`() {
        for (points in listOf(0, 50, 100, 300, 2700, 10000)) {
            val progress = LevelRules.progressInLevel(points)
            assertTrue(progress >= 0f && progress <= 1f)
        }
    }

    @Test
    fun `points to next level is non-negative`() {
        for (points in listOf(0, 100, 500, 8300)) {
            assertTrue(LevelRules.pointsToNextLevel(points) >= 0)
        }
    }

    @Test
    fun `max level returns full progress`() {
        val huge = LevelRules.requiredPointsFor(LevelRules.MAX_LEVEL) + 100000
        assertEquals(LevelRules.MAX_LEVEL, LevelRules.levelOf(huge))
        assertEquals(1f, LevelRules.progressInLevel(huge))
        assertEquals(0, LevelRules.pointsToNextLevel(huge))
    }

    @Test
    fun `title changes at milestones`() {
        val low = LevelRules.titleOf(1)
        val mid = LevelRules.titleOf(10)
        val high = LevelRules.titleOf(40)
        assertTrue(low != mid)
        assertTrue(mid != high)
    }

    @Test
    fun `unlocked new title detects boundary crossing`() {
        // 找一个称号变化的等级边界
        var found = false
        for (level in 2..30) {
            if (LevelRules.titleOf(level) != LevelRules.titleOf(level - 1)) {
                assertTrue(LevelRules.unlockedNewTitle(level - 1, level))
                found = true
                break
            }
        }
        assertTrue(found)
        // 同级不变
        org.junit.Assert.assertFalse(LevelRules.unlockedNewTitle(3, 3))
    }
}
