package com.example.lixing.data

import android.content.Context
import androidx.room.Room
import com.example.lixing.data.local.LiXingDatabase
import com.example.lixing.data.local.entity.EnglishEntryEntity
import com.example.lixing.domain.english.EnglishEntryType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class EnglishEntryDaoTest {
    private lateinit var database: LiXingDatabase

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication() as Context
        database = Room.inMemoryDatabaseBuilder(context, LiXingDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun `searches content and meaning and filters all three types`() = runTest {
        val dao = database.englishEntryDao()
        dao.upsert(entry(EnglishEntryType.WORD, "resilient", "有韧性的", 1))
        dao.upsert(entry(EnglishEntryType.PHRASE, "in the long run", "从长远来看", 2))
        dao.upsert(entry(EnglishEntryType.SENTENCE, "Small steps add up.", "微小的进步终会累积。", 3))

        assertEquals(3, dao.observe("", null).first().size)
        assertEquals("in the long run", dao.observe("LONG", null).first().single().content)
        assertEquals("resilient", dao.observe("韧性", null).first().single().content)
        assertEquals(
            "Small steps add up.",
            dao.observe("", EnglishEntryType.SENTENCE).first().single().content,
        )
    }

    @Test
    fun `upsert keeps one row when editing`() = runTest {
        val dao = database.englishEntryDao()
        val draft = entry(EnglishEntryType.WORD, "draft", "草稿", 1)
        dao.upsert(draft)
        val original = requireNotNull(dao.get(draft.id))

        dao.upsert(
            original.copy(
                type = EnglishEntryType.PHRASE,
                content = "draft up",
                meaning = "起草",
                updatedAt = Instant.ofEpochMilli(2),
            ),
        )

        val rows = dao.observe("", null).first()
        assertEquals(1, rows.size)
        assertEquals(EnglishEntryType.PHRASE, rows.single().type)
        assertEquals("起草", rows.single().meaning)
        assertEquals(original.createdAt, rows.single().createdAt)
    }

    private fun entry(
        type: EnglishEntryType,
        content: String,
        meaning: String,
        time: Long,
    ) = EnglishEntryEntity(
        type = type,
        content = content,
        meaning = meaning,
        createdAt = Instant.ofEpochMilli(time),
        updatedAt = Instant.ofEpochMilli(time),
    )
}
