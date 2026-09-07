package com.example.lixing.data.word

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 墨墨 OpenAPI 的响应 DTO。
 *
 * 真实返回带统一信封：`{"errors":[], "data":{...payload...}, "success":true}`，
 * payload 在 `data` 里（与 open.maimemo.com 文档画的顶层略有出入，以实测为准）。
 * 为兼容两种形态，每个响应同时声明 `data` 包装与顶层字段，用 [effective] 取数。
 */

// ---------- get_study_progress ----------

@Serializable
data class StudyProgressResponse(
    val data: StudyProgressData? = null,
    val progress: StudyProgressDto? = null,
) {
    val effective: StudyProgressDto? get() = data?.progress ?: progress
}

@Serializable
data class StudyProgressData(
    val progress: StudyProgressDto? = null,
)

@Serializable
data class StudyProgressDto(
    /** 已完成单词数。 */
    val finished: Int = 0,
    /** 今日应完成单词数。 */
    val total: Int = 0,
    /** 今日学习时长（毫秒）。 */
    @SerialName("study_time")
    val studyTime: Long = 0,
)

// ---------- get_today_items ----------

@Serializable
data class TodayItemsRequest(
    @SerialName("is_finished")
    val isFinished: Boolean? = null,
    @SerialName("is_new")
    val isNew: Boolean? = null,
    val limit: Int? = null,
)

@Serializable
data class TodayItemsResponse(
    val data: TodayItemsData? = null,
    @SerialName("today_items")
    val todayItems: List<StudyTodayItemDto> = emptyList(),
) {
    val effective: List<StudyTodayItemDto> get() = data?.todayItems ?: todayItems
}

@Serializable
data class TodayItemsData(
    @SerialName("today_items")
    val todayItems: List<StudyTodayItemDto> = emptyList(),
)

@Serializable
data class StudyTodayItemDto(
    @SerialName("voc_id")
    val vocId: String = "",
    @SerialName("voc_spelling")
    val vocSpelling: String = "",
    val order: Int = 0,
    @SerialName("is_new")
    val isNew: Boolean = false,
    @SerialName("is_finished")
    val isFinished: Boolean = false,
)

// ---------- vocabulary/query ----------

@Serializable
data class VocabularyQueryRequest(
    val spellings: List<String>? = null,
    val ids: List<String>? = null,
)

@Serializable
data class VocabularyQueryResponse(
    val data: VocabularyData? = null,
    val voc: List<VocabularyDto> = emptyList(),
) {
    val effective: List<VocabularyDto> get() = data?.voc ?: voc
}

@Serializable
data class VocabularyData(
    val voc: List<VocabularyDto> = emptyList(),
)

@Serializable
data class VocabularyDto(
    val id: String = "",
    val spelling: String = "",
)
