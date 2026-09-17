package com.example.lixing.data.assistant

import com.example.lixing.domain.assistant.PlanAction
import org.junit.Assert.assertEquals
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
}
