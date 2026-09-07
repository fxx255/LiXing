package com.example.lixing.domain.english

import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

/**
 * 背诵自评档位。三档对应墨墨式的「忘记 / 模糊 / 认识」。
 */
enum class ReviewGrade {
    /** 想不起来，需要马上再来一次。 */
    AGAIN,

    /** 想起来了但很吃力，缩短间隔、多见面。 */
    HARD,

    /** 顺利想起来，按 SM-2 正常拉长间隔。 */
    GOOD,
}

/** 一条卡片的复习状态（SM-2 的核心四个量）。 */
data class Sm2State(
    /** 难度系数（ease factor），默认 2.5，下限 [Sm2Scheduler.MIN_EASE]。 */
    val ease: Double = Sm2Scheduler.DEFAULT_EASE,
    /** 当前间隔（天）。 */
    val intervalDays: Int = 0,
    /** 连续答对次数。0 = 新卡。 */
    val reps: Int = 0,
    /** 累计「忘记」次数。 */
    val lapses: Int = 0,
)

/** 一次评分的结果：新状态 + 下次到期时间。 */
data class Sm2Result(
    val state: Sm2State,
    val dueAt: Instant,
)

/**
 * SM-2 间隔重复调度（纯函数，不碰数据库、不依赖 Android，方便 JVM 单测）。
 *
 * 规则（沿用经典 SM-2 并做了一点移动端简化）：
 * - **认识 GOOD**：间隔按 1 → 6 → 上次间隔 × 难度系数 递增，难度系数 +0.10；
 * - **模糊 HARD**：间隔只小幅增长（×1.2，至少 1 天），难度系数 −0.15，仍算学过一次；
 * - **忘记 AGAIN**：连续答对次数归零、忘记次数 +1、难度系数 −0.20，**10 分钟后再来**（当天内就能补一次，比直接推到明天更符合背诵手感）。
 *
 * 难度系数下限 [MIN_EASE]，避免连着几次忘记之后间隔被压到 0 天。
 */
object Sm2Scheduler {
    const val DEFAULT_EASE = 2.5
    const val MIN_EASE = 1.3
    private const val MAX_EASE = 3.0

    /** 忘记之后隔多久再来（分钟）。 */
    private const val AGAIN_DELAY_MINUTES = 10L

    /** 前两次答对的固定间隔（天）：SM-2 的 1 天 / 6 天。 */
    private const val FIRST_INTERVAL_DAYS = 1
    private const val SECOND_INTERVAL_DAYS = 6

    fun next(state: Sm2State, grade: ReviewGrade, now: Instant): Sm2Result = when (grade) {
        ReviewGrade.AGAIN -> {
            val ease = (state.ease - 0.20).coerceAtLeast(MIN_EASE)
            Sm2Result(
                state = Sm2State(
                    ease = ease,
                    intervalDays = 0,
                    reps = 0,
                    lapses = state.lapses + 1,
                ),
                dueAt = now.plus(AGAIN_DELAY_MINUTES, ChronoUnit.MINUTES),
            )
        }

        ReviewGrade.HARD -> {
            val ease = (state.ease - 0.15).coerceAtLeast(MIN_EASE)
            val interval = if (state.intervalDays <= 0) {
                FIRST_INTERVAL_DAYS
            } else {
                (state.intervalDays * 1.2).roundToInt().coerceAtLeast(FIRST_INTERVAL_DAYS)
            }
            Sm2Result(
                state = Sm2State(
                    ease = ease,
                    intervalDays = interval,
                    reps = state.reps + 1,
                    lapses = state.lapses,
                ),
                dueAt = now.plus(interval.toLong(), ChronoUnit.DAYS),
            )
        }

        ReviewGrade.GOOD -> {
            val ease = (state.ease + 0.10).coerceAtMost(MAX_EASE)
            val reps = state.reps + 1
            val interval = when (reps) {
                1 -> FIRST_INTERVAL_DAYS
                2 -> SECOND_INTERVAL_DAYS
                else -> (state.intervalDays * ease).roundToInt()
                    .coerceAtLeast(state.intervalDays + 1)
            }
            Sm2Result(
                state = Sm2State(
                    ease = ease,
                    intervalDays = interval,
                    reps = reps,
                    lapses = state.lapses,
                ),
                dueAt = now.plus(interval.toLong(), ChronoUnit.DAYS),
            )
        }
    }

    /** 从 0 开始连着答对 [times] 次后的间隔（天），用于测试与预览。 */
    fun previewIntervals(times: Int, now: Instant = Instant.now()): List<Int> {
        var state = Sm2State()
        val out = mutableListOf<Int>()
        repeat(times) {
            val result = next(state, ReviewGrade.GOOD, now)
            state = result.state
            out += state.intervalDays
        }
        return out
    }
}
