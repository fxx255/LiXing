package com.example.lixing.data.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.example.lixing.data.backup.VersionedBackupRepository
import com.example.lixing.data.prefs.UserPreferencesRepository
import com.example.lixing.data.sync.maimemo.MaimemoAutoSyncer
import com.example.lixing.data.sync.webdav.WebDavConfig
import com.example.lixing.data.sync.webdav.WebDavCredentialStore
import com.example.lixing.data.sync.webdav.WebDavException
import com.example.lixing.data.sync.webdav.WebDavTransportFactory
import com.example.lixing.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** 设置页看到的同步状态快照。 */
data class SyncUiState(
    val configured: Boolean = false,
    val account: String = "",
    val serverUrl: String = "",
    val busy: Boolean = false,
    /** 给用户看的一条提示（连接结果 / 同步结果 / 错误原因）。 */
    val message: String? = null,
    val lastReport: SyncReport? = null,
    val lastSyncAtMillis: Long? = null,
    val totalUploadedBytes: Long = 0L,
    val totalDownloadedBytes: Long = 0L,
)

/** 这次同步为什么被触发。 */
enum class SyncTrigger { MANUAL, AUTO_START, AUTO_IDLE }

/**
 * 空闲同步判定：数据最后一次变化后满 [idleMillis] 才允许同步。
 *
 * 抽成纯类是为了单测——监视循环里的墙钟与 delay 不好测，策略本身很好测。
 */
class IdleSyncPolicy(private val idleMillis: Long = DEFAULT_IDLE_MILLIS) {

    var dirtyAtMillis: Long? = null
        private set

    /** 数据有变化。连续变化会不断顺延触发时刻（去抖）。 */
    fun markDirty(nowMillis: Long) {
        dirtyAtMillis = nowMillis
    }

    fun shouldSync(nowMillis: Long): Boolean {
        val dirtyAt = dirtyAtMillis ?: return false
        return nowMillis - dirtyAt >= idleMillis
    }

    /** 同步完成（或判定后放弃）时调用，回到等待数据变化的初始状态。 */
    fun markSynced() {
        dirtyAtMillis = null
    }

    companion object {
        const val DEFAULT_IDLE_MILLIS = 30_000L
    }
}

/**
 * 凭据与传输的来源。
 *
 * 抽成接口的原因：默认实现走 AndroidKeyStore，JVM 单测里创建不了密钥；
 * 测试注入假实现即可让 [SyncRepository] 走真实的 FakeWebDavServer 全链路。
 */
interface SyncTransportSource {
    fun storedConfig(): WebDavConfig?
    fun saveConfig(config: WebDavConfig)
    fun clearConfig()
    fun transport(config: WebDavConfig): SyncTransport
    suspend fun checkConnection(config: WebDavConfig): WebDavException?
}

/** 生产实现：Keystore 加密的凭据仓库 + Phase ④ 的 WebDAV 工厂。 */
@Singleton
class WebDavSyncTransportSource @Inject constructor(
    private val credentials: WebDavCredentialStore,
    private val factory: WebDavTransportFactory,
) : SyncTransportSource {
    override fun storedConfig(): WebDavConfig? =
        runCatching { credentials.load() }.getOrNull()?.takeIf { it.isComplete }

    override fun saveConfig(config: WebDavConfig) = credentials.save(config)

    override fun clearConfig() = credentials.clear()

    override fun transport(config: WebDavConfig): SyncTransport = factory.transport(config)

    override suspend fun checkConnection(config: WebDavConfig): WebDavException? =
        factory.checkConnection(config)
}

/** 自动同步前问一问网络是否合适。抽成接口同样是给单测用的。 */
fun interface SyncNetworkChecker {
    fun isAutoSyncAllowed(): Boolean
}

/** 默认实现：Wi-Fi / 以太网等非计费网络才允许自动同步；手动同步不经过这里。 */
@Singleton
class DefaultSyncNetworkChecker @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : SyncNetworkChecker {
    override fun isAutoSyncAllowed(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }
}

/**
 * 同步编排：连接管理、手动/自动同步、恢复点与统计。
 *
 * 设计要点：
 * - 单飞：同一时刻最多一次同步（[Mutex.tryLock]），手动撞上自动时直接提示、不排队；
 * - 恢复点：手动同步前总是存一份 `before_sync` 恢复点（独立槽位，保留最近 3 份）；
 *   自动同步只在「第一次发布基线之前」（selfCursor < 0，全量合并风险最高）存；
 * - 失败可恢复：恢复点失败只提示不阻塞；同步失败保留上次成功状态，游标不推进。
 */
@Singleton
class SyncRepository @Inject constructor(
    private val engine: SyncEngine,
    private val store: SyncLocalStore,
    private val source: SyncTransportSource,
    private val backupRepository: VersionedBackupRepository,
    private val prefsRepository: UserPreferencesRepository,
    private val networkChecker: SyncNetworkChecker,
    private val maimemoAutoSyncer: MaimemoAutoSyncer,
    @param:ApplicationContext private val context: Context,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) {
    private val mutex = Mutex()
    private val policy = AutoSyncPolicy()
    private var monitoringJob: Job? = null
    @Volatile private var inForeground = false
    private val statsPrefs = context.getSharedPreferences(STATS_PREFS, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<SyncUiState> = _state.asStateFlow()

    /** Application 启动时调用：开一个监视循环（启动同步 + 空闲同步）。 */
    fun startMonitoring(scope: CoroutineScope) {
        if (monitoringJob?.isActive == true) return
        monitoringJob = scope.launch { monitor() }
    }

    fun onForeground() { inForeground = true; policy.requestForeground() }
    fun onBackground() { inForeground = false }
    fun requestAutoSync() { policy.requestForeground() }
    private fun now() = android.os.SystemClock.elapsedRealtime()

    private suspend fun monitor() {
        delay(INITIAL_SYNC_DELAY_MILLIS)
        policy.started(now())
        if (!tryAutoSync(SyncTrigger.AUTO_START)) policy.finished(false, now())
        while (currentCoroutineContext().isActive) {
            delay(POLL_INTERVAL_MILLIS)
            val clock = try { store.clock() } catch (e: Exception) {
                if (e is CancellationException) throw e
                continue
            }
            policy.observe(clock, now())
            if (policy.shouldSync(now(), inForeground)) {
                policy.started(now())
                if (!tryAutoSync(SyncTrigger.AUTO_IDLE)) policy.finished(false, now())
            }
        }
    }

    /**
     * 连接 WebDAV：先规整地址、再真连一次（PROPFIND），通过才落盘。
     * 返回 true 表示连接成功且已保存。
     */
    suspend fun connect(folderUrl: String, account: String, password: String): Boolean = mutex.withLock {
        val normalized = WebDavConfig.normalizeUrl(folderUrl)
        if (normalized == null) {
            _state.update {
                it.copy(message = "地址无法识别。示例：https://dav.jianguoyun.com/dav/lixing/")
            }
            return@withLock false
        }
        if (account.isBlank() || password.isBlank()) {
            _state.update { it.copy(message = "请填写账号和应用密码") }
            return@withLock false
        }
        val config = WebDavConfig(normalized, account.trim(), password)
        _state.update { it.copy(busy = true, message = "正在测试连接…") }
        val failure = runCatching { source.checkConnection(config) }
            .getOrElse {
                if (it is CancellationException) { _state.update { s -> s.copy(busy = false) }; throw it }
                WebDavException(WebDavException.Kind.NETWORK, it.message ?: "网络异常", it)
            }
        if (failure != null) {
            _state.update { it.copy(busy = false, message = "连接失败：${failure.userMessage()}") }
            return@withLock false
        }
        source.saveConfig(config)
        policy.requestForeground()
        _state.update {
            it.copy(
                configured = true,
                account = config.account,
                serverUrl = config.displayUrl,
                busy = false,
                message = "已连接 ${config.displayUrl}。请确保各设备使用相同账号和目录，开启自动同步后会自动合并。",
            )
        }
        true
    }

    /** 断开：清掉本机凭据。云端文件保留，重连同一目录后游标还在，能继续增量。 */
    suspend fun disconnect() = mutex.withLock {
        source.clearConfig()
        _state.update {
            it.copy(
                configured = false,
                account = "",
                serverUrl = "",
                busy = false,
                message = "已断开。云端文件已保留，重新连接同一目录即可继续同步。",
            )
        }
    }

    /** 手动同步：不限网络类型，总是先存恢复点。 */
    suspend fun syncNow() = runSync(SyncTrigger.MANUAL)

    /** 自动同步入口。internal 是为了在单测里直接驱动自动路径（绕开监视循环的墙钟）。 */
    internal suspend fun tryAutoSync(trigger: SyncTrigger = SyncTrigger.AUTO_IDLE) = runSync(trigger)

    private suspend fun runSync(trigger: SyncTrigger): Boolean {
        if (!mutex.tryLock(this)) {
            if (trigger == SyncTrigger.MANUAL) {
                _state.update { it.copy(message = "上一次同步还在进行中，稍等一下") }
            }
            return false
        }
        try {
            val config = runCatching { source.storedConfig() }.getOrNull()?.takeIf { it.isComplete }
            if (config == null) {
                if (trigger == SyncTrigger.MANUAL) {
                    _state.update { it.copy(message = "还没有连接 WebDAV，先在上方完成连接") }
                }
                return false
            }

            val prefs = prefsRepository.current()
            if (trigger != SyncTrigger.MANUAL) {
                if (!prefs.syncAutoEnabled) return false
                if (prefs.syncWifiOnly && !networkChecker.isAutoSyncAllowed()) {
                    _state.update {
                        it.copy(message = "已跳过自动同步：当前不是 Wi-Fi。可在设置里允许使用流量。")
                    }
                    return false
                }
            }

            _state.update {
                it.copy(
                    busy = true,
                    message = if (trigger == SyncTrigger.MANUAL) "正在同步…" else "正在自动同步…",
                )
            }
            try {
                policy.started(now())
                val report = withContext(io) { executeSync(trigger, config) }
                // 墨墨进度搭自动同步的顺风车（开关/节流/只增不减都在执行器内部判断）。
                val maimemoNote = withContext(io) {
                    if (report.isSuccess) runCatching { maimemoAutoSyncer.syncIfDue() }
                        .onFailure { if (it is CancellationException) throw it }.getOrNull() else null
                }
                _state.update { it.copy(busy = false, message = describe(report) + (maimemoNote ?: "")) }
                if (report.isSuccess) policy.finished(true, now(), report.publishedClock)
                return report.isSuccess
            } catch (e: Exception) {
                if (e is CancellationException) { _state.update { it.copy(busy = false) }; throw e }
                val reason = (e as? WebDavException)?.userMessage()
                    ?: e.message?.take(120)?.takeIf { it.isNotBlank() }
                    ?: e::class.java.simpleName
                _state.update { it.copy(busy = false, message = "同步失败：$reason") }
                return false
            }
        } finally {
            mutex.unlock(this)
        }
    }

    /** 必须在 [runSync] 持锁时调用。抛异常时游标不推进，下次同步自动续上。 */
    private suspend fun executeSync(trigger: SyncTrigger, config: WebDavConfig): SyncReport {
        // A cursor only describes one account + directory.
        val target = java.security.MessageDigest.getInstance("SHA-256")
            .digest("${config.folderUrl}\n${config.account}".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        if (statsPrefs.getString(KEY_TARGET, null) != target) {
            store.forgetPeers()
            statsPrefs.edit().putString(KEY_TARGET, target).remove(KEY_LAST_SYNC).apply()
            _state.update { it.copy(lastSyncAtMillis = null, lastReport = null) }
        }
        // 恢复点：手动总是存；自动只在第一次发布基线前存（之后每次只动增量，风险低）。
        if (trigger == SyncTrigger.MANUAL || selfCursor() < 0L ||
            store.cursors()[SyncEngine.REPAIR_CURSOR_KEY] != SyncEngine.REPAIR_REVISION) {
            runCatching { backupRepository.createBeforeSyncVersion() }.onFailure {
                if (it is CancellationException) throw it
                // 恢复点失败不阻塞同步：内核本身失败可恢复，恢复点只是额外保险。
            }
        }

        val report = engine.sync(source.transport(config))
        val now = System.currentTimeMillis()
        recordStats(report, now)

        _state.update {
            it.copy(
                lastReport = report,
                lastSyncAtMillis = if (report.isSuccess) now else it.lastSyncAtMillis,
                totalUploadedBytes = it.totalUploadedBytes + report.uploadedBytes,
                totalDownloadedBytes = it.totalDownloadedBytes + report.downloadedBytes,
            )
        }
        return report
    }

    private suspend fun selfCursor(): Long =
        runCatching { store.cursors()[SELF_CURSOR_KEY] }.getOrDefault(null) ?: -1L

    private fun recordStats(report: SyncReport, atMillis: Long) {
        val editor = statsPrefs.edit()
            .putLong(KEY_TOTAL_UP, statsPrefs.getLong(KEY_TOTAL_UP, 0L) + report.uploadedBytes)
            .putLong(KEY_TOTAL_DOWN, statsPrefs.getLong(KEY_TOTAL_DOWN, 0L) + report.downloadedBytes)
        if (report.isSuccess) editor.putLong(KEY_LAST_SYNC, atMillis)
        editor.apply()
    }

    private fun initialState(): SyncUiState {
        val config = runCatching { source.storedConfig() }.getOrNull()?.takeIf { it.isComplete }
        return SyncUiState(
            configured = config != null,
            account = config?.account.orEmpty(),
            serverUrl = config?.displayUrl.orEmpty(),
            totalUploadedBytes = statsPrefs.getLong(KEY_TOTAL_UP, 0L),
            totalDownloadedBytes = statsPrefs.getLong(KEY_TOTAL_DOWN, 0L),
            lastSyncAtMillis = statsPrefs.getLong(KEY_LAST_SYNC, 0L).takeIf { it > 0L },
        )
    }

    private fun describe(report: SyncReport): String {
        val parts = mutableListOf<String>()
        if (report.appliedRows > 0) parts += "收到 ${report.appliedRows} 项改动"
        if (report.appliedTombstones > 0) parts += "删除 ${report.appliedTombstones} 项"
        val pushed = report.pushedRows + report.pushedTombstones
        if (pushed > 0) parts += "推送 $pushed 项"
        if (report.errors.isNotEmpty()) {
            parts += "部分数据未同步（${report.errors.size} 项告警），失败记录会保留重试"
            parts += report.errors.first()
        }
        if (report.peers.isEmpty() && report.errors.isEmpty()) {
            parts += "尚未发现其他设备，请确认两端使用相同账号和目录，并各同步一次"
        } else if (parts.isEmpty()) parts += "已与 ${report.peers.size} 台设备核对，数据已是最新"
        return parts.joinToString("，")
    }

    companion object {
        /** 轮询本地时钟的间隔；操作停稳 [IdleSyncPolicy.DEFAULT_IDLE_MILLIS] 后才真正同步。 */
        const val POLL_INTERVAL_MILLIS = 5_000L

        /** 应用启动后先等一会儿再跑第一次同步，避开启动高峰。 */
        const val INITIAL_SYNC_DELAY_MILLIS = 8_000L

        private const val STATS_PREFS = "sync_stats"
        private const val KEY_TOTAL_UP = "total_uploaded"
        private const val KEY_TOTAL_DOWN = "total_downloaded"
        private const val KEY_LAST_SYNC = "last_sync_at"
        private const val KEY_TARGET = "sync_target"
    }
}
