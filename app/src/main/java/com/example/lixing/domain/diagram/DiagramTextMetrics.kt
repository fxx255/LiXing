package com.example.lixing.domain.diagram

import android.graphics.Paint
import ru.noties.jlatexmath.JLatexMathDrawable

enum class DiagramTextRole(val fontSizePx: Float) {
    TITLE(19f), LABEL(17f), SUB_LABEL(13f), EDGE_LABEL(14f),
}

/** Layout and drawing share measured runs, including the height of fractions. */
internal data class DiagramTextRun(
    val text: String,
    val width: Float,
    val height: Float,
    val formula: JLatexMathDrawable? = null,
)
internal data class DiagramTextLine(val runs: List<DiagramTextRun>) {
    val width = runs.sumOf { it.width.toDouble() }.toFloat()
    val height = runs.maxOfOrNull { it.height } ?: 0f
}
internal data class DiagramTextBlock(val lines: List<DiagramTextLine>) {
    val width = lines.maxOfOrNull { it.width } ?: 0f
    val height = lines.sumOf { (it.height + 4f).toDouble() }.toFloat()
}

internal object DiagramText {
    private val tokens = Regex("""\$[^$\n]+\$|\n|[A-Za-z0-9]+|[^\n]""")

    fun layout(text: String, role: DiagramTextRole, maxWidth: Float): DiagramTextBlock {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = role.fontSizePx }
        val lines = mutableListOf<DiagramTextLine>()
        var runs = mutableListOf<DiagramTextRun>()
        var width = 0f
        fun flush() {
            if (runs.isNotEmpty()) lines += DiagramTextLine(runs.toList())
            runs = mutableListOf()
            width = 0f
        }
        tokens.findAll(text).forEach { match ->
            val token = match.value
            if (token == "\n") { flush(); return@forEach }
            val formula = if (token.startsWith('$') && token.endsWith('$') && token.length > 2) {
                runCatching {
                    JLatexMathDrawable.builder(token.substring(1, token.length - 1))
                        .textSize(role.fontSizePx).build()
                }.getOrNull()
            } else null
            val run = DiagramTextRun(token,
                formula?.intrinsicWidth?.toFloat() ?: paint.measureText(token),
                formula?.intrinsicHeight?.toFloat() ?: (paint.fontMetrics.descent - paint.fontMetrics.ascent),
                formula)
            if (runs.isNotEmpty() && width + run.width > maxWidth) flush()
            if (runs.isEmpty() && token.isBlank()) return@forEach
            runs += run
            width += run.width
        }
        flush()
        return DiagramTextBlock(lines)
    }
}
