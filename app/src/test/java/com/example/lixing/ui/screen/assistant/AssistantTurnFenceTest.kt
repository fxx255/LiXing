package com.example.lixing.ui.screen.assistant

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 生成事件的**围栏**验收（协调者阻塞第 2 条）。
 *
 * 背景：`Reasoning` / `AnswerDelta` 早先完全不做身份判断，于是
 * ① 切会话时另一个会话的增量会写进当前气泡；
 * ② 同一请求重试后，旧 attempt 的迟到增量会把新正文整段覆盖。
 *
 * 这里直接测纯函数 [isSameTurn]：围栏结论完全由入参决定，
 * 不必为了断言它去构造整个 ViewModel。
 */
class AssistantTurnFenceTest {

    private fun sameTurn(
        eventRequest: String = "req-1",
        eventAttempt: String = "att-1",
        eventConversation: String? = "conv-1",
        trackedRequest: String? = "req-1",
        trackedAttempt: String? = "att-1",
        currentConversation: String? = "conv-1",
    ) = isSameTurn(
        eventRequestId = eventRequest,
        eventAttemptId = eventAttempt,
        eventConversationId = eventConversation,
        trackedRequestId = trackedRequest,
        trackedAttemptId = trackedAttempt,
        currentConversationId = currentConversation,
    )

    @Test
    fun `matching request attempt and conversation is accepted`() {
        assertTrue(sameTurn())
    }

    // ── ① 会话围栏：切走后不能污染另一个会话 ──

    @Test
    fun `event from another conversation is rejected`() {
        assertFalse(
            "切到别的会话后，原会话的增量不得写进来",
            sameTurn(eventConversation = "conv-1", currentConversation = "conv-2"),
        )
    }

    @Test
    fun `event without conversation still respects request fence`() {
        // 增量事件不带会话：仍须通过 request/attempt 两层。
        assertTrue(sameTurn(eventConversation = null))
        assertFalse(sameTurn(eventConversation = null, eventRequest = "req-other"))
    }

    // ── ② requestId 围栏：连续两轮互不干扰 ──

    @Test
    fun `event from a previous request in the same conversation is rejected`() {
        assertFalse(
            "上一轮的迟到事件不得写进新一轮",
            sameTurn(eventRequest = "req-old", trackedRequest = "req-new"),
        )
    }

    @Test
    fun `event is rejected when no turn is tracked yet`() {
        // 还没收到 Started 就来了增量：来源不可信，直接丢弃。
        assertFalse(
            "未锁定本轮身份时的增量必须丢弃",
            sameTurn(trackedRequest = null, trackedAttempt = null),
        )
    }

    // ── ③ attemptId 围栏：旧 attempt 迟到不得覆盖新正文 ──

    @Test
    fun `late event from an old attempt is rejected`() {
        assertFalse(
            "重试后旧 attempt 的迟到增量必须被丢弃",
            sameTurn(eventAttempt = "att-old", trackedAttempt = "att-new"),
        )
    }

    @Test
    fun `attempt fence is permissive only before an attempt is tracked`() {
        // 只锁定了 request、还没锁定 attempt 时允许通过（例如恢复重放）。
        assertTrue(sameTurn(trackedAttempt = null))
        // 一旦锁定，就必须严格匹配。
        assertFalse(sameTurn(eventAttempt = "att-2", trackedAttempt = "att-1"))
    }

    /** 三条围栏同时不满足时也必须拒绝（不能因为某一条"宽松"就放行）。 */
    @Test
    fun `all fences must pass together`() {
        assertFalse(
            sameTurn(
                eventRequest = "req-old",
                eventAttempt = "att-old",
                eventConversation = "conv-2",
                trackedRequest = "req-new",
                trackedAttempt = "att-new",
                currentConversation = "conv-1",
            ),
        )
    }
}
