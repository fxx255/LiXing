package com.example.lixing.data.sync.maimemo

import com.example.lixing.data.prefs.UserPreferencesRepository
import com.example.lixing.domain.time.StudyClock
import com.example.lixing.domain.usecase.WordSyncUseCase
import com.example.lixing.domain.word.WordSource
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 墨墨进度跟随「多端同步」自动打卡的执行器。
 *
 * 为什么需要它：WebDAV 同步的是本机 Room 数据，「打卡结果」会跨设备传播，
 * 但「拉取墨墨进度并打卡」这个动作本身依赖每台设备自己的 Token，
 * 以前只能在今日页手动点「同步打卡」。开启自动同步后，这里搭顺风车自动执行。
 *
 * 三道门禁，缺一不可：
 * 1. 开关：墨墨已启用 + 用户明确开启了「自动同步墨墨」（默认关闭，自动打卡有数据写入必须由用户授权）；
 * 2. Token：本机已配置墨墨 Token（Token 不进备份、不同步，没配置就静默跳过）；
 * 3. 节流：两次执行至少相隔 [MIN_INTERVAL_MILLIS]，避免频繁调用墨墨接口
 *    （[CheckInUseCase] 每次打卡都会给「累计打卡次数」+1，不加节流会污染统计）。
 *
 * 写入策略是「只增不减」（见 [WordSyncUseCase.invoke] 的 monotonic 参数）：
 * 墨墨完成比例上升才推进任务，绝不撤销用户已有的打卡与积分。
 */
interface MaimemoAutoSyncer {

    /**
     * 条件满足时执行一次墨墨同步。
     * @return 给用户的补充提示；null 表示未执行（被门禁跳过）或本次没有推进任何任务。
     */
    suspend fun syncIfDue(nowMillis: Long = System.currentTimeMillis()): String?
}

@Singleton
class DefaultMaimemoAutoSyncer @Inject constructor(
    private val prefsRepository: UserPreferencesRepository,
    private val wordSource: WordSource,
    private val wordSync: WordSyncUseCase,
) : MaimemoAutoSyncer {

    override suspend fun syncIfDue(nowMillis: Long): String? {
        val prefs = prefsRepository.current()
        if (!prefs.maimemoEnabled || !prefs.maimemoAutoSync) return null
        if (prefs.maimemoToken.isBlank()) return null
        val last = prefsRepository.maimemoLastSyncAt()
        if (shouldThrottle(nowMillis, last)) return null

        return try {
            if (!wordSource.isConfigured()) return null
            val result = wordSync(StudyClock(dayStart = prefs.dayStartTime), monotonic = true)
            // 无论是否推进任务都记一次时间：比例没变化时不必每次同步都打接口。
            prefsRepository.markMaimemoSynced(nowMillis)
            if (result.updatedTasks > 0) {
                "；墨墨已同步 ${result.updatedTasks} 个单词任务，+${result.pointsGained} 分"
            } else {
                null
            }
        } catch (e: Exception) {
            // 墨墨失败不影响 WebDAV 同步结果：只记日志，下次到点再试。
            android.util.Log.w("MaimemoAutoSync", "墨墨自动同步失败", e)
            null
        }
    }
}

/** 距上次执行不足 [MIN_INTERVAL_MILLIS] 时应跳过；[lastSyncAtMillis] 为 0 表示从未执行过。 */
internal fun shouldThrottle(nowMillis: Long, lastSyncAtMillis: Long): Boolean =
    lastSyncAtMillis > 0L && nowMillis - lastSyncAtMillis < MIN_INTERVAL_MILLIS

/** 两次自动同步墨墨的最小间隔：15 分钟。 */
internal const val MIN_INTERVAL_MILLIS = 15 * 60 * 1000L
