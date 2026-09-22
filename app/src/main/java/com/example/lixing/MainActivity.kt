package com.example.lixing

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.lixing.assistant.AssistantKeepAliveService
import com.example.lixing.data.cloud.BaiduNetdiskRepository
import com.example.lixing.data.prefs.UserPreferences
import com.example.lixing.data.update.AppUpdateController
import com.example.lixing.ui.LiXingApp
import com.example.lixing.ui.navigation.Routes
import com.example.lixing.ui.theme.DarkModePref
import com.example.lixing.ui.theme.LiXingTheme
import com.example.lixing.ui.theme.ThemeSeed
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var prefsRepository: com.example.lixing.data.prefs.UserPreferencesRepository

    @Inject
    lateinit var planRepository: com.example.lixing.data.repository.PlanRepository

    @Inject
    lateinit var baiduNetdiskRepository: BaiduNetdiskRepository

    @Inject
    lateinit var appUpdateController: com.example.lixing.data.update.AppUpdateController

    @Inject lateinit var syncRepository: com.example.lixing.data.sync.SyncRepository

    override fun onStart() {
        super.onStart()
        syncRepository.onForeground()
    }

    override fun onStop() {
        syncRepository.onBackground()
        super.onStop()
    }

    /**
     * 通知点进来时要处理的**一次性导航请求**。
     *
     * 为什么不能只存一个 route 字符串：
     * 1. **同一 route 连续点两次不会触发**：Compose 的 `LaunchedEffect(route)`
     *    只在值**变化**时重跑，两次都是 `assistant` 时第二次什么都不会发生；
     * 2. **会话 id 被丢掉**：目标是「回到那次生成所在的会话」，
     *    只导航到助手页仍会停在默认会话上。
     *
     * 所以用一个带自增 [NotificationNavigation.id] 的事件：
     * id 每次都不同 ⇒ 即使 route 相同也会重新触发；并带上 conversationId。
     */
    data class NotificationNavigation(
        val id: Long,
        val route: String,
        val conversationId: String?,
    )

    private val notificationNavigation =
        androidx.compose.runtime.mutableStateOf<NotificationNavigation?>(null)
    private val notificationNavId = java.util.concurrent.atomic.AtomicLong(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // 冷启动场景：activity 是新建的，直接从 intent 取通知目标。
        notificationNavigation.value = navigationFromIntent(intent)
        setContent {
            val prefs by prefsRepository.preferences.collectAsStateWithLifecycle(
                initialValue = UserPreferences(),
            )

            // 定一次起点：完成过引导、或已经有计划，都直接进今日；
            // 只有「既没引导过、又没计划」的全新用户才进引导页。
            // 这样清后台重开的老用户不会再被拉回欢迎页。
            var start by remember { mutableStateOf<String?>(null) }
            androidx.compose.runtime.LaunchedEffect(prefs.onboardingDone) {
                if (start == null) {
                    val hasPlan = runCatching { planRepository.hasAnyPlan() }.getOrDefault(false)
                    start = if (prefs.onboardingDone || hasPlan) Routes.TODAY else Routes.ONBOARDING
                }
            }
            // 可观察的一次性导航事件：onNewIntent（应用在后台时点通知）
            // 也会更新它，即使是同一条 route 也能重新触发。
            val navigation by notificationNavigation

            LiXingTheme(
                seed = prefs.themeSeed,
                darkMode = prefs.darkMode,
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    start?.let {
                        LiXingApp(
                            startDestination = it,
                            // 通知点进来时直达对应页面与会话。
                            notificationNavigation = navigation,
                        )
                    }
                    UpdateDialogHost()
                }
            }
        }
        handleBaiduOAuthIntent(intent)
    }

    /**
     * onNewIntent：应用已在后台时点通知走这里。
     *
     * 必须更新 [notificationNavigation]，否则 `setIntent` 之后 Compose 里读到的
     * 仍是旧值 —— 用户点了通知却停在原页面。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleBaiduOAuthIntent(intent)
        navigationFromIntent(intent)?.let { notificationNavigation.value = it }
    }

    /**
     * 从 intent 解析通知目标；没有就返回 null。
     *
     * 只接受白名单路由（助手页），避免外部 intent 把应用导航到任意页面。
     */
    private fun navigationFromIntent(intent: Intent?): NotificationNavigation? {
        val raw = intent?.getStringExtra(AssistantKeepAliveService.EXTRA_ROUTE) ?: return null
        val route = raw.takeIf { it == Routes.ASSISTANT } ?: return null
        return NotificationNavigation(
            id = notificationNavId.incrementAndGet(),
            route = route,
            conversationId = intent.getStringExtra(AssistantKeepAliveService.EXTRA_CONVERSATION_ID),
        )
    }

    /**
     * 启动后首次出现的版本弹窗。设置页里可以重看/再触发；这里只对发现新版本弹一次。
     */
    @androidx.compose.runtime.Composable
    private fun UpdateDialogHost() {
        val state by appUpdateController.state.collectAsStateWithLifecycle()
        val s = state as? AppUpdateController.State.Available ?: return
        var dismissed by remember { mutableStateOf(false) }
        if (dismissed) return
        AlertDialog(
            onDismissRequest = { dismissed = true; appUpdateController.acknowledge() },
            title = { Text("发现新版本 ${s.manifest.versionName}") },
            text = {
                val sizeText = if (s.manifest.sizeBytes > 0) {
                    "%.1f MB".format(s.manifest.sizeBytes / (1024.0 * 1024.0))
                } else "未知大小"
                val parts = buildList {
                    add("新版本大小：$sizeText")
                    add("已通过 SHA-256 校验后才能安装")
                    if (s.manifest.changelog.isNotBlank()) add("\n${s.manifest.changelog}")
                }
                Text(parts.joinToString("\n"))
            },
            confirmButton = {
                TextButton(onClick = { appUpdateController.startDownload() }) {
                    Text(if (s.force) "立即更新" else "下载并安装")
                }
            },
            dismissButton = if (s.force) null else {
                {
                    TextButton(onClick = { dismissed = true; appUpdateController.acknowledge() }) {
                        Text("稍后再说")
                    }
                }
            },
        )
    }

    /**
     * 从外部相机/相册返回时解除方向锁定。
     *
     * 相机自己声明竖屏会把屏幕带偏，回来后由这里把方向交还给系统（跟随用户 + 传感器）。
     * 没打过相机的正常场景 ScreenOrientationGuard 未 armed，这里是空操作。
     */
    override fun onResume() {
        super.onResume()
        com.example.lixing.ui.util.ScreenOrientationGuard.releaseAfterExternalCapture(this)
    }

    private fun handleBaiduOAuthIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme != "lixing" || uri.host != "oauth" || uri.path != "/baidu") return
        val ticket = uri.getQueryParameter("ticket") ?: return
        lifecycleScope.launch {
            val success = baiduNetdiskRepository.completeAuthorization(ticket)
            Toast.makeText(
                this@MainActivity,
                if (success) "百度网盘连接成功" else "百度网盘连接失败，请到设置中重试",
                Toast.LENGTH_LONG,
            ).show()
        }
    }
}
