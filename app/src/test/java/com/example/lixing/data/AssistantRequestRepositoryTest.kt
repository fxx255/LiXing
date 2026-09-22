package com.example.lixing.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.lixing.data.local.LiXingDatabase
import com.example.lixing.data.local.Migrations
import com.example.lixing.data.repository.AssistantRequestRepository
import com.example.lixing.domain.assistant.AssistantFailureKind
import com.example.lixing.domain.assistant.AssistantRequestStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 请求仓库与事务一致性验收（实施文档 §2、§3、§7.3、§7.4）。
 *
 * 覆盖的重点：
 * - 创建请求是**事务**：用户消息/回答占位/请求记录要么全有、要么全无；
 * - **attemptId 防晚到回调**：旧一轮的写入绝不能覆盖新一轮结果；
 * - 重试复用同一条用户消息与同一个回答位置（不重复插入）；
 * - 启动恢复只把真正遗留的在途请求转成中断，且**不发起任何网络请求**；
 * - 图槽位与部分正文在中断后保留。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class AssistantRequestRepositoryTest {

    private lateinit var db: LiXingDatabase
    private lateinit var repository: AssistantRequestRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, LiXingDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = AssistantRequestRepository(db.assistantRequestDao(), Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun seedConversation(id: String = "c1") {
        db.assistantChatDao().insertConversation(
            com.example.lixing.data.local.entity.AssistantConversationEntity(id = id, title = "会话"),
        )
    }

    @Test
    fun `reserved answer stays after its question regardless of lexical id order`() = runBlocking {
        seedConversation()
        val request = repository.createRequest(
            conversationId = "c1", userMessageId = "z-user", answerMessageId = "a-answer",
            attemptId = "attempt", userText = "问题", attachmentPaths = emptyList(), snapshotJson = "",
        )
        val rows = db.assistantChatDao().getMessages("c1")
        assertEquals(listOf("z-user", "a-answer"), rows.map { it.id })
        assertTrue("Room persists milliseconds; the answer must have a later stored timestamp",
            rows[1].createdAt.toEpochMilli() > rows[0].createdAt.toEpochMilli())
        repository.interrupt(request.requestId, "attempt", "片段", AssistantFailureKind.NETWORK, "中断")
        repository.beginRetry(request.requestId, "retry", null, null)
        assertTrue(repository.complete(request.requestId, "retry", "a-answer", "完整回答"))
        assertEquals(rows.map { it.id }, db.assistantChatDao().getMessages("c1").map { it.id })
    }

    @Test
    fun `createRequest writes answer placeholder and request in one transaction`() = runBlocking {
        seedConversation()
        val request = repository.createRequest(
            conversationId = "c1",
            userMessageId = "u1",
            answerMessageId = "a1",
            attemptId = "att1",
            userText = "问题",
            attachmentPaths = listOf("/tmp/one.jpg"),
            snapshotJson = "{\"model\":\"m\"}",
        )
        assertEquals(AssistantRequestStatus.PREPARING.name, request.status)
        // 回答占位必须已经在会话里：重试才有固定的更新目标。
        val answer = db.assistantRequestDao().getMessage("a1")
        assertNotNull("回答占位必须随事务一起落库", answer)
        assertEquals("", answer!!.content)
        assertEquals("a1", repository.get(request.requestId)!!.answerMessageId)
    }

    @Test
    fun `stale attempt cannot overwrite newer attempt result`() = runBlocking {
        seedConversation()
        val request = repository.createRequest(
            conversationId = "c1", userMessageId = "u1", answerMessageId = "a1",
            attemptId = "att1", userText = "问题", attachmentPaths = emptyList(), snapshotJson = "",
        )
        // 第一轮跑起来 → 中断 → 用户重试（换 attemptId）。
        assertTrue(repository.markRunning(request.requestId, "att1"))
        repository.interrupt(request.requestId, "att1", "", AssistantFailureKind.NETWORK, "网络错误")
        val retried = repository.beginRetry(request.requestId, "att2", null, null)
        assertEquals("att2", retried!!.attemptId)

        // 旧 attempt 的迟到回调必须被拒绝，绝不能覆盖新一轮。
        assertFalse(
            "旧 attempt 的完成写入必须被拒绝",
            repository.complete(request.requestId, "att1", "a1", "旧结果"),
        )
        assertEquals(AssistantRequestStatus.PREPARING.name, repository.get(request.requestId)!!.status)

        // 新 attempt 正常写入。
        assertTrue(repository.complete(request.requestId, "att2", "a1", "新结果"))
        assertEquals("新结果", db.assistantRequestDao().getMessage("a1")!!.content)
    }

    @Test
    fun `stale attempt cannot mark interrupted over newer running state`() = runBlocking {
        seedConversation()
        val request = repository.createRequest(
            conversationId = "c1", userMessageId = "u1", answerMessageId = "a1",
            attemptId = "att1", userText = "问题", attachmentPaths = emptyList(), snapshotJson = "",
        )
        repository.interrupt(request.requestId, "att1", "", AssistantFailureKind.NETWORK, "网络错误")
        repository.beginRetry(request.requestId, "att2", null, null)
        repository.markRunning(request.requestId, "att2")
        assertFalse(
            "旧 attempt 不得把新一轮标成中断",
            repository.interrupt(request.requestId, "att1", "旧片段", AssistantFailureKind.NETWORK, "旧错误"),
        )
        assertEquals(AssistantRequestStatus.RUNNING.name, repository.get(request.requestId)!!.status)
    }

    @Test
    fun `interrupt keeps partial text local and does not sync a half answer`() = runBlocking {
        seedConversation()
        val request = repository.createRequest(
            conversationId = "c1", userMessageId = "u1", answerMessageId = "a1",
            attemptId = "att1", userText = "问题", attachmentPaths = emptyList(), snapshotJson = "",
        )
        repository.markRunning(request.requestId, "att1")
        repository.savePartial(request.requestId, "att1", "已经生成的一半内容")
        repository.interrupt(
            requestId = request.requestId,
            attemptId = "att1",
            partialText = "已经生成的一半内容",
            kind = AssistantFailureKind.NETWORK,
            message = "网络错误",
        )
        val stored = repository.get(request.requestId)!!
        assertEquals(AssistantRequestStatus.INTERRUPTED.name, stored.status)
        assertEquals(AssistantFailureKind.NETWORK.name, stored.failureKind)
        // 部分正文保留在**请求记录**里（本机瞬时状态），界面据此合并展示。
        assertEquals("已经生成的一半内容", stored.partialText)
        // **不写进会话消息**：那条消息会进跨端同步与备份，半成品不该被同步出去。
        assertEquals("", db.assistantRequestDao().getMessage("a1")!!.content)
    }

    /** 终态不可复活：收尾之后迟到的在途写入必须被拒。 */
    @Test
    fun `late in-flight write cannot resurrect a completed request`() = runBlocking {
        seedConversation()
        val request = repository.createRequest(
            conversationId = "c1", userMessageId = "u1", answerMessageId = "a1",
            attemptId = "att1", userText = "问题", attachmentPaths = emptyList(), snapshotJson = "",
        )
        assertTrue(repository.complete(request.requestId, "att1", "a1", "最终正文"))
        assertFalse(
            "终态必须拒绝迟到的在途写入",
            repository.savePartial(request.requestId, "att1", "更晚的片段"),
        )
        assertEquals(AssistantRequestStatus.COMPLETED.name, repository.get(request.requestId)!!.status)
        assertEquals("最终正文", db.assistantRequestDao().getMessage("a1")!!.content)
    }

    @Test
    fun `beginRetry keeps same user message and answer slot`() = runBlocking {
        seedConversation()
        val request = repository.createRequest(
            conversationId = "c1", userMessageId = "u1", answerMessageId = "a1",
            attemptId = "att1", userText = "原问题", attachmentPaths = emptyList(), snapshotJson = "old",
        )
        // 只有中断状态才能重试（CAS 条件）。
        repository.interrupt(request.requestId, "att1", "", AssistantFailureKind.NETWORK, "网络错误")
        val retried = repository.beginRetry(request.requestId, "att2", "new-snapshot", listOf("/tmp/x.jpg"))!!
        // 最终只能有一条用户消息和一个回答位置。
        assertEquals("u1", retried.userMessageId)
        assertEquals("a1", retried.answerMessageId)
        assertEquals("原问题", retried.userText)
        assertEquals("new-snapshot", retried.snapshotJson)
        assertEquals(listOf("/tmp/x.jpg"), repository.decodeList(retried.attachmentPaths))
        assertEquals(AssistantRequestStatus.PREPARING.name, retried.status)
        // 失败原因要在重试时清空，否则界面会继续显示旧的错误。
        assertEquals("", retried.failureKind)
    }

    /** 重试的 CAS：并发两次只有一个成功；终态请求不能被重试复活。 */
    @Test
    fun `beginRetry is a compare and swap that refuses a second caller`() = runBlocking {
        seedConversation()
        val request = repository.createRequest(
            conversationId = "c1", userMessageId = "u1", answerMessageId = "a1",
            attemptId = "att1", userText = "问题", attachmentPaths = emptyList(), snapshotJson = "",
        )
        assertNull("在途状态不得重试", repository.beginRetry(request.requestId, "att-x", null, null))
        repository.interrupt(request.requestId, "att1", "", AssistantFailureKind.NETWORK, "网络错误")
        assertNotNull(repository.beginRetry(request.requestId, "att-a", null, null))
        assertNull("第二次重试必须被拒", repository.beginRetry(request.requestId, "att-b", null, null))
        assertEquals("att-a", repository.get(request.requestId)!!.attemptId)
        // 完成之后也不能再被重试复活。
        repository.complete(request.requestId, "att-a", "a1", "完成")
        assertNull(repository.beginRetry(request.requestId, "att-c", null, null))
    }

    /** 创建请求时用户消息、回答占位、请求记录在**同一个事务**里。 */
    @Test
    fun `createRequest writes user message answer slot and request together`() = runBlocking {
        seedConversation()
        val request = repository.createRequest(
            conversationId = "c1", userMessageId = "u1", answerMessageId = "a1",
            attemptId = "att1", userText = "用户原文", attachmentPaths = emptyList(), snapshotJson = "",
        )
        val messages = db.assistantChatDao().getMessages("c1")
        assertEquals(1, messages.count { it.id == "u1" })
        assertEquals("用户原文", messages.first { it.id == "u1" }.content)
        assertEquals(1, messages.count { it.id == "a1" })
        assertEquals("占位回答必须为空，不能是半成品", "", messages.first { it.id == "a1" }.content)
        assertNotNull(repository.get(request.requestId))
    }

    /** 收尾必须校验回答位置归属，不能写进别人的气泡。 */
    @Test
    fun `complete refuses a foreign answer slot`() = runBlocking {
        seedConversation()
        val request = repository.createRequest(
            conversationId = "c1", userMessageId = "u1", answerMessageId = "a1",
            attemptId = "att1", userText = "问题", attachmentPaths = emptyList(), snapshotJson = "",
        )
        db.assistantChatDao().insertMessage(
            com.example.lixing.data.local.entity.AssistantMessageEntity(
                id = "other", conversationId = "c1", role = "assistant", content = "",
            ),
        )
        assertFalse(repository.complete(request.requestId, "att1", "other", "不该写进去"))
        assertEquals("", db.assistantRequestDao().getMessage("other")!!.content)
    }

    /**
     * **回答位置已被删除时必须回滚整个收尾**（协调者第 7 条）。
     *
     * 早先 `updateMessageContent` 的返回值被忽略：正文写进一个不存在的行，
     * 请求却被标成 COMPLETED —— 用户看到一条永远空着的回答，而且因为已是终态，
     * 连重试入口都不会出现。现在行数为 0 就回滚，状态保持原样。
     */
    @Test
    fun `completion rolls back when the answer slot no longer exists`() = runBlocking {
        seedConversation()
        val request = repository.createRequest(
            conversationId = "c1", userMessageId = "u1", answerMessageId = "a1",
            attemptId = "att1", userText = "问题", attachmentPaths = emptyList(), snapshotJson = "",
        )
        // 模拟「回答消息被删除」（例如同步/清理把它拿掉了，但请求记录还在）。
        db.assistantChatDao().deleteMessage("a1")
        assertNull("回答位置已不存在", db.assistantRequestDao().getMessage("a1"))

        assertFalse(
            "回答位置不存在时收尾必须被拒（而不是假成功）",
            repository.complete(request.requestId, "att1", "a1", "最终正文"),
        )
        assertEquals(
            "收尾被拒时状态必须回滚，不能标成 COMPLETED",
            AssistantRequestStatus.PREPARING.name,
            repository.get(request.requestId)!!.status,
        )
    }

    /** 确认轮收尾同样要回滚（正文 + 信封是一个事务）。 */
    @Test
    fun `envelope completion rolls back when the answer slot is missing`() = runBlocking {
        seedConversation()
        val request = repository.createRequest(
            conversationId = "c1", userMessageId = "u1", answerMessageId = "a1",
            attemptId = "att1", userText = "问题", attachmentPaths = emptyList(), snapshotJson = "",
        )
        db.assistantChatDao().deleteMessage("a1")
        assertFalse(
            repository.completeWithReview(
                requestId = request.requestId,
                attemptId = "att1",
                answerMessageId = "a1",
                text = "最终正文",
                pendingReview = """{"actionsJson":"[]"}""",
                answerImagePaths = listOf("/tmp/x.png"),
            ),
        )
        assertEquals(
            AssistantRequestStatus.PREPARING.name,
            repository.get(request.requestId)!!.status,
        )
    }

    @Test
    fun `recoverOrphans only flips genuinely leftover requests`() = runBlocking {
        seedConversation()
        val leftover = repository.createRequest(
            conversationId = "c1", userMessageId = "u1", answerMessageId = "a1",
            attemptId = "att1", userText = "遗留问题", attachmentPaths = emptyList(), snapshotJson = "",
        )
        repository.savePartial(leftover.requestId, "att1", "遗留片段")
        val completed = repository.createRequest(
            conversationId = "c1", userMessageId = "u2", answerMessageId = "a2",
            attemptId = "att2", userText = "已完成", attachmentPaths = emptyList(), snapshotJson = "",
        )
        repository.complete(completed.requestId, "att2", "a2", "完成正文")

        val recovered = repository.recoverOrphans()

        assertEquals("只应恢复真正遗留的那一条", 1, recovered.size)
        assertEquals(leftover.requestId, recovered.first().requestId)
        val stored = repository.get(leftover.requestId)!!
        assertEquals(AssistantRequestStatus.INTERRUPTED.name, stored.status)
        assertEquals("遗留片段", stored.partialText)
        // 已完成的请求绝不能被改回中断。
        assertEquals(AssistantRequestStatus.COMPLETED.name, repository.get(completed.requestId)!!.status)
        // 恢复只改状态；全部结束之后不应再有任何在途请求。
        assertEquals(0, repository.countActive())
    }

    @Test
    fun `recoverOrphans is idempotent`() = runBlocking {
        seedConversation()
        repository.createRequest(
            conversationId = "c1", userMessageId = "u1", answerMessageId = "a1",
            attemptId = "att1", userText = "问题", attachmentPaths = emptyList(), snapshotJson = "",
        )
        assertEquals(1, repository.recoverOrphans().size)
        assertEquals("第二次扫描不应再报同一条", 0, repository.recoverOrphans().size)
    }

    @Test
    fun `cancel does not auto retry and marks cancelled`() = runBlocking {
        seedConversation()
        val request = repository.createRequest(
            conversationId = "c1", userMessageId = "u1", answerMessageId = "a1",
            attemptId = "att1", userText = "问题", attachmentPaths = emptyList(), snapshotJson = "",
        )
        repository.markRunning(request.requestId, "att1")
        assertTrue(repository.cancel(request.requestId, "att1", "取消前的片段"))
        val stored = repository.get(request.requestId)!!
        assertEquals(AssistantRequestStatus.CANCELLED.name, stored.status)
        assertEquals(AssistantFailureKind.CANCELLED.name, stored.failureKind)
        // 取消后不进入在途集合，重启扫描也不会把它变成可重试的网络任务。
        assertEquals(0, repository.countActive())
        assertEquals(0, repository.recoverOrphans().size)
    }

    @Test
    fun `completeWithReview writes text envelope and images atomically`() = runBlocking {
        seedConversation()
        val request = repository.createRequest(
            conversationId = "c1", userMessageId = "u1", answerMessageId = "a1",
            attemptId = "att1", userText = "问题", attachmentPaths = emptyList(), snapshotJson = "",
        )
        repository.markRunning(request.requestId, "att1")
        val ok = repository.completeWithReview(
            requestId = request.requestId,
            attemptId = "att1",
            answerMessageId = "a1",
            text = "最终正文",
            pendingReview = "{\"actionsJson\":\"[]\"}",
            answerImagePaths = listOf("/tmp/fig0.png"),
        )
        assertTrue(ok)
        val message = db.assistantRequestDao().getMessage("a1")!!
        assertEquals("最终正文", message.content)
        assertEquals("{\"actionsJson\":\"[]\"}", message.pendingReview)
        assertEquals(listOf("/tmp/fig0.png"), repository.decodeList(message.imagePaths))
        assertEquals(AssistantRequestStatus.COMPLETED.name, repository.get(request.requestId)!!.status)
    }

    @Test
    fun `deleting conversation cascades request records`() = runBlocking {
        seedConversation("c1")
        repository.createRequest(
            conversationId = "c1", userMessageId = "u1", answerMessageId = "a1",
            attemptId = "att1", userText = "问题", attachmentPaths = emptyList(), snapshotJson = "",
        )
        assertEquals(1, repository.forConversation("c1").size)
        repository.deleteForConversation("c1")
        assertEquals(0, repository.forConversation("c1").size)
    }

    @Test
    fun `only one active request is reported at a time`() = runBlocking {
        seedConversation()
        repository.createRequest(
            conversationId = "c1", userMessageId = "u1", answerMessageId = "a1",
            attemptId = "att1", userText = "问题", attachmentPaths = emptyList(), snapshotJson = "",
        )
        assertEquals(1, repository.countActive())
        assertNotNull(repository.anyActive())
        assertNotNull(repository.activeForConversation("c1"))
        assertNull(repository.activeForConversation("other"))
    }

    @Test
    fun `migration coverage matches runtime version`() {
        // 防止「提了 VERSION 却忘了加迁移」这类会在真机上崩溃的疏漏。
        assertEquals(LiXingDatabase.VERSION, Migrations.ALL.last().endVersion)
    }

    @Test
    fun `attachment list round trips through json encoding`() = runBlocking {
        seedConversation()
        val paths = listOf("/data/user/0/pkg/files/a.jpg", "/data/user/0/pkg/files/b.jpg")
        val request = repository.createRequest(
            conversationId = "c1", userMessageId = "u1", answerMessageId = "a1",
            attemptId = "att1", userText = "问题", attachmentPaths = paths, snapshotJson = "",
        )
        assertEquals(paths, repository.decodeList(request.attachmentPaths))
        assertEquals(emptyList<String>(), repository.decodeList(""))
    }
}
