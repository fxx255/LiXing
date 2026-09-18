package com.example.lixing.domain.english

import kotlinx.serialization.Serializable
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round

@Serializable
data class MemoryState(
    val stability: Double = 0.0,
    val difficulty: Double = 0.0,
    val phase: String = "NEW",
    val lastAt: Long? = null,
    val dueAt: Long? = null,
    val firstAt: Long? = null,
    val reviews: Int = 0,
    val lapses: Int = 0,
    val reps: Int = 0,
    val intervalDays: Int = 0,
)

/** FSRS-6 equations/default weights from FSRS4Anki v6.1.1 (MIT; assets/dictionary/licenses).
 * No random fuzz: preview and persisted scheduling use the same deterministic function.
 * Learning steps are our explicit product policy: Again 1 minute, Hard 10 minutes.
 */
object FsrsScheduler {
    const val VERSION = "fsrs-6.1.1-lixing-1"
    const val DAY = 86_400_000L
    private val w = doubleArrayOf(.212, 1.2931, 2.3065, 8.2956, 6.4133, .8334, 3.0194,
        .001, 1.8722, .1666, .796, 1.4835, .0614, .2629, 1.6483, .6014, 1.8729,
        .5425, .0912, .0658, .1542)
    private val decay = -w[20]
    private val factor = .9.pow(1 / decay) - 1
    private fun rounded(x: Double) = round(x * 100) / 100
    private fun initialDifficulty(rating: Int) = rounded((w[4] - exp(w[5] * (rating - 1)) + 1).coerceIn(1.0, 10.0))

    fun retrievability(state: MemoryState, now: Long): Double {
        val last = state.lastAt ?: return 0.0
        return (1 + factor * ((now - last).coerceAtLeast(0).toDouble() / DAY) /
            state.stability.coerceAtLeast(.01)).pow(decay).coerceIn(0.0, 1.0)
    }

    fun next(old: MemoryState, grade: ReviewGrade, now: Long): MemoryState {
        val rating = when (grade) { ReviewGrade.AGAIN -> 1; ReviewGrade.HARD -> 2; ReviewGrade.GOOD -> 3 }
        val newCard = old.lastAt == null && old.reviews == 0
        val s = old.stability.coerceAtLeast(.1)
        val d = old.difficulty.takeIf { it in 1.0..10.0 } ?: 5.0
        val elapsed = old.lastAt?.let { (now - it).coerceAtLeast(0).toDouble() / DAY } ?: 0.0
        val r = retrievability(old.copy(stability = s), now)
        val stability = if (newCard) rounded(max(w[rating - 1], .1)) else rounded(when {
            elapsed < 1 -> {
                var increase = exp(w[17] * (rating - 3 + w[18])) * s.pow(-w[19])
                if (rating >= 3) increase = max(increase, 1.0)
                s * increase
            }
            grade == ReviewGrade.AGAIN -> min(w[11] * d.pow(-w[12]) *
                ((s + 1).pow(w[13]) - 1) * exp((1 - r) * w[14]), s / exp(w[17] * w[18]))
            else -> s * (1 + exp(w[8]) * (11 - d) * s.pow(-w[9]) *
                (exp((1 - r) * w[10]) - 1) * if (grade == ReviewGrade.HARD) w[15] else 1.0)
        }).coerceIn(.01, 36500.0)
        val difficulty = if (newCard) initialDifficulty(rating) else
            (w[7] * initialDifficulty(4) + (1 - w[7]) *
                (d - w[6] * (rating - 3) * (10 - d) / 9)).coerceIn(1.0, 10.0)
        val learning = newCard || old.phase == "LEARNING" || old.phase == "RELEARNING"
        val delayMinutes = when {
            grade == ReviewGrade.AGAIN -> 1
            learning && grade == ReviewGrade.HARD -> 10
            else -> 0
        }
        // At desired retention 0.90 the FSRS interval equals stability, rounded to days.
        val days = if (delayMinutes > 0) 0 else kotlin.math.floor(stability + .5).toInt().coerceIn(1, 36500)
        val phase = if (delayMinutes == 0) "REVIEW" else
            if (newCard || old.phase == "LEARNING") "LEARNING" else "RELEARNING"
        return MemoryState(stability, difficulty, phase, now,
            now + if (delayMinutes > 0) delayMinutes * 60_000L else days * DAY,
            old.firstAt ?: now, old.reviews + 1,
            old.lapses + if (grade == ReviewGrade.AGAIN) 1 else 0,
            if (grade == ReviewGrade.AGAIN) 0 else old.reps + 1, days)
    }
}
