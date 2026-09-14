package com.example.lixing.ui.screen.assistant

import android.app.Application
import android.graphics.Color as AwtColor
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.dp
import com.example.lixing.data.assistant.normalizeAssistantMarkdown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 助手回答渲染层的交互回归（对应用户 v1.0.26 反馈的四类问题）：
 *
 * 1. 内容块（MarkdownChunk）的 Compose 高度必须等于 TextView 内容的真实高度——
 *    一旦偏小，后面的图/表格就会和文字叠在一起（「图片与文字重叠」「表格遮挡文字」）；
 * 2. 表格块必须能横向拖动（horizontalScroll 的可滚动区间 + 内容确实更宽）。
 *
 * 说明：真实界面里气泡位于 LazyColumn 的 item 内（高度无上界），测试内容也刻意
 * 短于测试窗口高度，避免窗口自身的高度约束把 `Modifier.height` 静默夹取——
 * 那是测试环境的失真，不是生产路径。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class MarkdownChunkInteractionTest {

    @get:Rule
    val compose = createComposeRule()

    private val eightLines = (1..8).joinToString("\n") { "第 $it 行内容，用来撑起多行高度。" }

    private val tableMarkdown = """
        下面是测量结果：

        | 频率点 | 幅值 |
        | --- | --- |
        | 0 Hz | 1.00 |
        | 50 Hz | 0.50 |
        | 200 Hz | 0.12 |

        以上就是全部数据。
    """.trimIndent()

    /**
     * 按生产路径独立量出内容的真实高度（与 MarkdownChunk 内部同一套规则）。
     *
     * 量两次、取第二次：Markwon 的表格 span 在首次 measure 时高度可能尚未计入，
     * 生产路径（update + LaunchedEffect 反复渲染测量）等价于复测后的稳定值。
     */
    private fun measureTextViewHeight(content: String, widthPx: Int, selectable: Boolean): Int {
        val context = RuntimeEnvironment.getApplication()
        val view = createMarkdownTextView(context, AwtColor.BLACK, AwtColor.BLUE, selectable)
        (view.tag as io.noties.markwon.Markwon).setMarkdown(view, content)
        view.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        val widthSpec = View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        view.measure(widthSpec, heightSpec)
        view.measure(widthSpec, heightSpec)
        return view.measuredHeight
    }

    /**
     * 问题④的根：内容块高度必须真实收敛。此前探针发现，一旦外层有界，
     * `Modifier.height` 会被静默夹取、内容溢出；真实界面是无界容器，
     * 这里钉住「无界（短内容）下高度与内容完全一致」这一前提。
     */
    @Test
    fun `文字块高度等于内容真实高度`() {
        var chunkHeight = -1
        compose.setContent {
            Column(Modifier.requiredWidth(300.dp)) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .onGloballyPositioned { chunkHeight = it.size.height },
                ) {
                    MarkdownChunk(content = eightLines, fixedWidthPx = null)
                }
            }
        }
        compose.waitForIdle()
        val expected = measureTextViewHeight(eightLines, widthPx = 300, selectable = true)
        println("PROBE text chunkHeight=$chunkHeight expected=$expected")
        assertEquals("内容块高度必须等于内容真实高度", expected, chunkHeight)
    }

    /** 问题③：表格块必须能横向拖出非零滚动量。 */
    @Test
    fun `表格块可以横向拖动`() {
        lateinit var scroll: ScrollState
        var scrollValue = -1
        compose.setContent {
            scroll = rememberScrollState()
            Column(Modifier.requiredWidth(300.dp)) {
                Box(
                    Modifier
                        .wrapContentWidth()
                        .horizontalScroll(scroll)
                        .testTag("table-box"),
                ) {
                    MarkdownChunk(
                        content = tableMarkdown,
                        fixedWidthPx = 540,
                        selectable = false,
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("table-box").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.runOnIdle { scrollValue = scroll.value }
        println("PROBE table scroll=$scrollValue")
        assertTrue("表格应能横向拖动（scroll=$scrollValue）", scrollValue > 0)
    }

    /** 问题②/④：表格块自身的 Compose 高度必须等于宽画布下内容的真实高度。 */
    @Test
    fun `表格块高度等于宽画布下的内容真实高度`() {
        var chunkHeight = -1
        compose.setContent {
            Column(Modifier.requiredWidth(300.dp)) {
                Box(
                    Modifier
                        .wrapContentWidth()
                        .onGloballyPositioned { chunkHeight = it.size.height },
                ) {
                    MarkdownChunk(
                        content = tableMarkdown,
                        fixedWidthPx = 540,
                        selectable = false,
                    )
                }
            }
        }
        compose.waitForIdle()
        // 生产路径总是先过内容管线再渲染，独立测量用同一份归一化结果
        val normalized = normalizeAssistantMarkdown(tableMarkdown)
        val expected = measureTextViewHeight(normalized, widthPx = 540, selectable = false)
        println("PROBE table chunkHeight=$chunkHeight expected=$expected")
        assertEquals("表格块高度必须等于内容真实高度", expected, chunkHeight)
    }
}
