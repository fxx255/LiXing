package com.example.lixing.data

import android.content.Context
import androidx.room.Room
import com.example.lixing.data.local.LiXingDatabase
import com.example.lixing.data.local.entity.AssistantConversationEntity
import com.example.lixing.data.local.entity.AssistantMessageEntity
import com.example.lixing.data.repository.AssistantChatRepository
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class AssistantChatDaoTest {
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
    fun `messages are stored per conversation in order`() = runTest {
        val dao = database.assistantChatDao()
        val a = conversation("会话 A", 1)
        val b = conversation("会话 B", 2)
        dao.insertConversation(a)
        dao.insertConversation(b)
        dao.insertMessage(message(a.id, "user", "你好", 1))
        dao.insertMessage(message(a.id, "assistant", "你好，有什么可以帮你？", 2))
        dao.insertMessage(message(b.id, "user", "另一个问题", 3))

        assertEquals(listOf("你好", "你好，有什么可以帮你？"), dao.getMessages(a.id).map { it.content })
        assertEquals(listOf("另一个问题"), dao.getMessages(b.id).map { it.content })
    }

    @Test
    fun `conversation list is ordered by most recently updated`() = runTest {
        val dao = database.assistantChatDao()
        val old = conversation("旧会话", 1)
        val new = conversation("新会话", 100)
        dao.insertConversation(old)
        dao.insertConversation(new)
        assertEquals(listOf(new.id, old.id), dao.getConversations().map { it.id })

        dao.touchConversation(old.id, Instant.ofEpochMilli(200))
        assertEquals(listOf(old.id, new.id), dao.getConversations().map { it.id })
    }

    @Test
    fun `deleting conversation cascades to its messages`() = runTest {
        val dao = database.assistantChatDao()
        val target = conversation("待删除", 1)
        dao.insertConversation(target)
        dao.insertMessage(message(target.id, "user", "一条", 1))
        dao.insertMessage(message(target.id, "assistant", "两条", 2))

        dao.deleteConversation(target.id)

        assertTrue(dao.getMessages(target.id).isEmpty())
        assertTrue(dao.getConversations().isEmpty())
    }

    @Test
    fun `message image paths round trip through repository`() = runTest {
        val dao = database.assistantChatDao()
        val conversation = conversation("images", 1)
        dao.insertConversation(conversation)
        val repository = AssistantChatRepository(dao, Dispatchers.IO)
        val paths = listOf("/photos/one.jpg", "/photos/two.jpg")

        repository.appendMessage(conversation.id, "user", "model-only OCR", paths, "question")

        val stored = dao.getMessages(conversation.id).single()
        assertEquals(paths, repository.decodeImagePaths(stored.imagePaths))
        assertEquals("model-only OCR", stored.content)
        assertEquals("question", stored.displayContent)
    }

    @Test
    fun `empty display content stays distinct from model content`() = runTest {
        val dao = database.assistantChatDao()
        val conversation = conversation("image only", 1)
        dao.insertConversation(conversation)
        val repository = AssistantChatRepository(dao, Dispatchers.IO)

        repository.appendMessage(
            conversation.id,
            "user",
            "model-only OCR with \$\$formula\$\$",
            listOf("/photos/question.jpg"),
            "",
        )

        val stored = dao.getMessages(conversation.id).single()
        assertEquals("model-only OCR with \$\$formula\$\$", stored.content)
        assertEquals("", stored.displayContent)
    }

    @Test
    fun `title is truncated and never empty`() {
        assertEquals("短问题", AssistantChatRepository.titleOf("短问题"))
        val long = "这是一个特别长的提问内容，用来验证标题会被截断成二十四个字加省略号"
        assertEquals(long.take(24) + "…", AssistantChatRepository.titleOf(long))
        assertEquals("新对话", AssistantChatRepository.titleOf("   "))
    }

    private fun conversation(title: String, time: Long) = AssistantConversationEntity(
        title = title,
        createdAt = Instant.ofEpochMilli(time),
        updatedAt = Instant.ofEpochMilli(time),
    )

    private fun message(conversationId: String, role: String, content: String, time: Long) =
        AssistantMessageEntity(
            conversationId = conversationId,
            role = role,
            content = content,
            createdAt = Instant.ofEpochMilli(time),
        )
}
