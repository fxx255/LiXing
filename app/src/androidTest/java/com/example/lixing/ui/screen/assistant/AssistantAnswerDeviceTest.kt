package com.example.lixing.ui.screen.assistant

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.lixing.domain.plot.Axis
import com.example.lixing.domain.plot.MarkLine
import com.example.lixing.domain.plot.PlotSpec
import com.example.lixing.domain.plot.Series
import com.example.lixing.ui.plot.PlotBitmapRenderer
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.noties.jlatexmath.JLatexMathAndroid
import java.io.File

@RunWith(AndroidJUnit4::class)
class AssistantAnswerDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun continuationAndThinkingKeepSeparateBoundsWithRealFormulasAndPlot() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        JLatexMathAndroid.init(context)
        val spec = PlotSpec(
            title = "同相/正交分量功率谱（示意）",
            x = Axis("f", min = -1.0, max = 1.0, grid = false,
                ticks = listOf(-1.0, 0.0, 1.0),
                tickLabels = mapOf(-1.0 to "${'$'}-B/2${'$'}", 0.0 to "O", 1.0 to "${'$'}B/2${'$'}")),
            y = Axis("", min = 0.0, max = 3.6, grid = false,
                ticks = listOf(2.0), tickLabels = mapOf(2.0 to "${'$'}N_0(2\\pi f_c)^2${'$'}")),
            series = listOf(Series(expr = "x^2+2")),
            markLines = listOf(MarkLine(x = -1.0), MarkLine(x = 1.0)),
            widthPx = 1400, heightPx = 900,
        )
        val plot = PlotBitmapRenderer(1f).render(spec, spec.widthPx, spec.heightPx)
        val output = File(context.getExternalFilesDir(null), "assistant-regression-spectrum.png")
        output.outputStream().use { plot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        plot.recycle()

        val first = (1..22).joinToString("\n\n") {
            "第 $it 段推导：功率谱满足 ${'$'}P(f)=4\\pi^2N_0(f^2+f_c^2)${'$'}。"
        }
        var append: () -> Unit = {}
        var finish: () -> Unit = {}
        var answerBounds = Rect.Zero
        var thinkingBounds = Rect.Zero
        compose.setContent {
            var content by remember { mutableStateOf(first) }
            var busy by remember { mutableStateOf(true) }
            append = { content += "\n\n续写完成：\n\n${'$'}${'$'}\nP_{y_c}(f)=P_{y_s}(f)=4\\pi^2N_0\\left(f_c^2+f^2\\right)\n${'$'}${'$'}\n\n[[FIGURE:1]]" }
            finish = { busy = false }
            MaterialTheme {
                LazyColumn(Modifier.fillMaxSize(), reverseLayout = true) {
                    item(key = "answer") {
                        Column {
                            if (busy) {
                                Box(Modifier.onGloballyPositioned { thinkingBounds = it.boundsInRoot() }) {
                                    ThinkingPanel("仍在继续分析", false, true, {})
                                }
                            }
                            Box(Modifier.onGloballyPositioned { answerBounds = it.boundsInRoot() }) {
                                MessageBubble("assistant", content, listOf(output.absolutePath)) { _, _ -> }
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { append() }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue("续写正文不可见", answerBounds.height > 0)
            assertTrue("思考面板遮挡正文: $answerBounds / $thinkingBounds",
                thinkingBounds.bottom <= answerBounds.top + 1)
        }
        compose.runOnIdle { finish() }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(answerBounds.height > 0) }
    }

    @Test fun streamedInlineFormulaStaysInItsParagraphBelowThinking() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        JLatexMathAndroid.init(context)
        var update: (String) -> Unit = {}
        var thinkingBounds = Rect.Zero
        var answerBounds = Rect.Zero
        compose.setContent {
            var content by remember { mutableStateOf("结论：当 ${'$'}x") }
            update = { content = it }
            MaterialTheme {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.onGloballyPositioned { thinkingBounds = it.boundsInRoot() }) {
                        ThinkingPanel("已经完成推导", true, false, {})
                    }
                    Box(Modifier.onGloballyPositioned { answerBounds = it.boundsInRoot() }) {
                        MessageBubble("assistant", content, emptyList(), streaming = true) { _, _ -> }
                    }
                }
            }
        }
        compose.runOnIdle { update("结论：当 ${'$'}x^2${'$'} 成立时，答案为 4。") }
        compose.mainClock.advanceTimeBy(320)
        compose.waitUntil(5_000) { answerBounds.height > 0 }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue("回答必须在思考面板下方", thinkingBounds.bottom <= answerBounds.top + 1)
            assertTrue(answerBounds.height > 0)
        }
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { bitmap ->
            File(context.getExternalFilesDir(null), "streamed-inline-formula.png")
                .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
