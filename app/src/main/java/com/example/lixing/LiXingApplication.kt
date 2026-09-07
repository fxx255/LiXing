package com.example.lixing

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.example.lixing.reminder.NotificationChannels
import com.example.lixing.reminder.ReminderScheduler
import com.example.lixing.data.sync.SyncRepository
import com.example.lixing.data.update.AppUpdateController
import com.example.lixing.worker.WorkerScheduler
import com.example.lixing.domain.meal.MealAnalyzer
import com.example.lixing.domain.meal.MealType
import ru.noties.jlatexmath.JLatexMathAndroid
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import java.io.File

/**
 * 应用入口。
 *
 * 启动时做三件事：
 * 1. 建通知渠道；
 * 2. 初始化 WorkManager（走 Hilt 的 [HiltWorkerFactory]，让 Worker 能注入依赖）；
 * 3. 注册每日 Worker + 排好最近的时段提醒。
 */
@HiltAndroidApp
class LiXingApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var workerScheduler: WorkerScheduler

    @Inject
    lateinit var reminderScheduler: ReminderScheduler

    @Inject
    lateinit var mealAnalyzer: MealAnalyzer

    @Inject
    lateinit var syncRepository: SyncRepository

    @Inject
    lateinit var appUpdateController: AppUpdateController

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(if (BuildConfig.DEBUG) android.util.Log.DEBUG else android.util.Log.WARN)
            .build()

    override fun onCreate() {
        super.onCreate()

        NotificationChannels.createAll(this)

        // JLatexMath（Android 版）把公式设置/字体放在 assets，静态初始化需要先 init(Context)。
        // 不 init 的话 TeXFormula 类初始化直接失败，所有公式都会走占位符兜底。
        JLatexMathAndroid.init(this)

        workerScheduler.scheduleDailyWorker()

        // 首次启动可能还没有计划，scheduleUpcoming 里会自动跳过
        appScope.launch {
            reminderScheduler.scheduleUpcoming()
        }

        // 多端同步：应用启动后跑第一次同步；之后监听本地变更，操作停止 30 秒后自动同步。
        // 循环内部自己检查「已连接 / 自动开关 / 仅 Wi-Fi」，未配置时只是空转轮询。
        syncRepository.startMonitoring(appScope)

        // 应用内更新：启动时静默检查一次（受「仅 Wi-Fi」「每天最多一次」「用户总开关」三重节流）。
        // startSilentCheck 自己内部用独立 Controller scope 启动协程，这里直接调用即可。
        appUpdateController.startSilentCheck(manual = false)

        runMealModelSmokeTestIfRequested()
    }

    /**
     * 仅调试包使用的一次性真机自检。通过 `run-as ... touch cache/meal_model_smoke_test` 触发，
     * 与拍照功能走同一个 [MealAnalyzer]；标记读取后立即删除，不影响正常启动。
     */
    private fun runMealModelSmokeTestIfRequested() {
        if (!BuildConfig.DEBUG) return
        val marker = File(cacheDir, "meal_model_smoke_test")
        if (!marker.exists()) return
        marker.delete()
        appScope.launch {
            val photo = File(cacheDir, "meal_model_smoke_input.jpg")
            runCatching {
                val bitmap = android.graphics.Bitmap.createBitmap(224, 224, android.graphics.Bitmap.Config.ARGB_8888)
                    .apply { eraseColor(android.graphics.Color.rgb(180, 120, 70)) }
                photo.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }
                val result = mealAnalyzer.analyze(photo.absolutePath, MealType.BREAKFAST)
                android.util.Log.i(
                    "MealModelSmoke",
                    "SUCCESS label=${result.modelLabel} confidence=${result.confidence}",
                )
            }.onFailure { error ->
                android.util.Log.e("MealModelSmoke", "FAILED", error)
            }
            photo.delete()
        }
    }
}
