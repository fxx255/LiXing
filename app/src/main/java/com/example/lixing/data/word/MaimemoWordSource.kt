package com.example.lixing.data.word

import com.example.lixing.data.prefs.UserPreferencesRepository
import com.example.lixing.di.IoDispatcher
import com.example.lixing.domain.word.WordDetail
import com.example.lixing.domain.word.WordItem
import com.example.lixing.domain.word.WordProgress
import com.example.lixing.domain.word.WordSource
import com.example.lixing.domain.word.WordSourceException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 墨墨背单词 OpenAPI 实现。
 *
 * 认证：用户在墨墨 App「我的 → 更多设置 → 实验功能 → 开放 API」获取个人 Token，
 * 放入 `Authorization: Bearer <token>`。无需 OAuth 流程。
 *
 * 线程：OkHttp 的 execute() 是阻塞调用，必须跑在 IO 调度器上，
 * 否则会抛 NetworkOnMainThreadException（ViewModel 默认在主线程调 suspend 方法）。
 */
@Singleton
class MaimemoWordSource @Inject constructor(
    private val prefsRepository: UserPreferencesRepository,
    @IoDispatcher private val io: CoroutineDispatcher,
) : WordSource {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        // 墨墨服务端严格校验字段类型：显式 null 会被拒绝（如 "'is_finished' property type must be boolean"），
        // 因此请求体中为 null 的可选字段必须直接省略。
        explicitNulls = false
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val baseUrl = "https://open.maimemo.com/open"
    private val mediaType = "application/json; charset=utf-8".toMediaType()

    override suspend fun isConfigured(): Boolean {
        val prefs = prefsRepository.current()
        return prefs.maimemoEnabled && prefs.maimemoToken.isNotBlank()
    }

    override suspend fun todayProgress(): WordProgress {
        val text = raw("/api/v1/memo/study/get_study_progress", "{}")
        val dto = try {
            json.decodeFromString<StudyProgressResponse>(text)
        } catch (e: Exception) {
            throw WordSourceException(
                WordSourceException.Kind.UNKNOWN,
                "解析响应失败，原始返回：${text.take(200)}",
            )
        }
        val p = dto.effective ?: throw WordSourceException(
            WordSourceException.Kind.UNKNOWN,
            "墨墨未返回今日进度，原始返回：${text.take(200)}",
        )
        return WordProgress(
            finished = p.finished,
            total = p.total,
            studyTimeMillis = p.studyTime,
        )
    }

    override suspend fun todayWords(
        onlyUnfinished: Boolean,
        onlyNew: Boolean,
        limit: Int,
    ): List<WordItem> {
        val body = TodayItemsRequest(
            isFinished = if (onlyUnfinished) false else null,
            isNew = if (onlyNew) true else null,
            limit = limit.coerceIn(1, 1000),
        )
        val dto = post<TodayItemsResponse>(
            "/api/v1/memo/study/get_today_items",
            json.encodeToString(TodayItemsRequest.serializer(), body),
        )
        return dto.effective.map {
            WordItem(
                vocId = it.vocId,
                spelling = it.vocSpelling,
                order = it.order,
                isNew = it.isNew,
                isFinished = it.isFinished,
            )
        }
    }

    override suspend fun lookupBySpelling(spellings: List<String>): List<WordDetail> {
        if (spellings.isEmpty()) return emptyList()
        val body = VocabularyQueryRequest(spellings = spellings.take(1000))
        val dto = post<VocabularyQueryResponse>(
            "/api/v1/memo/vocabulary/query",
            json.encodeToString(VocabularyQueryRequest.serializer(), body),
        )
        return dto.effective.map { WordDetail(id = it.id, spelling = it.spelling) }
    }

    /**
     * 通用 POST。整体切到 IO 调度器，带 Bearer Token，
     * 统一把 HTTP 错误映射成 [WordSourceException]。
     */
    private suspend inline fun <reified T> post(path: String, body: String): T {
        val text = raw(path, body)
        return try {
            json.decodeFromString<T>(text)
        } catch (e: Exception) {
            throw WordSourceException(
                WordSourceException.Kind.UNKNOWN,
                "解析响应失败，原始返回：${text.take(120)}",
            )
        }
    }

    /**
     * 网络层：整体切到 IO 调度器，带 Bearer Token，返回原始响应文本。
     * 非 2xx 统一映射成 [WordSourceException]。
     */
    private suspend fun raw(path: String, body: String): String = withContext(io) {
        val token = prefsRepository.current().maimemoToken
        if (token.isBlank()) {
            throw WordSourceException(
                WordSourceException.Kind.NOT_CONFIGURED,
                "尚未配置墨墨 Token",
            )
        }

        val request = Request.Builder()
            .url(baseUrl + path)
            .header("Accept", "application/json")
            .header("Authorization", "Bearer $token")
            .post(body.toRequestBody(mediaType))
            .build()

        val response = try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            throw WordSourceException(WordSourceException.Kind.NETWORK, "网络错误：${e.message}")
        }

        response.use { resp ->
            if (resp.isSuccessful) {
                return@withContext resp.body?.string().orEmpty()
            }

            val errBody = runCatching { resp.body?.string().orEmpty() }.getOrDefault("")
            when (resp.code) {
                401, 403 -> throw WordSourceException(
                    WordSourceException.Kind.UNAUTHORIZED,
                    "Token 无效或未授权(401)。请用墨墨账号重新获取 Token 后再试",
                )

                429 -> throw WordSourceException(
                    WordSourceException.Kind.RATE_LIMITED,
                    "请求过于频繁(429)，稍后再试",
                )

                else -> throw WordSourceException(
                    WordSourceException.Kind.UNKNOWN,
                    "服务返回 ${resp.code}" + (errBody.take(60).takeIf { it.isNotBlank() }?.let { "：$it" } ?: ""),
                )
            }
        }
    }
}
