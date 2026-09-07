package com.example.lixing.data.cloud

import org.junit.Assert.assertEquals
import org.junit.Test

class BaiduModelsTest {

    @Test
    fun refreshedTokenKeepsPreviousRefreshTokenWhenBaiduOmitsIt() {
        val credentials = BaiduTokenPayload(
            accessToken = "new-access",
            refreshToken = "",
            expiresIn = 3_600,
            scope = "basic netdisk",
        ).toCredentials(previousRefreshToken = "old-refresh", now = 1_000L)

        assertEquals("new-access", credentials.accessToken)
        assertEquals("old-refresh", credentials.refreshToken)
        assertEquals(3_601_000L, credentials.expiresAtEpochMillis)
    }

    @Test
    fun refreshedTokenUsesNewRefreshTokenWhenReturned() {
        val credentials = BaiduTokenPayload(
            accessToken = "new-access",
            refreshToken = "new-refresh",
            expiresIn = 3_600,
        ).toCredentials(previousRefreshToken = "old-refresh", now = 0L)

        assertEquals("new-refresh", credentials.refreshToken)
    }

    @Test
    fun suspiciouslyShortExpiryStillGetsFiveMinuteFloor() {
        val credentials = BaiduTokenPayload(
            accessToken = "access",
            refreshToken = "refresh",
            expiresIn = 0,
        ).toCredentials(now = 5_000L)

        assertEquals(305_000L, credentials.expiresAtEpochMillis)
    }
}
