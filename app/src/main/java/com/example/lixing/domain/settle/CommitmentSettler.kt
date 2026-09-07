package com.example.lixing.domain.settle

import com.example.lixing.data.local.entity.CommitmentEntity
import com.example.lixing.domain.model.CommitmentStatus
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** 一次承诺结算的结果。 */
data class CommitmentOutcome(
    /** 已写好状态与实际完成率的承诺，调用方直接落库。 */
    val settled: CommitmentEntity,
    /** 应发奖励积分。未达成为 0。 */
    val rewardPoints: Int,
    /** 结算说明，用于结果提示。 */
    val summary: String,
) {
    val succeeded: Boolean get() = settled.status == CommitmentStatus.SUCCEEDED
    val customReward: String? get() = settled.customReward.takeIf { succeeded && it.isNotBlank() }
    /** 区间内没有可统计的学习日，作废而非失败。 */
    val voided: Boolean get() = settled.status == CommitmentStatus.ABANDONED
}

/** 进行中承诺的实时进度，给「我的」页卡片用。 */
data class CommitmentProgress(
    val elapsedDays: Int,
    val totalDays: Int,
    val daysLeft: Int,
    val currentRatePercent: Int,
    val targetRatePercent: Int,
    /** 还差几个百分点。已达标为 0。 */
    val gapPercent: Int,
    /** 区间内是否已有可统计的学习日。 */
    val hasData: Boolean,
) {
    val onTrack: Boolean get() = hasData && gapPercent == 0
    val isLastDay: Boolean get() = daysLeft == 0
}

/**
 * 自我承诺结算。
 *
 * 判定口径复用 `day_record.completion_rate` 的区间平均值（见 DayRecordDao.getAverageCompletionRate）：
 * 加权完成率、排除请假日、排除没排任务的日子。和连续记录的达成阈值互不干涉。
 *
 * - 平均完成率 ≥ 目标 → SUCCEEDED，发 rewardPoints；
 * - 低于目标 → FAILED，不发奖励也**不扣分**（扣分会让人不敢立约，而立约本身是好行为）；
 * - 区间内一天有效数据都没有（整段请假或没排任务）→ ABANDONED，判失败太冤。
 *
 * 百分比统一向下取整：显示 84% 就不会被判成达标 85%，用户看到的数和结论始终自洽。
 *
 * 纯函数：不碰数据库、不读时钟。
 */
object CommitmentSettler {

    /** 按任意立誓维度的整数实际值结算。 */
    fun settleValue(
        commitment: CommitmentEntity,
        actualValue: Int?,
        settledDate: LocalDate,
    ): CommitmentOutcome {
        if (actualValue == null) {
            return CommitmentOutcome(
                settled = commitment.copy(
                    status = CommitmentStatus.ABANDONED,
                    actualRatePercent = null,
                    settledDate = settledDate,
                ),
                rewardPoints = 0,
                summary = "区间内没有可统计数据，本次立誓作废。",
            )
        }
        val target = commitment.targetRatePercent
        val succeeded = actualValue >= target
        val unit = commitment.metric.unit
        return CommitmentOutcome(
            settled = commitment.copy(
                status = if (succeeded) CommitmentStatus.SUCCEEDED else CommitmentStatus.FAILED,
                actualRatePercent = actualValue,
                settledDate = settledDate,
            ),
            rewardPoints = if (succeeded) commitment.rewardPoints else 0,
            summary = if (succeeded) "目标 $target$unit，实际 $actualValue$unit，达成。"
            else "目标 $target$unit，实际 $actualValue$unit，还差 ${target - actualValue}$unit。",
        )
    }

    fun progressOfValue(
        commitment: CommitmentEntity,
        actualValue: Int?,
        today: LocalDate,
    ): CommitmentProgress {
        val total = daysBetweenInclusive(commitment.startDate, commitment.endDate)
        val elapsed = when {
            today < commitment.startDate -> 0
            today >= commitment.endDate -> total
            else -> daysBetweenInclusive(commitment.startDate, today)
        }
        val current = actualValue ?: 0
        return CommitmentProgress(
            elapsedDays = elapsed,
            totalDays = total,
            daysLeft = (total - elapsed).coerceAtLeast(0),
            currentRatePercent = current,
            targetRatePercent = commitment.targetRatePercent,
            gapPercent = (commitment.targetRatePercent - current).coerceAtLeast(0),
            hasData = actualValue != null,
        )
    }

    /**
     * @param commitment 待结算的承诺（应为 ACTIVE 且已过 endDate）
     * @param averageRate 区间内加权完成率均值 0f~1f，无有效学习日时为 null
     * @param settledDate 结算日期
     */
    fun settle(
        commitment: CommitmentEntity,
        averageRate: Float?,
        settledDate: LocalDate,
    ): CommitmentOutcome {
        if (averageRate == null) {
            return CommitmentOutcome(
                settled = commitment.copy(
                    status = CommitmentStatus.ABANDONED,
                    actualRatePercent = null,
                    settledDate = settledDate,
                ),
                rewardPoints = 0,
                summary = "区间内没有可统计的学习日，本次承诺作废。",
            )
        }

        val actual = toPercent(averageRate)
        val target = commitment.targetRatePercent
        val succeeded = actual >= target

        return CommitmentOutcome(
            settled = commitment.copy(
                status = if (succeeded) CommitmentStatus.SUCCEEDED else CommitmentStatus.FAILED,
                actualRatePercent = actual,
                settledDate = settledDate,
            ),
            rewardPoints = if (succeeded) commitment.rewardPoints else 0,
            summary = if (succeeded) {
                "目标 $target%，实际 $actual%，达成。"
            } else {
                "目标 $target%，实际 $actual%，差 ${target - actual} 个百分点。"
            },
        )
    }

    /**
     * 进行中承诺的实时进度。
     *
     * @param averageRate 从开始日到今天（含）的完成率均值，无数据为 null
     * @param today 今天。早于 startDate 时按未开始算（elapsed = 0）
     */
    fun progressOf(
        commitment: CommitmentEntity,
        averageRate: Float?,
        today: LocalDate,
    ): CommitmentProgress {
        val total = daysBetweenInclusive(commitment.startDate, commitment.endDate)
        val elapsed = when {
            today < commitment.startDate -> 0
            today >= commitment.endDate -> total
            else -> daysBetweenInclusive(commitment.startDate, today)
        }
        val current = averageRate?.let { toPercent(it) } ?: 0
        val target = commitment.targetRatePercent

        return CommitmentProgress(
            elapsedDays = elapsed,
            totalDays = total,
            daysLeft = (total - elapsed).coerceAtLeast(0),
            currentRatePercent = current,
            targetRatePercent = target,
            gapPercent = (target - current).coerceAtLeast(0),
            hasData = averageRate != null,
        )
    }

    /** 0f~1f → 百分比整数，向下取整并夹到 0~100。 */
    private fun toPercent(rate: Float): Int =
        (rate * 100).toInt().coerceIn(0, 100)

    /** 含首尾的天数，至少 1。 */
    private fun daysBetweenInclusive(from: LocalDate, to: LocalDate): Int =
        (ChronoUnit.DAYS.between(from, to).toInt() + 1).coerceAtLeast(1)
}
