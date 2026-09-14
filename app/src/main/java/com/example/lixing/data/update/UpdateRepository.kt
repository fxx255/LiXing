package com.example.lixing.data.update

import android.util.Log
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

private const val TAG = "UpdateRepository"

/** GitHub Release 里的清单附件：`latest` 永远指向最新版，发版无需改客户端。 */
internal const val MANIFEST_ASSET_URL =
    "https://github.com/fxx255/LiXing/releases/latest/download/update.json"

/**
 * 清单源列表：**云函数优先，其后是（经加速镜像）直取 GitHub 附件，最后才是直连**。
 *
 * 为什么必须有兜底：云函数跑在 CloudBase（上海），免费版单次执行上限 3 秒，而它回源
 * GitHub 常态 2~10 秒 ⇒ 网络稍慢就必然超时并返回 502
 * （2026-09-14 实测：函数对任意 versionCode 都回 `{"ok":false,...}` + HTTP 502）。
 * 手机端走加速镜像反而更稳（APK 本来就是这么下的），所以云函数失败后逐源回退。
 */
internal fun manifestSources(
    endpoint: String,
    assetUrl: String = MANIFEST_ASSET_URL,
): List<String> {
    val sources = mutableListOf<String>()
    if (endpoint.isNotBlank()) sources += endpoint.trim()
    sources += DownloadMirrors.MIRROR_PREFIXES.map { it + assetUrl }
    sources += assetUrl
    return sources
}

/**
 * 拉取并解析云端版本清单。
 *
 * 网络层：单独建一个 OkHttpClient 而不是复用 [com.example.lixing.data.word.MaimemoWordSource] 那一个，
 * 因为版本清单请求必须走公共 DNS、且不允许走任何代理或自定义拦截器。
 * 失败策略：返回 [Result] 让调用方决定是否弹「检查失败」提示；
 * 失败原因（逐源）写日志，**不把 HTTP 状态码之类的技术细节直接甩到界面上**。
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
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false) // 节流场景下失败就跳过，不再额外重试
        .build()

    /**
     * 拉取一次清单。**不会**抛异常：网络错误、HTTP 4xx/5xx、JSON 反序列化失败统一变成 [Result.failure]。
     *
     * 多源逐个尝试，第一个能解析出清单的源获胜；全部失败时返回「网络或服务暂时不可用」，
     * 逐源原因写进日志便于排查。
     */
    suspend fun fetchManifest(): Result<UpdateManifest> = withContext(io) {
        val errors = mutableListOf<String>()
        for (source in manifestSources(BuildConfig.UPDATE_CHECK_URL)) {
            fetchAndParse(source, errors)?.let { return@withContext Result.success(it) }
        }
        Log.w(TAG, "所有清单源均不可用：" + errors.joinToString(" | "))
        Result.failure(IOException("网络或服务暂时不可用，请稍后重试"))
    }

    /** 单源拉取 + 解析；失败把原因记进 [errors] 并返回 null。 */
    private fun fetchAndParse(url: String, errors: MutableList<String>): UpdateManifest? = try {
        val req = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "LiXing-Android/${BuildConfig.VERSION_NAME}")
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            when {
                !resp.isSuccessful -> {
                    errors += "$url → HTTP ${resp.code}"
                    null
                }
                else -> {
                    val body = resp.body?.string()
                    if (body.isNullOrBlank()) {
                        errors += "$url → 空响应体"
                        null
                    } else {
                        runCatching { json.decodeFromString(UpdateManifest.serializer(), body) }
                            .onFailure { errors += "$url → 解析失败 ${it.message}" }
                            .getOrNull()
                    }
                }
            }
        }
    } catch (e: Exception) {
        errors += "$url → ${e.message ?: e::class.java.simpleName}"
        null
    }
}
