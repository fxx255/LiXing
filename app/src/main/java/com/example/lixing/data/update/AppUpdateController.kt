package com.example.lixing.data.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.example.lixing.BuildConfig
import com.example.lixing.data.prefs.UserPreferencesRepository
import com.example.lixing.data.sync.SyncNetworkChecker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 应用内更新「状态机 + 单例 StateFlow」入口。
 *
 * 为什么是单例：UI 多个屏幕（设置页 + 启动弹窗）都要观察到同一个状态；
 * 下载任务是后台级，跨 Activity 必须存活。
 *
 * 状态机（[State]）：
 *
 *        startCheck()
 *           │
 *   Idle ──▶ Checking ──▶ {Available(manifest), Idle}   // 失败或无需更新 → 回到 Idle
 *                                          │
 *                                  download()
 *                                          ▼
 *                                     Downloading(id, manifest)
 *                                          │
 *                       ┌───────(DOWNLOAD COMPLETE broadcast)
 *                       ▼
 *                   Verifying(id, manifest)
 *                       │
 *           ┌───────────┴───────────┐
 *         ✔︎ verified              ✗ hash mismatch
 *           │                          │
 *   ReadyToInstall(manifest, file)   Failed("校验失败")
 *
 * 任何失败都可以 retry() 回到 Checking。
 */
@Singleton
class AppUpdateController @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val prefsRepository: UserPreferencesRepository,
    private val networkChecker: SyncNetworkChecker,
    private val repository: UpdateRepository,
    private val downloader: UpdateDownloader,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    // 持有最近一次发现的「远端清单」。如果用户点了弹窗的「稍后」，状态回到 Idle，
    // 但清单仍要保留，以便用户进设置页点「立即更新」。
    @Volatile
    private var lastSeenManifest: UpdateManifest? = null
    val pendingManifest: UpdateManifest? get() = lastSeenManifest

    /**
     * 判断「现在到底该不该发请求」——把 Wi-Fi 设置、每天一次节流、开关状态都算清楚。
     *
     * @param manual true = 用户在设置页点了「立即检查」：跳过全部节流与网络限制。
     *   注意 manual 必须传入本函数，否则手动检查会被「每天最多一次」节流吞掉
     *   （启动静默检查已经写过时间戳），表现为点了按钮毫无反应。
     */
    suspend fun decideCheckPolicy(manual: Boolean): CheckPolicy {
        if (manual) return CheckPolicy.Allowed
        val prefs = prefsRepository.current()
        if (!prefs.updateAutoEnabled) return CheckPolicy.Skipped("用户在设置中关闭了自动检查")
        if (prefs.updateWifiOnly && !networkChecker.isAutoSyncAllowed()) {
            return CheckPolicy.Skipped("非 Wi-Fi 跳过")
        }
        val last = prefsRepository.updateLastCheckAt()
        val now = System.currentTimeMillis()
        val since = now - last
        if (last > 0L && since < CHECK_THROTTLE_MS) {
            return CheckPolicy.Skipped("距上次自动检查仅 ${since / 60_000} 分钟，跳过")
        }
        return CheckPolicy.Allowed
    }

    /**
     * 启动一次检查。
     *
     * @param manual true = 用户在设置页点的「立即检查」，忽略节流与 Wi-Fi 限制
     */
    fun startSilentCheck(manual: Boolean = false) {
        // Idle / Failed / UpToDate 都允许重新发起（Failed 的重试、UpToDate 的再查一次都是 manual=true）
        if (_state.value !is State.Idle &&
            _state.value !is State.Failed &&
            _state.value !is State.UpToDate
        ) return
        scope.launch {
            val decision = decideCheckPolicy(manual)
            if (decision is CheckPolicy.Skipped) {
                _state.value = State.Idle
                return@launch
            }
            _state.value = State.Checking
            val result = repository.fetchManifest()
            val manifest = result.getOrNull()
            if (manifest == null) {
                // 手动检查失败必须让用户看见原因；静默检查保持安静
                _state.value = if (manual) {
                    State.Failed("检查失败：${result.exceptionOrNull()?.message ?: "网络错误"}")
                } else {
                    State.Idle
                }
                return@launch
            }
            prefsRepository.markUpdateChecked(System.currentTimeMillis())
            val upgrade = manifest.isUpgradeFor(BuildConfig.VERSION_CODE, Build.VERSION.SDK_INT)
            when (upgrade) {
                is UpgradeDecision.Skip -> {
                    lastSeenManifest = null
                    if (manual) {
                        // 手动检查且确无新版本：给个明确的「已是最新版本」反馈
                        _state.value = State.UpToDate
                        scheduleUpToDateClear()
                    } else {
                        _state.value = State.Idle
                    }
                }
                is UpgradeDecision.Suggested, is UpgradeDecision.Required -> {
                    lastSeenManifest = manifest
                    _state.value = State.Available(manifest, force = upgrade is UpgradeDecision.Required)
                }
            }
        }
    }

    /** 「已是最新版本」提示展示几秒后自动回到待机，避免卡片一直停在提示态。 */
    private fun scheduleUpToDateClear() {
        scope.launch {
            kotlinx.coroutines.delay(UP_TO_DATE_VISIBLE_MS)
            if (_state.value is State.UpToDate) _state.value = State.Idle
        }
    }

    /** 用户在弹窗里点了「立即更新」。 */
    fun startDownload() {
        val manifest = lastSeenManifest ?: return
        // 清掉旧 apk、避免一连下两次
        scope.launch {
            try {
                val filename = "LiXing-${manifest.versionName}.apk"
                val id = withContext(Dispatchers.IO) { downloader.enqueue(manifest.apkUrl, filename) }
                _state.value = State.Downloading(id, manifest)
            } catch (e: Exception) {
                _state.value = State.Failed("下载启动失败: ${e.message}")
            }
        }
    }

    /** 由 [DownloadCompleteReceiver] 在收到广播时调用。 */
    fun onDownloadCompleted(downloadId: Long) {
        val manifest = lastSeenManifest ?: return
        val s = _state.value
        if (s !is State.Downloading || s.downloadId != downloadId) return
        scope.launch { verifyAndInstall(downloadId, manifest) }
    }

    /** 由 [DownloadCompleteReceiver] 周期性 tick 时调用——兜底用户清通知后没收到广播。 */
    fun onTick(downloadId: Long) {
        val s = _state.value
        if (s !is State.Downloading || s.downloadId != downloadId) return
        val status = downloader.queryStatus(downloadId) ?: return
        if (status.isSuccessful) scope.launch { verifyAndInstall(downloadId, s.manifest) }
    }

    private suspend fun verifyAndInstall(downloadId: Long, manifest: UpdateManifest) {
        _state.value = State.Verifying(downloadId, manifest)
        val file = withContext(Dispatchers.IO) {
            downloader.findDownloadedApk() ?: return@withContext null
        } ?: run {
            _state.value = State.Failed("下载完成后未找到 APK 文件")
            return
        }
        val ok = withContext(Dispatchers.IO) { sha256Matches(file, manifest.sha256) }
        if (!ok) {
            downloader.cancel(downloadId)
            _state.value = State.Failed("APK 校验失败，已自动删除")
            return
        }
        _state.value = State.ReadyToInstall(manifest, file)
    }

    /** 用户点「安装」。返回是否真的发起了安装 Intent。 */
    fun requestInstall(): Boolean {
        val s = _state.value as? State.ReadyToInstall ?: return false
        if (!canInstall()) return false
        return try {
            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", s.file)
            } else {
                Uri.fromFile(s.file)
            }
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                setDataAndType(uri, "application/vnd.android.package-archive")
            }
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            false
        } catch (e: SecurityException) {
            false
        }
    }

    /** 跳转到系统「安装未知应用」授权页。 */
    fun openInstallPermissionSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
    }

    fun canInstall(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        return context.packageManager.canRequestPackageInstalls()
    }

    /** 用户点了「稍后再说」或「忽略此版本」。 */
    fun acknowledge() {
        lastSeenManifest = null
        _state.value = State.Idle
    }

    /** 重置整个状态机（用户主动重启检查流）。 */
    fun reset() {
        val s = _state.value
        if (s is State.Downloading) downloader.cancel(s.downloadId)
        if (s is State.Verifying) downloader.cancel(s.downloadId)
        lastSeenManifest = null
        _state.value = State.Idle
    }

    private fun sha256Matches(file: File, expectedHex: String): Boolean {
        val expected = expectedHex.trim().lowercase()
        if (expected.length != 64) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        return actual == expected
    }

    sealed class State {
        data object Idle : State()
        data object Checking : State()

        /** 手动检查后确认没有新版本；展示几秒后自动回 [Idle]。 */
        data object UpToDate : State()
        data class Available(val manifest: UpdateManifest, val force: Boolean) : State()
        data class Downloading(val downloadId: Long, val manifest: UpdateManifest) : State()
        data class Verifying(val downloadId: Long, val manifest: UpdateManifest) : State()
        data class ReadyToInstall(val manifest: UpdateManifest, val file: File) : State()
        data class Failed(val reason: String) : State()
    }

    sealed class CheckPolicy {
        data object Allowed : CheckPolicy()
        data class Skipped(val reason: String) : CheckPolicy()
    }

    companion object {
        /** 每天最多自动查一次。 */
        private const val CHECK_THROTTLE_MS = 24L * 60 * 60 * 1000

        /** 「已是最新版本」提示的展示时长。 */
        private const val UP_TO_DATE_VISIBLE_MS = 5_000L
    }
}
