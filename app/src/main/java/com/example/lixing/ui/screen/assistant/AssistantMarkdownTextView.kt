package com.example.lixing.ui.screen.assistant

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.text.Spannable
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.util.Log
import android.view.MotionEvent
import android.widget.TextView
import com.example.lixing.data.assistant.normalizeAssistantMarkdown
import com.example.lixing.data.assistant.sanitizeAssistantLatex
import com.example.lixing.data.assistant.wrapLongFormulas
import io.noties.markwon.Markwon
import io.noties.markwon.ext.latex.JLatexMathPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.inlineparser.MarkwonInlineParserPlugin
import java.io.File
import java.time.Instant
import java.util.concurrent.Executors
import ru.noties.jlatexmath.JLatexMathDrawable

internal fun createMarkdownTextView(
    context: Context,
    textColor: Int,
    linkColor: Int,
    selectable: Boolean = true,
): TextView =
    object : TextView(context) {
        /**
         * 只在**真的按在链接/可点片段上**时才消费触摸，其余一律放行。
         *
         * 这一段是「文本块不得抢占触摸」的**真正兜底**，不能省。原因见下方 `apply {}`
         * 块内的长注释：`isClickable` / `isLongClickable` 这些属性会被 AOSP 框架随时
         * 改回来，靠「设一次属性」是不可靠的；而在 `onTouchEvent` 这一层按落点判定，
         * 才是与框架无关的最终裁决点。
         */
        override fun onTouchEvent(event: MotionEvent): Boolean {
            val text = text as? Spannable
            if (text != null &&
                (event.actionMasked == MotionEvent.ACTION_DOWN ||
                    event.actionMasked == MotionEvent.ACTION_UP)
            ) {
                if (!isPointInsideClickableSpan(text, event)) {
                    // 没有落在可点片段上 ⇒ 明确放行，交给父级（图片的 clickable、
                    // 表格的 horizontalScroll、页面的纵向滚动）。
                    // ACTION_DOWN 返回 false 会让后续 MOVE/UP 不再派发给本 View，
                    // 这正合我们要的语义。
                    return false
                }
            }
            return super.onTouchEvent(event)
        }
    }.apply {
        setTextColor(textColor)
        setLinkTextColor(linkColor)
        // selectable=false 用于表格块：setTextIsSelectable(true) 会顺带 setClickable(true)
        // + setLongClickable(true)，把 TextView 变成一个「点击可聚焦」的 View。在真机的
        // 触摸管线上，这类 View 有可能先于父级手势吃掉触摸事件，让外层的横向滚动拖不动。
        // 表格以「读 + 横向拖动」为主，牺牲单元格内的长按选中是划算的。
        if (selectable) {
            setTextIsSelectable(true)
            // ⚠️ 顺序至关重要：**先**设 MovementMethod，**再**关属性。
            //
            // AOSP `TextView.setMovementMethod()` 内部会调用
            // `fixFocusableAndClickableSettings()`，而它在 `mMovement != null` 时会
            // **重新打开** focusable/clickable/longClickable：
            //
            //     private void fixFocusableAndClickableSettings() {
            //         if (mMovement != null) {
            //             setFocusable(FOCUSABLE);
            //             setClickable(true);       // ← 又被打开
            //             setLongClickable(true);   // ← 又被打开
            //         } else { … }
            //     }
            //
            // v1.0.36 里我们把这三行写在了 `setMovementMethod()` **之前**，于是刚关掉就
            // 立刻被框架翻回来，「文本块不抢占触摸」这条修复实际上从未生效 ——
            // 这正是用户反馈「靠后的图还是点不开」迟迟不愈的原因。测试也因此在
            // `!view.isClickable` 上稳定变红（AOSP 源码为证，不是测试环境失真）。
            //
            // 因此这里把顺序倒过来：先挂 MovementMethod（长按选中/复制、链接点击靠它），
            // 再把框架顺手打开的三个属性关掉。
            //
            // 但仅靠「关属性」仍然脆弱：任何插件在 setText 之后重新 setMovementMethod
            // 都会再次触发 fix。所以真正的保障在上面的 `onTouchEvent` 覆写里 ——
            // 那里按「落点是否在可点片段上」决定是否消费触摸，与属性值无关。
            movementMethod = LinkMovementMethod.getInstance()
            isClickable = false
            isLongClickable = false
            isFocusable = false
        } else {
            // 表格块：**尽量不给 MovementMethod**。
            // LinkMovementMethod 继承 ScrollingMovementMethod，只要表格内容比格位高，
            // 用户就能在表格里上下拖动文字，和页面的纵向滚动直接打架（用户反馈
            // 「表格上下拖动与整个页面上下滚动冲突」）。表格的横向滚动由外层 Compose 的
            // horizontalScroll 负责，纵向完全交给页面；单元格内既不需要滚动也不需要选中。
            //
            // ⚠️ 但「设成 null」**拦不住 Markwon**：`CorePlugin.afterSetText()` 会在每次
            // setText 之后检查，只要发现 `getMovementMethod() == null` 就**强行塞进**
            // LinkMovementMethod：
            //
            //     public void afterSetText(TextView view) {
            //         if (!hasExplicitMovementMethod && view.getMovementMethod() == null) {
            //             view.setMovementMethod(LinkMovementMethod.getInstance());
            //         }
            //     }
            //
            // 而 `setMovementMethod` 又会通过 AOSP 的 `fixFocusableAndClickableSettings()`
            // 把 clickable / longClickable / focusable 一并打开（探针实测：表格块渲染后
            // 三个属性全是 true）。所以表格块同样会抢占触摸 —— 这正是「末尾表格拖不动」
            // 反复不愈的机制。**唯一可靠的防线是上面重写的 `onTouchEvent`**：表格块的内容
            // 里没有 `ClickableSpan`，于是任何触摸都会被它判定为「未命中可点片段」而放行。
            //
            // 这里仍然设置成 null（意图明确、且 Markwon 若未来版本尊重该值就能直接生效），
            // 但不再依赖它 —— 真正的保障在 `onTouchEvent`。
            movementMethod = null
        }
        textSize = MARKDOWN_TEXT_SIZE_SP
        val fallbackSizePx = 14f * resources.displayMetrics.scaledDensity
        val renderer = Markwon.builder(context)
            .usePlugin(MarkwonInlineParserPlugin.create())
            .usePlugin(TablePlugin.create(tableThemeFor(context)))
            .usePlugin(
                JLatexMathPlugin.create(this.textSize) { builder ->
                    builder.inlinesEnabled(true)
                    builder.theme().textColor(textColor)
                    // 单条公式解析失败时画占位，绝不让 ParseException 冒泡成整页闪退。
                    // 失败的 latex 与异常类型一并写入本地日志，便于事后定位（用户截图
                    // 只有 60 字符片段，根本无法重建真实失败原因）。
                    builder.errorHandler { latex, error ->
                        Log.w(RENDER_LOG_TAG, "latex render failed: $latex", error)
                        appendRenderErrorLog(
                            context,
                            "LATEX-PIECE:\n$latex\n---",
                            error,
                        )
                        LatexFallbackDrawable(
                            textColor,
                            fallbackSizePx,
                            latex,
                            error.javaClass.simpleName,
                        )
                    }
                },
            )
            .build()
        tag = renderer
    }

/**
 * 触摸点是否落在一个 `ClickableSpan` 上（判定用 widget 内坐标，与本 View 的滚动偏移无关）。
 *
 * 计算方式与 AOSP `LinkMovementMethod.onTouchEvent` 保持一致：先把坐标换算到
 * `Layout` 的坐标系（减内边距、加滚动量），再交给 `Layout` 反查字符偏移。
 *
 * 任何一步越界都返回 false —— 「判不出来」时必须**放行**触摸，宁可让链接偶尔点不到，
 * 也不能因为判定异常而把下方图片的点击整片吃掉。
 */
private fun TextView.isPointInsideClickableSpan(text: Spannable, event: MotionEvent): Boolean {
    val textLayout = layout ?: return false
    return runCatching {
        val x = event.x.toInt() - totalPaddingLeft + scrollX
        val y = event.y.toInt() - totalPaddingTop + scrollY
        if (y < 0 || y >= textLayout.height) return@runCatching false
        val line = textLayout.getLineForVertical(y)
        if (x < textLayout.getLineLeft(line) || x > textLayout.getLineRight(line)) {
            return@runCatching false
        }
        val offset = textLayout.getOffsetForHorizontal(line, x.toFloat())
        text.getSpans(offset, offset, ClickableSpan::class.java).isNotEmpty()
    }.getOrDefault(false)
}

/**
 * 渲染一次回答。
 *
 * 宽度为 0（还没完成布局）时**直接跳过**：以前这里会用屏幕宽度兜底，
 * 而气泡很可能只有 480～760dp，于是长公式不拆、直接溢出被裁；
 * 现在等 [widthPx] 到位后再渲染，最多晚一帧，不会错。
 */
internal fun renderMarkdown(
    view: TextView,
    content: String,
    widthPx: Int,
    textColor: Int,
    linkColor: Int,
) {
    if (widthPx <= 0) return
    view.setTextColor(textColor)
    view.setLinkTextColor(linkColor)
    val rendered = wrapLongFormulas(
        sanitizeAssistantLatex(normalizeAssistantMarkdown(content)),
        formulaMaxWidthPx(view, widthPx),
        formulaWidthMeasurer(view),
    )
    runCatching {
        (view.tag as Markwon).setMarkdown(view, rendered)
        // 重渲染后复位内部滚动：LinkMovementMethod 继承自 ScrollingMovementMethod，
        // 内容比框高的瞬间用户能把文字拖出偏移，重新渲染时必须清零，
        // 否则新内容会带着旧的滚动偏移显示（头尾被挡的观感来源之一）。
        view.scrollTo(0, 0)
    }.onFailure { error ->
        Log.e(RENDER_LOG_TAG, "markdown render failed, fallback to plain text", error)
        appendRenderErrorLog(view.context, rendered, error)
        view.text = buildString {
            append(content)
            append("\n\n[部分内容无法渲染，已回退为纯文本]")
        }
    }
}

internal const val RENDER_LOG_TAG = "MarkdownAnswer"

/**
 * 单条公式解析失败时的占位：灰底 + 标题 + 失败源码片段，保证不再抛异常炸掉整页。
 * 源码超过 60 字符会被截断并加省略号，避免占位占满气泡。
 */
private class LatexFallbackDrawable(
    private val textColor: Int,
    textSizePx: Float,
    rawLatex: String,
    errorType: String? = null,
) : Drawable() {
    private val title = if (errorType != null) "⚠ 公式无法渲染 ($errorType)" else "⚠ 公式无法渲染"
    private val snippet: String = rawLatex
        .replace('\n', ' ')
        .let { if (it.length > 60) it.substring(0, 60) + "…" else it }
    private val background = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x1F000000
        style = Paint.Style.FILL
    }
    private val foreground = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor
        alpha = 0xB0
        this.textSize = textSizePx
    }
    private val snippetPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor
        alpha = 0x90
        this.textSize = textSizePx * 0.85f
        typeface = android.graphics.Typeface.MONOSPACE
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        canvas.drawRoundRect(
            b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(),
            10f, 10f, background,
        )
        val titleBaseline = b.top + foreground.textSize + 6f
        canvas.drawText(title, (b.left + 12).toFloat(), titleBaseline, foreground)
        if (snippet.isNotEmpty()) {
            val snippetBaseline = titleBaseline + snippetPaint.textSize + 4f
            canvas.drawText(snippet, (b.left + 12).toFloat(), snippetBaseline, snippetPaint)
        }
    }

    override fun setAlpha(alpha: Int) {
        foreground.alpha = alpha
        snippetPaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        foreground.colorFilter = colorFilter
        snippetPaint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun getIntrinsicWidth(): Int {
        val titleWidth = foreground.measureText(title)
        val snippetWidth = if (snippet.isEmpty()) 0f else snippetPaint.measureText(snippet)
        return (maxOf(titleWidth, snippetWidth) + 24).toInt()
    }

    override fun getIntrinsicHeight(): Int =
        (foreground.textSize + snippetPaint.textSize + 28f).toInt()
}

/** 渲染失败的原文与异常写入本机日志，便于事后定位是哪类回答触发的。 */
private val RENDER_LOG_EXECUTOR = Executors.newSingleThreadExecutor()

internal fun appendRenderErrorLog(context: Context, markdown: String, error: Throwable) {
    runCatching {
        RENDER_LOG_EXECUTOR.execute {
            runCatching {
                val file = File(context.filesDir, "assistant-render.log")
                val entry = buildString {
                    appendLine("=== ${Instant.now()} ===")
                    appendLine("error: ${error::class.java.simpleName}: ${error.message}")
                    appendLine(markdown.take(1_500))
                    appendLine()
                }
                val kept = if (file.isFile && file.length() <= 200_000) file.readText() else ""
                file.writeText(kept + entry)
            }
        }
    }
}

/**
 * 公式可用宽度：TextView 实测宽度减去左右留白。
 *
 * 只认实测宽度——屏幕宽度在平板上远大于气泡宽度，用它兜底等于「不拆公式」。
 */
internal fun formulaMaxWidthPx(view: TextView, measuredWidthPx: Int): Int {
    val metrics = view.resources.displayMetrics
    val padding = (metrics.density * 24).toInt()
    return (measuredWidthPx - padding).coerceAtLeast((metrics.density * 120).toInt())
}

/** 优先用 JLatexMath 真实测量公式宽度；测量失败时按字符数估算。 */
internal fun formulaWidthMeasurer(view: TextView): (String) -> Int {
    val textSizePx = 17f * view.resources.displayMetrics.scaledDensity
    return { latex ->
        runCatching { JLatexMathDrawable.builder(latex).textSize(textSizePx).build().intrinsicWidth }
            .getOrDefault((latex.length * textSizePx * 0.62f).toInt())
    }
}
