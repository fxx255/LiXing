package com.example.lixing.domain.word

/**
 * 一天的单词学习进度。与具体数据源无关的领域模型。
 */
data class WordProgress(
    /** 已完成单词数。 */
    val finished: Int,
    /** 今日应完成单词数。 */
    val total: Int,
    /** 今日学习时长（毫秒）。 */
    val studyTimeMillis: Long,
) {
    val ratio: Float get() = if (total <= 0) 0f else (finished.toFloat() / total).coerceIn(0f, 1f)
    val isDone: Boolean get() = total > 0 && finished >= total
    val studyMinutes: Int get() = (studyTimeMillis / 60000).toInt()
}

/** 今日学习列表里的一个单词。 */
data class WordItem(
    val vocId: String,
    val spelling: String,
    val order: Int,
    val isNew: Boolean,
    val isFinished: Boolean,
)

/** 查词结果。 */
data class WordDetail(
    val id: String,
    val spelling: String,
)

/**
 * 单词数据源抽象 —— 这是为「智能体」预留的缝。
 *
 * 今天的实现是 [com.example.lixing.data.word.MaimemoWordSource]（墨墨背单词 OpenAPI）。
 * 未来的 AI 学习助手要「拿今日单词去出阅读题 / 做智能复习」时，只需要依赖这个接口，
 * 不需要关心数据来自墨墨还是别处；也可以新增一个 AgentWordSource 做装饰/聚合。
 *
 * 保持它纯领域、无 Android 依赖，方便在任意环境（含单测、未来的 agent 运行时）替换。
 */
interface WordSource {
    /** 数据源是否已配置（有可用凭证）。 */
    suspend fun isConfigured(): Boolean

    /** 今日学习进度。未连接/失败时抛 [WordSourceException]。 */
    suspend fun todayProgress(): WordProgress

    /**
     * 今日学习单词列表。
     * @param onlyUnfinished 只取未完成的
     * @param onlyNew 只取新学
     * @param limit 上限（墨墨单次最多 1000）
     */
    suspend fun todayWords(
        onlyUnfinished: Boolean = false,
        onlyNew: Boolean = false,
        limit: Int = 200,
    ): List<WordItem>

    /** 按拼写查词（供展示/校验/智能体取词）。 */
    suspend fun lookupBySpelling(spellings: List<String>): List<WordDetail>
}

/** 数据源异常。UI 层据此给出「未连接 / token 失效 / 网络错误」等提示。 */
class WordSourceException(
    val kind: Kind,
    message: String,
) : Exception(message) {
    enum class Kind { NOT_CONFIGURED, UNAUTHORIZED, NETWORK, RATE_LIMITED, UNKNOWN }
}
