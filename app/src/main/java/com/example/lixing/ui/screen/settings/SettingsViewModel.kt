package com.example.lixing.ui.screen.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.lixing.data.backup.BackupVersion
import com.example.lixing.data.backup.InspectedBackup
import com.example.lixing.data.backup.VersionedBackupRepository
import com.example.lixing.data.assistant.AiCredentialStore
import com.example.lixing.data.assistant.AiModelProfile
import com.example.lixing.data.assistant.AiReasoningEffort
import com.example.lixing.data.assistant.AiSearchProtocol
import com.example.lixing.data.assistant.AssistantModelClient
import com.example.lixing.data.cloud.BaiduNetdiskRepository
import com.example.lixing.data.cloud.CloudBackup
import com.example.lixing.data.exporter.ExportRepository
import com.example.lixing.data.prefs.UserPreferences
import com.example.lixing.data.prefs.UserPreferencesRepository
import com.example.lixing.data.sync.SyncRepository
import com.example.lixing.data.update.AppUpdateController
import com.example.lixing.domain.word.WordSource
import com.example.lixing.domain.word.WordSourceException
import com.example.lixing.ui.theme.DarkModePref
import com.example.lixing.ui.theme.ThemeSeed
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val prefsRepository: UserPreferencesRepository,
    private val exportRepository: ExportRepository,
    private val versionedBackupRepository: VersionedBackupRepository,
    private val baiduNetdiskRepository: BaiduNetdiskRepository,
    private val syncRepository: SyncRepository,
    private val wordSource: WordSource,
    private val assistantModelClient: AssistantModelClient,
    private val aiCredentialStore: AiCredentialStore,
    private val appUpdateController: AppUpdateController,
) : ViewModel() {

    private val _prefs = MutableStateFlow(UserPreferences())
    val prefs: StateFlow<UserPreferences> = _prefs.asStateFlow()

    /** 导出 / 备份的结果提示（文件路径或错误）。 */
    private val _exportMessage = MutableStateFlow<String?>(null)
    val exportMessage: StateFlow<String?> = _exportMessage.asStateFlow()

    private val _backupVersions = MutableStateFlow<List<BackupVersion>>(emptyList())
    val backupVersions: StateFlow<List<BackupVersion>> = _backupVersions.asStateFlow()

    private val _pendingRestore = MutableStateFlow<InspectedBackup?>(null)
    val pendingRestore: StateFlow<InspectedBackup?> = _pendingRestore.asStateFlow()

    private val _backupBusy = MutableStateFlow(false)
    val backupBusy: StateFlow<Boolean> = _backupBusy.asStateFlow()

    /** 待恢复的版本包来自哪个网盘副本（本地文件导入时为 null）。 */
    private val _pendingRestoreSource = MutableStateFlow<CloudBackup?>(null)

    /**
     * 「下载自网盘的版本包」恢复成功（restore 内含 SHA-256 校验）后才解锁的删除入口。
     * 这是「对端恢复成功并校验后才允许删除远端副本」约定的执行点。
     */
    private val _restoredCloudCopy = MutableStateFlow<CloudBackup?>(null)
    val restoredCloudCopy: StateFlow<CloudBackup?> = _restoredCloudCopy.asStateFlow()

    private val _aiProfiles = MutableStateFlow<List<AiModelProfile>>(emptyList())
    val aiProfiles: StateFlow<List<AiModelProfile>> = _aiProfiles.asStateFlow()

    private val _activeAiProfileId = MutableStateFlow<String?>(null)
    val activeAiProfileId: StateFlow<String?> = _activeAiProfileId.asStateFlow()

    private val _aiModels = MutableStateFlow<List<String>>(emptyList())
    val aiModels: StateFlow<List<String>> = _aiModels.asStateFlow()

    private val _aiModelsBusy = MutableStateFlow(false)
    val aiModelsBusy: StateFlow<Boolean> = _aiModelsBusy.asStateFlow()

    val baiduState = baiduNetdiskRepository.state
    val cloudBackups = baiduNetdiskRepository.cloudBackups
    val baiduAuthorizationUrl: String get() = baiduNetdiskRepository.authorizationUrl

    val syncState = syncRepository.state

    /** 透传更新检查的实时状态（供设置页检查卡片观察）。 */
    val updateState: StateFlow<AppUpdateController.State> = appUpdateController.state

    init {
        viewModelScope.launch {
            prefsRepository.preferences.collect { _prefs.value = it }
        }
        viewModelScope.launch {
            val current = prefsRepository.current()
            aiCredentialStore.migrateLegacyProfile(
                baseUrl = current.aiBaseUrl,
                model = current.aiModel,
                visionEnabled = current.aiVisionEnabled,
            )
            aiCredentialStore.activeProfile()?.let { applyAiProfile(it) }
            refreshAiProfiles()
        }
        refreshBackupVersions()
        if (baiduState.value.connected) refreshCloudBackups()
    }

    fun exportTasksCsv() = runExport("CSV") { exportRepository.exportTasksCsv() }

    fun exportPlanJson() = runExport("计划 JSON") { exportRepository.exportPlanJson() }

    fun backup() {
        viewModelScope.launch {
            runBackupAction {
                val version = versionedBackupRepository.createLocalVersion()
                "已保存本机版本：${version.displayTime()}"
            }
        }
    }

    fun suggestedBackupFileName(): String = versionedBackupRepository.suggestedFileName()

    fun exportVersion(uri: Uri) {
        viewModelScope.launch {
            runBackupAction {
                val version = versionedBackupRepository.exportVersionTo(uri)
                "版本已导出：${version.fileName}\n可在另一台设备中选择此文件恢复"
            }
        }
    }

    fun inspectRestore(uri: Uri) {
        viewModelScope.launch {
            _backupBusy.value = true
            _exportMessage.value = "正在校验备份…"
            _pendingRestoreSource.value = null // 本地文件导入，没有网盘来源
            try {
                _pendingRestore.value = versionedBackupRepository.inspectImport(uri)
                _exportMessage.value = null
            } catch (e: Exception) {
                _exportMessage.value = "无法读取备份：${e.message}"
            } finally {
                _backupBusy.value = false
            }
        }
    }

    fun cancelRestore() {
        _pendingRestore.value = null
        _pendingRestoreSource.value = null
    }

    fun confirmRestore() {
        val pending = _pendingRestore.value ?: return
        val source = _pendingRestoreSource.value
        _pendingRestore.value = null
        _pendingRestoreSource.value = null
        viewModelScope.launch {
            _backupBusy.value = true
            _exportMessage.value = "处理中…"
            try {
                val result = versionedBackupRepository.restore(pending)
                _exportMessage.value =
                    "恢复成功：${result.restored.displayTime()}\n恢复前数据已保存为 ${result.recovery.fileName}"
                _backupVersions.value = versionedBackupRepository.listLocalVersions()
                // restore() 内部完成整包 SHA-256 校验并成功写入后才走到这里，
                // 此刻才允许用户删除网盘上的那份副本。
                _restoredCloudCopy.value = source
            } catch (e: Exception) {
                _exportMessage.value = "操作失败：${e.message ?: e::class.java.simpleName}"
            } finally {
                _backupBusy.value = false
            }
        }
    }

    // ---------------- WebDAV 多端同步 ----------------

    fun connectWebDav(folderUrl: String, account: String, password: String) {
        viewModelScope.launch { syncRepository.connect(folderUrl, account, password) }
    }

    fun disconnectWebDav() = syncRepository.disconnect()

    fun syncWebDavNow() {
        viewModelScope.launch { syncRepository.syncNow() }
    }

    fun setSyncAutoEnabled(enabled: Boolean) =
        viewModelScope.launch { prefsRepository.setSyncAutoEnabled(enabled) }

    fun setSyncWifiOnly(enabled: Boolean) =
        viewModelScope.launch { prefsRepository.setSyncWifiOnly(enabled) }

    // ---------------- 应用内更新 ----------------

    fun setUpdateAutoEnabled(enabled: Boolean) =
        viewModelScope.launch { prefsRepository.setUpdateAutoEnabled(enabled) }

    fun setUpdateWifiOnly(enabled: Boolean) =
        viewModelScope.launch { prefsRepository.setUpdateWifiOnly(enabled) }

    /** 用户在设置页点「立即检查」。manual=true 跳过所有节流与 Wi-Fi 限制。 */
    fun checkUpdateNow() = appUpdateController.startSilentCheck(manual = true)

    /** 用户点「更新」或「重新检查」后，在当前状态下推进流程。 */
    fun startDownload() = appUpdateController.startDownload()

    /** 安装已下载好的 APK。返回是否真的发起了安装 Intent。 */
    fun requestInstall() = appUpdateController.requestInstall()

    /** 跳到系统「安装未知应用」授权页。 */
    fun openInstallPermissionSettings() = appUpdateController.openInstallPermissionSettings()

    /** 用户在弹窗/卡片里点「稍后」或「忽略」时调用。 */
    fun acknowledgeUpdate() = appUpdateController.acknowledge()

    // ---------------- 百度网盘版本 ----------------

    fun markBaiduAuthorizationStarted() = baiduNetdiskRepository.markAuthorizationStarted()

    fun disconnectBaidu() = baiduNetdiskRepository.disconnect()

    fun refreshCloudBackups() {
        viewModelScope.launch { baiduNetdiskRepository.refreshCloudBackups() }
    }

    /** 先生成与本机版本相同的完整包，再上传到百度网盘应用目录。 */
    fun uploadCloudBackup() {
        viewModelScope.launch {
            _backupBusy.value = true
            try {
                val version = versionedBackupRepository.createLocalVersion(reason = "baidu_upload")
                baiduNetdiskRepository.uploadBackup(java.io.File(version.path))
                _backupVersions.value = versionedBackupRepository.listLocalVersions()
            } catch (_: Exception) {
                // Repository 已把不含敏感信息的错误写入 baiduState。
            } finally {
                _backupBusy.value = false
            }
        }
    }

    /** 下载后先做完整性校验，再复用已有的二次确认和恢复前快照。 */
    fun downloadCloudBackup(backup: CloudBackup) {
        viewModelScope.launch {
            _backupBusy.value = true
            try {
                val file = baiduNetdiskRepository.downloadBackup(backup)
                _pendingRestoreSource.value = backup
                _pendingRestore.value = versionedBackupRepository.inspectLocalFile(file)
            } catch (_: Exception) {
                _pendingRestoreSource.value = null
                // 下载或校验失败时不弹恢复确认；原因显示在百度网盘状态区。
            } finally {
                _backupBusy.value = false
            }
        }
    }

    // ---------------- 网盘副本清理 ----------------

    /** 恢复成功后删除那份网盘副本（释放空间；删除失败时保留入口可重试）。 */
    fun deleteRestoredCloudCopy() {
        val backup = _restoredCloudCopy.value ?: return
        viewModelScope.launch {
            _backupBusy.value = true
            try {
                val deleted = baiduNetdiskRepository.deleteCloudBackup(backup)
                if (deleted) _restoredCloudCopy.value = null
            } finally {
                _backupBusy.value = false
            }
        }
    }

    /** 用户确认数据无误前不想删：收起入口（下次重新下载恢复后会出现）。 */
    fun dismissRestoredCloudCopy() {
        _restoredCloudCopy.value = null
    }

    private suspend fun runBackupAction(action: suspend () -> String) {
        _backupBusy.value = true
        _exportMessage.value = "处理中…"
        try {
            _exportMessage.value = action()
            _backupVersions.value = versionedBackupRepository.listLocalVersions()
        } catch (e: Exception) {
            _exportMessage.value = "操作失败：${e.message ?: e::class.java.simpleName}"
        } finally {
            _backupBusy.value = false
        }
    }

    private fun refreshBackupVersions() {
        viewModelScope.launch {
            _backupVersions.value = versionedBackupRepository.listLocalVersions()
        }
    }

    fun clearExportMessage() {
        _exportMessage.value = null
    }

    private fun runExport(label: String, action: suspend () -> String) {
        viewModelScope.launch {
            _exportMessage.value = try {
                "已保存 $label：\n${action()}"
            } catch (e: Exception) {
                "导出失败：${e.message}"
            }
        }
    }

    fun setThemeSeed(seed: ThemeSeed) = viewModelScope.launch { prefsRepository.setThemeSeed(seed) }

    fun setDarkMode(mode: DarkModePref) = viewModelScope.launch { prefsRepository.setDarkMode(mode) }

    fun setPenaltyEnabled(enabled: Boolean) = viewModelScope.launch { prefsRepository.setPenaltyEnabled(enabled) }

    fun setAchieveThreshold(percent: Int) = viewModelScope.launch { prefsRepository.setAchieveThreshold(percent) }

    fun setMakeupPerWeek(count: Int) = viewModelScope.launch { prefsRepository.setMakeupPerWeek(count) }

    fun setSlotReminderEnabled(enabled: Boolean) = viewModelScope.launch { prefsRepository.setSlotReminderEnabled(enabled) }

    fun setUrgeReminderEnabled(enabled: Boolean) = viewModelScope.launch { prefsRepository.setUrgeReminderEnabled(enabled) }

    fun setPomodoroMinutes(minutes: Int) = viewModelScope.launch { prefsRepository.setPomodoroMinutes(minutes) }

    // ---------------- 墨墨背单词 ----------------

    private val _maimemoMessage = MutableStateFlow<String?>(null)
    val maimemoMessage: StateFlow<String?> = _maimemoMessage.asStateFlow()

    fun setMaimemoToken(token: String) = viewModelScope.launch { prefsRepository.setMaimemoToken(token) }

    fun setMaimemoEnabled(enabled: Boolean) = viewModelScope.launch { prefsRepository.setMaimemoEnabled(enabled) }

    fun setMaimemoAutoSync(enabled: Boolean) = viewModelScope.launch { prefsRepository.setMaimemoAutoSync(enabled) }

    fun clearMaimemoMessage() {
        _maimemoMessage.value = null
    }

    /** 测试连接：拉一次今日进度，成功显示完成数，失败给出原因。 */
    fun testMaimemo() {
        viewModelScope.launch {
            _maimemoMessage.value = "正在连接墨墨…"
            _maimemoMessage.value = try {
                if (!wordSource.isConfigured()) {
                    "请先填写 Token 并保持「启用墨墨同步」开启"
                } else {
                    val p = wordSource.todayProgress()
                    "连接成功 ✅ 今日已背 ${p.finished}/${p.total} 个，学习 ${p.studyMinutes} 分钟"
                }
            } catch (e: WordSourceException) {
                "连接失败：${describe(e)}"
            } catch (e: Exception) {
                "连接失败：${e.message ?: e::class.java.simpleName}"
            }
        }
    }

    private fun describe(e: WordSourceException): String = when (e.kind) {
        WordSourceException.Kind.NOT_CONFIGURED ->
            "还没配置 Token。在墨墨 App「我的→更多设置→实验功能→开放 API」复制 Token 填进来"

        WordSourceException.Kind.UNAUTHORIZED ->
            "Token 无效或未授权。注意 Token 要和你的墨墨账号对应，重新复制一次（不要带空格）"

        WordSourceException.Kind.NETWORK -> "网络不通或无法访问 open.maimemo.com：${e.message}"

        WordSourceException.Kind.RATE_LIMITED -> "请求太频繁，稍后再试"

        WordSourceException.Kind.UNKNOWN -> e.message ?: "未知错误"
    }

    // ---------------- AI 学习助手 ----------------

    private val _aiTesting = MutableStateFlow(false)
    val aiTesting: StateFlow<Boolean> = _aiTesting.asStateFlow()

    private val _aiMessage = MutableStateFlow<String?>(null)
    val aiMessage: StateFlow<String?> = _aiMessage.asStateFlow()

    fun setAiAssistantEnabled(enabled: Boolean) {
        viewModelScope.launch { prefsRepository.setAiAssistantEnabled(enabled) }
    }

    fun setAiWebSearchEnabled(enabled: Boolean) = viewModelScope.launch {
        prefsRepository.setAiWebSearchEnabled(enabled)
    }

    private val _questionVisionProfileId = MutableStateFlow<String?>(null)
    val questionVisionProfileId: StateFlow<String?> = _questionVisionProfileId.asStateFlow()

    private val _mealVisionProfileId = MutableStateFlow<String?>(null)
    val mealVisionProfileId: StateFlow<String?> = _mealVisionProfileId.asStateFlow()

    fun setQuestionVisionProfile(id: String?) {
        viewModelScope.launch {
            aiCredentialStore.setQuestionVisionProfileId(id)
            refreshAiProfiles()
            _aiMessage.value = if (id == null) "拍题识题已恢复默认路由" else "已选择拍题识题模型"
        }
    }

    fun setMealVisionProfile(id: String?) {
        viewModelScope.launch {
            aiCredentialStore.setMealVisionProfileId(id)
            refreshAiProfiles()
            _aiMessage.value = if (id == null) "饮食校准已恢复端侧模型" else "已选择饮食校准模型"
        }
    }

    fun selectAiProfile(id: String) {
        viewModelScope.launch {
            val selected = aiCredentialStore.selectProfile(id) ?: return@launch
            applyAiProfile(selected)
            refreshAiProfiles()
            _aiMessage.value = "已切换到「${selected.name}」"
        }
    }

    /** Save or update one encrypted model profile, make it active, then test it. */
    fun saveAndTestAiProfile(
        id: String?,
        name: String,
        baseUrl: String,
        model: String,
        apiKey: String,
        visionEnabled: Boolean,
        searchProtocol: AiSearchProtocol,
        reasoningEffort: AiReasoningEffort,
    ) {
        viewModelScope.launch {
            _aiTesting.value = true
            _aiMessage.value = "正在保存并测试…"
            try {
                val saved = aiCredentialStore.upsertProfile(
                    id = id,
                    name = name,
                    baseUrl = baseUrl,
                    model = model,
                    apiKey = apiKey,
                    visionEnabled = visionEnabled,
                    searchProtocol = searchProtocol,
                    reasoningEffort = reasoningEffort,
                )
                applyAiProfile(saved)
                refreshAiProfiles()
                _aiMessage.value = assistantModelClient.testConnection()
            } catch (e: Exception) {
                _aiMessage.value = e.message ?: "测试失败"
            } finally {
                _aiTesting.value = false
            }
        }
    }

    fun fetchAiModels(baseUrl: String, apiKey: String) {
        viewModelScope.launch {
            _aiModelsBusy.value = true
            try {
                _aiModels.value = assistantModelClient.fetchModels(baseUrl, apiKey)
                _aiMessage.value = "已获取 ${_aiModels.value.size} 个模型"
            } catch (e: Exception) {
                _aiModels.value = emptyList()
                _aiMessage.value = "模型列表获取失败：${e.message ?: "未知错误"}；仍可手动填写模型名"
            } finally {
                _aiModelsBusy.value = false
            }
        }
    }

    fun clearAiModels() {
        _aiModels.value = emptyList()
    }

    fun testActiveAiProfile() {
        viewModelScope.launch {
            _aiTesting.value = true
            _aiMessage.value = "正在测试当前模型…"
            try {
                _aiMessage.value = assistantModelClient.testConnection()
            } catch (e: Exception) {
                _aiMessage.value = e.message ?: "测试失败"
            } finally {
                _aiTesting.value = false
            }
        }
    }

    fun deleteAiProfile(id: String) {
        viewModelScope.launch {
            val deletedName = _aiProfiles.value.firstOrNull { it.id == id }?.name.orEmpty()
            val next = aiCredentialStore.deleteProfile(id)
            if (next == null) {
                prefsRepository.setAiConfiguration("", "", false)
            } else {
                applyAiProfile(next)
            }
            refreshAiProfiles()
            _aiMessage.value = if (deletedName.isEmpty()) "模型配置已删除" else "已删除「$deletedName」"
        }
    }

    private suspend fun applyAiProfile(profile: AiModelProfile) {
        prefsRepository.setAiConfiguration(profile.baseUrl, profile.model, profile.visionEnabled)
    }

    private fun refreshAiProfiles() {
        _aiProfiles.value = aiCredentialStore.profiles()
        _activeAiProfileId.value = aiCredentialStore.activeProfileId()
        _questionVisionProfileId.value = aiCredentialStore.questionVisionProfileId()
        _mealVisionProfileId.value = aiCredentialStore.mealVisionProfileId()
    }
}
