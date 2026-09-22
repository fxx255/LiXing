package com.example.lixing.data.assistant

import com.example.lixing.domain.assistant.PlanAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 守卫「待确认方案」信封的编解码 —— 它是「确认」按钮能跨进程存活的载体。
 *
 * 背景：待确认方案以前只是内存状态，用户还没点确认就锁屏 / 切走再回来，
 * 方案和按钮一起消失。现在它随消息落库，恢复时靠这里解出来的原始
 * `plan_actions` JSON 重新解析、重新校验。
 */
class PendingPlanReviewTest {

    /** 注意协议字段名是 `kind`（不是 type）—— 模型给的原始 JSON 长这样。 */
    private val sampleJson =
        """[{"kind":"UPDATE_TASK_TEMPLATE","templateId":"t-1","repeatRule":"EVERY_N_DAYS"}]"""

    @Test
    fun `编解码往返不丢信息`() {
        val payload = PendingPlanReviewPayload(
            actionsJson = sampleJson,
            selected = listOf(true, false),
            applied = false,
        )
        assertEquals(payload, decodePendingReview(encodePendingReview(payload)))
    }

    /** 已应用的标记也要能存下来 —— 用来把按钮置灰而不是让它消失。 */
    @Test
    fun `已应用标记能往返`() {
        val payload = PendingPlanReviewPayload(actionsJson = sampleJson, applied = true)
        val restored = decodePendingReview(encodePendingReview(payload))
        assertEquals(true, restored?.applied)
    }

    /** 空串 = 没有待确认项（实体里新列的默认值就是空串）。 */
    @Test
    fun `空串表示没有待确认项`() {
        assertNull(decodePendingReview(""))
        assertNull(decodePendingReview("   "))
    }

    /**
     * **英语变更信封**必须能持久化（协调者第 7 条）。
     *
     * 早先英语方案只活在内存里：用户还没确认就锁屏/切走，变更就凭空消失
     * （与计划方案当年遇到的是同一个问题）。现在两者放进同一个信封。
     */
    @Test
    fun `英语变更信封能往返`() {
        val englishJson =
            """[{"kind":"ADD","type":"WORD","content":"ubiquitous","meaning":"无处不在的"}]"""
        val payload = PendingPlanReviewPayload(
            actionsJson = sampleJson,
            selected = listOf(true),
            englishActionsJson = englishJson,
            englishSelected = listOf(false, true),
        )
        val restored = decodePendingReview(encodePendingReview(payload))
        assertEquals(payload, restored)
        assertEquals(englishJson, restored?.englishActionsJson)
        assertEquals(listOf(false, true), restored?.englishSelected)
    }

    /** 计划与英语两个「已应用」标记互相独立：应用计划不该把英语也标成已应用。 */
    @Test
    fun `计划与英语的已应用标记互相独立`() {
        val planOnly = PendingPlanReviewPayload(actionsJson = sampleJson, applied = true)
        val restoredPlan = decodePendingReview(encodePendingReview(planOnly))
        assertEquals(true, restoredPlan?.applied)
        assertEquals(false, restoredPlan?.englishApplied)

        val englishOnly = PendingPlanReviewPayload(
            actionsJson = sampleJson,
            englishActionsJson = """[{"kind":"ADD"}]""",
            englishApplied = true,
        )
        val restoredEnglish = decodePendingReview(encodePendingReview(englishOnly))
        assertEquals(false, restoredEnglish?.applied)
        assertEquals(true, restoredEnglish?.englishApplied)
    }

    /** 旧信封（只有 plan 字段）必须仍能解码 —— 向后兼容。 */
    @Test
    fun `旧格式信封向后兼容`() {
        val legacy = """{"actionsJson":"[]","selected":[true],"applied":true}"""
        val decoded = decodePendingReview(legacy)
        assertEquals("[]", decoded?.actionsJson)
        assertEquals(true, decoded?.applied)
        assertEquals("", decoded?.englishActionsJson)
        assertEquals(false, decoded?.englishApplied)
    }

    /** 坏数据只当作「没有」处理，绝不能把会话打开流程搞崩。 */
    @Test
    fun `坏数据不崩溃只返回 null`() {
        assertNull(decodePendingReview("{ 这不是 JSON"))
        assertNull(decodePendingReview("""{"actionsJson":}"""))
    }

    /** 恢复时要能从原始 JSON 还原动作（复用正常解析路径）。 */
    @Test
    fun `从原始 JSON 还原动作`() {
        val actions = AssistantResponseParser.parseActionsJson(sampleJson)
        assertEquals(1, actions.size)
        val action = actions.first()
        assertTrue("应还原成 UpdateTaskTemplate", action is PlanAction.UpdateTaskTemplate)
        assertEquals("t-1", (action as PlanAction.UpdateTaskTemplate).templateId)
    }

    /** 数组里个别坏条目被跳过，不影响其余动作（与正常解析同语义）。 */
    @Test
    fun `动作数组里的坏条目被跳过`() {
        val actions = AssistantResponseParser.parseActionsJson(
            """[{"kind":"UPDATE_TASK_TEMPLATE"}, """ +
                """{"kind":"UPDATE_TASK_TEMPLATE","templateId":"t-2","repeatRule":"DAILY"}]""",
        )
        // 第一条缺 templateId ⇒ 跳过；第二条正常
        assertEquals(1, actions.size)
        assertEquals("t-2", (actions.first() as PlanAction.UpdateTaskTemplate).templateId)
    }

    @Test
    fun `空或坏的 JSON 返回空列表`() {
        assertTrue(AssistantResponseParser.parseActionsJson("").isEmpty())
        assertTrue(AssistantResponseParser.parseActionsJson("not json").isEmpty())
        assertTrue(AssistantResponseParser.parseActionsJson("{}").isEmpty())
    }

    /**
     * **CAS 认领语义**（协调者第 5 条）：
     * 确认必须"先原子认领、再执行副作用"，且**计划与英语各自独立认领**。
     * 这里验证编码层面的前提：认领 = 旧串 → 新串的精确替换。
     */
    @Test
    fun `认领只置本类标记不影响另一类`() {
        val before = PendingPlanReviewPayload(
            actionsJson = sampleJson,
            englishActionsJson = """[{"kind":"ADD"}]""",
        )
        val encodedBefore = encodePendingReview(before)

        // 认领英语：只置 englishApplied。
        val claimedEnglish = decodePendingReview(encodedBefore)!!.copy(englishApplied = true)
        assertEquals(false, claimedEnglish.applied)
        assertEquals(true, claimedEnglish.englishApplied)
        // 重新编码后计划部分保持原样（这是 CAS 的 expected 前提）。
        assertEquals(before.actionsJson, claimedEnglish.actionsJson)

        // 再认领计划：只置 applied。
        val claimedPlan = claimedEnglish.copy(applied = true)
        assertEquals(true, claimedPlan.applied)
        assertEquals(true, claimedPlan.englishApplied)
        // 两次认领的 expected 都能精确对上（CAS 必须是精确串匹配）。
        assertEquals(encodePendingReview(claimedEnglish), encodePendingReview(claimedEnglish.copy()))
        assertFalse(encodePendingReview(before) == encodePendingReview(claimedEnglish))
    }

    /**
     * 认领回滚：副作用整体失败时把标记恢复原样，用户可重试。
     */
    @Test
    fun `回滚认领恢复原始信封`() {
        val before = PendingPlanReviewPayload(actionsJson = sampleJson)
        val claimed = before.copy(applied = true)
        // 回滚 = 把 claimed 换回 before；两者编码必须都可精确匹配。
        assertEquals(encodedRoundTrip(before), encodePendingReview(before))
        assertEquals(encodedRoundTrip(claimed), encodePendingReview(claimed))
        assertNotEquals(encodePendingReview(before), encodePendingReview(claimed))
    }

    private fun encodedRoundTrip(payload: PendingPlanReviewPayload): String =
        decodePendingReview(encodePendingReview(payload))?.let { encodePendingReview(it) }.orEmpty()
}
