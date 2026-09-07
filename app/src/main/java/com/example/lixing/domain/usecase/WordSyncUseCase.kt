package com.example.lixing.domain.usecase

import com.example.lixing.data.local.entity.DailyTaskEntity
import com.example.lixing.data.repository.TaskRepository
import com.example.lixing.domain.time.StudyClock
import com.example.lixing.domain.word.WordProgress
import com.example.lixing.domain.word.WordSource
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/** 一次同步的结果。 */
data class WordSyncResult(
    val progress: WordProgress,
    /** 被更新的任务数。 */
    val updatedTasks: Int,
    /** 本次同步带来的总积分。 */
    val pointsGained: Int,
)

/**
 * 把墨墨的今日进度同步进「今日单词任务」。
 *
 * 适配策略：三个背单词任务（早/午/晚三遍）各自的目标量代表一遍的量，
 * 墨墨给出的是「全天」的 finished/total。我们把全天完成比例 ratio 乘到
 * 每个任务的目标上，得到该任务的实际完成量，再走现有 [CheckInUseCase]，
 * 这样积分、连续记录、成就全部复用既有规则，不另起炉灶。
 *
 * 未连接时抛 [com.example.lixing.domain.word.WordSourceException]，UI 提示去设置里连接。
 */
@Singleton
class WordSyncUseCase @Inject constructor(
    private val wordSource: WordSource,
    private val taskRepository: TaskRepository,
    private val checkIn: CheckInUseCase,
) {
    /** 只查询不写入，用于今日页展示「墨墨今日 X/Y」。 */
    suspend fun peekProgress(): WordProgress = wordSource.todayProgress()

    /**
     * @param monotonic 只增不减：仅当墨墨比例算出的完成量高于任务现有完成量时才写入。
     *   自动同步必须走这个模式——否则墨墨接口延迟或离线学习导致比例回落时，
     *   会把用户已完成的打卡下调并撤销积分（[CheckInUseCase] 是绝对值覆盖语义）。
     *   手动「同步打卡」保持 false，行为与用户预期一致（以墨墨为准）。
     */
    suspend operator fun invoke(clock: StudyClock, monotonic: Boolean = false): WordSyncResult {
        val progress = wordSource.todayProgress()
        val today = clock.today()
        // 只同步「背单词」任务。不能只按 MEMORIZE 类型过滤——
        // 「作文背诵」「背大题」也是 MEMORIZE，会被误勾选。
        val tasks = taskRepository.getTasksOfDay(today)
            .filter { it.isWordTask() }

        if (tasks.isEmpty()) {
            return WordSyncResult(progress, 0, 0)
        }

        var updated = 0
        var points = 0
        val recordedMinutes = tasks.sumOf { it.focusedMinutes }
        tasks.forEach { task ->
            val planned = planSyncValue(
                targetValue = task.targetValue,
                currentValue = task.actualValue,
                ratio = progress.ratio,
                finished = progress.finished,
                monotonic = monotonic,
            ) ?: return@forEach
            val result = checkIn(task.id, planned, clock)
            if (result is CheckInResult.Success) {
                updated++
                points += result.pointsGained
            }
        }

        // 墨墨返回的是今日累计背诵时长；只写入尚未记录的增量，避免重复同步叠加。
        val deltaMinutes = (progress.studyMinutes - recordedMinutes).coerceAtLeast(0)
        if (deltaMinutes > 0) {
            val target = tasks.firstOrNull { it.status.isEngaged } ?: tasks.firstOrNull()
            target?.let { taskRepository.addFocusedMinutes(it.id, deltaMinutes) }
        }

        return WordSyncResult(progress, updated, points)
    }
}

/**
 * 计算某个「背单词」任务本次应写入的完成量；返回 null 表示跳过该任务。
 *
 * - [monotonic] 为 true 时，只有算出的量大于任务现有完成量才写入（绝不回退、不撤销积分）；
 * - 至少反映“有在学”：ratio>0 但取整为 0 时记 1，避免同步了却显示没做。
 */
internal fun planSyncValue(
    targetValue: Int,
    currentValue: Int,
    ratio: Float,
    finished: Int,
    monotonic: Boolean,
): Int? {
    val value = (ratio * targetValue).roundToInt()
    val effective = if (finished > 0 && value == 0) 1 else value
    if (monotonic && effective <= currentValue) return null
    return effective
}

/**
 * 判定是否为「背单词」任务。用标题匹配（种子模板为「背单词（第 N 遍)」），
 * 以区别于同为 MEMORIZE 的作文背诵 / 背大题。
 */
private fun DailyTaskEntity.isWordTask(): Boolean = title.contains("单词")
