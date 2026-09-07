package com.example.lixing.domain

import com.example.lixing.domain.seed.KaoyanSeed
import com.example.lixing.domain.seed.MonthDaySpec
import com.example.lixing.domain.seed.SeedResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

class SeedResolverTest {

    @Test
    fun `default target date is third Saturday of December`() {
        val target = SeedResolver.defaultTargetDate(LocalDate.of(2026, 8, 3))
        assertEquals(DayOfWeek.SATURDAY, target.dayOfWeek)
        assertEquals(12, target.monthValue)
        // 第三个周六落在 15~21 日之间
        assertTrue(target.dayOfMonth in 15..21)
    }

    @Test
    fun `third Saturday of December is correct for 2026`() {
        val sat = SeedResolver.thirdSaturdayOfDecember(2026)
        assertEquals(DayOfWeek.SATURDAY, sat.dayOfWeek)
        assertEquals(12, sat.monthValue)
    }

    @Test
    fun `target date rolls to next year if passed`() {
        // 已经过了今年 12 月的考试日 → 取明年
        val target = SeedResolver.defaultTargetDate(LocalDate.of(2026, 12, 30))
        assertTrue(target.year >= 2027)
    }

    @Test
    fun `resolve month day handles month end`() {
        // 2 月 30 日 → 夹到 2 月末
        val spec = MonthDaySpec(2, 30)
        val date = SeedResolver.resolveMonthDay(spec, LocalDate.of(2026, 12, 19))
        assertEquals(2, date.monthValue)
        assertTrue(date.dayOfMonth <= 28)
    }

    @Test
    fun `kaoyan phases resolve within plan window`() {
        val start = LocalDate.of(2026, 3, 1)
        val target = LocalDate.of(2026, 12, 19)
        val resolved = SeedResolver.resolvePhaseRanges(KaoyanSeed.plan.phases, start, target)

        assertEquals(KaoyanSeed.plan.phases.size, resolved.size)

        // 基础阶段应裁到 7/31
        val base = resolved[0]
        assertNotNull(base)
        assertEquals(start, base!!.startDate) // 起点被裁到计划开始日
        assertTrue(base.endDate <= LocalDate.of(2026, 7, 31))

        // 冲刺阶段终点 = 目标日
        val sprint = resolved[2]
        assertNotNull(sprint)
        assertEquals(target, sprint!!.endDate)

        // 各阶段首尾相连或有序
        resolved.filterNotNull().zipWithNext().forEach { (a, b) ->
            assertTrue(a.endDate <= b.startDate || a.endDate < b.endDate)
        }
    }

    @Test
    fun `late start drops earlier phase`() {
        // 8 月才开始备考 → 基础阶段（到 7/31）区间为空，应被裁掉
        val start = LocalDate.of(2026, 8, 1)
        val target = LocalDate.of(2026, 12, 19)
        val resolved = SeedResolver.resolvePhaseRanges(KaoyanSeed.plan.phases, start, target)

        val base = resolved[0]
        assertNull(base) // 基础阶段被裁没

        // 强化与冲刺仍存在
        assertNotNull(resolved[1])
        assertNotNull(resolved[2])
    }

    @Test
    fun `kaoyan seed references valid slot and subject names`() {
        val slotNames = KaoyanSeed.plan.timeSlots.map { it.name }.toSet()
        val subjectNames = KaoyanSeed.plan.subjects.map { it.name }.toSet()
        val phaseNames = KaoyanSeed.plan.phases.map { it.name }.toSet()

        KaoyanSeed.plan.tasks.forEach { t ->
            assertTrue("任务 ${t.title} 的时段 ${t.slotName} 未定义", t.slotName in slotNames)
            assertTrue("任务 ${t.title} 的科目 ${t.subjectName} 未定义", t.subjectName in subjectNames)
            t.phaseName?.let {
                assertTrue("任务 ${t.title} 的阶段 $it 未定义", it in phaseNames)
            }
        }
    }

    @Test
    fun `kaoyan seed has expected structure`() {
        assertTrue(KaoyanSeed.plan.phases.size >= 3)
        assertTrue(KaoyanSeed.plan.subjects.size >= 4)
        assertTrue(KaoyanSeed.plan.timeSlots.isNotEmpty())
        assertTrue(KaoyanSeed.plan.tasks.isNotEmpty())
        // 至少一个关键任务（背单词）
        assertTrue(KaoyanSeed.plan.tasks.any { it.isKeystone })
    }

    @Test
    fun `v2 schedule within 8 to 2130 with rest gaps`() {
        val slots = KaoyanSeed.plan.timeSlots.sortedBy { it.start }
        // 8:00 开始、21:30 结束
        assertEquals(java.time.LocalTime.of(8, 0), slots.first().start)
        assertEquals(java.time.LocalTime.of(21, 30), slots.last().end)
        // 至少两段 >= 60 分钟的大休息（午饭/晚饭），允许小块连续（如晨间单词→上午）
        val bigRests = slots.zipWithNext().count { (a, b) ->
            java.time.Duration.between(a.end, b.start).toMinutes() >= 60
        }
        assertTrue("应至少有两段 ≥1 小时的休息，实际 $bigRests 段", bigRests >= 2)
    }

    @Test
    fun `politics is alternate-day not daily`() {
        val politics = KaoyanSeed.plan.tasks.filter { it.subjectName == KaoyanSeed.SUBJECT_POLITICS }
        assertTrue(politics.isNotEmpty())
        assertTrue("政治应隔天学", politics.all { it.repeatRule == com.example.lixing.domain.model.RepeatRule.EVERY_N_DAYS && it.intervalDays >= 2 })
    }
}
