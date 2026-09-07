package com.example.lixing.data.sync.webdav

import android.content.Context
import androidx.room.Room
import com.example.lixing.data.local.LiXingDatabase
import com.example.lixing.data.local.entity.EnglishEntryEntity
import com.example.lixing.data.sync.SyncEngine
import com.example.lixing.data.sync.SyncIdentity
import com.example.lixing.data.sync.SyncLocalStore
import com.example.lixing.data.sync.SyncTriggerInstaller
import com.example.lixing.domain.english.EnglishEntryType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.seconds

/**
 * 真·端到端：同步内核 + 真 WebDAV 传输（跑在本地假 WebDAV 服务器上）。
 *
 * SyncEngineTest 用的是内存 FakeTransport，只证明算法对；
 * 这个测试证明「HTTP 这一层接上之后算法依然对」——PROPFIND 列举、PUT 写快照、
 * 带 ETag 的 PUT 追加日志、跨设备收敛全走真实 HTTP 报文。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class WebDavSyncEndToEndTest {

    private lateinit var context: Context
    private lateinit var server: FakeWebDavServer
    private lateinit var dbA: LiXingDatabase
    private lateinit var dbB: LiXingDatabase
    private lateinit var transportA: WebDavTransport
    private lateinit var transportB: WebDavTransport
    private lateinit var engineA: SyncEngine
    private lateinit var engineB: SyncEngine

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication() as Context
        server = FakeWebDavServer(
            // 故意只留根目录：第一次同步必须自己把 /dav/lixing/ 建出来
            seedFolders = setOf("/"),
        )
        server.start()

        dbA = buildDatabase()
        dbB = buildDatabase()
        transportA = buildTransport()
        transportB = buildTransport()

        val storeA = SyncLocalStore(dbA, Dispatchers.IO)
        val storeB = SyncLocalStore(dbB, Dispatchers.IO)
        val identity = SyncIdentity(context)
        engineA = SyncEngine(storeA, identity, Dispatchers.IO)
        engineB = SyncEngine(storeB, identity, Dispatchers.IO)
    }

    @After
    fun tearDown() {
        dbA.close()
        dbB.close()
        server.stop()
    }

    private fun buildDatabase(): LiXingDatabase =
        Room.inMemoryDatabaseBuilder(context, LiXingDatabase::class.java)
            .allowMainThreadQueries()
            .build()
            .also { db ->
                db.openHelper.writableDatabase
                SyncTriggerInstaller().install(db.openHelper.writableDatabase)
            }

    private fun buildTransport(): WebDavTransport = WebDavTransport(
        WebDavClient(
            config = WebDavConfig(
                folderUrl = server.url,
                account = "user@example.com",
                password = "app-password",
            ),
            io = Dispatchers.IO,
        ),
    )

    @Test
    fun `two devices converge through real webdav calls`() = runTest(timeout = 300.seconds) {
        dbA.englishEntryDao().upsert(
            EnglishEntryEntity(
                id = "a-en",
                type = EnglishEntryType.WORD,
                content = "resilient",
                meaning = "有韧性的",
            ),
        )

        // A 首次同步：云端空 → 建目录 + 写快照
        val first = engineA.sync(transportA, DEVICE_A)
        assertTrue("A 应写快照", first.snapshotWritten)
        assertTrue("A 首次无错误：${first.errors}", first.errors.isEmpty())
        assertEquals(
            "云端应出现 A 的快照",
            listOf(SNAPSHOT_A),
            transportA.list().map { it.name },
        )

        // B 首次同步：拉 A 的快照建基线
        val second = engineB.sync(transportB, DEVICE_B)
        assertTrue("B 无错误：${second.errors}", second.errors.isEmpty())
        assertEquals(listOf(DEVICE_A), second.peers)
        assertNotNull("B 应有 A 的英语积累", dbB.englishEntryDao().get("a-en"))

        // B 改动 → 追加到 B 自己的日志 → A 拉到
        dbB.englishEntryDao().upsert(
            dbB.englishEntryDao().get("a-en")!!.copy(meaning = "B 修订的释义"),
        )
        dbB.englishEntryDao().upsert(
            EnglishEntryEntity(
                id = "b-en",
                type = EnglishEntryType.WORD,
                content = "pragmatic",
                meaning = "务实的",
            ),
        )
        engineB.sync(transportB, DEVICE_B)
        engineA.sync(transportA, DEVICE_A)

        assertEquals("A 收到 B 的修订", "B 修订的释义", dbA.englishEntryDao().get("a-en")!!.meaning)
        assertNotNull("A 收到 B 新建的条目", dbA.englishEntryDao().get("b-en"))

        // 日志确实被追加了（不是每次重写快照）
        assertTrue(
            "B 应有自己的增量日志",
            transportB.list().any { it.name == LOG_B },
        )

        // 删除走墓碑
        dbB.englishEntryDao().delete(dbB.englishEntryDao().get("b-en")!!)
        engineB.sync(transportB, DEVICE_B)
        engineA.sync(transportA, DEVICE_A)
        assertNull("A 收到墓碑后删掉 b-en", dbA.englishEntryDao().get("b-en"))

        // 两台设备最终一致
        assertEquals(
            dbA.englishEntryDao().get("a-en")!!.meaning,
            dbB.englishEntryDao().get("a-en")!!.meaning,
        )
    }

    @Test
    fun `sync reports a clear error when credentials are wrong`() = runTest(timeout = 120.seconds) {
        val wrong = WebDavTransport(
            WebDavClient(
                config = WebDavConfig(
                    folderUrl = server.url,
                    account = "user@example.com",
                    password = "wrong-password",
                ),
                io = Dispatchers.IO,
            ),
        )

        val report = engineA.sync(wrong, DEVICE_A)

        assertEquals(listOf("读取远端文件失败：账号或应用密码不正确（HTTP 401）"), report.errors)
        assertEquals(0, report.pushedRows)
    }

    private companion object {
        // SyncFiles 会再套一层 "device-" 前缀，所以这里只写后缀
        const val DEVICE_A = "a-1111"
        const val DEVICE_B = "b-2222"
        const val SNAPSHOT_A = "device-a-1111.snapshot"
        const val LOG_B = "device-b-2222.jsonl"
    }
}
