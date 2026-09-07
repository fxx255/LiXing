package com.example.lixing.data.cloud

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class BaiduNetdiskState(
    val connected: Boolean = false,
    val busy: Boolean = false,
    val message: String? = null,
)

data class CloudBackup(
    val fsId: Long,
    val fileName: String,
    val remotePath: String,
    val sizeBytes: Long,
    val modifiedAtEpochSeconds: Long,
) {
    fun displayTime(): String = Instant.ofEpochSecond(modifiedAtEpochSeconds)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
}

internal data class BaiduCredentials(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtEpochMillis: Long,
    val scope: String,
)

@Serializable
internal data class StoredBaiduCredentials(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtEpochMillis: Long,
    val scope: String,
) {
    fun toCredentials() = BaiduCredentials(accessToken, refreshToken, expiresAtEpochMillis, scope)

    companion object {
        fun from(value: BaiduCredentials) = StoredBaiduCredentials(
            value.accessToken,
            value.refreshToken,
            value.expiresAtEpochMillis,
            value.scope,
        )
    }
}

@Serializable
internal data class TicketRequest(val ticket: String)

@Serializable
internal data class RefreshRequest(@SerialName("refresh_token") val refreshToken: String)

@Serializable
internal data class WorkerTokenEnvelope(
    val ok: Boolean = false,
    val token: BaiduTokenPayload? = null,
    val error: String? = null,
)

@Serializable
internal data class BaiduTokenPayload(
    @SerialName("access_token") val accessToken: String = "",
    @SerialName("refresh_token") val refreshToken: String = "",
    @SerialName("expires_in") val expiresIn: Long = 0,
    val scope: String = "",
) {
    fun toCredentials(previousRefreshToken: String = "", now: Long = System.currentTimeMillis()) =
        BaiduCredentials(
            accessToken = accessToken,
            refreshToken = refreshToken.ifBlank { previousRefreshToken },
            expiresAtEpochMillis = now + expiresIn.coerceAtLeast(300L) * 1_000L,
            scope = scope,
        )
}

@Serializable
internal data class BaiduListResponse(
    val errno: Int = 0,
    val list: List<BaiduFileItem> = emptyList(),
)

@Serializable
internal data class BaiduFileItem(
    @SerialName("fs_id") val fsId: Long,
    @SerialName("server_filename") val fileName: String,
    val path: String,
    val size: Long = 0,
    @SerialName("server_mtime") val modifiedAt: Long = 0,
    val isdir: Int = 0,
)

@Serializable
internal data class BaiduPrecreateResponse(
    val errno: Int = 0,
    val uploadid: String = "",
    @SerialName("return_type") val returnType: Int = 0,
)

@Serializable
internal data class BaiduMetaResponse(
    val errno: Int = 0,
    val list: List<BaiduMetaItem> = emptyList(),
)

@Serializable
internal data class BaiduMetaItem(
    @SerialName("fs_id") val fsId: Long,
    val dlink: String = "",
)
