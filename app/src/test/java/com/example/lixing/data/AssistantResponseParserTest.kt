package com.example.lixing.data

import com.example.lixing.data.assistant.AssistantResponseParser
import com.example.lixing.data.assistant.normalizeAssistantMarkdown
import com.example.lixing.domain.assistant.EnglishEntryAction
import com.example.lixing.domain.assistant.PlanAction
import com.example.lixing.domain.english.EnglishEntryType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantResponseParserTest {

    @Test
    fun `plain text becomes reply without actions`() {
        val parsed = AssistantResponseParser.parse("先把数学放上午试试，不满意再调。")
        assertEquals("先把数学放上午试试，不满意再调。", parsed.reply)
        assertTrue(parsed.actions.isEmpty())
        assertTrue(parsed.warnings.isEmpty())
    }

    @Test
    fun `structured reply parses all supported action kinds`() {
        val raw = """
            {
              "reply": "已给出建议",
              "plan_actions": [
                {"kind":"UPDATE_TIME_SLOT","slotId":3,"startTime":"08:00","endTime":"10:30","reason":"上午缩短"},
                {"kind":"UPDATE_TASK_TEMPLATE","templateId":7,"targetValue":90,"isEnabled":true,"reason":"提高目标"},
                {"kind":"INSERT_TASK_TEMPLATE","subjectId":2,"timeSlotId":3,"title":"错题复盘","taskType":"REVIEW","targetType":"MINUTES","targetValue":30,"repeatRule":"DAILY","isKeystone":false,"reason":"补上复盘"},
                {"kind":"UPDATE_TODAY_TASK","taskId":11,"targetValue":60,"timeSlotId":5,"reason":"今天减量并移到下午"},
                {"kind":"SKIP_TODAY_TASK","taskId":12,"reason":"今天不做"},
                {"kind":"TAKE_TODAY_OFF","reason":"今天休息"},
                {"kind":"KEEP_TODAY_TASKS","keepTaskIds":[11,12,12],"reason":"只保留背单词"}
              ]
            }
        """.trimIndent()

        val parsed = AssistantResponseParser.parse(raw)
        assertEquals("已给出建议", parsed.reply)
        assertEquals(7, parsed.actions.size)

        val slot = parsed.actions[0] as PlanAction.UpdateTimeSlot
        assertEquals("3", slot.slotId)
        assertEquals("08:00", slot.startTime.toString())

        val template = parsed.actions[1] as PlanAction.UpdateTaskTemplate
        assertEquals(90, template.targetValue)
        assertEquals(true, template.isEnabled)

        val insert = parsed.actions[2] as PlanAction.InsertTaskTemplate
        assertEquals("错题复盘", insert.title)
        assertEquals(30, insert.targetValue)

        val today = parsed.actions[3] as PlanAction.UpdateTodayTask
        assertEquals(60, today.targetValue)
        assertEquals("5", today.timeSlotId)

        val skipped = parsed.actions[4] as PlanAction.SkipTodayTask
        assertEquals("12", skipped.taskId)
        assertEquals("今天不做", skipped.reason)

        assertTrue(parsed.actions[5] is PlanAction.TakeTodayOff)
        val keep = parsed.actions[6] as PlanAction.KeepTodayTasks
        assertEquals(listOf("11", "12"), keep.keepTaskIds)
        assertTrue(parsed.warnings.isEmpty())
    }

    @Test
    fun `legacy today target action remains compatible`() {
        val parsed = AssistantResponseParser.parse(
            """{"reply":"兼容旧协议","plan_actions":[{"kind":"UPDATE_TODAY_TASK_TARGET","taskId":11,"targetValue":45}]}""",
        )

        val action = parsed.actions.single() as PlanAction.UpdateTodayTask
        assertEquals(45, action.targetValue)
        assertEquals(null, action.timeSlotId)
        assertTrue(parsed.warnings.isEmpty())
    }

    @Test
    fun `skip today task requires a valid task id`() {
        val parsed = AssistantResponseParser.parse(
            """{"reply":"x","plan_actions":[{"kind":"SKIP_TODAY_TASK","taskId":0}]}""",
        )

        assertTrue(parsed.actions.isEmpty())
        assertTrue(parsed.warnings.isNotEmpty())
    }

    @Test
    fun `keep today tasks rejects empty or invalid ids`() {
        val empty = AssistantResponseParser.parse(
            """{"reply":"x","plan_actions":[{"kind":"KEEP_TODAY_TASKS","keepTaskIds":[]}]}""",
        )
        val blank = AssistantResponseParser.parse(
            """{"reply":"x","plan_actions":[{"kind":"KEEP_TODAY_TASKS","keepTaskIds":[1,"  "]}]}""",
        )
        // UUID 化后 keepTaskIds 接受任意非空字符串 id（0 也不再被当作非法）
        val numeric = AssistantResponseParser.parse(
            """{"reply":"x","plan_actions":[{"kind":"KEEP_TODAY_TASKS","keepTaskIds":[1,0,1]}]}""",
        )

        assertTrue(empty.actions.isEmpty())
        assertTrue(blank.actions.isEmpty())
        assertTrue(empty.warnings.isNotEmpty())
        assertTrue(blank.warnings.isNotEmpty())
        val keep = numeric.actions.single() as PlanAction.KeepTodayTasks
        assertEquals(listOf("1", "0"), keep.keepTaskIds)
    }

    @Test
    fun `invalid entries are skipped with warnings`() {
        val raw = """
            {
              "reply": "部分无效",
              "plan_actions": [
                {"kind":"DELETE_PLAN","planId":1},
                {"kind":"UPDATE_TIME_SLOT","slotId":3,"startTime":"25:99","endTime":"09:00"},
                {"kind":"UPDATE_TODAY_TASK_TARGET","taskId":11,"targetValue":99999},
                {"kind":"UPDATE_TIME_SLOT","slotId":3,"startTime":"07:00","endTime":"09:00","reason":"ok"}
              ]
            }
        """.trimIndent()

        val parsed = AssistantResponseParser.parse(raw)
        assertEquals(1, parsed.actions.size)
        assertTrue(parsed.actions[0] is PlanAction.UpdateTimeSlot)
        assertTrue(parsed.warnings.size >= 3)
    }

    @Test
    fun `same start and end time is rejected`() {
        val raw = """
            {"reply":"x","plan_actions":[{"kind":"UPDATE_TIME_SLOT","slotId":1,"startTime":"09:00","endTime":"09:00"}]}
        """.trimIndent()
        val parsed = AssistantResponseParser.parse(raw)
        assertTrue(parsed.actions.isEmpty())
        assertTrue(parsed.warnings.isNotEmpty())
    }

    @Test
    fun `insert without title is rejected`() {
        val raw = """
            {"reply":"x","plan_actions":[{"kind":"INSERT_TASK_TEMPLATE","subjectId":1,"timeSlotId":2,"title":"  "}]}
        """.trimIndent()
        val parsed = AssistantResponseParser.parse(raw)
        assertTrue(parsed.actions.isEmpty())
    }

    @Test
    fun `markdown and latex remain intact in structured reply`() {
        val raw = """{"reply":"## 解答\\n\\n行内公式：${'$'}${'$'}x^2${'$'}${'$'}","plan_actions":[]}"""
        val parsed = AssistantResponseParser.parse(raw)
        assertTrue(parsed.reply.contains("## 解答"))
        assertTrue(parsed.reply.contains("x^2"))
        assertTrue(parsed.actions.isEmpty())
    }

    @Test
    fun `double escaped paragraphs and numbered lists become markdown breaks`() {
        val raw = """{"reply":"先给结论。\\n\\n建议：\\n1. 第一项\\n2. 第二项","plan_actions":[]}"""
        val parsed = AssistantResponseParser.parse(raw)
        assertEquals("先给结论。\n\n建议：\n1. 第一项\n2. 第二项", parsed.reply)
    }

    @Test
    fun `latex commands beginning with n are not treated as line breaks`() {
        val raw = """{"reply":"公式：${'$'}${'$'}\\nu + \\nabla f${'$'}${'$'}","plan_actions":[]}"""
        val parsed = AssistantResponseParser.parse(raw)
        assertEquals("公式：${'$'}${'$'}\\nu + \\nabla f${'$'}${'$'}", parsed.reply)
    }

    @Test
    fun `standard single dollar formulas are normalized for Markwon`() {
        val normalized = normalizeAssistantMarkdown(
            "已知 ${'$'}f(x)=2^x${'$'}，求 ${'$'}D(-1)${'$'}。",
        )

        assertEquals(
            "已知 ${'$'}${'$'}f(x)=2^x${'$'}${'$'}，求 ${'$'}${'$'}D(-1)${'$'}${'$'}。",
            normalized,
        )
    }

    @Test
    fun `slash latex delimiters are normalized but code and prices are untouched`() {
        val normalized = normalizeAssistantMarkdown(
            "价格 ${'$'}5，行内代码 `${'$'}x${'$'}`，公式 \\(x+1\\)。\n\\[\nx^2=1\n\\]",
        )

        assertEquals(
            "价格 ${'$'}5，行内代码 `${'$'}x${'$'}`，公式 ${'$'}${'$'}x+1${'$'}${'$'}。\n" +
                "${'$'}${'$'}\nx^2=1\n${'$'}${'$'}",
            normalized,
        )
    }

    @Test
    fun `json code fence is tolerated`() {
        val parsed = AssistantResponseParser.parse("""```json
            {"reply":"可读回答","plan_actions":[]}
            ```""".trimIndent())
        assertEquals("可读回答", parsed.reply)
    }

    @Test
    fun `truncated structured response salvages reply`() {
        val parsed = AssistantResponseParser.parse("""{"reply":"第一行\n第二行","plan_actions":[""")
        assertEquals("第一行\n第二行", parsed.reply)
        assertTrue(parsed.warnings.isNotEmpty())
    }

    @Test
    fun `english add update and delete actions are parsed`() {
        val raw = """
            {
              "reply": "已整理出 3 处表达",
              "plan_actions": [],
              "english_actions": [
                {"kind":"ADD_ENGLISH_ENTRY","type":"PHRASE","content":"in the long run","meaning":"从长远来看","reason":"高频短语"},
                {"kind":"UPDATE_ENGLISH_ENTRY","id":7,"meaning":"重新释义","reason":"原释义不准确"},
                {"kind":"DELETE_ENGLISH_ENTRY","id":9,"reason":"重复条目"}
              ]
            }
        """.trimIndent()

        val parsed = AssistantResponseParser.parse(raw)
        assertEquals(3, parsed.englishActions.size)

        val add = parsed.englishActions[0] as EnglishEntryAction.Add
        assertEquals(EnglishEntryType.PHRASE, add.type)
        assertEquals("in the long run", add.content)
        assertEquals("从长远来看", add.meaning)

        val update = parsed.englishActions[1] as EnglishEntryAction.Update
        assertEquals("7", update.id)
        assertEquals(null, update.content)
        assertEquals("重新释义", update.meaning)

        val delete = parsed.englishActions[2] as EnglishEntryAction.Delete
        assertEquals("9", delete.id)
        assertTrue(parsed.warnings.isEmpty())
    }

    @Test
    fun `invalid english actions are skipped with warnings`() {
        val raw = """
            {
              "reply": "已整理",
              "english_actions": [
                {"kind":"ADD_ENGLISH_ENTRY","type":"WORD","content":"resilient"},
                {"kind":"UPDATE_ENGLISH_ENTRY","id":0,"content":"x"},
                {"kind":"DELETE_ENGLISH_ENTRY","id":3,"reason":"重复"},
                {"kind":"UNKNOWN","id":1}
              ]
            }
        """.trimIndent()

        val parsed = AssistantResponseParser.parse(raw)
        assertEquals(1, parsed.englishActions.size)
        assertTrue(parsed.englishActions[0] is EnglishEntryAction.Delete)
        assertTrue(parsed.warnings.size >= 3)
    }

    @Test
    fun `json wrapped in prose and fences still yields actions`() {
        // 模型偶尔在 JSON 前后加说明文字或围栏：整体解析失败时应能提取出动作
        val raw = "好的，下面是调整方案：\n```json\n" +
            "{\"reply\":\"方案如下\",\"plan_actions\":[{\"kind\":\"UPDATE_TIME_SLOT\",\"slotId\":3," +
            "\"startTime\":\"08:00\",\"endTime\":\"10:30\",\"reason\":\"提前\"}],\"english_actions\":[]}\n" +
            "```\n以上供确认。"
        val parsed = AssistantResponseParser.parse(raw)
        assertEquals("方案如下", parsed.reply)
        assertEquals(1, parsed.actions.size)
        assertTrue(parsed.actions[0] is PlanAction.UpdateTimeSlot)
    }

    @Test
    fun `truncated english actions salvage complete entries`() {
        // 长回答撞上输出上限把 english_actions 截断：已完整的条目应被抢救出来，
        // 而不是整包丢弃导致确认按钮消失
        val raw = """{"reply":"已整理","plan_actions":[],"english_actions":[""" +
            """{"kind":"ADD_ENGLISH_ENTRY","type":"PHRASE","content":"in the long run","meaning":"从长远来看","reason":"高频"},""" +
            """{"kind":"ADD_ENGLISH_ENTRY","type":"WORD","conte"""
        val parsed = AssistantResponseParser.parse(raw)
        assertEquals("已整理", parsed.reply)
        assertEquals(1, parsed.englishActions.size)
        val add = parsed.englishActions[0] as EnglishEntryAction.Add
        assertEquals("in the long run", add.content)
        assertTrue(parsed.warnings.isNotEmpty())
    }

    @Test
    fun `truncated plan actions salvage complete entries`() {
        val raw = """{"reply":"方案","plan_actions":[""" +
            """{"kind":"UPDATE_TIME_SLOT","slotId":3,"startTime":"08:00","endTime":"10:30","reason":"提前"},""" +
            """{"kind":"UPDATE_TIME_SLOT","slotI"""
        val parsed = AssistantResponseParser.parse(raw)
        assertEquals("方案", parsed.reply)
        assertEquals(1, parsed.actions.size)
        val slot = parsed.actions[0] as PlanAction.UpdateTimeSlot
        assertEquals("3", slot.slotId)
        assertTrue(parsed.warnings.isNotEmpty())
    }

    @Test
    fun `braces inside strings do not break truncated salvage`() {
        // 正文和字段值里含花括号时，扫描器必须跳过字符串字面量内部
        val raw = """{"reply":"说明 {含花括号} 正文","english_actions":[""" +
            """{"kind":"ADD_ENGLISH_ENTRY","type":"WORD","content":"{curly} brace","meaning":"花括号"},""" +
            """{"kind":"ADD_ENGL"""
        val parsed = AssistantResponseParser.parse(raw)
        assertEquals(1, parsed.englishActions.size)
        val add = parsed.englishActions[0] as EnglishEntryAction.Add
        assertEquals("{curly} brace", add.content)
        assertTrue(parsed.reply.contains("{含花括号}"))
    }

    @Test
    fun `update time slot accepts requiredTaskCount`() {
        val raw = """
            {"reply":"ok","plan_actions":[
              {"kind":"UPDATE_TIME_SLOT","slotId":3,"requiredTaskCount":2,"reason":"加一点约束"}
            ]}
        """.trimIndent()
        val parsed = AssistantResponseParser.parse(raw)
        assertEquals(1, parsed.actions.size)
        val action = parsed.actions[0] as PlanAction.UpdateTimeSlot
        assertEquals(2, action.requiredTaskCount)
        assertEquals(null, action.startTime)
    }

    @Test
    fun `update time slot rejects out of range requiredTaskCount`() {
        val raw = """
            {"reply":"ok","plan_actions":[
              {"kind":"UPDATE_TIME_SLOT","slotId":3,"requiredTaskCount":99,"reason":"越界"},
              {"kind":"UPDATE_TIME_SLOT","slotId":3,"requiredTaskCount":"abc","reason":"非数字"}
            ]}
        """.trimIndent()
        val parsed = AssistantResponseParser.parse(raw)
        assertTrue(parsed.actions.isEmpty())
        assertTrue(parsed.warnings.size >= 2)
    }
}
