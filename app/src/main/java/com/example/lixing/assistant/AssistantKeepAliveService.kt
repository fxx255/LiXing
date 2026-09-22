package com.example.lixing.assistant

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import com.example.lixing.MainActivity
import com.example.lixing.R
import com.example.lixing.reminder.NotificationChannels
import com.example.lixing.ui.navigation.Routes
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AI 生成期间的进程保活契约。
 *
 * 背景：AI 请求跑在协程里，没有保活时 App 切后台/熄屏后进程降级为 cached，
 * 随后被系统冻结（Android 12+ Cached Apps Freezer）或被厂商 ROM 直接查杀，
 * SSE 流读取随之中断——已生成的回答全部丢失，表现为「熄屏后没有任何输出」。
 *
 * 生成期间启动前台服务抬高进程优先级可以显著降低冻结/查杀概率。
 */
interface AssistantGenerationGuard {
    /**
     * 按 **requestId 持有**一个保护令牌。
     *
     * 为什么不用简单的引用计数：任务结束与另一个开始交错时（A 结束的
     * `release` 恰好排在 B 的 `acquire` 之后），计数实现会把 B 刚拿到的
     * 保护一起扣掉并停掉服务。按 id 持有则天然幂等：同一个 id 重复 acquire
     * 不增加保护，未持有过的 id release 不产生任何副作用。
     *
     * 返回令牌供 [release] 使用。
     *
     * [conversationId]：本轮生成所属会话。会带进前台服务，
     * 让通知的点击意图能**直接落回那一次生成所在的会话**
     * （否则用户点通知只回到助手页的默认对话，还得自己找）。
     */
    fun acquire(requestId: String, conversationId: String? = null): String

    /** 释放令牌；所有出口（成功/失败/取消）都必须调用。 */
    fun release(token: String)

    /** 当前受保护的请求数，供测试与诊断断言。 */
    fun activeCount(): Int

    /**
     * 旧的无标识保活（照片转写等短流程仍在使用）。
     *
     * 内部走一个**稳定的合成 requestId**，因此不会与真实生成任务的令牌互相干扰：
     * 照片转写结束时不会把正在跑的回答生成的保护一起扣掉。
     */
    fun begin()

    /** 与 [begin] 配对的释放。 */
    fun end()
}

/**
 * 按 requestId 持有令牌的前台服务保护。
 *
 * Android 版本差异都做了处理：
 * - Android 12+ 后台启动前台服务受限 → 启动失败时**不崩溃**，只是少了保活，
 *   任务仍有明确状态（日志 + 令牌立刻归还，不会把别人的保护扣掉）；
 * - Android 13 通知权限未授予 → 服务仍能起，通知可能不显示，不影响正确性；
 * - Android 14 要求声明服务类型 → 用 FOREGROUND_SERVICE_TYPE_DATA_SYNC；
 * - Android 15 dataSync 有超时 → 超时回调里停止服务；网络取消由任务自身负责。
 */
@Singleton
class ForegroundAssistantGenerationGuard @Inject constructor(
    @ApplicationContext private val context: Context,
) : AssistantGenerationGuard {

    /** requestId → 令牌。所有读写都在 [lock] 内进行。 */
    private val holders = ConcurrentHashMap<String, String>()

    /**
     * 保护「持有者集合变更 + 起/停服务」这一个**复合动作**的锁。
     *
     * 为什么 `ConcurrentHashMap` 不够：它只能保证单次操作原子，而真正的争用是
     * 「A 刚 putIfAbsent 但还没 startService」与「B 已 remove 并看到空集合、去
     * stopService」交错 —— 结果是 A 以为自己有保护，服务却被 B 停掉了
     * （生成期间失去前台优先级，容易被系统冻结）。
     *
     * 用同一把锁把「改集合」与「起/停服务」串行化，就不会出现这种中间态。
     * **旧 `begin()/end()` 的嵌套计数也必须在同一把锁内**（见 [begin]）。
     */
    private val lock = Any()

    /**
     * 旧 `begin()`/`end()` 接口的嵌套计数（照片链路会嵌套调用）。
     *
     * 与 [holders] 一样只在 [lock] 内读写：放在锁外时，
     * 「计数归零 → 去停服务」与「另一个 begin 把计数从 0 加到 1」会交错，
     * 于是刚要开始的保护被上一条链路停掉。
     */
    private var legacyHolds = 0

    /**
     * 最近一次带 [conversationId] 的持有。
     *
     * 旧接口 [begin] 不带会话：此时**沿用当前活动请求的会话**，
     * 而不是把通知的 contentIntent 退化成「没有会话」——
     * 照片转写（走旧接口）结束时的通知点击也应该能回到用户所在的会话。
     */
    @Volatile
    private var lastConversationId: String? = null

    override fun acquire(requestId: String, conversationId: String?): String {
        synchronized(lock) {
            if (requestId != LEGACY_HOLDER_ID && conversationId != null) {
                lastConversationId = conversationId
            }
            val token = "$requestId#${System.nanoTime()}"
            val previous = holders.putIfAbsent(requestId, token)
            // 同一 requestId 已经持有：返回既有令牌，不再叠加保护，也不重复起服务。
            if (previous != null) return previous
            // 启动失败时**不保留持有者**：否则 activeCount() 会虚报"已受保护"，
            // 调用方据此以为保活生效，实际没有 —— 这是「谎报保护」。
            if (!startServiceSafely(conversationId ?: lastConversationId)) {
                holders.remove(requestId, token)
                return token
            }
            return token
        }
    }

    override fun release(token: String) {
        synchronized(lock) {
            val requestId = token.substringBeforeLast('#')
            // 只有仍持有该令牌的调用者才有权释放；晚到的旧 release 不会误停服务。
            if (holders.remove(requestId, token) && holders.isEmpty()) {
                stopServiceSafely()
            }
        }
    }

    override fun activeCount(): Int = holders.size

    /**
     * 旧接口：用固定的合成 id 持有。照片转写与回答生成各持各的，
     * 谁结束都不会误停对方。
     *
     * 计数与集合变更在同一把锁内完成（早先在锁外自增，会与 [end] 的
     * 「归零即停服务」交错，把刚要开始的保护停掉）。
     */
    override fun begin() {
        synchronized(lock) {
            legacyHolds++
            if (legacyHolds == 1) {
                // acquire 内部会重新进入同一把锁（可重入），语义仍然正确。
                acquire(LEGACY_HOLDER_ID, lastConversationId)
            }
        }
    }

    override fun end() {
        synchronized(lock) {
            if (legacyHolds > 0) legacyHolds--
            if (legacyHolds != 0) return
            holders.remove(LEGACY_HOLDER_ID)?.let { if (holders.isEmpty()) stopServiceSafely() }
        }
    }

    /**
     * 启动保活服务。
     *
     * 返回**是否真的启动成功**：失败时调用方必须撤销持有者记录，
     * 不能对外虚报「已受保护」。保活本身是尽力而为，失败不抛异常、
     * 也不影响用户的提问。
     */
    private fun startServiceSafely(conversationId: String? = null): Boolean {
        val intent = AssistantKeepAliveService.intent(context, conversationId)
        return runCatching { context.startForegroundService(intent) }
            .onFailure { error ->
                android.util.Log.w("AssistantKeepAlive", "启动保活前台服务失败", error)
            }
            .isSuccess
    }

    private fun stopServiceSafely() {
        runCatching { context.stopService(Intent(context, AssistantKeepAliveService::class.java)) }
            .onFailure { android.util.Log.w("AssistantKeepAlive", "停止保活前台服务失败", it) }
    }

    companion object {
        /** 旧接口使用的合成持有者 id，不会与真实 requestId 冲突。 */
        private const val LEGACY_HOLDER_ID = "__legacy__"
    }
}

/**
 * AI 回答生成期间的常驻通知。
 *
 * 服务只负责让进程保持前台优先级，不承载生成逻辑（生成在应用级管理器里）。
 * `START_NOT_STICKY`：进程被杀后不由系统拉起 —— 恢复走持久记录的重试入口，
 * 绝不靠粘性重启假装续跑。
 *
 * ## 点击通知回到对应的会话
 *
 * [contentIntent] 指向助手页；额外带上会话 id，让用户在「切走 / 熄屏」后
 * 点通知能直接落回那一次生成所在的会话，而不是停在别的对话上。
 */
class AssistantKeepAliveService : Service() {

    @Inject
    lateinit var generationManager: AssistantGenerationManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // 服务由系统在进程存活时拉起（startForegroundService），此时 Hilt
        // 的 Application 已就绪；注入失败也不该让服务崩溃（保活是尽力而为）。
        runCatching {
            EntryPointAccessors.fromApplication(
                applicationContext,
                AssistantServiceEntryPoint::class.java,
            ).generationManager().also { generationManager = it }
        }.onFailure { Log.w(TAG, "注入生成管理器失败，超时回调将无法取消网络", it) }
        val notification = buildNotification(
            if (::generationManager.isInitialized) generationManager.activeConversationId() else null,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 会话变化时刷新通知（contentIntent 要指向当前这一轮）。
        runCatching { refreshNotification(intent?.getStringExtra(EXTRA_CONVERSATION_ID)) }
        return START_NOT_STICKY
    }

    /**
     * Android 15 起 dataSync 前台服务有运行时长上限，超时后系统调用这里。
     *
     * 重载签名必须是 **`onTimeout(startId: Int, fgsType: Int)`**：
     * Android 15 引入了双参数版本，单参数的 `onTimeout(startId)` 在
     * 那种情况下**不会被调用** —— 只实现单参数等于超时处理完全失效。
     *
     * 超时后要做三件事（缺一不可）：
     * 1. 真实取消进行中的网络请求（否则 SSE 还在读，用户以为已经停了）；
     * 2. **在 NonCancellable 里尽力保存中断状态**（下次启动才有恢复入口）；
     * 3. 停掉自己，不长期驻留。
     */
    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    override fun onTimeout(startId: Int, fgsType: Int) {
        super.onTimeout(startId, fgsType)
        handleTimeout()
    }

    /** 单参数版本：Android 15 之前（以及部分 ROM）走这条。 */
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onTimeout(startId: Int) {
        super.onTimeout(startId)
        handleTimeout()
    }

    /**
     * 系统超时的统一处理。
     *
     * 用 `goAsync` 式的独立作用域而不是 `serviceScope.cancel()`：
     * 我们需要**先把状态落盘**再退出，所以要一个不会被服务销毁打断的收尾窗口。
     */
    private fun handleTimeout() {
        Log.w(TAG, "前台服务超时：通知管理器中断活动请求并停止服务")
        val manager = if (::generationManager.isInitialized) generationManager else null
        if (manager == null) {
            stopSelf()
            return
        }
        // 在应用作用域里收尾：服务的生命周期已经不受我们控制，
        // 把取消与落盘交给管理器自己持有的 scope。
        manager.onForegroundServiceTimeout()
        stopSelf()
    }

    private fun refreshNotification(conversationId: String?) {
        val notification = buildNotification(conversationId)
        val manager = getSystemService(android.app.NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(conversationId: String?): Notification =
        NotificationCompat.Builder(this, NotificationChannels.CHANNEL_ASSISTANT)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.assistant_keepalive_title))
            .setContentText(getString(R.string.assistant_keepalive_text))
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(assistantContentIntent(conversationId))
            .build()

    /** 打开助手页的通知点击意图；带上会话 id 以便回到同一会话。 */
    private fun assistantContentIntent(conversationId: String?): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            // 用路由字符串打开助手页（与应用内导航保持一致）。
            putExtra(EXTRA_ROUTE, Routes.ASSISTANT)
            conversationId?.let { putExtra(EXTRA_CONVERSATION_ID, it) }
            // 从通知进来时不要复用旧任务的 intent（否则 extra 会被忽略）。
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        // 请求码按会话区分：不同会话的通知点击能各自落回对应会话。
        val requestCode = conversationId?.hashCode() ?: 0
        return PendingIntent.getActivity(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        private const val TAG = "AssistantKeepAlive"
        const val NOTIFICATION_ID = 2001

        /** 通知里携带的会话 id；助手页据此定位到对应会话。 */
        const val EXTRA_CONVERSATION_ID = "assistant_conversation_id"

        /** 通知里携带的目标路由。 */
        const val EXTRA_ROUTE = "assistant_route"

        /** 启动服务时带上会话 id，通知点击就能回到那一轮所在的会话。 */
        fun intent(context: Context, conversationId: String?): Intent =
            Intent(context, AssistantKeepAliveService::class.java).apply {
                conversationId?.let { putExtra(EXTRA_CONVERSATION_ID, it) }
            }
    }
}

/** 服务的 Hilt 入口点：Service 不能直接用字段注入（无 @AndroidEntryPoint 改造面）。 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface AssistantServiceEntryPoint {
    fun generationManager(): AssistantGenerationManager
}
