package com.example.lixing.data.assistant

import com.example.lixing.domain.assistant.EnglishEntryAction
import com.example.lixing.domain.assistant.PlanAction
import com.example.lixing.domain.plot.Axis
import com.example.lixing.domain.plot.ExprEval
import com.example.lixing.domain.plot.MarkArea
import com.example.lixing.domain.plot.MarkLine
import com.example.lixing.domain.plot.PlotSpec
import com.example.lixing.domain.plot.Series
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
import kotlinx.serialization.json.doubleOrNull
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
    /** 模型因达到输出长度上限（finish_reason=length）被截断。 */
    val truncated: Boolean = false,
    /** 模型要求绘制的图表：结构化参数，由客户端本地渲染成图后挂到这条消息上。 */
    val plots: List<PlotSpec> = emptyList(),
    /**
     * 本轮服务商返回的原始思考内容（reasoning 通道）。
     *
     * 正常回答用不到它，只用于一个补救场景：部分兼容服务商在续写时会把本该输出的
     * 正文写进 reasoning 通道，导致正文区不增长、内容只出现在思考面板里。调用方
     * 据此把误入思考的内容取回正文。
     *
     * 注意：**不参与持久化**。思考内容从不写库、不备份（见 [AssistantViewModel] 的约定）。
     */
    val rawReasoning: String = "",
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
/**
 * 修复 JSON 字符串里的 LaTeX 反斜杠（模型最常踩的坑）。
 *
 * 模型在 reply 正文里写公式时经常直接输出单反斜杠（`\frac`、`\alpha`、`\cdot`），
 * 而 JSON 只允许 `\" \\ \/ \b \f \n \r \t \uXXXX` 这几种转义：
 * - `\a`、`\c` 这类**非法序列会让整包解析失败**，界面只能把协议原文（连同
 *   `"plan_actions": []`）当回答显示出来；
 * - `\f`、`\n`、`\t` 虽然合法，却会把 `\frac` 悄悄吃成「换页符 + rac」，公式被改坏。
 *
 * 判据：LaTeX 命令总是「反斜杠 + 多个字母」，而 JSON 转义只跟单个字符。
 * 因此字符串内部「反斜杠 + b/f/n/r/t + 又一个 ASCII 字母」按 LaTeX 处理补成双反斜杠，
 * 其余非法字母同理；真正的换行 `\n`（后面跟中文或标点）不受影响。
 */
internal fun sanitizeJsonEscapes(raw: String): String {
    if ('\\' !in raw) return raw
    val out = StringBuilder(raw.length + 32)
    var inString = false
    var i = 0
    while (i < raw.length) {
        val c = raw[i]
        if (c == '"') {
            inString = !inString
            out.append(c)
            i++
            continue
        }
        if (!inString || c != '\\') {
            out.append(c)
            i++
            continue
        }
        val next = raw.getOrNull(i + 1)
        when {
            next == null -> { out.append("\\\\"); i++ }
            // 已经是合法转义（\\" \\\\ \/）：原样保留
            next == '"' || next == '\\' || next == '/' -> { out.append(c).append(next); i += 2 }
            next == 'u' && i + 5 < raw.length &&
                raw.substring(i + 2, i + 6).all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' } -> {
                out.append(c).append(next).append(raw, i + 2, i + 6)
                i += 6
            }
            // \n 的歧义最大，必须看后面的字母到底拼成什么：
            // 「正文换行 + 英文单词开头」（\nf(x)、\nuv、\nnetwork）里的 \n 是真正的换行，
            // 而 \nabla、\neq、\notin 才是 LaTeX 命令。
            // 早先的判据是「转义字母后面还跟着字母」就按 LaTeX 处理，结果把前者也误还原成
            // 字面反斜杠 n——换行被吃掉、文字连成一片，正文里直接冒出 \nf(x)、\nuv。
            next == 'n' ->
                // 从 n 本身开始取词（i 指向反斜杠），否则 \nabla 会被截成 abla
                if (isLatexNCommand(raw, i + 1)) {
                    out.append("\\\\"); i++
                } else {
                    out.append(c).append(next); i += 2
                }
            // 其余转义字母不用这么讲究：t/f/b/r 的真义（制表/换页/退格/回车）几乎不会出现在
            // 正文里，而 \text \frac \beta \rightarrow 是高频命令；\alpha \cdot \delta 之类
            // 在 JSON 里本就是非法转义，一律按 LaTeX 还原。
            else -> { out.append("\\\\"); i++ }
        }
    }
    return out.toString()
}

/**
 * `\n` 后面跟的字母是否拼成了一个已知的 n 开头 LaTeX 命令。
 *
 * 用精确匹配而不是前缀匹配：所有命令都是完整单词（`\nabla`、`\neq`、`\notin`），
 * 而换行后的英文单词（`network`、`next`）拼出来是别的东西，不会误判。
 */
private fun isLatexNCommand(raw: String, start: Int): Boolean {
    val word = StringBuilder()
    var i = start
    while (i < raw.length && (raw[i] in 'a'..'z' || raw[i] in 'A'..'Z')) {
        word.append(raw[i])
        i++
    }
    return word.isNotEmpty() && word.toString() in LATEX_N_COMMANDS
}

/** `\n` 开头的常见 LaTeX 命令（不含纯换行语义）。 */
private val LATEX_N_COMMANDS = setOf(
    "nabla", "ne", "neq", "neg", "not", "notin", "nu", "nmid", "natural",
    "newline", "nearrow", "nwarrow", "nrightarrow", "nleftarrow",
    "nRightarrow", "nLeftarrow", "nvdash", "nsubseteq", "nsupseteq",
    "ngtr", "nless", "nleq", "ngeq", "nparallel", "nonumber", "normalsize",
    "noindent", "nobreak",
)

object AssistantResponseParser {

    const val MAX_TARGET_VALUE = 9_999
    /** 「每时段至少完成几项」的上限，与编辑页 EditScreens 的校验保持一致。 */
    const val MAX_REQUIRED_TASK_COUNT = 20
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
        runCatching { json.parseToJsonElement(sanitizeJsonEscapes(text)) }.getOrNull() as? JsonObject

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
        val plots = parsePlots(root, warnings)

        return ParsedAssistantReply(
            reply = reply.ifEmpty { trimmed },
            actions = actions,
            warnings = warnings,
            englishActions = englishActions,
            plots = plots,
        )
    }

    private const val MAX_PLOTS = 4
    private const val MAX_SERIES = 6
    private const val MAX_POINTS = 5000
    private const val MAX_MARKS = 12
    private val SERIES_STYLES = setOf("line", "dashed", "marker")

    /**
     * 解析模型给出的绘图请求（顶层 `plots` 数组，或单个 `plot` 对象）。
     *
     * 只做结构校验与上限夹紧，**绝不执行模型给的字符串**——表达式最终交给白名单
     * 求值器 [ExprEval]；这里先试编译一次，把写坏的表达式挡在渲染之前。
     * 单张图不合法就丢掉它并记警告，不影响正文和其它图。
     */
    private fun parsePlots(root: JsonObject, warnings: MutableList<String>): List<PlotSpec> {
        val array = root["plots"] as? JsonArray
        val single = root["plot"] as? JsonObject
        val items: List<JsonElement> = when {
            array != null -> array.take(MAX_PLOTS)
            single != null -> listOf(single)
            else -> return emptyList()
        }
        return items.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            runCatching { parsePlot(obj) }
                .onFailure { warnings += "有一张图表没能生成：${it.message ?: "参数不合法"}" }
                .getOrNull()
        }
    }

    private fun parsePlot(obj: JsonObject): PlotSpec {
        val seriesArray = obj["series"] as? JsonArray ?: throw IllegalArgumentException("缺少 series")
        val series = seriesArray.take(MAX_SERIES).mapNotNull { element ->
            parsePlotSeries(element as? JsonObject ?: return@mapNotNull null)
        }
        require(series.isNotEmpty()) { "series 为空" }
        return PlotSpec(
            title = (obj["title"] as? JsonPrimitive)?.contentOrNull.orEmpty().take(80),
            x = parseAxis(obj["x"] as? JsonObject),
            y = parseAxis(obj["y"] as? JsonObject),
            series = series,
            legend = (obj["legend"] as? JsonPrimitive)?.booleanOrNull ?: (series.size > 1),
            markLines = (obj["markLines"] as? JsonArray)?.take(MAX_MARKS)
                ?.mapNotNull { el ->
                    val o = el as? JsonObject ?: return@mapNotNull null
                    val x = (o["x"] as? JsonPrimitive)?.doubleOrNull
                    val y = (o["y"] as? JsonPrimitive)?.doubleOrNull
                    if (x == null && y == null) {
                        null
                    } else {
                        MarkLine(x, y, (o["label"] as? JsonPrimitive)?.contentOrNull?.take(16))
                    }
                }
                .orEmpty(),
            markAreas = (obj["markAreas"] as? JsonArray)?.take(MAX_MARKS)
                ?.mapNotNull { el ->
                    val o = el as? JsonObject ?: return@mapNotNull null
                    val x0 = (o["x0"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
                    val x1 = (o["x1"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
                    if (x1 <= x0) {
                        null
                    } else {
                        MarkArea(x0, x1, (o["label"] as? JsonPrimitive)?.contentOrNull?.take(16))
                    }
                }
                .orEmpty(),
        )
    }

    private fun parsePlotSeries(obj: JsonObject): Series? {
        val expr = (obj["expr"] as? JsonPrimitive)?.contentOrNull?.take(240)?.takeIf { it.isNotBlank() }
        val points = (obj["points"] as? JsonArray)?.take(MAX_POINTS)?.mapNotNull { el ->
            val pair = el as? JsonArray ?: return@mapNotNull null
            if (pair.size < 2) return@mapNotNull null
            val x = (pair[0] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
            val y = (pair[1] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
            if (!x.isFinite() || !y.isFinite()) null else x to y
        }
        if (expr == null && points.isNullOrEmpty()) return null
        if (expr != null) {
            // 表达式里出现分号/换行一律拒绝；再试编译一次，写坏的别留到渲染时才发现
            require(expr.none { it == ';' || it == '\n' || it == '\r' }) { "表达式包含非法字符" }
            ExprEval.compile(expr)
        }
        return Series(
            label = (obj["label"] as? JsonPrimitive)?.contentOrNull.orEmpty().take(40),
            expr = expr,
            points = points,
            style = (obj["style"] as? JsonPrimitive)?.contentOrNull
                ?.takeIf { it in SERIES_STYLES } ?: "line",
            fill = (obj["fill"] as? JsonPrimitive)?.booleanOrNull ?: false,
            colorIndex = (((obj["colorIndex"] as? JsonPrimitive)?.doubleOrNull)?.toInt() ?: 0)
                .coerceIn(0, 5),
            opacity = ((obj["opacity"] as? JsonPrimitive)?.doubleOrNull ?: 1.0).coerceIn(0.05, 1.0),
        )
    }

    private fun parseAxis(obj: JsonObject?): Axis {
        if (obj == null) return Axis()
        val min = (obj["min"] as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() }
        val max = (obj["max"] as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() }
        // 区间非法就退回自动范围，绝不把 min>=max 传进渲染器
        val validRange = min != null && max != null && max > min
        return Axis(
            label = (obj["label"] as? JsonPrimitive)?.contentOrNull.orEmpty().take(24),
            unit = (obj["unit"] as? JsonPrimitive)?.contentOrNull.orEmpty().take(16),
            min = if (validRange) min else null,
            max = if (validRange) max else null,
            grid = (obj["grid"] as? JsonPrimitive)?.booleanOrNull ?: true,
            ticks = (obj["ticks"] as? JsonArray)?.take(20)
                ?.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull?.takeIf(Double::isFinite) },
            tickLabels = (obj["tickLabels"] as? JsonObject)?.mapNotNull { (key, value) ->
                val tick = key.toDoubleOrNull() ?: return@mapNotNull null
                val text = (value as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                // 上限 24：刻度文案本来就该短，但也要容得下 `$N_0(2\pi f_c)^2$` 这类
                // 带 LaTeX 定界符的写法（16 字符会把公式从中间截断，渲染出来是残缺的）
                tick to text.take(24)
            }?.toMap().orEmpty(),
        )
    }

    /**
     * 截断恢复：整包 JSON 没闭合时（典型原因是长回答撞上输出上限），
     * 尽力把已完整的 reply 与动作条目抢救出来，而不是只留正文丢掉按钮。
     *
     * plots 必须一起抢救：长回答被截断时模型往往**已经把 plots 写在前面**，
     * 只恢复正文会导致 `imagePaths` 为空，而正文里的 `[[FIGURE:n]]` 锚点仍在，
     * 渲染层拿不到图就把锚点当成普通文字显示出来（用户看到正文里裸着
     * `[[FIGURE:1]]`）。这是「图片不显示 + 锚点露出」的根因之一。
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
        val plots = salvagePlots(normalized, warnings)
        return ParsedAssistantReply(
            reply = reply,
            actions = actions,
            warnings = warnings,
            englishActions = englishActions,
            plots = plots,
        )
    }

    /**
     * 从截断文本里抢救已完整闭合的 plot 对象。
     *
     * `plots` 是数组，被截断时通常只有最后一项不完整；前面的项照样能用。
     * 复用 [salvageActionArray] 的逐字符扫描（跳过字符串字面量）拿到已闭合的 `{...}`，
     * 再走与正常路径相同的 [parsePlot] 校验，坏的那张丢掉并记警告。
     */
    private fun salvagePlots(raw: String, warnings: MutableList<String>): List<PlotSpec> {
        val arrayStart = raw.indexOf("\"plots\"")
        if (arrayStart < 0) {
            // 也支持单个 `"plot": {...}` 的写法
            val single = salvageActionArray(raw, "plot") { element, _, _ ->
                (element as? JsonObject)?.let { runCatching { parsePlot(it) }.getOrNull() }
            }
            return single.take(MAX_PLOTS)
        }
        val objects = salvageActionArray(raw, "plots") { element, _, _ ->
            (element as? JsonObject)?.let { obj ->
                runCatching { parsePlot(obj) }
                    .onFailure { warnings += "有一张图表没能生成：${it.message ?: "参数不合法"}" }
                    .getOrNull()
            }
        }
        // 数组本身已闭合时优先走正常路径，避免多一次扫描带来的偏差
        parseJsonObject(raw)?.let { root ->
            parsePlots(root, warnings).takeIf { it.isNotEmpty() }?.let { return it }
        }
        return objects.take(MAX_PLOTS)
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
            val element = runCatching { json.parseToJsonElement(sanitizeJsonEscapes(text)) }.getOrNull()
                ?: return@mapIndexedNotNull null
            parseOne(element, index, warnings)
        }
        return parsed
    }

    /** Salvages the reply string when a model hits its output limit mid-JSON object. */
    private fun partialReply(raw: String): String? {
        val match = Regex("\\\"reply\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"\\\\])*)").find(raw) ?: return null
        val encoded = "\"${match.groupValues[1]}\""
        return runCatching {
            json.parseToJsonElement(sanitizeJsonEscapes(encoded)).jsonPrimitive.contentOrNull
        }
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
        val required = obj.optionalInt("requiredTaskCount")
        require(required == null || required in 0..MAX_REQUIRED_TASK_COUNT) {
            "每时段任务数需在 0~$MAX_REQUIRED_TASK_COUNT 之间"
        }
        require(start != null || end != null || required != null) {
            "没有给出任何新时间或新的任务数"
        }
        require(start == null || end == null || start < end) { "开始时间必须早于结束时间" }
        return PlanAction.UpdateTimeSlot(
            slotId = slotId,
            startTime = start,
            endTime = end,
            requiredTaskCount = required,
            reason = obj.reason(),
        )
    }

    /** 可选整数字段：没填返回 null；填了但不是整数（越界由调用方校验）。 */
    private fun JsonObject.optionalInt(key: String): Int? {
        val element = this[key] ?: return null
        val value = (element as? JsonPrimitive)?.intOrNull
        require(value != null) { "$key 必须是整数" }
        return value
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
    return normalizeTables(normalizeMathDelimiters(normalized))
}

/**
 * 修复 Markdown 表格在 CommonMark 下「渲染不出来」的两种常见形态。
 *
 * 1. **缺少空行**：CommonMark 要求表格与上一段落之间必须有空行，否则整块表格
 *    会被当成普通段落，渲染成一行带竖线的文字。实测（Robolectric + 真实 Markwon）
 *    下表前无空行时 `TableSpan` 数量为 0，即表格完全没被识别——这正是用户截图里
 *    「表格只剩一行、竖线都没了」的成因。模型在「先写一句话、紧接着贴表格」时
 *    极高频地漏掉这个空行。
 * 2. **单元格里的竖线**：行内公式含绝对值/范数（`$$|f| \le B/2$$`）时，裸 `|`
 *    会被 TablePlugin 当成单元格分隔符，导致该行列数与其它行不一致，表格解析错乱
 *    甚至数据行被整行丢弃。这里把「已经处于公式内」的裸 `|` 转义成 `\|`。
 *
 * 只在识别为表格的行上动手，代码围栏内一律跳过。
 */
private fun normalizeTables(markdown: String): String {
    if ('|' !in markdown) return markdown
    val lines = markdown.split('\n')
    val out = ArrayList<String>(lines.size + 8)
    var fence: String? = null
    var index = 0
    while (index < lines.size) {
        val line = lines[index]
        val trimmedStart = line.trimStart()
        val lineFence = when {
            trimmedStart.startsWith("```") -> "```"
            trimmedStart.startsWith("~~~") -> "~~~"
            else -> null
        }
        if (lineFence != null) {
            fence = if (fence == null) lineFence else if (fence == lineFence) null else fence
            out += line
            index++
            continue
        }
        if (fence != null) {
            out += line
            index++
            continue
        }
        // 表格首行 = 含 | 且下一行是分隔行
        val next = lines.getOrNull(index + 1)
        if ('|' in line && next != null && isTableSeparatorLine(next)) {
            // ① 表格上方补空行（它前面若是非空、非表格内容）
            if (out.isNotEmpty() && out.last().isNotBlank()) out += ""
            out += escapeTableRowPipes(line)
            out += escapeTableRowPipes(next)
            index += 2
            // ② 数据行：一路吃到不再是表格行
            while (index < lines.size && '|' in lines[index] && lines[index].isNotBlank()) {
                out += escapeTableRowPipes(lines[index])
                index++
            }
            // ③ 表格下方补空行（后面若还有内容）
            if (index < lines.size && lines[index].isNotBlank()) out += ""
            continue
        }
        out += line
        index++
    }
    return out.joinToString("\n")
}

/** Markdown 表格的分隔行（`| --- | :--: |`）。与渲染层的判定保持一致。 */
private fun isTableSeparatorLine(line: String): Boolean {
    val trimmed = line.trim()
    if ('-' !in trimmed) return false
    val cells = trimmed.trim('|').split('|')
    return cells.isNotEmpty() && cells.all { cell ->
        val token = cell.trim()
        token.isNotEmpty() && token.all { it == '-' || it == ':' } && '-' in token
    }
}

/**
 * 把表格行中**位于公式内部**的裸 `|` 转义为 `\|`。
 *
 * 只处理 `$$...$$` 区间内的竖线：公式外的 `|` 是单元格分隔符，必须保留。
 * 表格引线（行首/行尾的 `|`）一定在公式外，天然不受影响。
 */
private fun escapeTableRowPipes(row: String): String {
    if (!row.contains("$$")) return row
    val out = StringBuilder(row.length + 8)
    var index = 0
    var inMath = false
    while (index < row.length) {
        if (row.startsWith("$$", index)) {
            inMath = !inMath
            out.append("$$")
            index += 2
            continue
        }
        val c = row[index]
        if (c == '|' && inMath && !row.isEscaped(index)) {
            out.append("\\|")
        } else {
            out.append(c)
        }
        index++
    }
    return out.toString()
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
    // 这里**不能**把纯数字排除掉。
    //
    // 原来是 `if (value.all { it.isDigit() || it == '.' || it == ',' }) return false`，
    // 于是 `$0$` / `$1$` 这种最简公式不会被提升成块级、独自留在行内 ——
    // 而同一段文本里其它公式（以及 `\(...\)` 写法）都会走提升路径。
    // 两条路径不一致，用户看到的就是「单独出现的 $0$ 渲染不出来」（v1.0.35 反馈）。
    // 纯数字在数学语境里本来就是公式（数值 0），没有理由区别对待。
    //
    // 会不会误判货币写法？不会：`$5 ... $10` 这种 body 里必然含空格或字母，
    // 仍然会被下面「数学符号 / 单变量」两条规则挡掉。
    if ('\\' in value) return true
    if (value.any { it in "=<>^_{}()[]+-*/|!≤≥≠≈∞∈∉⊂⊆⊃⊇∪∩∑∏∫√±×÷→⇒∂" }) return true
    if (value.all { it.isDigit() || it == '.' }) return true
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

/**
 * 给 `\frac` 的裸参数补上花括号：`\frac B2` → `\frac{B}{2}`。
 *
 * 标准 LaTeX 允许 `\frac B2`（两个单 token），但 **JLatexMath 不接受**，会抛
 * ParseException，于是整条公式退化成「⚠ 公式无法渲染」的灰色占位
 * （用户反馈的 `0,\qquad f_c-\frac B2<|f|<f_c+\frac B2` 整条失败就是此因）。
 * 只补花括号、不动其它结构；本来就是 `\frac{}{}` 的写法是幂等的。
 *
 * 注意正则要求反斜杠后**紧跟** `frac`，所以 `\dfrac` 不会被误伤。
 */
private val FRAC_BRACELESS =
    Regex("""\\frac\s*(\{[^{}]*\}|[A-Za-z0-9])\s*(\{[^{}]*\}|[A-Za-z0-9])""")

internal fun normalizeLatexFractions(latex: String): String =
    FRAC_BRACELESS.replace(latex) { m ->
        val num = m.groupValues[1].removeSurrounding("{", "}")
        val den = m.groupValues[2].removeSurrounding("{", "}")
        """\frac{$num}{$den}"""
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
    // 需要额外宏包、JLatexMath 里根本不存在的命令（已逐个实测确认）。
    // 按语义降级，而不是留着让整段公式渲染失败：
    result = result.replace(Regex("""\\cancel\s*\{([^{}]*)\}""")) { it.groupValues[1] } // cancel 包
    result = result.replace(Regex("""\\color\s*\{[^{}]*\}""")) { "" } // xcolor 的 \color
    result = result.replace(Regex("""\\intertext\s*\{([^{}]*)\}""")) {
        "\\\\ \\text{${it.groupValues[1]}}" // amsmath：降级成「换行 + 文本」
    }
    result = result.replace(Regex("""\\begin\{dcases\}""")) { "\\begin{cases}" } // mathtools
    result = result.replace(Regex("""\\end\{dcases\}""")) { "\\end{cases}" }
    result = normalizeUnsupportedDelimiters(result)
    // \frac 的裸参数补花括号（JLatexMath 不支持 \frac B2），放最后统一处理
    result = normalizeLatexFractions(result)
    return result
}

/**
 * 降级 JLatexMath 不支持的左右成对定界符命令。
 *
 * ⚠️ **实测结论（[JLatexMathDelimiterSupportTest] 里的探测表，改前务必先读）**：
 * JLatexMath 对大小写变体的支持**极不对称**——
 *
 * | 写法 | 结果 |
 * |---|---|
 * | `\lvert … \rvert` | ❌ ParseException |
 * | `\lVert … \rVert` | ✅ |
 * | `\left\lvert … \right\rvert` | ❌ ParseException |
 * | `\|…\|` / `\left\|…\right\|` | ✅ |
 * | `|…|` / `\left|…\right|` | ✅ |
 * | `\lbrace`/`\langle`/`\lceil`/`\lfloor` | ✅ |
 *
 * 即：**双竖线能用、单竖线不能用**（只差一个字母大小写）。用户截图里
 * `\lvert y_L(T)\rvert` 整条渲染失败就是此因。
 *
 * 所以必须**分开降级**，绝不能把两类混成一个规则：
 * - `\lvert`/`\rvert` → `|`（绝对值/模，单竖线语义）
 * - `\lVert`/`\rVert` → `\|`（范数，双竖线语义）
 *
 * 若把 `\lVert` 也换成单竖线，会**悄悄改变数学含义**（把范数写成绝对值），
 * 比渲染失败更糟——渲染失败用户看得见，语义变错看不见。
 *
 * `\left`/`\right` 前缀要**一起换**（`\left\lvert` → `\left|`）：只换掉后半段会
 * 留下 `\left|`，虽然恰好也合法，但分两步走容易在「`\left` 后面不是 `\lvert`」
 * 这类形态上误伤，整体匹配更稳。
 */
private fun normalizeUnsupportedDelimiters(latex: String): String {
    var result = latex
    // ⚠️ 替换串一律用 **lambda 形式** `{ "…" }`，不要用字符串重载：
    // `Regex.replace(input, "\\|")` 走的是 Java `Matcher.replaceAll` 语义，
    // 会把替换串里的 `\` 当转义符再解析一次 ⇒ `\|` 变成字面 `|`，
    // 反斜杠被静默吃掉（范数 `\lVert x \rVert` 会退化成 `| x |`，语义从
    // 「范数」悄悄变成「绝对值」——渲染成功但含义错了，比渲染失败更糟）。
    // lambda 的返回值是字面量，不经过转义解析，所见即所得。
    // 双竖线（范数）
    result = result.replace(Regex("""\\left\s*\\lVert""")) { "\\left\\|" }
    result = result.replace(Regex("""\\right\s*\\rVert""")) { "\\right\\|" }
    result = result.replace(Regex("""\\lVert""")) { "\\|" }
    result = result.replace(Regex("""\\rVert""")) { "\\|" }
    // 单竖线（绝对值/模）——这个是真正的 FAIL 项，必须降级
    result = result.replace(Regex("""\\left\s*\\lvert""")) { "\\left|" }
    result = result.replace(Regex("""\\right\s*\\rvert""")) { "\\right|" }
    result = result.replace(Regex("""\\lvert""")) { "|" }
    result = result.replace(Regex("""\\rvert""")) { "|" }
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
            val beginRe = Regex("""\\begin\{${Regex.escape(envName)}\}""")
            val endRe = Regex("""\\end\{${Regex.escape(envName)}\}""")
            // 先数起始行自身：模型最常把 cases/matrix 等写成「\begin{...}...\end{...}」单行，
            // 之前只往后续行找闭合，单行环境永远匹配不到 → 不补 $$ → 整段落成 Markdown 纯文本
            // （表现为 \\ 被 Markdown 吃成 \ 、& 原样露出）。
            var depth = beginRe.findAll(line).count() - endRe.findAll(line).count()
            if (depth == 0) {
                // 单行环境已在本行闭合。行内任一侧已带 $$ 的（已在公式里）原样放行，
                // 交给 wrapLongFormulas 按宽度拆分；其余整行补 $$ 包裹。
                // 还要统计环境之前的整份文档：多行显示块经常是「$$ 单独一行 + 环境单独一行」，
                // 此时当前行两侧都没有 $$，但环境仍已处于打开的显示块内。
                val alreadyInMath =
                    countDisplayDelimiters(lines, 0, i) % 2 == 1 ||
                        line.substring(0, envMatch.range.first).contains("$$") ||
                        line.substring(envMatch.range.last + 1).contains("$$")
                if (alreadyInMath) {
                    out += line
                } else {
                    out += "$$" + line.trim() + "$$"
                }
                i++
                continue
            }
            val startIdx = i
            var j = i + 1
            while (j < lines.size && depth > 0) {
                val l = lines[j]
                depth += beginRe.findAll(l).count()
                depth -= endRe.findAll(l).count()
                if (depth == 0) break
                j++
            }
            if (j < lines.size && depth == 0) {
                val endIdx = j
                // 这个环境是否**已经处在某个未闭合的 $$ 显示块内部**？
                //
                // 只判断「上一行是不是以 $$ 开头」是不够的 —— 模型很常把公式写成
                //     $$
                //     S_{Y_c}(f) = S_{Y_s}(f) =      ← 等号收尾，environment 在下一行
                //     \begin{cases}
                //     ...
                //     \end{cases}
                //     $$
                // 此时上一行既不以 $$ 开头、也不以 $$ 结尾，于是被判为「未包裹」，
                // 我们再补一对 $$ 塞进去 ⇒ **块内凭空多出一对定界符**，
                // 显示块被从中间劈开：`=` 之前的内容渲染成公式，`\begin{cases}` 起始的
                // 后半段掉出数学上下文、以 LaTeX 源码原样显示
                // （用户截图正是「`S_{Y_c}(f) = S_{Y_s}(f) =` 是公式，下面 cases 是源码」）。
                //
                // 正确判据是**数 $$ 的收支**：从文档开头累计到环境起始行之前，
                // 若已出现**奇数**个 $$，说明显示块已经打开、环境本就在其中，绝不能补。
                val insideOpenDisplay =
                    countDisplayDelimiters(lines, 0, startIdx) % 2 == 1
                // 兼容旧判据：上一行以 $$ 开头（含「$$ 内容」）且环境之后紧跟收尾 $$ 的写法
                val legacyWrapped = startIdx > 0 && lines[startIdx - 1].trimStart().startsWith("$$") &&
                    endIdx + 1 < lines.size && lines[endIdx + 1].trimEnd().endsWith("$$")
                val alreadyWrapped = insideOpenDisplay || legacyWrapped
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
 * 统计 [from, until) 行范围内出现的 `$$` 定界符个数。
 *
 * 用于判断某个位置是否已经处在未闭合的显示块内部（奇数 = 已打开）。
 * 只数成对出现的 `$$`；行内的 `$...$`（单美元）不计入，因为它不改变显示块状态。
 * 代码块内部的 `$$` 同样不计入 —— 那里的内容是字面量，不参与数学块配对。
 */
private fun countDisplayDelimiters(lines: List<String>, from: Int, until: Int): Int {
    var count = 0
    var fence: String? = null
    for (index in from until minOf(until, lines.size)) {
        val line = lines[index]
        val trimmedStart = line.trimStart()
        val lineFence = when {
            trimmedStart.startsWith("```") -> "```"
            trimmedStart.startsWith("~~~") -> "~~~"
            else -> null
        }
        if (lineFence != null) {
            fence = if (fence == null) lineFence else if (fence == lineFence) null else fence
            continue
        }
        if (fence != null) continue
        var i = 0
        while (i < line.length - 1) {
            if (line[i] == '$' && line[i + 1] == '$') {
                count++
                i += 2
            } else {
                i++
            }
        }
    }
    return count
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
    // equation 是 JLatexMath 唯一不认识的常见环境（实测报 "Unknown environment: equation"；
    // align / gather / multline / eqnarray / split / aligned 都能渲染）。
    // 它只表示「独立成行 + 编号」，剥掉标签对公式内容毫无影响，外层 $$ 由调用方补。
    // 之前 KNOWN_MATH_ENVIRONMENTS 把它列进白名单却没人转换它，于是每次都原样送进渲染器、
    // 每次都抛异常 → 表现为「同一处公式反复修也渲染不出来」。
    if (envName == "equation" || envName == "equation*") {
        return block
            .replace(Regex("""\\begin\{equation\*?\}"""), "")
            .replace(Regex("""\\end\{equation\*?\}"""), "")
            .trim()
    }
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
