package com.example.lixing.data

import android.app.Application
import androidx.room.Room
import com.example.lixing.data.local.LiXingDatabase
import com.example.lixing.data.local.entity.EnglishEntryEntity
import com.example.lixing.data.repository.EnglishEntryRepository
import com.example.lixing.data.repository.memory
import com.example.lixing.data.repository.reconcileEnglishMemory
import com.example.lixing.domain.english.EnglishEntryType
import com.example.lixing.domain.english.ReviewGrade
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class EnglishReviewRepositoryTest {
    private lateinit var db: LiXingDatabase
    private lateinit var repo: EnglishEntryRepository
    private val now = Instant.parse("2026-09-19T12:00:00Z")
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), LiXingDatabase::class.java).allowMainThreadQueries().build()
        repo = EnglishEntryRepository(db.englishEntryDao(), db, Dispatchers.IO)
    }
    @After fun close() = db.close()
    private suspend fun card(id: String = "a"): EnglishEntryEntity = EnglishEntryEntity(id = id, type = EnglishEntryType.WORD, content = "abide", meaning = "遵守").also { db.englishEntryDao().upsert(it) }

    @Test fun `forgotten cards wait until due and never become new`() = runTest {
        val entry = card()
        repo.grade(entry, ReviewGrade.AGAIN, "event", "session", now)
        assertEquals(0, repo.dueCounts(now, 20).newTotal)
        assertTrue(repo.reviewQueue(now.plusSeconds(59), 20).isEmpty())
        assertEquals(entry.id, repo.reviewQueue(now.plusSeconds(60), 0).single().id)
        assertEquals(1, repo.history(entry.id).size)
    }

    @Test fun `daily new quota survives another session and resets at study day boundary`() = runTest {
        val a = card(); card("b")
        val zone = ZoneId.of("UTC")
        val start = LocalTime.of(4, 0)
        repo.grade(a, ReviewGrade.GOOD, "event", "session", now)
        assertEquals(0, repo.dueCounts(now.plusSeconds(10), 1, start, zone).newAvailable)
        assertEquals(0, repo.dueCounts(Instant.parse("2026-09-20T03:59:59Z"), 1, start, zone).newAvailable)
        assertEquals(1, repo.dueCounts(Instant.parse("2026-09-20T04:00:00Z"), 1, start, zone).newAvailable)
    }

    @Test fun `duplicate grade is idempotent and stale edit preserves memory`() = runTest {
        val original = card()
        repo.grade(original, ReviewGrade.GOOD, "same", "session", now)
        repo.grade(original, ReviewGrade.GOOD, "same", "session", now)
        val learned = repo.get(original.id)!!.memory()
        repo.save(original, original.type, original.content, "新释义")
        assertEquals(learned, repo.get(original.id)!!.memory())
        assertEquals(1, repo.history(original.id).size)
    }

    @Test fun `undo restores memory and new word quota atomically`() = runTest {
        val original = card()
        repo.grade(original, ReviewGrade.GOOD, "event", "session", now)
        assertTrue(repo.undo("event"))
        assertFalse(repo.undo("event"))
        assertEquals(original.memory(), repo.get(original.id)!!.memory())
        assertEquals(1, repo.dueCounts(now, 1).newAvailable)
        assertTrue(repo.history(original.id).isEmpty())
    }

    @Test fun `reconciliation of two device histories is deterministic`() = runTest {
        val a = card()
        val first = repo.grade(a, ReviewGrade.GOOD, "a-event", "a-session", now)
        // Other device graded from its old snapshot while offline.
        val second = first.copy(id = "b-event", grade = "HARD", reviewedAt = now.plusSeconds(300).toEpochMilli(), sessionId = "b-session")
        db.englishEntryDao().upsertLog(second)
        reconcileEnglishMemory(db.englishEntryDao(), a.id)
        val result = repo.get(a.id)!!.memory()
        assertEquals(2, result.reviews)
        reconcileEnglishMemory(db.englishEntryDao(), a.id)
        assertEquals(result, repo.get(a.id)!!.memory())
    }
}
