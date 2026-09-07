package com.example.lixing.data

import android.content.Context
import androidx.room.Room
import com.example.lixing.data.backup.InspectedBackup
import com.example.lixing.data.backup.BackupData
import com.example.lixing.data.backup.BackupFormat
import com.example.lixing.data.backup.BackupManifest
import com.example.lixing.data.backup.TablePayload
import com.example.lixing.data.backup.VersionedBackupRepository
import com.example.lixing.data.local.LiXingDatabase
import com.example.lixing.data.local.entity.StudyPlanEntity
import com.example.lixing.data.local.entity.EnglishEntryEntity
import com.example.lixing.data.local.entity.AssistantConversationEntity
import com.example.lixing.data.repository.AssistantChatRepository
import com.example.lixing.domain.english.EnglishEntryType
import com.example.lixing.data.prefs.UserPreferencesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate
import java.time.Instant
import java.util.zip.ZipFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class VersionedBackupRepositoryTest {
    private lateinit var database: LiXingDatabase
    private lateinit var repository: VersionedBackupRepository

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication() as Context
        database = Room.inMemoryDatabaseBuilder(context, LiXingDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val syncStore = com.example.lixing.data.sync.SyncLocalStore(database, Dispatchers.IO)
        // 同步触发器与元表（恢复流程会走 withApplyingGuard）
        database.openHelper.writableDatabase
        com.example.lixing.data.sync.SyncTriggerInstaller().install(database.openHelper.writableDatabase)
        repository = VersionedBackupRepository(
            context,
            database,
            UserPreferencesRepository(context),
            syncStore,
            Dispatchers.IO,
        )
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun `version package contains manifest and restores database rows`() = runTest {
        database.planDao().insertPlan(
            StudyPlanEntity(
                name = "跨设备计划",
                startDate = LocalDate.of(2026, 8, 1),
                targetDate = LocalDate.of(2026, 12, 19),
            ),
        )
        val version = repository.createLocalVersion()
        ZipFile(version.path).use { zip ->
            assertTrue(zip.getEntry("manifest.json") != null)
            assertTrue(zip.getEntry("data.json") != null)
        }

        val importCopy = File(version.path).copyTo(
            File(version.path).resolveSibling("restore_test_${System.nanoTime()}.lixingbackup"),
        )
        database.planDao().deleteAllPlans()
        assertEquals(0, database.planDao().countPlans())

        repository.restore(InspectedBackup(version.copy(path = importCopy.path), importCopy.path))

        assertEquals("跨设备计划", database.planDao().getActivePlan()?.name)
    }

    @Test
    fun `v2 package restores into current schema with defaults for newer columns`() = runTest {
        database.planDao().insertPlan(
            StudyPlanEntity(
                name = "旧版可恢复计划",
                startDate = LocalDate.of(2026, 8, 1),
                targetDate = LocalDate.of(2026, 12, 19),
            ),
        )
        val current = repository.createLocalVersion()
        val v2File = rewriteAsV2(File(current.path))
        database.planDao().deleteAllPlans()

        repository.restore(InspectedBackup(current.copy(path = v2File.path), v2File.path))

        assertEquals("旧版可恢复计划", database.planDao().getActivePlan()?.name)
    }

    @Test
    fun `version package restores english entries`() = runTest {
        val dao = database.englishEntryDao()
        dao.upsert(
            EnglishEntryEntity(
                type = EnglishEntryType.SENTENCE,
                content = "Small steps add up over time.",
                meaning = "微小的进步会随时间累积。",
            ),
        )
        val version = repository.createLocalVersion()
        val importCopy = File(version.path).copyTo(
            File(version.path).resolveSibling("english_restore_${System.nanoTime()}.lixingbackup"),
        )
        dao.deleteAll()
        assertEquals(0, dao.observe("", null).first().size)

        repository.restore(InspectedBackup(version.copy(path = importCopy.path), importCopy.path))

        val restored = dao.observe("", null).first().single()
        assertEquals(EnglishEntryType.SENTENCE, restored.type)
        assertEquals("微小的进步会随时间累积。", restored.meaning)
    }

    @Test
    fun `version package restores assistant message images`() = runTest {
        val context = RuntimeEnvironment.getApplication() as Context
        val chatRepository = AssistantChatRepository(database.assistantChatDao(), Dispatchers.IO)
        val conversation = AssistantConversationEntity(
            title = "photo question",
            createdAt = Instant.ofEpochMilli(1),
            updatedAt = Instant.ofEpochMilli(1),
        )
        database.assistantChatDao().insertConversation(conversation)
        val conversationId = conversation.id
        val originalBytes = byteArrayOf(1, 3, 5, 7, 9)
        val photo = File(context.cacheDir, "assistant_photo_${System.nanoTime()}.jpg").apply {
            writeBytes(originalBytes)
        }
        chatRepository.appendMessage(conversationId, "user", "read this", listOf(photo.absolutePath))

        val version = repository.createLocalVersion()
        assertEquals(1, version.photoCount)
        val importCopy = File(version.path).copyTo(
            File(version.path).resolveSibling("assistant_photo_restore_${System.nanoTime()}.lixingbackup"),
        )
        photo.delete()

        repository.restore(InspectedBackup(version.copy(path = importCopy.path), importCopy.path))

        val stored = database.assistantChatDao().getMessages(conversationId).single()
        val restoredPath = chatRepository.decodeImagePaths(stored.imagePaths).single()
        assertTrue(File(restoredPath).isFile)
        assertTrue(originalBytes.contentEquals(File(restoredPath).readBytes()))
    }

    private fun rewriteAsV2(source: File): File {
        val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
        val (manifest, payload) = ZipFile(source).use { zip ->
            val manifest = zip.getInputStream(zip.getEntry("manifest.json"))
                .bufferedReader().use { json.decodeFromString<BackupManifest>(it.readText()) }
            val payload = zip.getInputStream(zip.getEntry("data.json"))
                .bufferedReader().use { json.decodeFromString<BackupData>(it.readText()) }
            manifest to payload
        }
        val v2Payload = payload.copy(
            tables = payload.tables
                .filterNot { it.name == "meal_record" || it.name == "english_entry" }
                .map { table ->
                    when (table.name) {
                        "time_slot" -> table.withoutColumns("required_task_count")
                        "daily_task" -> table.withoutColumns("slot_required_task_count")
                        "commitment" -> table.withoutColumns("metric", "custom_reward")
                        else -> table
                    }
                },
        )
        val dataBytes = json.encodeToString(v2Payload).toByteArray(Charsets.UTF_8)
        val v2Manifest = manifest.copy(
            databaseVersion = 2,
            payloadSha256 = BackupFormat.sha256(dataBytes),
        )
        val target = source.resolveSibling("v2_restore_${System.nanoTime()}.lixingbackup")
        ZipOutputStream(target.outputStream()).use { out ->
            out.putNextEntry(ZipEntry("manifest.json"))
            out.write(json.encodeToString(v2Manifest).toByteArray(Charsets.UTF_8))
            out.closeEntry()
            out.putNextEntry(ZipEntry("data.json"))
            out.write(dataBytes)
            out.closeEntry()
        }
        return target
    }

    private fun TablePayload.withoutColumns(vararg removed: String): TablePayload {
        val keep = columns.indices.filter { columns[it] !in removed }
        return copy(
            columns = keep.map(columns::get),
            rows = rows.map { row -> keep.map(row::get) },
        )
    }
}
