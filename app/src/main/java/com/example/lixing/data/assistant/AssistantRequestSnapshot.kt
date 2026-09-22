package com.example.lixing.data.assistant

/**
 * 重试所需的本机快照：模型/端点**身份**、推理与联网设置、只读上下文，
 * 以及**提交时钉下的完整复原信息**（原问题、原历史、照片路由、配置档位）。
 *
 * ## 为什么需要它（而不是「用当前设置重试」）
 *
 * 用户点重试时，provider 可能已经换了（设置页改了模型/地址）。若沿用「当前设置」
 * 直接重发，就会把**原来那道题**发给一个用户没预期的供应商 —— 题目内容可能包含
 * 家庭作业原文、照片转写结果，这属于隐私边界问题，不是体验问题。
 *
 * 所以提交时把身份钉下来；重试时以**快照为权威**，配置变化就明确拒绝。
 *
 * ## 版本与兼容
 *
 * [version] 区分快照代数：`1` = 只有身份/上下文的旧版（重试需要重新准备），
 * `2` = 含原问题/历史/照片路由/配置档位的完整版。旧字段全部有默认值，
 * 解码旧快照不会失败；但**旧版不完整快照在重试时必须明确失败**，而不是猜路由。
 *
 * ## 绝不含密钥或图片内容
 *
 * 只存**非敏感身份**与**持久化附件路径**：模型名、端点身份（由 baseUrl 主机+路径
 * 派生，去掉查询串与凭证）、协议、推理档位、联网开关、上下文种类与字符数、
 * 附件的**私有目录路径**（不是 base64）。API key / Authorization 运行时从既有凭证
 * 存储取，从不落库。
 */
@kotlinx.serialization.Serializable
data class AssistantRequestSnapshot(
    /** 模型标识（如 `deepseek-chat`）。 */
    val model: String = "",
    /**
     * 端点身份：主机 + 路径，**不含**查询串、用户名、端口凭证。
     *
     * 用途：区分「同一个模型名挂在两个不同供应商」——早先只按模型名归集 usage，
     * 会把两家的样本混在一起算命中率。
     */
    val endpointIdentity: String = "",
    /** `responses` / `chat_completions`。 */
    val protocol: String = "",
    /** 用户明确选择的那一档推理强度。 */
    val reasoningEffort: String = "DEFAULT",
    val webSearchEnabled: Boolean = false,
    /** 本次附带的上下文种类（名称列表），用于提示与诊断。 */
    val contextKinds: List<String> = emptyList(),
    /** 上下文与历史的规模，用于诊断（不含内容本身）。 */
    val contextChars: Int = 0,
    val historyMessages: Int = 0,
    /** 提交时是否带图（不带图片内容）。 */
    val hasImages: Boolean = false,
    /**
     * 提交时使用的**只读上下文原文**。
     *
     * 重试发生在几小时甚至第二天，此时「今天的计划」可能已经变了、或者上下文
     * 重建失败。没有原文就只能发一个空上下文出去。仍然**不含**密钥或聊天正文。
     */
    val sourceContext: String = "",
    /**
     * 提交时用户原文（图片题的转写结果也算）。
     *
     * 重试要重发同一道题；旧记录里 `user_text` 可能只是"看图"这类占位。
     */
    val sourceUserText: String = "",
    // ── v2：完整复原信息 ──
    /**
     * 快照代数；**缺省是 1**（旧序列化数据里没有这个字段 ⇒ 解码成 v1），
     * 新写入必须显式置 [Companion.VERSION_V2]。
     */
    val version: Int = VERSION_V1,
    /** 快照是否已包含**准备结果**（转写文本/上下文/路由已补写）。 */
    val prepared: Boolean = false,
    /** 本轮的联网策略（提交时的实际生效值，重试沿用）。 */
    val forceWebSearch: Boolean = false,
    /** 全局联网开关在**提交时**的生效值（重试沿用，不读当前开关）。 */
    val effectiveWebSearchEnabled: Boolean = false,
    /** 自动续写上限（用户设置，提交时钉下）。 */
    val maxContinuations: Int = 0,
    /** 主模型的配置档案 id（为空表示走旧式偏好配置）。 */
    val primaryProfileId: String = "",
    /** 题目识别模型的配置档案 id（仅转写路由时非空）。 */
    val visionProfileId: String = "",
    /** 题目识别模型的模型名（安全身份，重试校验用）。 */
    val visionModel: String = "",
    /** 题目识别端点身份（主机+路径，重试校验用）。 */
    val visionEndpointIdentity: String = "",
    /** 照片路由：[PHOTO_ROUTE_NONE] / [PHOTO_ROUTE_DIRECT] / [PHOTO_ROUTE_TRANSCRIBE]。 */
    val photoRoute: String = PHOTO_ROUTE_NONE,
    /** 提交时钉下的**原始有界历史**（不含本轮问题与回答位）。 */
    val originalHistory: List<SnapshotHistoryMessage> = emptyList(),
    /** 用户手动勾选的上下文种类（与自动选择分开记录）。 */
    val manualContextKinds: List<String> = emptyList(),
    /** 本轮是否处于「计划变更流程」。 */
    val inPlanChangeFlow: Boolean = false,
) {
    /**
     * 这份快照是否携带重试所需的完整复原信息。
     *
     * **纯照片题的 sourceUserText 可以为空** —— 可重试的判据是身份齐备且路由可识别，
     * 而不是"有文字"。
     */
    val isComplete: Boolean
        get() = version >= VERSION_V2 &&
            model.isNotBlank() &&
            endpointIdentity.isNotBlank() &&
            (photoRoute == PHOTO_ROUTE_NONE ||
                photoRoute == PHOTO_ROUTE_DIRECT ||
                photoRoute == PHOTO_ROUTE_TRANSCRIBE)

    /** 这份快照是否是旧版（v1）：只有身份与上下文，重试必须明确失败而不是猜。 */
    val isLegacy: Boolean get() = version < VERSION_V2

    /** 诊断/usage 归集键：端点身份 + 模型 + 协议。 */
    val usageGroupKey: String
        get() = listOf(
            endpointIdentity.ifBlank { "未知端点" },
            model.ifBlank { "未知模型" },
            protocol.ifBlank { "未知协议" },
        ).joinToString(" | ")

    companion object {
        /** 旧版快照（只有身份与上下文）；缺 version 字段的旧数据解码成这个。 */
        const val VERSION_V1 = 1
        const val VERSION_V2 = 2
        const val PHOTO_ROUTE_NONE = "none"
        const val PHOTO_ROUTE_DIRECT = "direct"
        const val PHOTO_ROUTE_TRANSCRIBE = "transcribe"
    }
}

/** 快照 → 运行时不可变策略（不含密钥）；字段一一对应，重试以此为准。 */
fun AssistantRequestSnapshot.toPolicy(): AssistantRequestPolicy = AssistantRequestPolicy(
    primaryProfileId = primaryProfileId,
    visionProfileId = visionProfileId,
    visionModel = visionModel,
    visionEndpointIdentity = visionEndpointIdentity,
    model = model,
    endpointIdentity = endpointIdentity,
    searchProtocol = protocol,
    reasoningEffort = reasoningEffort,
    effectiveWebSearchEnabled = effectiveWebSearchEnabled,
)

/** 快照里的一条历史消息：角色 + 正文 + 消息 id（用于定位，不含附件内容）。 */
@kotlinx.serialization.Serializable
data class SnapshotHistoryMessage(
    val id: String = "",
    val role: String = "",
    val text: String = "",
)

/**
 * 一次生成使用的**不可变运行时策略**（内存对象，绝不落库、绝不含密钥）。
 *
 * 来自快照（重试）或提交时钉下的档案；进入管理器后传给客户端，
 * 使整轮 HTTP —— 主请求、转写、chooseContext、恢复、续写 —— 都使用
 * **同一份**身份与设置，中途不再重读"当前活动档案"。
 */
data class AssistantRequestPolicy(
    /** 主模型档案 id；为空表示旧式偏好配置。 */
    val primaryProfileId: String = "",
    /** 题目识别档案 id（仅转写路由时非空）。 */
    val visionProfileId: String = "",
    /** 题目识别模型名 + 安全端点身份（转写/重试校验用）。 */
    val visionModel: String = "",
    val visionEndpointIdentity: String = "",
    /** 提交时钉下的主模型名与安全端点身份。 */
    val model: String = "",
    val endpointIdentity: String = "",
    /** 提交时钉下的联网协议（responses / chat_completions）。 */
    val searchProtocol: String = "",
    /** 提交时钉下的推理档位。 */
    val reasoningEffort: String = "",
    /** 提交时钉下的**全局联网开关生效值**（重试沿用，不读当前开关）。 */
    val effectiveWebSearchEnabled: Boolean = false,
) {
    /** 快照可直接转成策略；两者字段一一对应。 */
    companion object
}

/** 快照的序列化（容错：坏快照退化成空快照，绝不因此让重试不可用）。 */
object AssistantSnapshotCodec {
    private val json = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(snapshot: AssistantRequestSnapshot): String =
        runCatching { json.encodeToString(AssistantRequestSnapshot.serializer(), snapshot) }.getOrDefault("")

    fun decode(raw: String): AssistantRequestSnapshot? =
        if (raw.isBlank()) {
            null
        } else {
            runCatching { json.decodeFromString(AssistantRequestSnapshot.serializer(), raw) }.getOrNull()
        }
}

/**
 * 由 baseUrl 派生**安全端点身份**：scheme://host[:port]/path，
 * **不含**查询串、用户信息、片段与任何凭证。
 *
 * 为什么不能直接用 baseUrl：它可能带 `?api-version=...&key=...` 这类查询串，
 * 落库就等于把凭证写进数据库与备份。而只按模型名归集又会把**不同供应商的
 * 同名模型**混在一起算缓存命中率。
 *
 * 刻意手写解析而不走 OkHttp 的 HttpUrl：这段逻辑要能在纯 JVM 单测里跑，
 * 不引入任何网络栈依赖，行为也完全可预测。
 */
fun endpointIdentityOf(baseUrl: String): String {
    val trimmed = baseUrl.trim()
    if (trimmed.isEmpty()) return ""
    // 先砍掉查询串与片段 —— 这里最可能夹带凭证。
    val withoutQuery = trimmed.substringBefore('?').substringBefore('#')
    // 再砍掉 userInfo（user:pass@host 里的凭证）。
    val schemeEnd = withoutQuery.indexOf("://")
    val scheme = if (schemeEnd > 0) withoutQuery.substring(0, schemeEnd + 3) else ""
    val afterScheme = withoutQuery.substring(scheme.length)
    val slash = afterScheme.indexOf('/')
    val authority = if (slash >= 0) afterScheme.substring(0, slash) else afterScheme
    val path = if (slash >= 0) afterScheme.substring(slash) else ""
    val hostAndPort = authority.substringAfterLast('@')
    val normalizedPath = path.trimEnd('/')
    return scheme + hostAndPort + normalizedPath
}
