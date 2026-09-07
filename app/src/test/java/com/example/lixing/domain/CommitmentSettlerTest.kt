package com.example.lixing.domain

import com.example.lixing.domain.model.CommitmentStatus
import com.example.lixing.domain.settle.CommitmentSettler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class CommitmentSettlerTest {

    private val settledOn = LocalDate.of(2026, 7, 31)

    // ---------------- 结算判定 ----------------

    @Test
    fun `rate above target succeeds and pays reward`() {
        val outcome = CommitmentSettler.settle(
            commitment = TestFixtures.commitment(targetPercent = 85, reward = 100),
            averageRate = 0.91f,
            settledDate = settledOn,
        )

        assertTrue(outcome.succeeded)
        assertEquals(CommitmentStatus.SUCCEEDED, outcome.settled.status)
        assertEquals(91, outcome.settled.actualRatePercent)
        assertEquals(100, outcome.rewardPoints)
        assertEquals(settledOn, outcome.settled.settledDate)
    }

    @Test
    fun `rate exactly at target succeeds`() {
        // 目标是「不低于」，等于必须算达成
        val outcome = CommitmentSettler.settle(
            commitment = TestFixtures.commitment(targetPercent = 85),
            averageRate = 0.85f,
            settledDate = settledOn,
        )

        assertTrue(outcome.succeeded)
        assertEquals(85, outcome.settled.actualRatePercent)
    }

    @Test
    fun `rate below target fails without penalty`() {
        val outcome = CommitmentSettler.settle(
            commitment = TestFixtures.commitment(targetPercent = 85, reward = 100),
            averageRate = 0.72f,
            settledDate = settledOn,
        )

        assertFalse(outcome.succeeded)
        assertEquals(CommitmentStatus.FAILED, outcome.settled.status)
        assertEquals(72, outcome.settled.actualRatePercent)
        // 失败不扣分，只是拿不到奖励
        assertEquals(0, outcome.rewardPoints)
        assertTrue(outcome.summary.contains("13"))
    }

    @Test
    fun `percent floors so displayed number matches verdict`() {
        // 84.9% 显示成 84%，就不能被判成达标 85%——用户看到的数和结论必须自洽
        val outcome = CommitmentSettler.settle(
            commitment = TestFixtures.commitment(targetPercent = 85),
            averageRate = 0.8499f,
            settledDate = settledOn,
        )

        assertEquals(84, outcome.settled.actualRatePercent)
        assertFalse(outcome.succeeded)
    }

    @Test
    fun `no data voids instead of failing`() {
        // 整段请假或没排任务：判失败太冤
        val outcome = CommitmentSettler.settle(
            commitment = TestFixtures.commitment(),
            averageRate = null,
            settledDate = settledOn,
        )

        assertTrue(outcome.voided)
        assertFalse(outcome.succeeded)
        assertEquals(CommitmentStatus.ABANDONED, outcome.settled.status)
        assertNull(outcome.settled.actualRatePercent)
        assertEquals(0, outcome.rewardPoints)
    }

    @Test
    fun `full rate is capped at 100 percent`() {
        val outcome = CommitmentSettler.settle(
            commitment = TestFixtures.commitment(targetPercent = 100),
            averageRate = 1f,
            settledDate = settledOn,
        )

        assertEquals(100, outcome.settled.actualRatePercent)
        assertTrue(outcome.succeeded)
    }

    @Test
    fun `zero reward commitment still succeeds`() {
        // 有人立约只为约束自己，不图分
        val outcome = CommitmentSettler.settle(
            commitment = TestFixtures.commitment(targetPercent = 80, reward = 0),
            averageRate = 0.9f,
            settledDate = settledOn,
        )

        assertTrue(outcome.succeeded)
        assertEquals(0, outcome.rewardPoints)
    }

    // ---------------- 进行中进度 ----------------

    @Test
    fun `progress counts elapsed days inclusive`() {
        val commitment = TestFixtures.commitment(
            start = LocalDate.of(2026, 7, 1),
            end = LocalDate.of(2026, 7, 30),
        )
        val p = CommitmentSettler.progressOf(
            commitment = commitment,
            averageRate = 0.8f,
            today = LocalDate.of(2026, 7, 10),
        )

        assertEquals(30, p.totalDays)
        assertEquals(10, p.elapsedDays)
        assertEquals(20, p.daysLeft)
        assertEquals(80, p.currentRatePercent)
        assertEquals(5, p.gapPercent)
        assertFalse(p.onTrack)
    }

    @Test
    fun `progress reports on track when target met`() {
        val p = CommitmentSettler.progressOf(
            commitment = TestFixtures.commitment(targetPercent = 85),
            averageRate = 0.88f,
            today = LocalDate.of(2026, 7, 10),
        )

        assertEquals(0, p.gapPercent)
        assertTrue(p.onTrack)
    }

    @Test
    fun `progress before start date shows nothing elapsed`() {
        val p = CommitmentSettler.progressOf(
            commitment = TestFixtures.commitment(start = LocalDate.of(2026, 7, 1)),
            averageRate = null,
            today = LocalDate.of(2026, 6, 20),
        )

        assertEquals(0, p.elapsedDays)
        assertFalse(p.hasData)
        assertFalse(p.onTrack)
    }

    @Test
    fun `progress on last day has no days left`() {
        val p = CommitmentSettler.progressOf(
            commitment = TestFixtures.commitment(
                start = LocalDate.of(2026, 7, 1),
                end = LocalDate.of(2026, 7, 30),
            ),
            averageRate = 0.9f,
            today = LocalDate.of(2026, 7, 30),
        )

        assertEquals(30, p.elapsedDays)
        assertEquals(0, p.daysLeft)
        assertTrue(p.isLastDay)
    }

    @Test
    fun `progress past end date does not exceed total days`() {
        // 到期但还没结算（用户几天没开 App）：进度不该超出区间
        val p = CommitmentSettler.progressOf(
            commitment = TestFixtures.commitment(
                start = LocalDate.of(2026, 7, 1),
                end = LocalDate.of(2026, 7, 30),
            ),
            averageRate = 0.9f,
            today = LocalDate.of(2026, 8, 5),
        )

        assertEquals(30, p.elapsedDays)
        assertEquals(0, p.daysLeft)
    }

    @Test
    fun `single day commitment counts as one day`() {
        val day = LocalDate.of(2026, 7, 1)
        val p = CommitmentSettler.progressOf(
            commitment = TestFixtures.commitment(start = day, end = day),
            averageRate = null,
            today = day,
        )

        assertEquals(1, p.totalDays)
        assertEquals(1, p.elapsedDays)
        assertEquals(0, p.daysLeft)
    }

    @Test
    fun `no data progress reports zero rate`() {
        val p = CommitmentSettler.progressOf(
            commitment = TestFixtures.commitment(targetPercent = 85),
            averageRate = null,
            today = LocalDate.of(2026, 7, 10),
        )

        assertEquals(0, p.currentRatePercent)
        assertEquals(85, p.gapPercent)
        assertFalse(p.hasData)
    }
}
