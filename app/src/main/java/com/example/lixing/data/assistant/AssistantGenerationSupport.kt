package com.example.lixing.data.assistant

import com.example.lixing.domain.assistant.AssistantMessage

/**
 * 生成流程的共享纯逻辑。
 *
 * 这些函数原本是 `AssistantViewModel` 的 internal 顶层函数。迁移到应用级管理器后
 * 两边都要用，所以集中放在这里；ViewModel 继续复用同一实现，
 * 避免「界面按一套规则合并、管理器按另一套规则合并」这种会悄悄撕坏长回答的分裂。
 */

/** 发给模型的历史上限：条数与总字符双限，超长时从最早的消息开始丢。 */
const val HISTORY_MAX_MESSAGES = 24
const val HISTORY_MAX_CHARS = 24_000

/** 自动续写时回传给模型的「已生成内容」尾部长度。 */
const val CONTINUATION_ECHO_CHARS = 12_000

/** 续写重试的内部指令（不写入会话）。 */
const val CONTINUE_INSTRUCTION =
    "上面是这个长回答已经写出的部分（可能只截取了尾部）。请直接接着写未完成的内容，" +
        "若末尾段落或公式被截断，请从该段落开头重新写完整段落（保持开头一致），再继续后文；" +
        "不要只猜测补一个公式后缀。保留成对的公式定界符和加粗标记，不写‘接上文’。" +
        "不要重复更早已完成的段落，也不要提前收尾——除非确实已经全部写完。仍只输出独立完整的 JSON 对象。"

/** 单轮「有效产出」阈值：低于它视为模型在收尾或打转。 */
const val MIN_MEANINGFUL_CHARS = 200

/** 累计输出绝对熔断长度。 */
const val TOTAL_CHAR_FUSE = 240_000

/** 模型声明缺计划数据的约定标记。 */
const val NEED_PLAN_CONTEXT_MARKER = "[[NEED_PLAN_CONTEXT]]"

/** 约定标记没能消化掉时给用户的、可执行的提示。 */
const val PLAN_CONTEXT_UNAVAILABLE_HINT =
    "（没能读到你的计划数据，暂时无法生成修改方案。请在输入框上方确认「当前计划」已开启，或检查是否还有进行中的计划。）"

/**
 * 合并原始 reply 片段，含「续写重放了未完成段落」的情况。
 *
 * 必须在 Markdown 规范化**之前**做，否则重放段落会被当成新内容重复追加。
 */
fun mergeAssistantContinuation(previous: String, next: String): String {
    if (previous.isEmpty()) return next
    if (next.isEmpty()) return previous
    val incoming = next.trimStart()
    val paragraphStart = previous.lastIndexOf("\n\n").let { if (it < 0) 0 else it + 2 }
    val tail = previous.substring(paragraphStart)
    // 续写指令要求重写未完成段落，因此需要足够长的公共前缀才算「同一段」；
    // 阈值定小了会让 `(1)`、`解：` 这类常见开头被误判成重放。
    val common = tail.commonPrefixWith(incoming).length
    if (common >= 12) return previous.substring(0, paragraphStart) + incoming
    // 有些端点会原样重放上一段结尾。
    for (length in minOf(previous.length, incoming.length) downTo 16) {
        if (previous.regionMatches(previous.length - length, incoming, 0, length)) {
            return previous + incoming.substring(length)
        }
    }
    return previous + next
}

private val FIGURE_ANCHOR_PATTERN = Regex("""(?im)^[ \t]*\[\[\s*FIGURE\s*:\s*(\d+)\s*\]\][ \t]*$""")

/**
 * 把续写轮里的 `[[FIGURE:n]]` 锚点按已累积的图表数平移。
 *
 * 续写轮里模型不知道前面已画过几张图，锚点通常从 1 重新编号；
 * 不平移的话第 2 轮的 `[[FIGURE:1]]` 会错误地指到第 1 轮的第一张图上。
 *
 * 这是**唯一实现**：界面与管理器共用，历史测试（`FigureAnchorOffsetTest`）
 * 直接测的就是本函数。早先两边各有一份、行为还不一致
 * （`base <= 0` / `base == 0`、是否规范化输出），等于没有回归保护。
 */
fun offsetFigureAnchors(text: String, base: Int): String {
    if (base <= 0 || !text.contains("[[")) return text
    return FIGURE_ANCHOR_PATTERN.replace(text) { match ->
        val shifted = (match.groupValues[1].toIntOrNull() ?: return@replace match.value) + base
        "[[FIGURE:$shifted]]"
    }
}

/**
 * 构造真正发给模型的历史消息。
 *
 * 自动续写会把长回答切成多段 assistant 消息，这里先合并相邻片段，
 * 否则模型看到的是「一串半截回答」，很容易接不上；再按条数与总字符裁剪。
 */
fun buildModelHistory(messages: List<AssistantMessage>): List<AssistantMessage> {
    if (messages.isEmpty()) return emptyList()
    val merged = mutableListOf<AssistantMessage>()
    for (message in messages) {
        val previous = merged.lastOrNull()
        if (previous != null && previous.role == "assistant" && message.role == "assistant") {
            merged[merged.lastIndex] = previous.copy(content = previous.content + message.content)
        } else {
            merged += message
        }
    }
    var chars = 0
    val kept = ArrayDeque<AssistantMessage>()
    for (message in merged.takeLast(HISTORY_MAX_MESSAGES).asReversed()) {
        val cost = message.content.length + 8
        if (kept.isNotEmpty() && chars + cost > HISTORY_MAX_CHARS) break
        kept.addFirst(message)
        chars += cost
    }
    return kept.toList()
}

/** 独立的 `id` 字样判据；不能用 `contains("id")`，否则 `provide`/`idea` 会误判。 */
private val ID_WORD = Regex("""\bid\b""", RegexOption.IGNORE_CASE)

/**
 * 模型这一轮是否在声明「缺计划数据 / 拿不到 id」。
 *
 * 误判代价小（多带一段数据、多重试一次），漏判代价大（编造 id，
 * 要等用户点「接受」才暴露失败），所以判据偏宽松。
 */
fun replyNeedsPlanContext(reply: ParsedAssistantReply): Boolean {
    if (NEED_PLAN_CONTEXT_MARKER in reply.reply) return true
    // 已产出计划动作 ⇒ 手上有数据，不是在要数据。
    if (reply.actions.isNotEmpty()) return false
    val text = reply.reply
    val complainsMissing = listOf(
        "没有拿到", "没拿到", "拿不到", "缺少", "没有提供", "没有找到",
        "无法生成", "没有生成", "都没有", "需要带",
    ).any(text::contains)
    return complainsMissing && ID_WORD.containsMatchIn(text)
}

/**
 * 清理最终正文里的内部控制标记。
 *
 * 约定标记若最终没被消化（已补过数据仍要、或出现在续写轮），
 * 绝不能把它原样露给用户 —— 那比「拿不到数据」更让人摸不着头脑。
 */
fun stripControlMarkers(part: String): String {
    if (NEED_PLAN_CONTEXT_MARKER !in part) return part
    val stripped = part.replace(NEED_PLAN_CONTEXT_MARKER, "").trim()
    return if (stripped.isEmpty()) {
        PLAN_CONTEXT_UNAVAILABLE_HINT
    } else {
        "$stripped\n\n$PLAN_CONTEXT_UNAVAILABLE_HINT"
    }
}

/**
 * 截断收尾：去掉悬空的加粗标记、补齐未闭合的公式块，并附加截断说明。
 *
 * 与锚点平移一样，这是界面与管理器共用的**唯一实现**（原为 ViewModel 私有函数）。
 */
fun polishTruncatedTail(text: String): String {
    var t = text.trimEnd()
    if (t.endsWith("**")) t = t.removeSuffix("**").trimEnd()
    // $$ 出现奇数次 → 有未闭合的公式块，补一个闭合
    if (t.split("$$").size % 2 == 0) t += "\n$$"
    return "$t\n\n——（回答达到单次输出上限被截断）"
}

/**
 * 是否应当继续自动续写。
 *
 * 三道刹车：① 模型确实被输出上限截断；② 没有连续两轮几乎没内容；
 * ③ 未超过用户的续写轮数上限与总熔断长度。[barrenRounds] 为已连续空转轮数。
 */
fun shouldContinueGeneration(
    truncated: Boolean,
    barrenRounds: Int,
    continuation: Int,
    maxContinuations: Int,
    accumulatedChars: Int,
): Boolean = truncated &&
    barrenRounds < 2 &&
    continuation < maxContinuations &&
    accumulatedChars < TOTAL_CHAR_FUSE

/** 单轮产出是否算「有效内容」（低于阈值视为收尾或打转）。 */
fun isMeaningfulRound(replyText: String): Boolean = replyText.length >= MIN_MEANINGFUL_CHARS
