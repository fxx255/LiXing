package com.example.lixing.data.assistant

import com.example.lixing.domain.assistant.EnglishEntryAction
import com.example.lixing.domain.assistant.PlanAction
import com.example.lixing.data.repository.EnglishEntryRepository
import com.example.lixing.domain.english.EnglishEntryType
import com.example.lixing.domain.model.RepeatRule
import com.example.lixing.domain.model.TargetType
import com.example.lixing.domain.model.TaskType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.time.LocalTime

/** 发往网关的聊天请求。 */
@Serializable
data class ChatMessageDto(val role: String, val content: String)

@Serializable
data class ChatRequestDto(
    val messages: List<ChatMessageDto>,
    val context: String,
)

/** 解析后的助手回复：正文 + 结构化计划建议 + 英语积累变更建议 + 解析过程中产生的警告。 */
data class ParsedAssistantReply(
    val reply: String,
    val actions: List<PlanAction>,
    val warnings: List<String>,
    val englishActions: List<EnglishEntryAction> = emptyList(),
)

/**
 * 解析助手返回文本。
 *
 * 容错策略（按顺序尝试）：
 * - 整条文本是合法 JSON → 正常解析；
 * - 整条不是 JSON → 提取消息中的 ```json 围栏块或首个 `{` 到末个 `}` 的子串再解析（模型偶尔在 JSON 前后加说明文字）；
 * - 仍是截断的 JSON（常见于长回答撞上输出上限）→ 抢救已完整的 reply 正文和已完整的动作条目，而不是全部丢弃；
 * - 单条动作字段非法时跳过该动作并记录警告，不影响其余动作与正文。
 */
object AssistantResponseParser {

    const val MAX_TARGET_VALUE = 9_999
    private val MAX_ENGLISH_CONTENT = EnglishEntryRepository.MAX_CONTENT_LENGTH
    private val MAX_ENGLISH_MEANING = EnglishEntryRepository.MAX_MEANING_LENGTH
    private val json = Json { ignoreUnknownKeys = true }
    private val timeRegex = Regex("""^([01]?\d|2[0-3]):([0-5]\d)$""")

    fun parse(raw: String): ParsedAssistantReply {
        val trimmed = raw.trim()
        val normalized = trimmed
            .removePrefix("```json")
            .removePrefix("```JSON")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        parseJsonObject(normalized)?.let { return fromRoot(it, trimmed) }

        // 模型偶尔在 JSON 外面包了说明文字或围栏：尝试任意位置提取 JSON 再解析。
        for (candidate in jsonCandidates(trimmed)) {
            val root = parseJsonObject(candidate) ?: continue
            return fromRoot(root, trimmed)
        }

        // 截断的 JSON：抢救正文与已完整的动作条目，避免「回答正常但按钮消失」。
        salvageTruncated(normalized, trimmed)?.let { return it }

        return ParsedAssistantReply(trimmed, emptyList(), emptyList())
    }

    private fun parseJsonObject(text: String): JsonObject? =
        runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject

    /** 从消息中提取可能的 JSON 候选：```json 围栏块、首个 `{` 到末个 `}` 的子串。 */
    private fun jsonCandidates(raw: String): List<String> {
        val candidates = mutableListOf<String>()
        Regex("```(?:json|JSON)?\\s*(\\{[\\s\\S]*)```").find(raw)?.let {
            candidates += it.groupValues[1].trim()
        }
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start in 0 until end) candidates += raw.substring(start, end + 1)
        return candidates
    }

    private fun fromRoot(root: JsonObject, trimmed: String): ParsedAssistantReply {
        val warnings = mutableListOf<String>()
        val reply = (root["reply"] as? JsonPrimitive)?.contentOrNull
            ?.let(::normalizeAssistantMarkdown)
            ?.trim()
            .orEmpty()
        val actions = (root["plan_actions"] as? JsonArray)
            ?.mapIndexedNotNull { index, element -> parseAction(element, index, warnings) }
            .orEmpty()
        val englishActions = (root["english_actions"] as? JsonArray)
            ?.mapIndexedNotNull { index, element -> parseEnglishAction(element, index, warnings) }
            .orEmpty()

        return ParsedAssistantReply(
            reply = reply.ifEmpty { trimmed },
            actions = actions,
            warnings = warnings,
            englishActions = englishActions,
        )
    }

    /**
     * 截断恢复：整包 JSON 没闭合时（典型原因是长回答撞上输出上限），
     * 尽力把已完整的 reply 与动作条目抢救出来，而不是只留正文丢掉按钮。
     */
    private fun salvageTruncated(normalized: String, trimmed: String): ParsedAssistantReply? {
        val reply = partialReply(normalized) ?: return null
        val warnings = mutableListOf(
            "模型返回的结构化协议未完整结束（可能被输出上限截断），已恢复正文与可解析的动作",
        )
        val actions = salvageActionArray(normalized, "plan_actions") { element, index, warns ->
            parseAction(element, index, warns)
        }
        val englishActions = salvageActionArray(normalized, "english_actions") { element, index, warns ->
            parseEnglishAction(element, index, warns)
        }
        return ParsedAssistantReply(
            reply = reply,
            actions = actions,
            warnings = warnings,
            englishActions = englishActions,
        )
    }

    /**
     * 在截断的原始文本中定位 `"key": [` 数组，逐字符扫描（跳过字符串字面量），
     * 提取所有已完整闭合的 `{ ... }` 对象并解析；未闭合的尾巴直接忽略。
     */
    private fun <T> salvageActionArray(
        raw: String,
        key: String,
        parseOne: (JsonElement, Int, MutableList<String>) -> T?,
    ): List<T> {
        val keyIndex = raw.indexOf("\"$key\"")
        if (keyIndex < 0) return emptyList()
        val arrayStart = raw.indexOf('[', keyIndex)
        if (arrayStart < 0) return emptyList()

        val objects = mutableListOf<String>()
        var depth = 0
        var objStart = -1
        var inString = false
        var escaped = false
        for (i in arrayStart + 1 until raw.length) {
            val c = raw[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when {
                c == '"' -> inString = true
                c == '{' -> {
                    if (depth == 0) objStart = i
                    depth++
                }
                c == '}' -> {
                    depth--
                    if (depth == 0 && objStart >= 0) {
                        objects += raw.substring(objStart, i + 1)
                        objStart = -1
                    }
                    if (depth < 0) break
                }
            }
        }
        val warnings = mutableListOf<String>()
        val parsed = objects.mapIndexedNotNull { index, text ->
            val element = runCatching { json.parseToJsonElement(text) }.getOrNull() ?: return@mapIndexedNotNull null
            parseOne(element, index, warnings)
        }
        return parsed
    }

    /** Salvages the reply string when a model hits its output limit mid-JSON object. */
    private fun partialReply(raw: String): String? {
        val match = Regex("\\\"reply\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"\\\\])*)").find(raw) ?: return null
        val encoded = "\"${match.groupValues[1]}\""
        return runCatching { json.parseToJsonElement(encoded).jsonPrimitive.contentOrNull }
            .getOrNull()
            ?.let(::normalizeAssistantMarkdown)
    }

    private fun parseAction(element: JsonElement, index: Int, warnings: MutableList<String>): PlanAction? {
        val obj = element as? JsonObject ?: run {
            warnings += "第 ${index + 1} 条计划建议不是对象，已忽略"
            return null
        }
        val kind = (obj["kind"] as? JsonPrimitive)?.contentOrNull
        return try {
            when (kind) {
                "UPDATE_TIME_SLOT" -> parseUpdateTimeSlot(obj)
                "UPDATE_TASK_TEMPLATE" -> parseUpdateTemplate(obj)
                "INSERT_TASK_TEMPLATE" -> parseInsertTemplate(obj)
                "UPDATE_TODAY_TASK", "UPDATE_TODAY_TASK_TARGET" -> parseUpdateTodayTask(obj)
                "SKIP_TODAY_TASK" -> parseSkipTodayTask(obj)
                "TAKE_TODAY_OFF" -> PlanAction.TakeTodayOff(obj.reason())
                "KEEP_TODAY_TASKS" -> parseKeepTodayTasks(obj)
                else -> {
                    warnings += "不支持的计划建议类型「$kind」，已忽略"
                    null
                }
            }
        } catch (e: IllegalArgumentException) {
            warnings += "第 ${index + 1} 条计划建议不合法：${e.message}，已忽略"
            null
        }
    }

    private fun parseUpdateTimeSlot(obj: JsonObject): PlanAction? {
        val slotId = obj.idString("slotId")
        require(slotId != null) { "缺少有效的 slotId" }
        val start = obj.time("startTime")
        val end = obj.time("endTime")
        require(start != null || end != null) { "没有给出任何新时间" }
        require(start == null || end == null || start < end) { "开始时间必须早于结束时间" }
        return PlanAction.UpdateTimeSlot(slotId, start, end, obj.reason())
    }

    private fun parseUpdateTemplate(obj: JsonObject): PlanAction? {
        val templateId = obj.idString("templateId")
        require(templateId != null) { "缺少有效的 templateId" }
        val title = (obj["title"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() && it.length <= 60 }
        val targetValue = obj.targetValue()
        val timeSlotId = obj.idString("timeSlotId")
        val repeatRule = obj.enum<RepeatRule>("repeatRule")
        val isKeystone = (obj["isKeystone"] as? JsonPrimitive)?.booleanOrNull
        val isEnabled = (obj["isEnabled"] as? JsonPrimitive)?.booleanOrNull
        require(
            title != null || targetValue != null || timeSlotId != null ||
                repeatRule != null || isKeystone != null || isEnabled != null,
        ) { "没有给出任何字段变化" }
        return PlanAction.UpdateTaskTemplate(
            templateId, title, targetValue, timeSlotId, repeatRule, isKeystone, isEnabled, obj.reason(),
        )
    }

    private fun parseInsertTemplate(obj: JsonObject): PlanAction? {
        val subjectId = obj.idString("subjectId")
        val timeSlotId = obj.idString("timeSlotId")
        val title = (obj["title"] as? JsonPrimitive)?.contentOrNull?.trim()
        require(
            subjectId != null && timeSlotId != null &&
                !title.isNullOrEmpty() && title.length <= 60,
        ) { "缺少有效的科目、时段或标题" }
        val targetValue = obj.targetValue() ?: 1
        return PlanAction.InsertTaskTemplate(
            subjectId = subjectId,
            timeSlotId = timeSlotId,
            title = title,
            taskType = obj.enum<TaskType>("taskType") ?: TaskType.CUSTOM,
            targetType = obj.enum<TargetType>("targetType") ?: TargetType.BOOLEAN,
            targetValue = targetValue,
            repeatRule = obj.enum<RepeatRule>("repeatRule") ?: RepeatRule.DAILY,
            isKeystone = (obj["isKeystone"] as? JsonPrimitive)?.booleanOrNull ?: false,
            note = (obj["note"] as? JsonPrimitive)?.contentOrNull?.trim()?.take(200).orEmpty(),
            reason = obj.reason(),
        )
    }

    private fun parseUpdateTodayTask(obj: JsonObject): PlanAction? {
        val taskId = obj.idString("taskId")
        require(taskId != null) { "缺少有效的 taskId" }
        val targetValue = obj.targetValue()
        val timeSlotId = obj.idString("timeSlotId")
        require(targetValue != null || timeSlotId != null) { "没有给出任何今日任务变化" }
        return PlanAction.UpdateTodayTask(taskId, targetValue, timeSlotId, obj.reason())
    }

    private fun parseSkipTodayTask(obj: JsonObject): PlanAction? {
        val taskId = obj.idString("taskId")
        require(taskId != null) { "缺少有效的 taskId" }
        return PlanAction.SkipTodayTask(taskId, obj.reason())
    }

    private fun parseKeepTodayTasks(obj: JsonObject): PlanAction? {
        val rawIds = obj["keepTaskIds"] as? JsonArray
            ?: throw IllegalArgumentException("缺少 keepTaskIds")
        val ids = rawIds.map { element ->
            (element as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
                ?: throw IllegalArgumentException("keepTaskIds 必须全部是有效的任务 id")
        }.distinct()
        require(ids.isNotEmpty() && ids.size <= 50) { "keepTaskIds 需要包含 1~50 个任务" }
        return PlanAction.KeepTodayTasks(ids, obj.reason())
    }

    // ---------------- English entry actions ----------------

    private fun parseEnglishAction(
        element: JsonElement,
        index: Int,
        warnings: MutableList<String>,
    ): EnglishEntryAction? {
        val obj = element as? JsonObject ?: run {
            warnings += "第 ${index + 1} 条英语积累变更不是对象，已忽略"
            return null
        }
        val kind = (obj["kind"] as? JsonPrimitive)?.contentOrNull
        return try {
            when (kind) {
                "ADD_ENGLISH_ENTRY" -> parseAddEnglishEntry(obj)
                "UPDATE_ENGLISH_ENTRY" -> parseUpdateEnglishEntry(obj)
                "DELETE_ENGLISH_ENTRY" -> parseDeleteEnglishEntry(obj)
                else -> {
                    warnings += "不支持的英语积累变更类型「$kind」，已忽略"
                    null
                }
            }
        } catch (e: IllegalArgumentException) {
            warnings += "第 ${index + 1} 条英语积累变更不合法：${e.message}，已忽略"
            null
        }
    }

    private fun parseAddEnglishEntry(obj: JsonObject): EnglishEntryAction? {
        val type = obj.enum<EnglishEntryType>("type") ?: throw IllegalArgumentException("缺少有效的 type")
        val content = obj.string("content")?.trim()
            ?: throw IllegalArgumentException("缺少英文内容")
        val meaning = obj.string("meaning")?.trim()
            ?: throw IllegalArgumentException("缺少释义")
        require(content.isNotEmpty() && content.length <= MAX_ENGLISH_CONTENT) {
            "英文内容需在 1~$MAX_ENGLISH_CONTENT 个字符内"
        }
        require(meaning.isNotEmpty() && meaning.length <= MAX_ENGLISH_MEANING) {
            "释义需在 1~$MAX_ENGLISH_MEANING 个字符内"
        }
        return EnglishEntryAction.Add(type, content, meaning, obj.reason())
    }

    private fun parseUpdateEnglishEntry(obj: JsonObject): EnglishEntryAction? {
        val id = obj.idString("id")
        require(id != null) { "缺少有效的 id" }
        val type = obj.enum<EnglishEntryType>("type")
        val content = obj.string("content")?.trim()
        val meaning = obj.string("meaning")?.trim()
        require(type != null || !content.isNullOrEmpty() || !meaning.isNullOrEmpty()) {
            "没有给出任何字段变化"
        }
        if (content != null) {
            require(content.isNotEmpty() && content.length <= MAX_ENGLISH_CONTENT) {
                "英文内容需在 1~$MAX_ENGLISH_CONTENT 个字符内"
            }
        }
        if (meaning != null) {
            require(meaning.isNotEmpty() && meaning.length <= MAX_ENGLISH_MEANING) {
                "释义需在 1~$MAX_ENGLISH_MEANING 个字符内"
            }
        }
        return EnglishEntryAction.Update(
            id = id,
            type = type,
            content = content?.takeIf { it.isNotEmpty() },
            meaning = meaning?.takeIf { it.isNotEmpty() },
            reason = obj.reason(),
        )
    }

    private fun parseDeleteEnglishEntry(obj: JsonObject): EnglishEntryAction? {
        val id = obj.idString("id")
        require(id != null) { "缺少有效的 id" }
        return EnglishEntryAction.Delete(id, obj.reason())
    }

    // ---------------- helpers ----------------

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.long(key: String): Long? =
        (this[key] as? JsonPrimitive)?.longOrNull

    /** 容错读取 id：兼容模型输出 UUID 字符串或裸数字。 */
    private fun JsonObject.idString(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.trim()
            ?.takeIf { it.isNotEmpty() && it != "0" }

    private fun JsonObject.reason(): String =
        ((this["reason"] as? JsonPrimitive)?.contentOrNull?.trim()?.take(120)).orEmpty()

    /** 时间字段：未提供返回 null；提供了但格式非法直接让整条动作失败。 */
    private fun JsonObject.time(key: String): LocalTime? {
        val raw = (this[key] as? JsonPrimitive)?.contentOrNull?.trim() ?: return null
        require(timeRegex.matches(raw)) { "时间格式应为 HH:mm，收到「$raw」" }
        return LocalTime.parse(raw.padStart(5, '0'))
    }

    /** 目标量统一校验：必须是 1..MAX_TARGET_VALUE 的正整数，非法时整条动作失败。 */
    private fun JsonObject.targetValue(): Int? {
        val element = this["targetValue"] as? JsonPrimitive ?: return null
        val value = element.intOrNull
        require(value != null && value in 1..MAX_TARGET_VALUE) {
            "目标量必须是 1~$MAX_TARGET_VALUE 的整数"
        }
        return value
    }

    private inline fun <reified T : Enum<T>> JsonObject.enum(key: String): T? {
        val raw = (this[key] as? JsonPrimitive)?.contentOrNull ?: return null
        return runCatching { java.lang.Enum.valueOf(T::class.java, raw) }.getOrNull()
    }
}

/**
 * Repairs the second layer of escaping occasionally added by compatible providers.
 *
 * A global `\\n` replacement is deliberately avoided: it would turn LaTeX commands
 * such as `\\nu` into a line break followed by `u`. Paragraph breaks and the common
 * Markdown marker cases are unambiguous and are enough to restore the rendered reply.
 */
internal fun normalizeAssistantMarkdown(raw: String): String {
    var normalized = raw
    if (raw.contains("\\n") || raw.contains("\\r")) {
        normalized = normalized
            .replace("\\r\\n\\r\\n", "\n\n")
            .replace("\\n\\n", "\n\n")
            .replace("\\r\\n", "\n")
        normalized = normalized.replace(
            Regex("""\\n(?=\s*(?:#{1,6}\s|[-*+]\s|\d+[.)]\s|>\s|```))"""),
            "\n",
        )
        normalized = normalized.replace(
            Regex("""\\r\\n(?=\s*(?:#{1,6}\s|[-*+]\s|\d+[.)]\s|>\s|```))"""),
            "\n",
        )
        // Chinese prose is also a safe paragraph/list continuation; LaTeX commands are not.
        normalized = normalized.replace(Regex("""\\n(?=[\u3400-\u9fff])"""), "\n")
    }
    return normalizeMathDelimiters(normalized)
}

/** Converts common model LaTeX delimiters to the double-dollar syntax used by Markwon. */
private fun normalizeMathDelimiters(markdown: String): String {
    var fence: String? = null
    return markdown.split('\n').joinToString("\n") { line ->
        val trimmedStart = line.trimStart()
        val lineFence = when {
            trimmedStart.startsWith("```") -> "```"
            trimmedStart.startsWith("~~~") -> "~~~"
            else -> null
        }
        if (lineFence != null) {
            fence = if (fence == null) lineFence else if (fence == lineFence) null else fence
            line
        } else if (fence != null) {
            line
        } else {
            val trimmed = line.trim()
            when (trimmed) {
                "\\[", "\\]" -> line.replace(trimmed, "${'$'}${'$'}")
                else -> normalizeInlineMathDelimiters(line)
            }
        }
    }
}

private fun normalizeInlineMathDelimiters(line: String): String {
    val output = StringBuilder(line.length + 16)
    var index = 0
    var inlineCodeTicks = 0
    while (index < line.length) {
        if (line[index] == '`') {
            val tickCount = line.runLength(index, '`')
            inlineCodeTicks = when {
                inlineCodeTicks == 0 -> tickCount
                inlineCodeTicks == tickCount -> 0
                else -> inlineCodeTicks
            }
            output.append(line, index, index + tickCount)
            index += tickCount
            continue
        }
        if (inlineCodeTicks == 0 && !line.isEscaped(index)) {
            val opening = when {
                line.startsWith("\\(", index) -> "\\("
                line.startsWith("\\[", index) -> "\\["
                else -> null
            }
            if (opening != null) {
                val closing = if (opening == "\\(") "\\)" else "\\]"
                val closingIndex = line.indexOf(closing, index + opening.length)
                if (closingIndex >= 0) {
                    output.append("${'$'}${'$'}")
                    output.append(line, index + opening.length, closingIndex)
                    output.append("${'$'}${'$'}")
                    index = closingIndex + closing.length
                    continue
                }
            }
            if (line[index] == '$') {
                if (line.getOrNull(index + 1) == '$') {
                    output.append("${'$'}${'$'}")
                    index += 2
                    continue
                }
                val closingIndex = line.findClosingSingleDollar(index + 1)
                if (closingIndex >= 0) {
                    val body = line.substring(index + 1, closingIndex)
                    if (looksLikeInlineMath(body)) {
                        output.append("${'$'}${'$'}").append(body).append("${'$'}${'$'}")
                        index = closingIndex + 1
                        continue
                    }
                }
            }
        }
        output.append(line[index])
        index++
    }
    return output.toString()
}

private fun String.findClosingSingleDollar(startIndex: Int): Int {
    var index = startIndex
    while (index < length) {
        if (this[index] == '`') return -1
        if (this[index] == '$' && !isEscaped(index) && getOrNull(index - 1) != '$' && getOrNull(index + 1) != '$') {
            return index
        }
        index++
    }
    return -1
}

private fun String.isEscaped(index: Int): Boolean {
    var slashCount = 0
    var cursor = index - 1
    while (cursor >= 0 && this[cursor] == '\\') {
        slashCount++
        cursor--
    }
    return slashCount % 2 == 1
}

private fun String.runLength(startIndex: Int, char: Char): Int {
    var end = startIndex
    while (end < length && this[end] == char) end++
    return end - startIndex
}

private fun looksLikeInlineMath(raw: String): Boolean {
    val value = raw.trim()
    if (value.isBlank() || value.any { it in '\u3400'..'\u9fff' }) return false
    if (value.all { it.isDigit() || it == '.' || it == ',' }) return false
    if ('\\' in value) return true
    if (value.any { it in "=<>^_{}()[]+-*/|!≤≥≠≈∞∈∉⊂⊆⊃⊇∪∩∑∏∫√±×÷→⇒∂" }) return true
    if (Regex("""^(?:[A-Za-z]|[A-Za-z]_[A-Za-z0-9]+)$""").matches(value)) return true
    return Regex("""^(?:sin|cos|tan|log|ln|lim|max|min)\s+[A-Za-z]$""").matches(value)
}

/**
 * 把模型偶尔产出的、JLatexMath 不支持或包裹不规范的 LaTeX 环境，
 * 在渲染前再清洗一次，降低公式以源码形式露出的概率。
 *
 * 处理项：
 * 1. 删除 \tag{}、\tag*{}、\hspace{}、\vspace{} 等不支持的命令；
 * 2. 删除 [6pt] 等行距/间距标记；
 * 3. 把落单的 \begin{aligned}...\end{aligned} 块补 $$ 并转换成 array 环境。
 */
internal fun sanitizeAssistantLatex(markdown: String): String {
    var text = removeUnsupportedLatexCommands(markdown)
    text = convertAlignedEnvironments(text)
    return text
}

private fun removeUnsupportedLatexCommands(text: String): String {
    var result = text
    result = result.replace(Regex("""\\tag(\*)?\s*\{[^}]*\}"""), "")
    result = result.replace(Regex("""\\hspace\s*\{[^}]*\}"""), "")
    result = result.replace(Regex("""\\vspace\s*\{[^}]*\}"""), "")
    result = result.replace(Regex("""\\hfill|\\vfill"""), "")
    // 模型偶尔在 \\ 后输出 [6pt]、[12pt] 等间距，JLatexMath 会解析失败。
    result = result.replace(
        Regex("""\[\d+(?:\.\d+)?(?:pt|em|ex|mm|cm|in)\]""", RegexOption.IGNORE_CASE),
        "",
    )
    return result
}

private val KNOWN_MATH_ENVIRONMENTS = setOf(
    "aligned", "aligned*", "alignedat", "align", "align*",
    "gather", "gather*", "equation", "equation*", "eqnarray", "eqnarray*",
    "cases", "matrix", "pmatrix", "bmatrix", "vmatrix", "Vmatrix", "Bmatrix",
    "array", "split", "multline",
)

private val KNOWN_MATH_ENV_PATTERN = Regex(
    """\\begin\{(${KNOWN_MATH_ENVIRONMENTS.joinToString("|") { Regex.escape(it) }})\}""",
)

/**
 * 遍历 Markdown（跳过代码块），把 \begin{aligned} 等独立数学环境补齐 $$ 包裹，
 * 并把 aligned 转换成 JLatexMath 更友好的 array 环境。
 */
private fun convertAlignedEnvironments(markdown: String): String {
    val lines = markdown.split('\n')
    val out = ArrayList<String>(lines.size + 8)
    var i = 0
    var fence: String? = null
    while (i < lines.size) {
        val line = lines[i]
        val trimmedStart = line.trimStart()
        val lineFence = when {
            trimmedStart.startsWith("```") -> "```"
            trimmedStart.startsWith("~~~") -> "~~~"
            else -> null
        }
        if (lineFence != null) {
            fence = if (fence == null) lineFence else if (fence == lineFence) null else fence
            out += line
            i++
            continue
        }
        if (fence != null) {
            out += line
            i++
            continue
        }
        val envMatch = KNOWN_MATH_ENV_PATTERN.find(line)
        if (envMatch != null) {
            val envName = envMatch.groupValues[1]
            val startIdx = i
            var depth = 1
            var j = i + 1
            while (j < lines.size && depth > 0) {
                val l = lines[j]
                depth += Regex("""\\begin\{${Regex.escape(envName)}\}""").findAll(l).count()
                depth -= Regex("""\\end\{${Regex.escape(envName)}\}""").findAll(l).count()
                if (depth == 0) break
                j++
            }
            if (j < lines.size && depth == 0) {
                val endIdx = j
                val alreadyWrapped = startIdx > 0 && lines[startIdx - 1].trim() == "$$" &&
                    endIdx + 1 < lines.size && lines[endIdx + 1].trim() == "$$"
                val block = (startIdx..endIdx).joinToString("\n") { lines[it] }
                val transformed = transformEnvironment(block, envName)
                if (!alreadyWrapped) {
                    out += "$$"
                    out += transformed
                    out += "$$"
                } else {
                    out += transformed
                }
                i = endIdx + 1
                continue
            }
        }
        out += line
        i++
    }
    return out.joinToString("\n")
}

/**
 * 把 aligned 环境改写成 JLatexMath 更友好的 array 环境。
 *
 * 只要出现「拿不准」的结构（嵌套环境、表格线、行数或列数过多、大括号 / \left \right 不配对），
 * 就原样返回交给渲染器处理：宁可显示源码，也不要生成一个解析必然失败、进而拖垮整段渲染的 array。
 *
 * aligned 各行的 `&` 数量可以不一样，但 array 要求所有行列数一致；
 * 这里先算出最大 `&` 数，再给每行补齐到统一列数。
 */
private fun transformEnvironment(block: String, envName: String): String {
    if (envName != "aligned" && envName != "aligned*") return block
    val begin = "\\begin{$envName}"
    val end = "\\end{$envName}"
    val trimmed = block.trim()
    if (!trimmed.startsWith(begin) || !trimmed.endsWith(end)) return block
    val inner = trimmed.removePrefix(begin).removeSuffix(end).trim()
    if (inner.isEmpty()) return block
    if (inner.contains("\\begin{") || inner.contains("\\hline")) return block
    // 大括号 / \left \right 不配对就别转：array 解析会爆，转回去还能让 JLatexMath 自己拼。
    if (inner.count { it == '{' } != inner.count { it == '}' }) return block
    if (Regex("""\\left\b""").findAll(inner).count() != Regex("""\\right\b""").findAll(inner).count()) return block
    val rows = inner.split(Regex("""\\\\""")).map { it.trim() }.filter { it.isNotEmpty() }
    if (rows.isEmpty() || rows.size > MAX_ARRAY_ROWS) return block
    val maxAmpersands = rows.maxOf { row -> row.count { it == '&' } }
    val columns = maxAmpersands + 1
    if (columns > MAX_ARRAY_COLUMNS) return block
    val spec = "c".repeat(columns)
    // 把每行的 `&` 数补齐到 maxAmpersands，避免 array 列数不一致
    val padded = rows.map { row ->
        val missing = maxAmpersands - row.count { it == '&' }
        if (missing <= 0) row else row.trimEnd() + (1..missing).joinToString("") { " &" }
    }
    val body = padded.joinToString(" \\\\ ")
        .replace(Regex("""\s*&\s*"""), " & ")
        .replace(Regex("""\s+"""), " ")
        .trim()
    return "\\begin{array}{$spec} $body \\end{array}"
}

private const val MAX_ARRAY_ROWS = 12
private const val MAX_ARRAY_COLUMNS = 6
