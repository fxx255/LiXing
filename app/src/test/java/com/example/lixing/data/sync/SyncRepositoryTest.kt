package com.example.lixing.data.sync

import android.content.Context
import androidx.room.Room
import com.example.lixing.data.backup.VersionedBackupRepository
import com.example.lixing.data.local.LiXingDatabase
import com.example.lixing.data.local.entity.EnglishEntryEntity
import com.example.lixing.data.prefs.UserPreferencesRepository
import com.example.lixing.data.sync.maimemo.MaimemoAutoSyncer
import com.example.lixing.data.sync.webdav.FakeWebDavServer
import com.example.lixing.data.sync.webdav.WebDavClient
import com.example.lixing.data.sync.webdav.WebDavConfig
import com.example.lixing.data.sync.webdav.WebDavTransport
import com.example.lixing.domain.english.EnglishEntryType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import kotlin.time.Duration.Companion.seconds

/**
 * [SyncRepository] 的端到端测试：真 Room、真偏好存储、真 WebDAV 报文（假服务器），
 * 只把「凭据来源」和「网络判断」换成假实现（Keystore 与系统连接状态 JVM 里没有）。
 *
 * 覆盖：连接校验、手动同步（恢复点 + 统计 + 云端基线）、自动同步的开关与
 * 仅 Wi-Fi 跳过、恢复点槽位只保留最近 3 份且不占用户版本数。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class SyncRepositoryTest {

    private lateinit var context: Context
    private lateinit var server: FakeWebDavServer
    private lateinit var db: LiXingDatabase
    private lateinit var prefsRepository: UserPreferencesRepository
    private lateinit var backupRepository: VersionedBackupRepository
    private lateinit var fakeSource: FakeTransportSource
    private lateinit var networkChecker: FakeNetworkChecker
    private lateinit var maimemoSyncer: FakeMaimemoAutoSyncer
    private lateinit var repository: SyncRepository

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication() as Context
        server = FakeWebDavServer(seedFolders = setOf("/"))
        server.start()

        db = Room.inMemoryDatabaseBuilder(context, LiXingDatabase::class.java)
            .allowMainThreadQueries()
            .build()
            .also { d ->
                d.openHelper.writableDatabase
                SyncTriggerInstaller().install(d.openHelper.writableDatabase)
            }
        prefsRepository = UserPreferencesRepository(context)
        val store = SyncLocalStore(db, Dispatchers.IO)
        backupRepository = VersionedBackupRepository(
            context, db, prefsRepository, store, Dispatchers.IO,
        )
        fakeSource = FakeTransportSource(server)
        // 默认已连接（等价于用户配好过）；连接相关的测试再显式改它
        fakeSource.config = WebDavConfig(server.url, "user@example.com", "app-password")
        // DataStore 在同一进程里是按文件名共享的单例，跨测试会泄漏值，这里显式复位
        kotlinx.coroutines.runBlocking {
            prefsRepository.setSyncAutoEnabled(true)
            prefsRepository.setSyncWifiOnly(true)
        }
        networkChecker = FakeNetworkChecker(allowed = true)
        maimemoSyncer = FakeMaimemoAutoSyncer()
        repository = SyncRepository(
            engine = SyncEngine(store, SyncIdentity(context), Dispatchers.IO),
            store = store,
            source = fakeSource,
            backupRepository = backupRepository,
            prefsRepository = prefsRepository,
            networkChecker = networkChecker,
            maimemoAutoSyncer = maimemoSyncer,
            context = context,
            io = Dispatchers.IO,
        )
    }

    @After
    fun tearDown() {
        db.close()
        server.stop()
    }

    // ---------------- 连接 ----------------

    @Test
    fun `connect with a garbage url is rejected`() = runTest {
        val ok = repository.connect("ftp://example.com/dav/", "user", "pass")

        assertFalse(ok)
        assertTrue(repository.state.value.message.orEmpty().contains("地址无法识别"))
        // 已有配置不应被垃圾输入覆盖
        assertEquals("user@example.com", fakeSource.config?.account)
    }

    @Test
    fun `connect with blank credentials is rejected`() = runTest {
        val ok = repository.connect(server.url, "user@example.com", "  ")

        assertFalse(ok)
        assertTrue(repository.state.value.message.orEmpty().contains("账号和应用密码"))
    }

    @Test
    fun `connect succeeds and persists credentials`() = runTest {
        val ok = repository.connect(server.url, "user@example.com", "app-password")

        assertTrue(ok)
        assertTrue(repository.state.value.configured)
        assertEquals("user@example.com", repository.state.value.account)
        assertEquals("user@example.com", fakeSource.config?.account)
        // 连接测试真的发了一次 PROPFIND
        assertTrue("PROPFIND" in server.methods())
    }

    // ---------------- 手动同步 ----------------

    @Test
    fun `manual sync publishes baseline and records stats`() = runTest(timeout = 180.seconds) {
        db.englishEntryDao().upsert(entry("a-en", "resilient"))
        fakeSource.config = null // 模拟「还没连接」，先验证提示
        repository.syncNow()
        assertTrue(repository.state.value.message.orEmpty().contains("还没有连接"))

        fakeSource.config = WebDavConfig(server.url, "user@example.com", "app-password")
        repository.syncNow()

        val state = repository.state.value
        assertNotNull("应有同步报告", state.lastReport)
        assertTrue("首次同步应写快照", state.lastReport!!.snapshotWritten)
        assertTrue("应统计上传字节", state.totalUploadedBytes > 0L)
        assertNotNull("应记录最后同步时间", state.lastSyncAtMillis)
        assertTrue("云端应有写入", "PUT" in server.methods())
    }

    @Test
    fun `manual sync keeps the before_sync slot at three`() = runTest(timeout = 300.seconds) {
        repeat(4) { index ->
            db.englishEntryDao().upsert(entry("en-$index", "word-$index"))
            repository.syncNow()
        }

        val slot = beforeSyncDir()
        val files = slot.listFiles { f -> f.extension == "lixingbackup" }.orEmpty()
        assertTrue("恢复点槽位应有文件", files.isNotEmpty())
        assertTrue("恢复点最多保留 3 份，实际 ${files.size}", files.size <= 3)
        // 独立槽位：用户的版本目录（versioned-backups 根目录）不被占用
        val userVersions = slot.parentFile
            ?.listFiles { f -> f.isFile && f.extension == "lixingbackup" }
            .orEmpty()
        assertTrue("用户版本目录不应被 before_sync 占用", userVersions.isEmpty())
    }

    @Test
    fun `failed sync keeps the previous success state`() = runTest(timeout = 180.seconds) {
        repository.syncNow()
        val goodState = repository.state.value
        assertTrue(goodState.totalUploadedBytes > 0L)

        // 接下来所有请求都 500：同步失败，但统计与状态不回退、不写坏
        server.forceStatus = 500
        repository.syncNow()

        val badState = repository.state.value
        // 内核把「远端读不到」转成报告里的告警而不是抛异常——这正是失败可恢复的设计
        assertTrue("失败要记录在报告里", badState.lastReport?.errors?.isNotEmpty() == true)
        assertTrue("消息要提示告警", badState.message.orEmpty().contains("告警"))
        assertTrue("上传统计不回退", badState.totalUploadedBytes >= goodState.totalUploadedBytes)
        assertFalse(badState.busy)
    }

    // ---------------- 自动同步 ----------------

    @Test
    fun `auto sync is skipped when wifi only and not on wifi`() = runTest(timeout = 120.seconds) {
        networkChecker.allowed = false
        repository.tryAutoSync(SyncTrigger.AUTO_START)

        assertTrue(repository.state.value.message.orEmpty().contains("跳过自动同步"))
        assertTrue("不应发出任何写请求", server.requests().none { it.method == "PUT" })
    }

    @Test
    fun `auto sync is silent when disabled by the user`() = runTest(timeout = 120.seconds) {
        prefsRepository.setSyncAutoEnabled(false)
        repository.tryAutoSync(SyncTrigger.AUTO_START)

        assertEquals(null, repository.state.value.message)
        assertTrue(server.requests().none { it.method == "PUT" })
    }

    @Test
    fun `auto sync runs when allowed`() = runTest(timeout = 180.seconds) {
        db.englishEntryDao().upsert(entry("a-en", "resilient"))
        repository.tryAutoSync(SyncTrigger.AUTO_START)

        val state = repository.state.value
        assertTrue("自动同步应把基线推上去", state.lastReport!!.snapshotWritten)
    }

    @Test
    fun `successful sync runs maimemo auto check in and appends its note`() = runTest(timeout = 180.seconds) {
        maimemoSyncer.note = "；墨墨已同步 2 个单词任务，+10 分"
        repository.tryAutoSync(SyncTrigger.AUTO_START)

        assertEquals("应顺带执行一次墨墨自动打卡", 1, maimemoSyncer.calls)
        assertTrue(
            "墨墨结果应拼进同步提示：${repository.state.value.message}",
            repository.state.value.message.orEmpty().contains("墨墨已同步 2 个单词任务"),
        )
    }

    @Test
    fun `maimemo note is optional and does not break the sync message`() = runTest(timeout = 180.seconds) {
        maimemoSyncer.note = null
        repository.tryAutoSync(SyncTrigger.AUTO_START)

        assertEquals(1, maimemoSyncer.calls)
        assertTrue(repository.state.value.message.orEmpty().isNotBlank())
    }

    // ---------------- 断开 ----------------

    @Test
    fun `disconnect clears configuration but keeps the message helpful`() = runTest {
        repository.disconnect()

        assertFalse(repository.state.value.configured)
        assertEquals(null, fakeSource.config)
        assertTrue(repository.state.value.message.orEmpty().contains("已断开"))
    }

    // ---------------- 假实现 ----------------

    private class FakeTransportSource(private val server: FakeWebDavServer) : SyncTransportSource {
        var config: WebDavConfig? = null

        override fun storedConfig(): WebDavConfig? = config?.takeIf { it.isComplete }

        override fun saveConfig(config: WebDavConfig) {
            this.config = config
        }

        override fun clearConfig() {
            config = null
        }

        override fun transport(config: WebDavConfig): SyncTransport =
            WebDavTransport(WebDavClient(config, Dispatchers.IO))

        // 与生产实现一致：真的发一次 PROPFIND
        override suspend fun checkConnection(config: WebDavConfig) =
            WebDavClient(config, Dispatchers.IO).check()
    }

    private class FakeNetworkChecker(var allowed: Boolean) : SyncNetworkChecker {
        override fun isAutoSyncAllowed(): Boolean = allowed
    }

    private fun beforeSyncDir(): File =
        File(
            File(context.getExternalFilesDir(null) ?: context.filesDir, "versioned-backups"),
            "before-sync",
        )

    private fun entry(id: String, content: String) = EnglishEntryEntity(
        id = id,
        type = EnglishEntryType.WORD,
        content = content,
        meaning = "释义-$id",
    )
}

/** 记录调用次数的墨墨自动同步假实现：真实门禁（开关/节流）由 DefaultMaimemoAutoSyncer 自己测。 */
private class FakeMaimemoAutoSyncer(
    var note: String? = null,
) : MaimemoAutoSyncer {
    var calls = 0
    override suspend fun syncIfDue(nowMillis: Long): String? {
        calls++
        return note
    }
}
