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

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
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

            LiXingTheme(
                seed = prefs.themeSeed,
                darkMode = prefs.darkMode,
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    start?.let { LiXingApp(startDestination = it) }
                    UpdateDialogHost()
                }
            }
        }
        handleBaiduOAuthIntent(intent)
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleBaiduOAuthIntent(intent)
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
