package com.example.lixing.data.assistant

/**
 * 把过长的公式在顶层“安全断点”处拆成多个块级公式，避免右侧溢出被裁剪。
 * 断点只取「大括号深度 0 且不在 \begin{...} 环境内部」的位置，保证每段仍是可独立渲染的 LaTeX；
 * 整体就是一个 array/aligned 行式环境时，按行解包而不是硬切环境。
 */
internal fun wrapLongFormulas(
    markdown: String,
    maxWidthPx: Int,
    measure: (String) -> Int,
): String {
    if (maxWidthPx <= 0) return markdown
    val out = ArrayList<String>()
    var fence: String? = null
    var inDisplay = false
    val block = StringBuilder()
    for (line in markdown.split('\n')) {
        val trimmed = line.trimStart()
        val lineFence = when {
            trimmed.startsWith("```") -> "```"
            trimmed.startsWith("~~~") -> "~~~"
            else -> null
        }
        val t = line.trim()
        when {
            lineFence != null -> {
                fence = if (fence == null) lineFence else if (fence == lineFence) null else fence
                out += line
            }
            fence != null -> out += line
            inDisplay -> {
                when {
                    // 纯 $$ 行：常规关闭
                    t == DISPLAY_DELIMITER -> {
                        inDisplay = false
                        out += splitFormulaBlocks(block.toString().trim(), maxWidthPx, measure)
                        block.setLength(0)
                    }
                    // 「内容 $$」行：内容并入块后再关闭（模型偶尔把闭合符写在公式尾部）。
                    // 行内必须只有这一个 $$，否则可能是「…$$ … $$」的行内对，交回普通文本处理。
                    t.endsWith(DISPLAY_DELIMITER) &&
                        t.indexOf(DISPLAY_DELIMITER) == t.length - DISPLAY_DELIMITER.length -> {
                        if (t.length > DISPLAY_DELIMITER.length) {
                            block.append('\n').append(t.substring(0, t.length - DISPLAY_DELIMITER.length))
                        }
                        inDisplay = false
                        out += splitFormulaBlocks(block.toString().trim(), maxWidthPx, measure)
                        block.setLength(0)
                    }
                    else -> {
                        if (block.isNotEmpty()) block.append('\n')
                        block.append(line)
                    }
                }
            }
            t == DISPLAY_DELIMITER -> inDisplay = true
            // 「$$ 内容」行（如 "$$y(n) ="）：开启显示块，内容并入块。
            // 之前这种写法开不了显示块，后续行的裸环境会整段落成纯文本。
            // 仅当行内再无第二个 $$ 时成立；「$$…$$ 文字」这类行内公式仍走 wrapInlineLine。
            t.startsWith(DISPLAY_DELIMITER) && t.indexOf(DISPLAY_DELIMITER, 2) < 0 -> {
                inDisplay = true
                val content = t.substring(DISPLAY_DELIMITER.length).trim()
                if (content.isNotEmpty()) block.append(content)
            }
            else -> out += wrapInlineLine(line, maxWidthPx, measure)
        }
    }
    if (inDisplay && block.toString().isNotBlank()) {
        out += splitFormulaBlocks(block.toString().trim(), maxWidthPx, measure)
    }
    return out.joinToString("\n")
}

private const val DISPLAY_DELIMITER = "$$"

private fun splitFormulaBlocks(latex: String, maxWidthPx: Int, measure: (String) -> Int): List<String> {
    if (latex.isEmpty()) return emptyList()
    return splitFormula(latex, maxWidthPx, measure).flatMap { listOf(DISPLAY_DELIMITER, it, DISPLAY_DELIMITER) }
}

private fun wrapInlineLine(line: String, maxWidthPx: Int, measure: (String) -> Int): List<String> {
    if (!line.contains(DISPLAY_DELIMITER)) return listOf(line)
    val out = mutableListOf<String>()
    val text = StringBuilder()
    var i = 0
    while (i < line.length) {
        if (line.startsWith(DISPLAY_DELIMITER, i)) {
            val close = line.indexOf(DISPLAY_DELIMITER, i + 2)
            if (close < 0) {
                text.append(line, i, line.length)
                break
            }
            val latex = line.substring(i + 2, close)
            if (measure(latex) > maxWidthPx) {
                text.toString().trim().takeIf { it.isNotEmpty() }?.let { out += it }
                out += splitFormulaBlocks(latex, maxWidthPx, measure)
                text.setLength(0)
            } else {
                text.append(DISPLAY_DELIMITER).append(latex).append(DISPLAY_DELIMITER)
            }
            i = close + 2
        } else {
            text.append(line[i])
            i++
        }
    }
    text.toString().trim().takeIf { it.isNotEmpty() }?.let { out += it }
    return out.ifEmpty { listOf(line) }
}

/** 优先级依次为：对齐换行 → 逗号分号 → 关系符 → 加减号。 */
internal fun splitFormula(latex: String, maxWidthPx: Int, measure: (String) -> Int): List<String> {
    val value = latex.trim()
    if (value.isEmpty()) return emptyList()
    if (measure(value) <= maxWidthPx) return listOf(value)
    // 整体是一个 array/aligned 这类「行式」环境且放不下：
    // 先按行解包成独立的块级公式（去掉环境包裹与对齐符 &），
    // 而不是在环境内部的 \\ 处硬切——那会产生带无配对 \begin/\end 的孤儿片段，必然解析失败。
    unwrapEnvironmentRows(value)?.let { rows ->
        return rows.flatMap { row ->
            if (measure(row) <= maxWidthPx) listOf(row) else splitFormula(row, maxWidthPx, measure)
        }
    }
    var pieces = listOf(value)
    for (priority in 0..3) {
        if (pieces.all { measure(it) <= maxWidthPx }) break
        pieces = pieces.flatMap { piece ->
            if (measure(piece) <= maxWidthPx) listOf(piece) else greedySplit(piece, priority, maxWidthPx, measure)
        }
    }
    return pieces
}

/**
 * 把 `\begin{env} ...\end{env}`（env ∈ [UNWRAPPABLE_ENVS]）解包成若干行独立公式。
 *
 * 仅当满足以下全部条件才解包，否则返回 null（交给环境感知的通用拆分兜底）：
 * - 环境是「行式」的对齐/堆叠环境（array/aligned 等；cases、各类 matrix 是真正的
 *   表格/矩阵，行内 `&` 是分隔符不是对齐符，拆开语义就错了）；
 * - 体内没有嵌套环境、没有 \hline；
 * - 每行至多 1 个 `&`（对齐符才可安全删除；>1 说明是表格，拆开会改变含义）。
 *
 * 已知代价：真正的两列表格（`a & b \\ c & d`）若超宽会被拍平成 `a b`。
 * 权衡是：走到这里的都是「整块已放不下」的公式，原样保留只会渲染成源码占位框。
 */
internal fun unwrapEnvironmentRows(latex: String): List<String>? {
    val match = ENVIRONMENT_LINE_RE.find(latex) ?: return null
    val env = match.groupValues[1]
    if (env !in UNWRAPPABLE_ENVS) return null
    val body = stripLeadingGroup(match.groupValues[2].trim())
    if (body.isEmpty() || body.contains("\\begin{") || body.contains("\\hline")) return null
    val (rows, skippedRowBreak) = splitTopLevelRows(body)
    // 有被跳过的 \\（\left[ 之类跨行配对括号内部）：按行解包会留下带孤立括号和残留 \\ 的坏行，
    // 此时整块保留——原环境是合法 LaTeX，交给渲染器整体处理。
    if (skippedRowBreak) return null
    if (rows.size < 2) return null
    if (rows.any { it.count { c -> c == '&' } > 1 }) return null
    val cleaned = rows.map { it.replace("&", " ").trim().replace(Regex(" {2,}"), " ") }.filter { it.isNotEmpty() }
    return cleaned.ifEmpty { null }
}

/** 匹配「整段就是一个数学环境」：\begin{x}…\end{x}，允许跨行。 */
private val ENVIRONMENT_LINE_RE = Regex(
    """^\\begin\{([A-Za-z*]+)\}(.*)\\end\{\1\}$""",
    setOf(RegexOption.DOT_MATCHES_ALL),
)

/** 行式环境：行是独立公式，`&` 只是对齐符，解包后仍各自成立。 */
private val UNWRAPPABLE_ENVS = setOf(
    "array", "aligned", "aligned*", "align", "align*",
    "gather", "gather*", "split", "multline",
)

/** 去掉 array 的列定义前缀（如 {cc}），aligned 等没有前缀则原样返回。 */
private fun stripLeadingGroup(body: String): String {
    if (!body.startsWith("{")) return body
    var depth = 0
    for (index in body.indices) {
        when (body[index]) {
            '{' -> depth++
            '}' -> {
                depth--
                if (depth == 0) return body.substring(index + 1).trim()
            }
        }
    }
    return body
}

/** 在「括号深度 0」（与 splitPoints 同一套 ()[]{} 规则）的 `\\` 处把环境体切成行；深度 > 0 处的 `\\` 跳过并上报。 */
private fun splitTopLevelRows(body: String): Pair<List<String>, Boolean> {
    val rows = mutableListOf<String>()
    val current = StringBuilder()
    var depth = 0
    var skippedRowBreak = false
    var i = 0
    while (i < body.length) {
        if (body.startsWith("\\\\", i)) {
            if (depth == 0) {
                rows += current.toString()
                current.setLength(0)
            } else {
                skippedRowBreak = true
                current.append("\\\\")
            }
            i += 2
            continue
        }
        when (body[i]) {
            '{', '(', '[' -> depth++
            '}', ')', ']' -> depth--
        }
        current.append(body[i])
        i++
    }
    rows += current.toString()
    return Pair(
        rows.map { it.trim() }.filter { it.isNotEmpty() },
        skippedRowBreak,
    )
}

private fun greedySplit(latex: String, priority: Int, maxWidthPx: Int, measure: (String) -> Int): List<String> {
    val cuts = splitPoints(latex, priority)
    if (cuts.isEmpty()) return listOf(latex)
    val segments = mutableListOf<String>()
    var start = 0
    for (cut in cuts) {
        if (cut > start) segments += latex.substring(start, cut)
        start = cut
    }
    segments += latex.substring(start)
    val merged = mutableListOf<StringBuilder>()
    for (segment in segments) {
        if (segment.isBlank()) continue
        val last = merged.lastOrNull()
        if (last == null || measure(last.toString() + segment) > maxWidthPx) {
            merged += StringBuilder(segment)
        } else {
            last.append(segment)
        }
    }
    val result = merged.map { it.toString().trim() }.filter { it.isNotEmpty() }
    return result.ifEmpty { listOf(latex) }
}

private fun splitPoints(latex: String, priority: Int): List<Int> {
    val points = mutableListOf<Int>()
    var depth = 0
    // \begin{...}...\end{...} 环境内部绝不能切：切开的片段带着无配对的环境标记，必然解析失败。
    var envDepth = 0
    var i = 0
    while (i < latex.length) {
        val c = latex[i]
        if (c == '\\') {
            if (latex.startsWith("\\begin{", i)) {
                envDepth++
                i += "\\begin{".length
                while (i < latex.length && latex[i] != '}') i++
                i++
                continue
            }
            if (latex.startsWith("\\end{", i)) {
                envDepth--
                i += "\\end{".length
                while (i < latex.length && latex[i] != '}') i++
                i++
                continue
            }
            if (priority == 0 && envDepth == 0 && latex.startsWith("\\\\", i)) {
                if (depth == 0) points += i
                i += 2
                continue
            }
            if (priority == 2 && envDepth == 0) {
                val command = RELATION_COMMANDS.firstOrNull {
                    latex.startsWith(it, i) && latex.getOrNull(i + it.length)?.isLetter() != true
                }
                if (command != null) {
                    if (depth == 0) points += i
                    i += command.length
                    continue
                }
            }
            // 符号命令（\, \; \! \- \{ \} 等）必须与反斜杠作为一个整体跳过：
            // 否则 \, 里的逗号会被当成优先级 1 断点，切出以孤立 \ 结尾的非法片段；
            // \{ \} 里的花括号还会污染深度计数。
            i++
            if (i < latex.length && latex[i].isLetter()) {
                while (i < latex.length && latex[i].isLetter()) i++
            } else if (i < latex.length) {
                i++
            }
            continue
        }
        when (c) {
            '{', '(', '[' -> depth++
            '}', ')', ']' -> depth--
        }
        if (depth == 0 && envDepth == 0) {
            val isPoint = when (priority) {
                1 -> c == ',' || c == ';'
                2 -> c == '=' || c == '<' || c == '>'
                3 -> c == '+' || c == '-'
                else -> false
            }
            if (isPoint) points += i
        }
        i++
    }
    return points
}

private val RELATION_COMMANDS = listOf(
    "\\Rightarrow", "\\Leftarrow", "\\rightarrow", "\\leftarrow",
    "\\subseteq", "\\supseteq", "\\subset", "\\supset",
    "\\geq", "\\leq", "\\neq", "\\ge", "\\le", "\\ne",
    "\\approx", "\\equiv", "\\to",
)