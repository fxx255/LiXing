package com.example.lixing.ui.screen.assistant

import android.graphics.Bitmap
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.lixing.domain.diagram.*
import com.example.lixing.ui.diagram.DiagramRenderer
import com.example.lixing.data.diagram.DiagramImageStore
import com.example.lixing.data.assistant.normalizeAssistantMarkdown
import com.example.lixing.data.assistant.sanitizeAssistantLatex
import com.example.lixing.data.assistant.wrapLongFormulas
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.noties.jlatexmath.JLatexMathAndroid
import ru.noties.jlatexmath.JLatexMathDrawable
import java.io.File

@RunWith(AndroidJUnit4::class)
class DiagramDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun math(text: String) = "$" + text + "$"

    @Test fun failedSlotsHaveAnExplanationAndDoNotOpenAnEmptyViewer() {
        compose.setContent {
            MaterialTheme {
                MessageBubble("assistant", "回答正文\n\n[[FIGURE:1]]", listOf("", "")) { _, _ ->
                    fail("A failed figure must not open the viewer")
                }
            }
        }
        compose.onNodeWithText("第 1 张图未能生成，可让助手重新绘制。").assertExists()
        compose.onNodeWithText("第 2 张图未能生成，可让助手重新绘制。").assertExists()
        compose.onAllNodesWithContentDescription("生成的图表").assertCountEquals(0)
    }

    @Test fun missingPngCanBeRecreatedFromItsSavedSpec() {
        JLatexMathAndroid.init(context)
        val store = DiagramImageStore(context)
        val spec = coherent().copy(title = "自动验收：结构恢复")
        val path = requireNotNull(store.render(spec))
        val original = File(path).readBytes()
        assertTrue(File(path).delete())
        assertEquals(path, store.restore(path))
        assertArrayEquals(original, File(path).readBytes())
    }

    private fun coherent() = DiagramSpec("相干解调", listOf(
        DiagramNode("in", math("s(t)"), DiagramNodeShape.IO, subLabel = "接收信号"),
        DiagramNode("mix", "乘法器", DiagramNodeShape.MIXER),
        DiagramNode("lpf", "低通滤波器", DiagramNodeShape.BLOCK, subLabel = math("H(f)")),
        DiagramNode("out", math("m_o(t)"), DiagramNodeShape.IO, subLabel = "解调输出"),
        DiagramNode("carrier", math("2\\cos(2\\pi f_c t)"), DiagramNodeShape.IO, subLabel = "本地载波")),
        listOf(DiagramEdge("in", "mix"), DiagramEdge("mix", "lpf"), DiagramEdge("lpf", "out"),
            DiagramEdge("carrier", "mix", toPort = DiagramPort.BOTTOM)))

    private fun envelope() = DiagramSpec("AM 包络检波", listOf(
        DiagramNode("in", math("s_{AM}(t)"), DiagramNodeShape.IO),
        DiagramNode("detector", "包络检波器", DiagramNodeShape.BLOCK, subLabel = "二极管 + RC"),
        DiagramNode("dc", "隔直电容", DiagramNodeShape.BLOCK, subLabel = "去除直流分量"),
        DiagramNode("out", math("m(t)"), DiagramNodeShape.IO)), listOf(
        DiagramEdge("in", "detector"), DiagramEdge("detector", "dc"), DiagramEdge("dc", "out")))

    private fun save(spec: DiagramSpec, name: String): String {
        JLatexMathAndroid.init(context)
        val image = DiagramRenderer.render(spec)
        assertTrue("Zoomable diagram should retain detail", image.width >= 1000)
        val file = File(context.getExternalFilesDir(null), "$name.png")
        file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
        return file.absolutePath
    }

    private fun screenshot(name: String) {
        // System window animations are not covered by Compose's idle clock.
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        Thread.sleep(350)
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { image ->
            File(context.getExternalFilesDir(null), "$name.png").outputStream().use {
                image.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            image.recycle()
        }
    }

    @Test fun diagramAfterLongAnswerOpensWithCorrectPageAndUnanchoredImageRemainsVisible() {
        val first = save(coherent(), "diagram-coherent")
        val second = save(envelope(), "diagram-envelope")
        val content = (1..24).joinToString("\n\n") { "第 $it 段推导：" + math("y(t)=m(t)\\cos(2\\pi f_c t)") } +
            "\n\n相干解调过程如下：\n\n[[FIGURE:2]]\n\nAM 信号也可以通过包络检波恢复消息。"
        lateinit var state: LazyListState
        lateinit var scope: CoroutineScope
        var clicks = 0
        var selected = -1
        var received = emptyList<String>()
        compose.setContent {
            var viewer by remember { mutableStateOf(false) }
            state = rememberLazyListState()
            scope = rememberCoroutineScope()
            MaterialTheme {
                LazyColumn(Modifier.fillMaxSize(), state, reverseLayout = true) {
                    item {
                        MessageBubble("assistant", content, listOf("", first, second)) { images, index ->
                            received = images; selected = index; clicks++; viewer = true
                        }
                    }
                }
                if (viewer) PhotoViewerDialog(received, selected) { viewer = false }
            }
        }
        compose.waitForIdle()
        compose.onAllNodesWithContentDescription("生成的图表").assertCountEquals(2)
        val image = compose.onAllNodesWithContentDescription("生成的图表")[0]
        val targetY = compose.onRoot().fetchSemanticsNode().size.height / 3f
        repeat(3) {
            val y = image.fetchSemanticsNode().positionInRoot.y
            compose.runOnIdle { scope.launch { state.scrollBy(targetY - y) } }
            compose.waitForIdle()
        }
        screenshot("diagram-long-answer")
        image.assertIsDisplayed().performTouchInput { down(center); advanceEventTime(150); up() }
        compose.runOnIdle {
            assertEquals(1, clicks)
            assertEquals(listOf(first, second), received)
            assertEquals(0, selected)
        }
        compose.onNodeWithContentDescription("关闭").assertExists()
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("照片大图 1").fetchSemanticsNodes().isNotEmpty() }
        screenshot("diagram-viewer")
        compose.onNodeWithContentDescription("照片大图 1").performTouchInput { doubleClick(center) }
        compose.waitForIdle()
        screenshot("diagram-viewer-zoomed")
        compose.onNodeWithContentDescription("关闭").performClick()
        compose.onNodeWithContentDescription("关闭").assertDoesNotExist()
    }

    @Test fun reportedFormulaRendersOnDeviceAtNarrowWidths() {
        JLatexMathAndroid.init(context)
        val formula = """1=\frac{1}{4}\left(\frac{1}{n}+\frac{1}{4}\right)\ \Rightarrow\ \frac{1}{n}+\frac{1}{4}=4\ \Rightarrow\ \frac{1}{n}=\frac{15}{4}\ \Rightarrow\ n=\frac{4}{15}\approx0.267"""
        val display = "$$" + formula + "$$"
        for (width in listOf(240, 480, 800, 1100)) {
            val prepared = wrapLongFormulas(sanitizeAssistantLatex(normalizeAssistantMarkdown(display)), width) {
                JLatexMathDrawable.builder(it).textSize(36f).build().intrinsicWidth
            }
            val pieces = Regex("""\$\$([\s\S]*?)\$\$""").findAll(prepared).toList()
            assertTrue(pieces.isNotEmpty())
            pieces.forEach { JLatexMathDrawable.builder(it.groupValues[1].trimStart() + "\n").textSize(36f).build() }
        }
        compose.setContent {
            MaterialTheme {
                LazyColumn(Modifier.fillMaxSize()) {
                    item { MessageBubble("assistant", "原式的完整推导：\n\n$display\n\n以上各步应完整显示。", emptyList()) { _, _ -> } }
                }
            }
        }
        compose.waitForIdle()
        screenshot("formula-repaired")
    }

    @Test fun realTextMeasurementsKeepLongLabelsAndFractionsInsideTheirBoxes() {
        JLatexMathAndroid.init(context)
        val spec = coherent().let { it.copy(nodes = it.nodes.map { node ->
            if (node.id != "lpf") node else node.copy(label = "低通滤波器用于保留基带信号并抑制高频分量",
                subLabel = math("H(f)=\\frac{1}{1+j2\\pi fRC}"))
        }) }
        val layout = DiagramLayout.layout(spec)
        val filter = layout.nodes.single { it.node.id == "lpf" }
        val main = DiagramLayout.label(filter.node)
        val sub = DiagramLayout.subLabel(filter.node)
        assertTrue(main.lines.size >= 2)
        assertTrue(filter.width >= maxOf(main.width, sub.width) + 30)
        assertTrue(filter.height >= main.height + sub.height + 32)
        save(spec, "diagram-long-label")
    }
}
