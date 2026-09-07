package com.example.lixing.data.update

import com.example.lixing.BuildConfig
import com.example.lixing.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 拉取并解析云端版本清单。
 *
 * 网络层：单独建一个 OkHttpClient 而不是复用 [com.example.lixing.data.word.MaimemoWordSource] 那一个，
 * 因为版本清单请求必须走公共 DNS、且不允许走任何代理或自定义拦截器。
 * 失败策略：返回 [Result] 让调用方决定是否弹「检查失败」提示。
 */
@Singleton
class UpdateRepository @Inject constructor(
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false) // 节流场景下失败就跳过，不再额外重试
        .build()

    /**
     * 拉取一次清单。**不会**抛异常：网络错误、HTTP 4xx/5xx、JSON 反序列化失败统一变成 [Result.failure]。
     */
    suspend fun fetchManifest(): Result<UpdateManifest> = withContext(io) {
        val url = BuildConfig.UPDATE_CHECK_URL.trim()
        if (url.isBlank()) return@withContext Result.failure(IllegalStateException("UPDATE_CHECK_URL 未配置"))
        try {
            val req = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("User-Agent", "LiXing-Android/${BuildConfig.VERSION_NAME}")
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(IOException("HTTP ${resp.code}"))
                }
                val body = resp.body?.string()
                    ?: return@withContext Result.failure(IOException("空响应体"))
                runCatching { json.decodeFromString(UpdateManifest.serializer(), body) }
                    .onFailure { return@withContext Result.failure(IOException("清单解析失败: ${it.message}")) }
                    .let { Result.success(it.getOrThrow()) }
            }
        } catch (e: IOException) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
