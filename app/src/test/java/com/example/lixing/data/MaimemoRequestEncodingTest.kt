package com.example.lixing.data

import com.example.lixing.data.word.TodayItemsRequest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 墨墨服务端严格校验请求字段类型：可选字段为 null 时必须省略，
 * 不能写成显式 null（实测返回 400 "'is_finished' property type must be boolean"）。
 */
class MaimemoRequestEncodingTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    @Test
    fun `null optional fields are omitted from request body`() {
        val body = json.encodeToString(
            TodayItemsRequest.serializer(),
            TodayItemsRequest(isFinished = null, isNew = null, limit = 100),
        )
        assertTrue(body.contains("\"limit\":100"))
        assertFalse("null filters must be omitted, got: $body", body.contains("is_finished"))
        assertFalse("null filters must be omitted, got: $body", body.contains("is_new"))
    }

    @Test
    fun `provided filters are kept`() {
        val body = json.encodeToString(
            TodayItemsRequest.serializer(),
            TodayItemsRequest(isFinished = false, isNew = true, limit = 50),
        )
        assertTrue(body.contains("\"is_finished\":false"))
        assertTrue(body.contains("\"is_new\":true"))
    }
}