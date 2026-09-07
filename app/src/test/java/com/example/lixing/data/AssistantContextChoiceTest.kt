package com.example.lixing.data

import com.example.lixing.data.assistant.parseContextKinds
import com.example.lixing.domain.assistant.AssistantContextKind
import com.example.lixing.ui.screen.assistant.inferAssistantContext
import com.example.lixing.ui.screen.assistant.requiredAssistantContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantContextChoiceTest {

    @Test
    fun `parses model context choice`() {
        val kinds = parseContextKinds("""{"needed":["PLAN","WORDS"]}""")
        assertEquals(setOf(AssistantContextKind.PLAN, AssistantContextKind.WORDS), kinds)
    }

    @Test
    fun `accepts lowercase and skips unknown kinds`() {
        val kinds = parseContextKinds("""{"needed":["stats","DELETE_DB","today"]}""")
        assertEquals(setOf(AssistantContextKind.STATS, AssistantContextKind.TODAY), kinds)
    }

    @Test
    fun `accepts json wrapped in a markdown fence`() {
        val kinds = parseContextKinds("""```json
            {"needed":["PLAN","TODAY"]}
            ```""".trimIndent())
        assertEquals(setOf(AssistantContextKind.PLAN, AssistantContextKind.TODAY), kinds)
    }

    @Test
    fun `empty or invalid replies fall back to nothing`() {
        assertTrue(parseContextKinds("""{"needed":[]}""").isEmpty())
        assertTrue(parseContextKinds("我选不出来").isEmpty())
        assertTrue(parseContextKinds("").isEmpty())
        assertTrue(parseContextKinds("""["PLAN"]""").isEmpty())
    }

    @Test
    fun `today leave request always includes today context`() {
        assertEquals(
            setOf(AssistantContextKind.TODAY),
            requiredAssistantContext("帮我今日请假，只保留背单词", emptySet()),
        )
        assertEquals(
            setOf(AssistantContextKind.PLAN, AssistantContextKind.TODAY),
            requiredAssistantContext("删掉今天其他任务", setOf(AssistantContextKind.PLAN)),
        )
    }

    @Test
    fun `unrelated request keeps selected contexts unchanged`() {
        val selected = setOf(AssistantContextKind.ENGLISH)
        assertEquals(selected, requiredAssistantContext("帮我写一篇英语短文", selected))
    }

    @Test
    fun `explicit local data questions are inferred without model routing`() {
        assertEquals(
            setOf(AssistantContextKind.PLAN),
            inferAssistantContext("给我看看当前计划"),
        )
        assertEquals(
            setOf(AssistantContextKind.TODAY),
            inferAssistantContext("我今天有什么任务？"),
        )
        assertEquals(
            setOf(AssistantContextKind.STATS),
            inferAssistantContext("分析我最近 7 天的薄弱科目"),
        )
        assertEquals(
            setOf(AssistantContextKind.ENGLISH),
            inferAssistantContext("用我的英语积累出一组小测验"),
        )
        assertEquals(
            setOf(AssistantContextKind.WORDS),
            inferAssistantContext("考考我今天背的单词"),
        )
        assertEquals(
            setOf(AssistantContextKind.STATS),
            inferAssistantContext("我最近学习得怎么样？"),
        )
    }
}
