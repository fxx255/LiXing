package com.example.lixing.ui.screen.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.lixing.ui.theme.Blush
import com.example.lixing.data.assistant.AiModelProfile
import com.example.lixing.data.assistant.AiReasoningEffort
import com.example.lixing.data.assistant.AiSearchProtocol
import com.example.lixing.data.update.AppUpdateController
import com.example.lixing.ui.theme.DarkModePref
import com.example.lixing.ui.theme.LiXingRadius
import com.example.lixing.ui.theme.ThemeSeed

/** 设置页。分区包卡，主题用色卡选择。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val prefs by viewModel.prefs.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // ---- 外观 ----
            SettingsCard("外观") {
                Text(
                    "主题色",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ThemeSeed.entries.forEach { seed ->
                        SeedSwatch(
                            seed = seed,
                            selected = prefs.themeSeed == seed,
                            onClick = { viewModel.setThemeSeed(seed) },
                        )
                    }
                }

                Spacer(Modifier.height(4.dp))
                Text(
                    "深浅模式",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DarkModePref.entries.forEach { mode ->
                        FilterChip(
                            selected = prefs.darkMode == mode,
                            onClick = { viewModel.setDarkMode(mode) },
                            shape = LiXingRadius.Pill,
                            label = { Text(mode.label) },
                        )
                    }
                }
            }

            // ---- 激励规则 ----
            SettingsCard("激励规则") {
                SwitchRow(
                    label = "漏卡扣分",
                    subtitle = "关掉后漏卡只记录、不扣分",
                    checked = prefs.penaltyEnabled,
                    onCheckedChange = viewModel::setPenaltyEnabled,
                )
                SliderRow(
                    label = "达成阈值",
                    value = "${prefs.achieveThresholdPercent}%",
                    subtitle = "当天完成率达到它才算「达成」，保住连续记录",
                    sliderValue = prefs.achieveThresholdPercent.toFloat(),
                    range = 30f..90f,
                    steps = 11,
                    onChange = { viewModel.setAchieveThreshold(it.toInt()) },
                )
            }

            // ---- 提醒 ----
            SettingsCard("提醒") {
                SwitchRow(
                    label = "时段开始提醒",
                    subtitle = "每个时段开始前提醒",
                    checked = prefs.slotReminderEnabled,
                    onCheckedChange = viewModel::setSlotReminderEnabled,
                )
                SwitchRow(
                    label = "催办提醒",
                    subtitle = "时段快结束还有任务没做时提醒",
                    checked = prefs.urgeReminderEnabled,
                    onCheckedChange = viewModel::setUrgeReminderEnabled,
                )
            }

            // ---- 专注 ----
            SettingsCard("专注") {
                SliderRow(
                    label = "番茄钟时长",
                    value = "${prefs.pomodoroMinutes} 分钟",
                    subtitle = null,
                    sliderValue = prefs.pomodoroMinutes.toFloat(),
                    range = 25f..60f,
                    steps = 6,
                    onChange = { viewModel.setPomodoroMinutes(it.toInt()) },
                )
            }

            // ---- 墨墨背单词 ----
            SettingsCard("墨墨背单词") { MaimemoSection(viewModel, prefs) }
            SettingsCard("AI 学习助手") { AiAssistantSection(viewModel, prefs) }

            // ---- 多端备份 ----
            SettingsCard("百度网盘") { BaiduNetdiskSection(viewModel) }

            // ---- 多端同步（WebDAV）----
            SettingsCard("多端同步") { WebDavSyncSection(viewModel, prefs) }

            // ---- 英语背诵 ----
            SettingsCard("英语背诵") { EnglishReviewSection(viewModel, prefs) }

            // ---- 应用内更新 ----
            SettingsCard("更新") { UpdateSection(viewModel, prefs) }

            // ---- 数据 ----
            SettingsCard("数据") { ExportSection(viewModel) }

            Text(
                text = "默认仅存本机；只有你主动导出版本包时，系统文件选择器才会把文件交给所选位置。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
            )
        }
    }
}

/** 分区卡片。 */
@Composable
private fun SettingsCard(
    title: String,
    content: @Composable () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = LiXingRadius.Tile,
        shadowElevation = 1.dp,
    ) {
        Column(
            Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            content()
        }
    }
}

/** 主题色卡：一个渐变圆点，选中时打勾并放大。 */
@Composable
private fun SeedSwatch(
    seed: ThemeSeed,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(if (selected) 48.dp else 42.dp)
                .clip(LiXingRadius.Pill)
                .background(
                    if (seed == ThemeSeed.DYNAMIC) {
                        Brush.sweepGradient(
                            listOf(
                                Color(0xFFFF9A6C), Color(0xFFE86E8A),
                                Color(0xFF9B7EDE), Color(0xFF4CAF88), Color(0xFFFF9A6C),
                            ),
                        )
                    } else {
                        Brush.linearGradient(listOf(seed.seed, blend(seed.seed)))
                    },
                )
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = "已选择 ${seed.label}",
                    tint = Color.White,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = seed.label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 色卡渐变的第二色：朝霞粉方向混一点，和头卡渐变呼应。 */
private fun blend(c: Color): Color = Color(
    red = c.red + (Blush.red - c.red) * 0.5f,
    green = c.green + (Blush.green - c.green) * 0.5f,
    blue = c.blue + (Blush.blue - c.blue) * 0.5f,
)

@Composable
private fun SliderRow(
    label: String,
    value: String,
    subtitle: String?,
    sliderValue: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(
                value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (subtitle != null) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = sliderValue,
            onValueChange = onChange,
            valueRange = range,
            steps = steps,
        )
    }
}

@Composable
private fun MaimemoSection(viewModel: SettingsViewModel, prefs: com.example.lixing.data.prefs.UserPreferences) {
    val message by viewModel.maimemoMessage.collectAsStateWithLifecycle()

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SwitchRow(
            label = "启用墨墨同步",
            subtitle = "用墨墨的进度自动同步今日单词任务，默认关闭、不联网",
            checked = prefs.maimemoEnabled,
            onCheckedChange = viewModel::setMaimemoEnabled,
        )

        if (prefs.maimemoEnabled) {
            Text(
                text = "获取 Token 二选一：① 墨墨 App「我的→更多设置→实验功能→开放 API」复制；" +
                    "② 浏览器登录墨墨后打开 open.maimemo.com/open/api/v1/tokens/openapi。" +
                    "Token 与你的墨墨账号绑定，无需另做开发者注册。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            androidx.compose.material3.OutlinedTextField(
                value = prefs.maimemoToken,
                onValueChange = viewModel::setMaimemoToken,
                label = { Text("墨墨 Token") },
                placeholder = { Text("粘贴你的 Token") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedButton(
                onClick = viewModel::testMaimemo,
                shape = LiXingRadius.Pill,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("测试连接") }

            SwitchRow(
                label = "跟随多端同步自动打卡",
                subtitle = "开启后每次同步会顺带拉取墨墨进度并打卡单词任务（至少间隔 15 分钟）；" +
                    "只推进不回退，不会撤销你已有的打卡与积分",
                checked = prefs.maimemoAutoSync,
                onCheckedChange = viewModel::setMaimemoAutoSync,
            )

            message?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun BaiduNetdiskSection(viewModel: SettingsViewModel) {
    val context = LocalContext.current
    val state by viewModel.baiduState.collectAsStateWithLifecycle()
    val cloudBackups by viewModel.cloudBackups.collectAsStateWithLifecycle()
    val localBusy by viewModel.backupBusy.collectAsStateWithLifecycle()
    val restoredCloudCopy by viewModel.restoredCloudCopy.collectAsStateWithLifecycle()
    val busy = state.busy || localBusy

    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Text(
            if (state.connected) "已连接。凭证由系统密钥库加密，仅保存在这台手机。"
            else "连接后可把完整版本包上传到自己的百度网盘，并在另一台设备下载恢复。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (!state.connected) {
            OutlinedButton(
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(viewModel.baiduAuthorizationUrl))
                    if (runCatching { context.startActivity(intent) }.isSuccess) {
                        viewModel.markBaiduAuthorizationStarted()
                    }
                },
                enabled = !busy,
                shape = LiXingRadius.Pill,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("连接百度网盘") }
        } else {
            OutlinedButton(
                onClick = viewModel::uploadCloudBackup,
                enabled = !busy,
                shape = LiXingRadius.Pill,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("上传当前完整版本") }

            OutlinedButton(
                onClick = viewModel::refreshCloudBackups,
                enabled = !busy,
                shape = LiXingRadius.Pill,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("刷新网盘版本") }

            if (cloudBackups.isNotEmpty()) {
                Text("网盘版本", style = MaterialTheme.typography.labelLarge)

                // 下载进度（网盘下载很慢，必须给看得见的反馈）
                val progress by viewModel.baiduDownloadProgress.collectAsStateWithLifecycle()
                if (progress.active) {
                    Column(Modifier.fillMaxWidth()) {
                        Text(
                            "正在下载 ${progress.fileName}（${formatFileSize(progress.downloadedBytes)}" +
                                if (progress.totalBytes > 0) {
                                    " / ${formatFileSize(progress.totalBytes)}${progress.percentText}"
                                } else "",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { progress.fraction },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                cloudBackups.take(10).forEach { backup ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                backup.displayTime(),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                "${backup.fileName} · ${formatFileSize(backup.sizeBytes)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(
                            onClick = { viewModel.downloadCloudBackup(backup) },
                            enabled = !busy,
                        ) { Text("下载恢复") }
                    }
                }
            }

            // 恢复成功（含 SHA-256 校验）后才出现的清理入口：
            // 先让用户确认数据无误，再删网盘副本释放空间。
            restoredCloudCopy?.let { copy ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "「${copy.fileName}」已在本机恢复成功",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "确认数据无误后可删除网盘上的这份副本，释放空间",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(
                        onClick = viewModel::dismissRestoredCloudCopy,
                        enabled = !busy,
                    ) { Text("忽略") }
                    TextButton(
                        onClick = viewModel::deleteRestoredCloudCopy,
                        enabled = !busy,
                    ) { Text("删除副本") }
                }
            }

            TextButton(
                onClick = viewModel::disconnectBaidu,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("断开百度网盘") }
        }

        state.message?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Text(
            "跨设备不会静默覆盖：上传会创建新版本；下载后仍需确认，恢复前也会自动保存本机恢复点。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun WebDavSyncSection(
    viewModel: SettingsViewModel,
    prefs: com.example.lixing.data.prefs.UserPreferences,
) {
    val state by viewModel.syncState.collectAsStateWithLifecycle()
    var url by remember { mutableStateOf("") }
    var account by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Text(
            if (state.configured) "已连接 ${state.serverUrl}\n账号：${state.account}"
            else "通过任意支持 WebDAV 的网盘（如坚果云）在多台设备间保持数据一致。" +
                "应用密码由系统密钥库加密保存，仅在本机。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (!state.configured) {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("服务器目录地址") },
                placeholder = { Text("https://dav.jianguoyun.com/dav/lixing/") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = account,
                onValueChange = { account = it },
                label = { Text("账号") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("应用密码") },
                placeholder = { Text("网盘网页端生成的专用密码") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(
                onClick = {
                    viewModel.connectWebDav(url, account, password)
                },
                enabled = !state.busy,
                shape = LiXingRadius.Pill,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("连接并测试") }
        } else {
            SwitchRow(
                label = "自动同步",
                subtitle = "应用启动后、以及操作停止 30 秒后自动同步",
                checked = prefs.syncAutoEnabled,
                onCheckedChange = viewModel::setSyncAutoEnabled,
            )
            SwitchRow(
                label = "仅 Wi-Fi 自动同步",
                subtitle = "打开后自动同步只在 Wi-Fi 下进行；手动同步不受限",
                checked = prefs.syncWifiOnly,
                onCheckedChange = viewModel::setSyncWifiOnly,
            )

            OutlinedButton(
                onClick = viewModel::syncWebDavNow,
                enabled = !state.busy,
                shape = LiXingRadius.Pill,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (state.busy) "同步中…" else "立即同步") }

            state.lastSyncAtMillis?.let { last ->
                val time = java.time.Instant.ofEpochMilli(last)
                    .atZone(java.time.ZoneId.systemDefault())
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                Text(
                    "上次同步：$time · 本次流量见下方统计",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            state.lastReport?.let { report ->
                Text(
                    "上次同步：收到 ${report.appliedRows + report.appliedTombstones} 项，" +
                        "推送 ${report.pushedRows + report.pushedTombstones} 项，" +
                        "上传 ${formatFileSize(report.uploadedBytes.toLong())}，" +
                        "下载 ${formatFileSize(report.downloadedBytes.toLong())}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text(
                "累计流量：上传 ${formatFileSize(state.totalUploadedBytes)}，" +
                    "下载 ${formatFileSize(state.totalDownloadedBytes)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            TextButton(
                onClick = viewModel::disconnectWebDav,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("断开 WebDAV") }
        }

        state.message?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Text(
            "手动同步前会自动保存 before_sync 恢复点（独立保留最近 3 份，不占你的版本数）。" +
                "两台设备各自只写自己的云端文件，不需要锁，也不需要手动选版本。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text(
            "照片不参与日常增量同步（只同步数据，换机后照片路径留空）。" +
                "要把照片一并搬过去，用上面百度网盘通道：「上传当前完整版本」→ 另一台「下载恢复」；" +
                "另一台恢复成功后可以删掉网盘上的那份副本。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun formatFileSize(bytes: Long): String = when {
    bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

@Composable
private fun EnglishReviewSection(
    viewModel: SettingsViewModel,
    prefs: com.example.lixing.data.prefs.UserPreferences,
) {
    SliderRow(
        label = "每日新学上限",
        value = "${prefs.englishDailyNewLimit} 条",
        subtitle = "每天最多新学这么多单词/短语；到期需要复习的不受此限制",
        sliderValue = prefs.englishDailyNewLimit.toFloat(),
        range = 5f..100f,
        steps = 18,
        onChange = { viewModel.setEnglishDailyNewLimit(it.toInt()) },
    )
}

@Composable
private fun UpdateSection(
    viewModel: SettingsViewModel,
    prefs: com.example.lixing.data.prefs.UserPreferences,
) {
    val state by viewModel.updateState.collectAsStateWithLifecycle()

    SwitchRow(
        label = "启动时自动检查更新",
        subtitle = "每天最多检查一次；只在 Wi-Fi 下检查（受下方开关控制）",
        checked = prefs.updateAutoEnabled,
        onCheckedChange = viewModel::setUpdateAutoEnabled,
    )
    SwitchRow(
        label = "仅在 Wi-Fi 下自动检查",
        subtitle = "关闭后任何网络都会自动检查；手动「立即检查」不受此开关限制",
        checked = prefs.updateWifiOnly,
        onCheckedChange = viewModel::setUpdateWifiOnly,
    )

    Spacer(Modifier.height(8.dp))
    Text(
        text = "当前版本：${com.example.lixing.BuildConfig.VERSION_NAME} (${com.example.lixing.BuildConfig.VERSION_CODE})",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(Modifier.height(8.dp))
    when (val s = state) {
        AppUpdateController.State.Idle -> {
            OutlinedButton(onClick = viewModel::checkUpdateNow) {
                Text("立即检查")
            }
        }
        is AppUpdateController.State.Checking -> {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("正在检查最新版本…", style = MaterialTheme.typography.bodyMedium)
            }
        }
        AppUpdateController.State.UpToDate -> {
            Text(
                "✓ 已是最新版本",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = viewModel::checkUpdateNow) {
                Text("再检查一次")
            }
        }
        is AppUpdateController.State.Available -> {
            val sizeText = if (s.manifest.sizeBytes > 0) formatFileSize(s.manifest.sizeBytes) else "未知大小"
            Text(
                "发现新版本 ${s.manifest.versionName}（$sizeText）",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (s.manifest.changelog.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    s.manifest.changelog,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = viewModel::startDownload) {
                Text(if (s.force) "立即更新" else "下载并安装")
            }
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = viewModel::acknowledgeUpdate) { Text("稍后再说") }
        }
        is AppUpdateController.State.Downloading -> {
            Text("下载中…（${s.manifest.versionName}）", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "下载由系统 DownloadManager 接管，系统通知栏有进度。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        is AppUpdateController.State.Verifying -> {
            Text("下载完成，正在校验完整性…", style = MaterialTheme.typography.bodyMedium)
        }
        is AppUpdateController.State.ReadyToInstall -> {
            Text("已就绪（${s.manifest.versionName}）", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            Button(onClick = viewModel::requestInstall) { Text("立即安装") }
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = viewModel::openInstallPermissionSettings) {
                Text("授权「安装未知应用」")
            }
        }
        is AppUpdateController.State.Failed -> {
            Text(
                "检查或下载失败：${s.reason}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = viewModel::checkUpdateNow) { Text("重试") }
        }
    }
}

@Composable
private fun ExportSection(viewModel: SettingsViewModel) {
    val message by viewModel.exportMessage.collectAsStateWithLifecycle()
    val versions by viewModel.backupVersions.collectAsStateWithLifecycle()
    val pendingRestore by viewModel.pendingRestore.collectAsStateWithLifecycle()
    val busy by viewModel.backupBusy.collectAsStateWithLifecycle()
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri -> uri?.let(viewModel::exportVersion) }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(viewModel::inspectRestore) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = viewModel::exportTasksCsv,
            shape = LiXingRadius.Pill,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("导出打卡数据（CSV）") }

        OutlinedButton(
            onClick = viewModel::exportPlanJson,
            shape = LiXingRadius.Pill,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("导出计划（JSON）") }

        OutlinedButton(
            onClick = viewModel::backup,
            enabled = !busy,
            shape = LiXingRadius.Pill,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("保存本机版本") }

        OutlinedButton(
            onClick = { exportLauncher.launch(viewModel.suggestedBackupFileName()) },
            enabled = !busy,
            shape = LiXingRadius.Pill,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("导出版本包（网盘 / 文件）") }

        OutlinedButton(
            onClick = { importLauncher.launch(arrayOf("application/octet-stream", "application/zip", "*/*")) },
            enabled = !busy,
            shape = LiXingRadius.Pill,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("从版本包恢复") }

        Text(
            "恢复会替换当前数据，但会先自动保存恢复点。版本包暂未加密，且不会包含墨墨 Token。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (versions.isNotEmpty()) {
            Text("本机最近版本", style = MaterialTheme.typography.labelLarge)
            versions.take(3).forEach { version ->
                Text(
                    "${version.displayTime()} · ${version.taskCount} 项任务 · ${version.photoCount} 张照片",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        message?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    pendingRestore?.let { inspected ->
        val version = inspected.version
        AlertDialog(
            onDismissRequest = viewModel::cancelRestore,
            title = { Text("确认恢复此版本？") },
            text = {
                Text(
                    "时间：${version.displayTime()}\n" +
                        "设备：${version.deviceName}\n" +
                        "计划：${version.planName ?: "无"}\n" +
                        "任务：${version.taskCount} 项，照片：${version.photoCount} 张\n\n" +
                        "当前数据将被替换，恢复前会自动创建本机恢复点。",
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmRestore) { Text("确认恢复") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelRestore) { Text("取消") }
            },
        )
    }
}

@Composable
private fun SwitchRow(
    label: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
/** AI assistant settings: encrypted model profiles with instant switching. */
@Composable
private fun AiAssistantSection(viewModel: SettingsViewModel, prefs: com.example.lixing.data.prefs.UserPreferences) {
    val message by viewModel.aiMessage.collectAsStateWithLifecycle()
    val testing by viewModel.aiTesting.collectAsStateWithLifecycle()
    val profiles by viewModel.aiProfiles.collectAsStateWithLifecycle()
    val activeId by viewModel.activeAiProfileId.collectAsStateWithLifecycle()
    val models by viewModel.aiModels.collectAsStateWithLifecycle()
    val modelsBusy by viewModel.aiModelsBusy.collectAsStateWithLifecycle()
    var editorOpen by remember { mutableStateOf(false) }
    var editingId by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<AiModelProfile?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SwitchRow(
            label = "启用 AI 学习助手",
            subtitle = "只在你主动提问时联网，不会后台上传数据",
            checked = prefs.aiAssistantEnabled,
            onCheckedChange = viewModel::setAiAssistantEnabled,
        )

        if (prefs.aiAssistantEnabled) {
            Text(
                text = "可保存多家 OpenAI 兼容模型。每个配置的 API 密钥均由本机密钥库加密，且不进入版本备份。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SwitchRow(
                label = "启用联网搜索",
                subtitle = "模型自主判断是否联网，由服务端执行搜索",
                checked = prefs.aiWebSearchEnabled,
                onCheckedChange = viewModel::setAiWebSearchEnabled,
            )
            SliderRow(
                label = "长回答自动续写",
                value = if (prefs.assistantAutoContinue == 0) "关闭" else "最多 ${prefs.assistantAutoContinue} 段",
                subtitle = "回答撞到模型输出上限时自动接着写；正常几段内会写完，这里只是保护上限",
                sliderValue = prefs.assistantAutoContinue.toFloat(),
                range = 0f..8f,
                steps = 7,
                onChange = { viewModel.setAssistantAutoContinue(it.toInt()) },
            )

            if (profiles.isEmpty()) {
                Text(
                    "还没有模型配置",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                profiles.forEach { profile ->
                    val active = profile.id == activeId
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(LiXingRadius.Tile)
                            .background(
                                if (active) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceContainerLow,
                            )
                            .clickable(enabled = !testing) { viewModel.selectAiProfile(profile.id) }
                            .padding(start = 4.dp, end = 2.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = active,
                            onClick = { viewModel.selectAiProfile(profile.id) },
                            enabled = !testing,
                        )
                        Column(Modifier.weight(1f)) {
                            Text(profile.name, fontWeight = FontWeight.SemiBold)
                            Text(
                                buildString {
                                    append(profile.model)
                                    append(if (profile.visionEnabled) " · 可看图" else " · 文字模型")
                                    append(
                                        when (profile.searchProtocol) {
                                            AiSearchProtocol.RESPONSES -> " · 联网：Responses"
                                            AiSearchProtocol.CHAT_COMPLETIONS -> " · 联网：ChatCompletions"
                                            AiSearchProtocol.OFF -> " · 不联网"
                                        },
                                    )
                                    append(
                                        when (profile.reasoningEffort) {
                                            AiReasoningEffort.LOW -> " · 低思考"
                                            AiReasoningEffort.MEDIUM -> " · 中思考"
                                            AiReasoningEffort.HIGH -> " · 高思考"
                                        },
                                    )
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                        IconButton(
                            onClick = { viewModel.clearAiModels(); editingId = profile.id; editorOpen = true },
                            enabled = !testing,
                        ) {
                            Icon(Icons.Filled.Edit, contentDescription = "编辑模型配置")
                        }
                        IconButton(
                            onClick = { pendingDelete = profile },
                            enabled = !testing,
                        ) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = "删除模型配置",
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { viewModel.clearAiModels(); editingId = null; editorOpen = true },
                    enabled = !testing,
                    shape = LiXingRadius.Pill,
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("添加模型")
                }
                if (activeId != null) {
                    OutlinedButton(
                        onClick = viewModel::testActiveAiProfile,
                        enabled = !testing,
                        shape = LiXingRadius.Pill,
                    ) { Text(if (testing) "测试中…" else "测试当前连接") }
                }
            }

            val questionVisionId by viewModel.questionVisionProfileId.collectAsStateWithLifecycle()
            val mealVisionId by viewModel.mealVisionProfileId.collectAsStateWithLifecycle()
            VisionProfilePicker(
                label = "拍题识题模型（多模态）",
                noneLabel = "不指定（本地 OCR 兜底）",
                subtitle = "当前模型为纯文本模型时，先由该模型转写照片题目再作答；不指定时用离线本地 OCR。",
                profiles = profiles,
                currentId = questionVisionId,
                onSelect = viewModel::setQuestionVisionProfile,
            )
            VisionProfilePicker(
                label = "饮食校准模型（多模态）",
                noneLabel = "不指定（端侧模型）",
                subtitle = "饮食拍照后由该模型校准名称、克重与营养；不指定或失败时用端侧模型。",
                profiles = profiles,
                currentId = mealVisionId,
                onSelect = viewModel::setMealVisionProfile,
            )

            message?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (editorOpen) {
        val profile = profiles.firstOrNull { it.id == editingId }
        AiProfileEditorDialog(
            profile = profile,
            testing = testing,
            models = models,
            modelsBusy = modelsBusy,
            fetchMessage = message,
            onFetchModels = viewModel::fetchAiModels,
            onModelsInvalidated = viewModel::clearAiModels,
            onDismiss = { editorOpen = false },
            onSave = { name, baseUrl, model, apiKey, visionEnabled, searchProtocol, reasoningEffort ->
                viewModel.saveAndTestAiProfile(
                    id = profile?.id,
                    name = name,
                    baseUrl = baseUrl,
                    model = model,
                    apiKey = apiKey,
                    visionEnabled = visionEnabled,
                    searchProtocol = searchProtocol,
                    reasoningEffort = reasoningEffort,
                )
                editorOpen = false
            },
        )
    }

    pendingDelete?.let { profile ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除模型配置？") },
            text = { Text("将删除「${profile.name}」及其本机 API 密钥。") },
            confirmButton = {
                TextButton(onClick = { viewModel.deleteAiProfile(profile.id); pendingDelete = null }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun AiProfileEditorDialog(
    profile: AiModelProfile?,
    testing: Boolean,
    models: List<String>,
    modelsBusy: Boolean,
    fetchMessage: String?,
    onFetchModels: (String, String) -> Unit,
    onModelsInvalidated: () -> Unit,
    onDismiss: () -> Unit,
    onSave: (String, String, String, String, Boolean, AiSearchProtocol, AiReasoningEffort) -> Unit,
) {
    var name by remember(profile?.id) { mutableStateOf(profile?.name.orEmpty()) }
    var baseUrl by remember(profile?.id) { mutableStateOf(profile?.baseUrl.orEmpty()) }
    var model by remember(profile?.id) { mutableStateOf(profile?.model.orEmpty()) }
    var apiKey by remember(profile?.id) { mutableStateOf("") }
    var visionEnabled by remember(profile?.id) { mutableStateOf(profile?.visionEnabled ?: false) }
    var searchProtocol by remember(profile?.id) {
        mutableStateOf(profile?.searchProtocol ?: AiSearchProtocol.RESPONSES)
    }
    var reasoningEffort by remember(profile?.id) {
        mutableStateOf(profile?.reasoningEffort ?: AiReasoningEffort.LOW)
    }
    var modelMenuOpen by remember(profile?.id) { mutableStateOf(false) }
    var error by remember(profile?.id) { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (profile == null) "添加模型" else "编辑模型") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it; error = null; onModelsInvalidated() },
                    label = { Text("接口地址") },
                    placeholder = { Text("如 https://api.deepseek.com") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it; error = null },
                    label = {
                        Text(if (profile?.hasApiKey == true) "API 密钥（留空则沿用）" else "API 密钥")
                    },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(
                    onClick = { onFetchModels(baseUrl, apiKey) },
                    enabled = !modelsBusy && baseUrl.isNotBlank() && apiKey.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (modelsBusy) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (modelsBusy) "正在获取模型…" else "获取模型列表")
                }
                fetchMessage?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Box(Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = model,
                        onValueChange = { model = it; error = null },
                        label = { Text("模型名") },
                        placeholder = { Text("选择或手动输入模型") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (models.isNotEmpty()) {
                        TextButton(
                            onClick = { modelMenuOpen = true },
                            modifier = Modifier.align(Alignment.CenterEnd),
                        ) { Text("选择") }
                        DropdownMenu(
                            expanded = modelMenuOpen,
                            onDismissRequest = { modelMenuOpen = false },
                            modifier = Modifier.heightIn(max = 320.dp),
                        ) {
                            val filtered = models.filter { model.isBlank() || it.contains(model, ignoreCase = true) }
                                .ifEmpty { models }
                            filtered.forEach { id ->
                                DropdownMenuItem(
                                    text = { Text(id) },
                                    onClick = { model = id; modelMenuOpen = false; error = null },
                                )
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; error = null },
                    label = { Text("配置名称") },
                    placeholder = { Text("如 DeepSeek / 视觉模型") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                SwitchRow(
                    label = "多模态模型（可看图）",
                    subtitle = "开启时直接发图；关闭时只发送本地 OCR 文字",
                    checked = visionEnabled,
                    onCheckedChange = { visionEnabled = it },
                )
                Text("联网搜索协议", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AiSearchProtocol.entries.forEach { protocol ->
                        FilterChip(
                            selected = searchProtocol == protocol,
                            onClick = { searchProtocol = protocol },
                            label = {
                                Text(
                                    when (protocol) {
                                        AiSearchProtocol.RESPONSES -> "Responses API"
                                        AiSearchProtocol.CHAT_COMPLETIONS -> "Chat Completions"
                                        AiSearchProtocol.OFF -> "关闭"
                                    },
                                )
                            },
                        )
                    }
                }
                Text(
                    text = "Responses API 适用于 DeepSeek 等服务端搜索；Chat Completions 适用于小米 MiMo 等注入 web_search 工具的服务。不选默认 Responses API。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("思考强度", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AiReasoningEffort.entries.forEach { effort ->
                        FilterChip(
                            selected = reasoningEffort == effort,
                            onClick = { reasoningEffort = effort },
                            label = {
                                Text(
                                    when (effort) {
                                        AiReasoningEffort.LOW -> "低"
                                        AiReasoningEffort.MEDIUM -> "中"
                                        AiReasoningEffort.HIGH -> "高"
                                    },
                                )
                            },
                        )
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !testing,
                onClick = {
                    error = when {
                        name.isBlank() -> "请填写配置名称"
                        baseUrl.isBlank() -> "请填写接口地址"
                        model.isBlank() -> "请填写模型名"
                        apiKey.isBlank() && profile?.hasApiKey != true -> "请填写 API 密钥"
                        else -> null
                    }
                    if (error == null) {
                        onSave(name, baseUrl, model, apiKey, visionEnabled, searchProtocol, reasoningEffort)
                    }
                },
            ) { Text("保存并测试") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun VisionProfilePicker(
    label: String,
    noneLabel: String,
    subtitle: String,
    profiles: List<AiModelProfile>,
    currentId: String?,
    onSelect: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val current = profiles.firstOrNull { it.id == currentId }
    Column(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        OutlinedButton(onClick = { expanded = true }, shape = LiXingRadius.Pill) {
            Text(current?.name ?: noneLabel, maxLines = 1)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text(noneLabel) }, onClick = { onSelect(null); expanded = false })
            profiles.filter { it.visionEnabled }.forEach { profile ->
                DropdownMenuItem(
                    text = { Text("${profile.name}（${profile.model}）") },
                    onClick = { onSelect(profile.id); expanded = false },
                )
            }
        }
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
