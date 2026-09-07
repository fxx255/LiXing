package com.example.lixing.assistant

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.lixing.R
import com.example.lixing.reminder.NotificationChannels
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AI 生成期间的进程保活契约。
 *
 * 背景：AI 请求跑在 viewModelScope 协程里，没有任何保活。App 切后台/熄屏后进程降级为
 * cached，随后被系统冻结（Android 12+ Cached Apps Freezer）或被 MIUI 等厂商 ROM 直接查杀，
 * SSE 流读取随之中断——已生成的回答全部丢失，表现为「熄屏后没有任何输出」。
 *
 * 生成期间启动前台服务抬高进程优先级，可以显著降低冻结/查杀概率；
 * 生成结束（成功、失败、取消）时停止。
 */
interface AssistantGenerationGuard {
    fun begin()
    fun end()
}

/**
 * 引用计数实现：支持「照片链路」的嵌套调用
 * （performDirectPhotoSend 的转写/OCR 和后续 performPhotoSend 的回答各自 begin/end 一次），
 * 计数归零才真正停掉服务。
 */
@Singleton
class ForegroundAssistantGenerationGuard @Inject constructor(
    @ApplicationContext private val context: Context,
) : AssistantGenerationGuard {

    private val activeCount = AtomicInteger(0)

    override fun begin() {
        if (activeCount.getAndIncrement() == 0) {
            val intent = Intent(context, AssistantKeepAliveService::class.java)
            runCatching {
                context.startForegroundService(intent)
            }.onFailure { error ->
                // 个别 ROM 对后台启动前台服务有限制；失败不影响正常生成，只是少了保活。
                android.util.Log.w("AssistantKeepAlive", "启动保活前台服务失败", error)
                activeCount.decrementAndGet()
            }
        }
    }

    override fun end() {
        if (activeCount.updateAndGet { current -> (current - 1).coerceAtLeast(0) } == 0) {
            context.stopService(Intent(context, AssistantKeepAliveService::class.java))
        }
    }
}

/**
 * AI 回答生成期间的常驻通知。空壳服务：只负责让进程保持前台优先级，
 * 不承载任何逻辑；生成进度仍在 ViewModel 的流式回调里。
 */
class AssistantKeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, NotificationChannels.CHANNEL_ASSISTANT)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.assistant_keepalive_title))
            .setContentText(getString(R.string.assistant_keepalive_text))
            .setOngoing(true)
            .setSilent(true)
            .build()

    companion object {
        const val NOTIFICATION_ID = 2001
    }
}
