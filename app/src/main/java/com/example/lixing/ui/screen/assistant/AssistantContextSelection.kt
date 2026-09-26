package com.example.lixing.ui.screen.assistant

import com.example.lixing.data.assistant.ParsedAssistantReply
import com.example.lixing.domain.assistant.AssistantContextKind

internal fun requiredAssistantContext(
    prompt: String,
    selected: Set<AssistantContextKind>,
    /**
     * 本轮是否处于「计划变更流程」中（见 [inPlanChangeFlow]）。
     *
     * 为什么要单独判：`inferAssistantContext` 是**关键词猜意图**，而改计划的说法经常不含
     * 「计划」二字（「以后都改」「政治都放到晚上」「英语改成题目专项」）。一旦漏判，
     * 模型就拿不到任何带 `[id=...]` 的计划数据，于是只能拒绝生成（表现为「确认页起不来」）
     * 或**编造 id**（点「接受」时报「任务模板不存在（id=…）」）—— 这正是
     * 「第一轮能改、第二轮改不了」的根因。
     *
     * 判据放宽是有意的：多注入一次计划数据只多花几百 token，
     * 而漏判的代价是整轮改计划直接失败。
     */
    inPlanChangeFlow: Boolean = false,
): Set<AssistantContextKind> {
    val inferred = inferAssistantContext(prompt)
    val todayMarkers = listOf(
        "今日请假", "今天请假", "今日不做", "今天不做", "只保留", "仅保留",
        "跳过今日", "跳过今天", "取消今日任务", "取消今天任务", "删除今日任务", "删掉今天",
    )
    val required = if (todayMarkers.any(prompt::contains)) {
        setOf(AssistantContextKind.TODAY)
    } else {
        emptySet()
    }
    // 「正在改计划」⇒ 无条件带上计划数据，不依赖关键词是否命中
    val flowRequired = if (inPlanChangeFlow) setOf(AssistantContextKind.PLAN) else emptySet()
    return selected + inferred + required + flowRequired
}

/** 助手给出待确认方案时惯用的措辞；命中即认为下一轮仍在计划变更流程里。 */
private val PLAN_FLOW_MARKERS = listOf(
    "待确认方案", "请确认", "确认后生效", "计划变更", "调整方案",
)

/**
 * 本轮是否处于「计划变更流程」中 —— 也就是：用户这句话多半是在回应上一轮的方案。
 *
 * 两个判据：① 手上还有待确认动作；② 上一条助手消息在谈方案。
 * 只要其一成立就把计划数据带上，因为**模型生成任何计划动作都必须引用真实 id**。
 *
 * ⚠️ 这里宁可放宽：误判的代价只是多带一段计划数据（几百 token），
 * 漏判的代价是整轮改计划失败、还可能让模型编造 id。
 */
internal fun inPlanChangeFlow(state: AssistantUiState): Boolean {
    if (state.pendingActions.isNotEmpty()) return true
    val lastAssistant = state.messages.lastOrNull { it.role == "assistant" } ?: return false
    val text = lastAssistant.displayContent ?: lastAssistant.content
    return PLAN_FLOW_MARKERS.any(text::contains)
}

/**
 * 模型声明「需要计划数据但上下文里没有」时使用的约定标记。
 *
 * 提示词里要求模型缺数据时**只回这一行**（见 `AssistantModelClient.SYSTEM_PROMPT`），
 * 客户端收到后会自动补上计划数据重试一次，用户无感。
 */
internal const val NEED_PLAN_CONTEXT_MARKER = "[[NEED_PLAN_CONTEXT]]"

/**
 * 约定标记没能被消化掉时替换给用户的提示。
 *
 * 出现它说明补注入也没拿到计划数据（例如当前没有生效中的计划），
 * 此时必须告诉用户**下一步做什么**，而不是只留一句「我拿不到数据」。
 */
internal const val PLAN_CONTEXT_UNAVAILABLE_HINT =
    "（没能读到你的计划数据，暂时无法生成修改方案。请在输入框上方确认「当前计划」已开启，或检查是否还有进行中的计划。）"

/**
 * 模型这一轮是否在声明「缺计划数据 / 拿不到 id」。
 *
 * 两道判据并用：
 * 1. **约定标记** —— 模型遵守提示词时最可靠；
 * 2. **措辞兜底** —— 模型未必遵守约定，于是再看它是否「既没产出任何计划动作，
 *    又在正文里同时提到『没有拿到 / 缺少』+『计划 / 模板 / 时段』+『id』」。
 *
 * 误判的代价很小（多带一段计划数据、多重试一次请求），
 * 而漏判的代价是模型编造 id、要等用户点「接受」才发现失败 —— 所以这里偏向宽松。
 */
internal fun replyNeedsPlanContext(reply: ParsedAssistantReply): Boolean {
    if (NEED_PLAN_CONTEXT_MARKER in reply.reply) return true
    // 已经产出了计划动作 ⇒ 说明它手上有数据，不是「缺数据」
    if (reply.actions.isNotEmpty()) return false
    val text = reply.reply
    // 判据以 **id** 为准，而不是「计划/模板/时段/任务」这些名词：
    // 实测模型具体怎么说很随意，有时只说「我这一轮拿到的上下文里一个真实 id 都没有」，
    // 一个业务名词都不带 —— 那样会被名词条件挡掉。
    // 而「缺 id」正是这个问题的核心症状，提到它才是真的在要计划数据。
    //
    // 反面：不要求名词也不放宽到「提到 id 就算」——因为误触发的代价不只是多一次请求，
    // 还会**丢弃第一次已经生成的回答**（重试会重置正文），用户会看到回答闪一下重来。
    val complainsMissing = listOf(
        "没有拿到", "没拿到", "拿不到", "缺少", "没有提供", "没有找到",
        "无法生成", "没有生成", "都没有", "需要带",
    ).any(text::contains)
    return complainsMissing && ID_WORD.containsMatchIn(text)
}

/**
 * 独立的 `id` 字样。
 *
 * ⚠️ 不能直接用 `contains("id")` —— `provide`、`idea`、`consider` 里都含 "id"，
 * 会把普通英文回答误判成「缺 id」，进而白触发一次补注入重试。
 * 中文文本里 id 两侧通常是汉字（属非单词字符），所以 `\b` 依然能正确匹配。
 */
private val ID_WORD = Regex("""\bid\b""", RegexOption.IGNORE_CASE)

internal fun inferAssistantContext(prompt: String): Set<AssistantContextKind> {
    val compact = prompt.lowercase().replace(Regex("\\s+"), "")
    val inferred = mutableSetOf<AssistantContextKind>()

    val planMarkers = listOf(
        "当前计划", "我的计划", "学习计划", "计划安排", "计划内容", "计划是什么",
        "调整计划", "修改计划", "科目安排", "时段安排", "任务模板", "上午的安排",
        "下午的安排", "晚上的安排", "早上的安排",
    )
    val todayMarkers = listOf(
        "今日任务", "今天任务", "今天的任务", "今天要做", "今日要做", "今天安排",
        "今日安排", "今天进度", "今日进度", "今天完成", "今日完成", "今天还有",
        "今日还有", "今日待办", "今天待办",
    )
    val statsMarkers = listOf(
        "最近7天", "近7天", "最近七天", "近七天", "近期统计", "学习统计", "完成率",
        "薄弱科目", "专注时长", "学习效率", "各科投入", "学习投入", "最近复盘",
    )
    val englishMarkers = listOf(
        "英语积累", "我的英语积累", "积累的英语", "英语小测", "英语测验", "英语复习",
        "短语积累", "句子积累", "好词好句", "记入英语", "加入英语", "存入英语",
        "加入积累", "记到英语", "修改英语积累", "删掉英语积累",
    )
    val wordsMarkers = listOf(
        "墨墨", "今日单词", "今天的单词", "今天背的单词", "背单词列表",
    )

    val refersToToday = listOf("今天", "今日", "当天").any(compact::contains)
    val asksAboutTasks = listOf("任务", "安排", "进度", "待办", "做什么", "完成").any(compact::contains)
    val refersToRecentPeriod = listOf("最近", "近期", "近一周", "过去一周").any(compact::contains)
    val asksAboutPerformance = listOf(
        "学习", "表现", "状态", "效率", "投入", "完成", "复盘", "怎么样", "如何",
    ).any(compact::contains)

    if (planMarkers.any(compact::contains) || "计划" in compact) inferred += AssistantContextKind.PLAN
    if (todayMarkers.any(compact::contains) || (refersToToday && asksAboutTasks)) {
        inferred += AssistantContextKind.TODAY
    }
    if (statsMarkers.any(compact::contains) || (refersToRecentPeriod && asksAboutPerformance)) {
        inferred += AssistantContextKind.STATS
    }
    if (englishMarkers.any(compact::contains)) inferred += AssistantContextKind.ENGLISH
    if (wordsMarkers.any(compact::contains)) inferred += AssistantContextKind.WORDS
    return inferred
}
