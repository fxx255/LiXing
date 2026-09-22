package com.example.lixing.assistant

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * 真机文件 IO 验收：`AssistantDraftStore` 在**真实 Android 文件系统**上的
 * 原子覆盖写行为（Windows Robolectric 的 `AtomicFile.renameTo` 覆盖既有文件
 * 会失败，shadow 只能模拟，不能替代真机证据）。
 *
 * 隔离纪律（与 `AssistantRuntimeDeviceTest` 一致）：
 *  - 仅在协调者的 UID 隔离 MuMu 12-1 (API 35) 上运行，必须传 `assistantIsolated=true`；
 *  - 只用随机 UUID conversationId 隔离，**不读不写数据库、不联网**；
 *  - cleanup 只删本测试 UUID 的草稿文件（base/.bak/.new）与本测试自己创建的
 *    附件文件，绝不动其他会话的草稿，也**不删除附件目录本身**。
 */
@RunWith(AndroidJUnit4::class)
class AssistantDraftDeviceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val conversationId = "draft-device-it-${UUID.randomUUID()}"
    private val createdAttachmentPaths = mutableListOf<String>()
    private lateinit var fixtureDir: File

    @Before
    fun requireIsolatedDevice() {
        check(InstrumentationRegistry.getArguments().getString("assistantIsolated") == "true") {
            "Run only after backup and UID IPv4/IPv6 isolation; pass assistantIsolated=true"
        }
        fixtureDir = File(context.cacheDir, "draft_device_test_${UUID.randomUUID()}").apply { mkdirs() }
    }

    @After
    fun cleanUpOnlyOwnFiles() {
        // 1) 本 UUID 的草稿文件（含 AtomicFile 的 .bak/.new 残留），只删这三个名字。
        val base = draftFileFor(conversationId)
        listOf(base, File(base.parentFile, "${base.name}.bak"), File(base.parentFile, "${base.name}.new"))
            .forEach { it.delete() }
        // 2) 本测试经 persistAttachments 复制进私有持久目录的附件副本。
        createdAttachmentPaths.forEach { runCatching { File(it).delete() } }
        // 3) 本测试在 cacheDir 自建的夹具目录。
        if (::fixtureDir.isInitialized) fixtureDir.deleteRecursively()
        // 不清空数据库、不删 assistant_drafts / assistant_attachments 目录本身、不联网。
    }

    /** 同一草稿连续 save 12 次（≥10），每次用 **fresh** store 读磁盘断言覆盖生效。 */
    @Test
    fun repeatedSaveOverwritesSameDraftOnRealDisk() = runBlocking {
        val photos = persistFixtureAttachments()
        val store = AssistantDraftStore(context)
        repeat(12) { round ->
            val text = "真机覆盖第${round + 1}次-截断${"x".repeat(if (round % 2 == 0) 100 else 1)}"
            store.save(conversationId, text, photos)
            // 全新实例：无内存 pending，只能从磁盘读 —— 验证覆盖写确实落了盘。
            val fresh = AssistantDraftStore(context)
            val draft = fresh.load(conversationId)
            assertNotNull("第${round + 1}次保存后磁盘上必须有草稿", draft)
            assertEquals("第${round + 1}次保存后文字必须整体覆盖", text, draft!!.text)
            assertEquals("第${round + 1}次保存后附件路径必须完整保留", photos, draft.photoPaths)
        }
    }

    /** 单例 store 的 20 次编辑异步落盘：最后编辑必须保留；clear 草稿不删附件。 */
    @Test
    fun concurrentReservePersistKeepsFinalTextAndAttachmentsAndClearSparesThem() = runBlocking {
        val photos = persistFixtureAttachments()
        val store = AssistantDraftStore(context)
        val finalText = "并发第20次编辑"

        // 与应用的 @Singleton 生命周期相同。reserve 按用户编辑顺序同步发生，
        // persist 在 IO 协程中任意调度；新实例只在所有写入结束后读取磁盘。
        (1..20).map { index ->
            val reservation = store.reserveSave(conversationId, "并发第${index}次编辑", photos)
            async(Dispatchers.IO) {
                store.persist(reservation)
            }
        }.awaitAll()

        val fresh = AssistantDraftStore(context)
        val draft = fresh.load(conversationId)
        assertNotNull("并发写之后磁盘上必须有草稿", draft)
        assertEquals("并发覆盖后最终文字必须是最后内容", finalText, draft!!.text)
        assertEquals("并发覆盖后附件路径必须保留", photos, draft.photoPaths)

        // fresh store 再验一次（真·新实例，读的是已提交字节）。
        val fresh2 = AssistantDraftStore(context)
        assertEquals(finalText, fresh2.load(conversationId)!!.text)

        // 删除草稿：文件必须消失，但附件文件一个都不能删。
        AssistantDraftStore(context).clear(conversationId)
        assertNull("clear 后草稿必须为空", AssistantDraftStore(context).load(conversationId))
        photos.forEach { path ->
            assertTrue("clear 草稿不得删除附件文件: $path", File(path).isFile && File(path).length() > 0L)
        }
    }

    // ── 夹具与路径工具 ──

    /** 在 cacheDir 造两个非空附件，经公共 API persistAttachments 复制进私有持久目录，并登记待清理。 */
    private suspend fun persistFixtureAttachments(): List<String> {
        val sources = (1..2).map { index ->
            File(fixtureDir, "fixture_att_$index.txt").apply {
                writeText("设备测试附件内容-$index-${UUID.randomUUID()}")
            }.canonicalPath
        }
        val copied = AssistantDraftStore(context).persistAttachments(sources)
        createdAttachmentPaths += copied
        return copied
    }

    /** 与 `AssistantDraftStore.fileFor` 相同的净化规则，仅用于定位本 UUID 的草稿文件。 */
    private fun draftFileFor(conversationId: String): File {
        val name = conversationId.replace(Regex("[^A-Za-z0-9_-]"), "_")
        return File(File(context.filesDir, "assistant_drafts").apply { mkdirs() }, "draft_$name.txt")
    }
}
