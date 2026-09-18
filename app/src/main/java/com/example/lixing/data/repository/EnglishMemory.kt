package com.example.lixing.data.repository

import com.example.lixing.data.local.entity.EnglishEntryEntity
import com.example.lixing.data.local.dao.EnglishEntryDao
import com.example.lixing.domain.english.MemoryState
import com.example.lixing.domain.english.FsrsScheduler
import com.example.lixing.domain.english.ReviewGrade
import kotlinx.serialization.json.Json
import java.time.Instant
import kotlin.math.exp
import kotlin.math.pow

fun EnglishEntryEntity.memory(): MemoryState {
    val learned = reviewLastAt != null || reviewReps > 0
    val s = fsrsStability.takeIf { it > 0 } ?: if (learned) reviewIntervalDays.toDouble().coerceAtLeast(.1) else 0.0
    // FSRS4Anki's SM-2 conversion; old dueAt is preserved until the next actual review.
    val d = fsrsDifficulty.takeIf { it > 0 } ?: if (learned)
        (11 - (reviewEase - 1) / (exp(1.8722) * s.pow(-.1666) * (exp(.1 * .796) - 1))).coerceIn(1.0, 10.0) else 0.0
    return MemoryState(s, d,
        if (learned && reviewPhase == "NEW") if (reviewIntervalDays == 0) "RELEARNING" else "REVIEW" else reviewPhase,
        reviewLastAt?.toEpochMilli(), reviewDueAt?.toEpochMilli(),
        firstLearnedAt?.toEpochMilli() ?: reviewLastAt?.toEpochMilli(),
        reviewCount.takeIf { it > 0 } ?: (reviewReps + reviewLapses), reviewLapses, reviewReps, reviewIntervalDays)
}

fun EnglishEntryEntity.withMemory(m: MemoryState) = copy(
    fsrsStability = m.stability, fsrsDifficulty = m.difficulty, reviewPhase = m.phase,
    reviewLastAt = m.lastAt?.let(Instant::ofEpochMilli), reviewDueAt = m.dueAt?.let(Instant::ofEpochMilli),
    firstLearnedAt = m.firstAt?.let(Instant::ofEpochMilli), reviewCount = m.reviews,
    reviewLapses = m.lapses, reviewReps = m.reps, reviewIntervalDays = m.intervalDays,
)

/** Merge independent devices' review events deterministically without discarding either history. */
suspend fun reconcileEnglishMemory(dao: EnglishEntryDao, entryId: String) {
    val entry = dao.get(entryId) ?: return
    val logs = dao.history(entryId)
    if (logs.isEmpty()) return
    var memory = Json.decodeFromString<MemoryState>(logs.first().beforeState)
    for (log in logs) if (!log.undone) {
        memory = FsrsScheduler.next(memory, ReviewGrade.valueOf(log.grade), log.reviewedAt)
    }
    if (entry.memory() != memory) dao.upsert(entry.withMemory(memory))
}
