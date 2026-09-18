package com.example.lixing.data

import android.app.Application
import androidx.room.Room
import com.example.lixing.data.dictionary.DictionaryRepository
import com.example.lixing.data.local.LiXingDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class EnglishDictionaryTest {
    @Test fun `bundled dictionary opens verifies checksum and resolves actual words offline`() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val db = Room.inMemoryDatabaseBuilder(context, LiXingDatabase::class.java).allowMainThreadQueries().build()
        try {
            val dictionary = DictionaryRepository(context, db.englishEntryDao(), Dispatchers.IO)
            val word = dictionary.lookup(" ABIDE ")!!
            assertTrue(word.translation.contains("遵守") || word.translation.contains("忍受"))
            assertTrue(word.phonetic.isNotBlank())
            assertTrue(word.definitions.isNotEmpty())
            assertTrue(dictionary.lookup("apple")!!.examples.any { it.chinese.isNotBlank() })
            assertNotNull(dictionary.lookup("in the long run"))
            assertNotNull(dictionary.lookup("abides"))
            assertNull(dictionary.lookup("xyzzy-nonword-test-984"))
        } finally { db.close() }
    }
}
