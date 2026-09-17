package com.example.lixing.ui.screen.assistant

import com.example.lixing.data.assistant.ParsedAssistantReply
import com.example.lixing.domain.assistant.AssistantContextKind
import com.example.lixing.domain.assistant.AssistantMessage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 守卫：**会引发计划变更的话，必须带上计划数据（含真实 id）**。
 *
 * ## 背景（一轮真实故障）
 *
 * 上下文注入有三条途径，任一命中才会带上「## 当前计划」（**只有它带 `[id=...]`**）：
 * 1. `manual` —— 用户手动勾选；
 * 2. `modelClient.chooseContext(prompt)` —— **单独一次模型调用，只传当前这一句话、没有对话历史**；
 * 3. `inferAssistantContext(prompt)` —— 关键词匹配，要求句子里**含「计划」二字**。
 *
 * 于是出现一条稳定的故障链：
 *
 * ```
 * 用户：「以后都改，X 都放到晚上，Y 改成题目专项」   ← 明显是改计划，但没有「计划」二字
 *   ⇒ 三条途径全不命中 ⇒ 不注入计划数据 ⇒ 模型拿不到任何真实 id
 *   ⇒ 模型要么拒绝生成（表现为「确认页一直起不来」），
 *      要么**编造 id** ⇒ 用户点「接受」时报「任务模板不存在（id=...）」
 * ```
 *
 * 这解释了为什么「第一轮改计划顺利、第二轮就不行」：第一轮往往说了「计划」二字，
 * 后续用「再生成一次」「以后都改」这类指代性短句就全都不命中。
 *
 * ## 现在的两道防线
 *
 * - **流程态强制注入**（[inPlanChangeFlow]）：只要还在改计划的流程里（有待确认动作、
 *   或上一轮助手刚给过方案），就无条件带上计划数据 —— 覆盖「再生成一次」这类场景。
 * - **自纠正兜底**（[replyNeedsPlanContext]）：模型若声明「缺 id」，客户端补上计划数据
 *   自动重试一次，覆盖「第一句话就要改计划」这种流程态还没建立的场合。
 */
class ContextInferenceTest {

    private fun kindsFor(
        prompt: String,
        manual: Set<AssistantContextKind> = emptySet(),
        inPlanChangeFlow: Boolean = false,
    ) = requiredAssistantContext(prompt, manual, inPlanChangeFlow)

    // ---------- 第一道防线：流程态强制注入 ----------

    /** 「再生成一次，没看到」这类指代性短句，靠关键词永远猜不到，流程态必须兜住。 */
    @Test
    fun `流程态下指代性短句也注入计划数据`() {
        val prompt = "再生成一次，没看到"
        assertTrue(
            "处于改计划流程中就必须带 id，否则模型只能编：kinds=${kindsFor(prompt, inPlanChangeFlow = true)}",
            AssistantContextKind.PLAN in kindsFor(prompt, inPlanChangeFlow = true),
        )
    }

    /** 用户在反馈「方案应用失败」，也仍在流程里。 */
    @Test
    fun `流程态下方案失败反馈也注入计划数据`() {
        val prompt = "刚才那版无法生成出错，接受失败"
        assertTrue(
            "要重生成就必须能拿到 id：kinds=${kindsFor(prompt, inPlanChangeFlow = true)}",
            AssistantContextKind.PLAN in kindsFor(prompt, inPlanChangeFlow = true),
        )
    }

    /** 变更意图通常不含「计划」二字，流程态必须覆盖。 */
    @Test
    fun `流程态下变更意图也注入计划数据`() {
        val prompt = "以后都改，数学都放到晚上，英语改成题目专项"
        assertTrue(
            "这类句子明显要改计划：kinds=${kindsFor(prompt, inPlanChangeFlow = true)}",
            AssistantContextKind.PLAN in kindsFor(prompt, inPlanChangeFlow = true),
        )
    }

    /** 上一条助手消息在谈方案 ⇒ 判定处于流程态。 */
    @Test
    fun `上一条助手消息谈方案时判定为流程态`() {
        val state = AssistantUiState(
            messages = listOf(
                AssistantMessage("user", "帮我调整一下计划"),
                AssistantMessage("assistant", "下面是待确认方案，请确认"),
            ),
        )
        assertTrue("上一轮刚给过方案，本轮多半是在回应它", inPlanChangeFlow(state))
    }

    /** 上一条是用户消息（还没得到方案）⇒ 不算流程态。 */
    @Test
    fun `没有方案上下文时不算流程态`() {
        val state = AssistantUiState(
            messages = listOf(AssistantMessage("assistant", "这是一道题的解法……")),
        )
        assertFalse("普通回答不该被误判成方案", inPlanChangeFlow(state))
    }

    // ---------- 第二道防线：自纠正 ----------

    /** 模型遵守约定回了标记 ⇒ 必须补数据重试。 */
    @Test
    fun `约定标记表示缺计划数据`() {
        val reply = parsed(NEED_PLAN_CONTEXT_MARKER)
        assertTrue("约定标记必须被识别", replyNeedsPlanContext(reply))
    }

    /** 措辞兜底：模型没遵守约定、但明确说拿不到 id。 */
    @Test
    fun `措辞兜底识别缺计划数据`() {
        // 实测出现过的说法：既不含「缺少」也不含「无法生成」，只说了「一个真实 id 都没有」
        assertTrue(
            "这种自述要能接住，否则用户只能看到一句无用的解释",
            replyNeedsPlanContext(parsed("我这一轮拿到的上下文里一个真实 id 都没有。")),
        )
        assertTrue(
            replyNeedsPlanContext(parsed("没有拿到这两条任务模板的 id，无法生成计划动作。")),
        )
    }

    /**
     * `id` 必须按独立单词匹配。
     *
     * `contains("id")` 会把 `provide` / `idea` / `consider` 也算命中，
     * 于是普通英文回答里只要出现「缺少」+「计划」就会被误判成缺 id，白触发一次重试。
     */
    @Test
    fun `英文单词里的 id 子串不误判`() {
        assertFalse(
            "provide 里的 id 不该算命中",
            replyNeedsPlanContext(parsed("这个计划缺少一个 provide 步骤。")),
        )
    }

    /** 已经产出了计划动作 ⇒ 说明它有数据，不能误触发重试。 */
    @Test
    fun `产出动作时不判定为缺数据`() {
        val reply = ParsedAssistantReply(
            reply = "已生成方案，请确认",
            actions = listOf(
                com.example.lixing.domain.assistant.PlanAction.UpdateTaskTemplate(
                    templateId = "t-1",
                    title = null,
                    targetValue = null,
                    timeSlotId = null,
                    repeatRule = null,
                    isKeystone = null,
                    isEnabled = null,
                    reason = "测试：只要有一条动作就说明模型拿到了真实 id",
                ),
            ),
            warnings = emptyList(),
        )
        assertFalse("有动作就不能重试，否则会陷入来回拉锯", replyNeedsPlanContext(reply))
    }

    /** 普通回答里恰好出现「缺少」和「计划」和 id 字样时不能误判。 */
    @Test
    fun `普通回答不误触发重试`() {
        assertFalse(replyNeedsPlanContext(parsed("这道题的思路是先求导，再代入边界条件。")))
        // 只说「缺少」但没提计划 → 不是缺计划数据
        assertFalse(replyNeedsPlanContext(parsed("题目缺少一个条件，无法求解。")))
    }

    // ---------- 基线：原有行为不变 ----------

    /** 明确提到「计划」时当然要带。 */
    @Test
    fun `明确提到计划时注入计划数据`() {
        assertTrue(
            "含「计划」二字应注入：kinds=${kindsFor("重新生成一下计划")}",
            AssistantContextKind.PLAN in kindsFor("重新生成一下计划"),
        )
    }

    /** 用户手动勾选「当前计划」时，任何问题都带。 */
    @Test
    fun `手动勾选计划时始终注入`() {
        val kinds = kindsFor("今天天气怎么样", setOf(AssistantContextKind.PLAN))
        assertTrue("手动勾选应始终生效：kinds=$kinds", AssistantContextKind.PLAN in kinds)
    }

    /** 与计划无关的闲聊、纯知识提问不应被强行塞入计划数据（避免无谓的 token 开销）。 */
    @Test
    fun `纯知识提问不注入计划数据`() {
        assertTrue(
            "纯知识提问不该带计划数据：kinds=${kindsFor("解释一下傅里叶变换的物理意义")}",
            AssistantContextKind.PLAN !in kindsFor("解释一下傅里叶变换的物理意义"),
        )
    }

    private fun parsed(text: String) = ParsedAssistantReply(
        reply = text,
        actions = emptyList(),
        warnings = emptyList(),
    )
}
