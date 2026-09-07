package com.example.lixing.domain.english

/**
 * 背诵待办数量快照。
 *
 * @property dueReview 今天到期、需要复习的卡片数（复习不限量）
 * @property newAvailable 今天还能新学的数量（= min(未学总数, 每日上限)）
 * @property newTotal 尚未学过的新卡总数（不受上限影响的真实库存）
 * @property reviewableTotal 参与背诵的条目总数（单词 + 短语）
 */
data class DueCounts(
    val dueReview: Int = 0,
    val newAvailable: Int = 0,
    val newTotal: Int = 0,
    val reviewableTotal: Int = 0,
) {
    /** 这一轮总共会刷多少张卡。 */
    val total: Int get() = dueReview + newAvailable
    val isEmpty: Boolean get() = total == 0
}
