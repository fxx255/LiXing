package com.example.lixing.domain.diagram

import android.graphics.Paint
import ru.noties.jlatexmath.JLatexMathDrawable

enum class DiagramTextRole(val fontSizePx: Float) {
    // The bitmap is shown inside a phone-width message bubble.  The old
    // values were legible at the renderer's native size but became tiny after
    // the wide diagram was fitted into the bubble.
    TITLE(24f), LABEL(25f), SUB_LABEL(20f), EDGE_LABEL(25f),
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
    private val cosineCarrier = Regex("""(?i)\bcos\s*2\s*π\s*f_?\s*c\s*t\b""")
    private val sineCarrier = Regex("""(?i)\bsin\s*2\s*π\s*f_?\s*c\s*t\b""")

    /**
     * Older model responses commonly wrote the carrier labels as plain text
     * (`cos 2πfct`).  Turn that narrow, unambiguous notation into math before
     * measuring and drawing it, so the c subscript is retained like it is in
     * the textbook.
     */
    private fun normalizeCommonMath(text: String): String {
        if ('$' in text) return text
        return text
            .replace(cosineCarrier) { "\$\\cos 2\\pi f_c t\$" }
            .replace(sineCarrier) { "\$\\sin 2\\pi f_c t\$" }
    }

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
        tokens.findAll(normalizeCommonMath(text)).forEach { match ->
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
