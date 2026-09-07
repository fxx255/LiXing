package com.example.lixing.data

import com.example.lixing.data.word.StudyProgressResponse
import com.example.lixing.data.word.TodayItemsResponse
import com.example.lixing.data.word.VocabularyQueryResponse
import com.example.lixing.domain.word.WordProgress
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用 open.maimemo.com/api_bundle.yaml 里的示例字段构造 JSON，
 * 验证 DTO 的字段名/序列化与官方 schema 对齐，防止联调时才发现错位。
 */
class MaimemoDtoTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `parse study progress with data envelope`() {
        // 真实返回形态：payload 包在 data 里
        val raw = """{"errors":[],"data":{"progress":{"finished":200,"total":200,"study_time":3357192}},"success":true}"""
        val dto = json.decodeFromString<StudyProgressResponse>(raw)
        val p0 = dto.effective!!
        assertEquals(200, p0.finished)
        assertEquals(200, p0.total)
        assertEquals(3357192L, p0.studyTime)

        val p = WordProgress(p0.finished, p0.total, p0.studyTime)
        assertTrue(p.isDone)
        assertEquals(55, p.studyMinutes)
    }

    @Test
    fun `parse study progress top-level fallback`() {
        val raw = """{"progress":{"finished":10,"total":20,"study_time":114514}}"""
        val dto = json.decodeFromString<StudyProgressResponse>(raw)
        val p0 = dto.effective!!
        assertEquals(10, p0.finished)
        assertEquals(20, p0.total)

        val p = WordProgress(p0.finished, p0.total, p0.studyTime)
        assertEquals(0.5f, p.ratio, 0.01f)
        assertFalse(p.isDone)
    }

    @Test
    fun `progress done when finished ge total`() {
        val p = WordProgress(20, 20, 0)
        assertTrue(p.isDone)
        assertEquals(1f, p.ratio, 0.001f)
    }

    @Test
    fun `progress zero total safe`() {
        val p = WordProgress(0, 0, 0)
        assertEquals(0f, p.ratio, 0.001f)
        assertFalse(p.isDone)
    }

    @Test
    fun `parse today items`() {
        val raw = """{"data":{"today_items":[
            {"voc_id":"v1","voc_spelling":"apple","order":1,"is_new":true,"is_finished":false},
            {"voc_id":"v2","voc_spelling":"world","order":2,"is_new":false,"is_finished":true}
        ]},"success":true}"""
        val dto = json.decodeFromString<TodayItemsResponse>(raw)
        assertEquals(2, dto.effective.size)
        assertEquals("apple", dto.effective[0].vocSpelling)
        assertTrue(dto.effective[0].isNew)
        assertTrue(dto.effective[1].isFinished)
    }

    @Test
    fun `parse vocabulary query`() {
        val raw = """{"data":{"voc":[{"id":"abc","spelling":"apple"}]},"success":true}"""
        val dto = json.decodeFromString<VocabularyQueryResponse>(raw)
        assertEquals(1, dto.effective.size)
        assertEquals("apple", dto.effective[0].spelling)
    }

    @Test
    fun `tolerate unknown fields`() {
        val raw = """{"data":{"progress":{"finished":1,"total":2,"study_time":3,"future_field":"x"}},"success":true}"""
        val dto = json.decodeFromString<StudyProgressResponse>(raw)
        assertEquals(1, dto.effective!!.finished)
    }
}
