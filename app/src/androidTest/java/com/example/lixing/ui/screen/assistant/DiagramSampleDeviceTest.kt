package com.example.lixing.ui.screen.assistant

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.lixing.assistant.DefaultFigureRenderer
import com.example.lixing.data.assistant.AssistantFigure
import com.example.lixing.data.diagram.DiagramImageStore
import com.example.lixing.data.plot.PlotImageStore
import com.example.lixing.data.prefs.UserPreferencesRepository
import com.example.lixing.domain.diagram.*
import com.example.lixing.ui.diagram.DiagramRenderer
import com.example.lixing.ui.theme.DarkModePref
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import ru.noties.jlatexmath.JLatexMathAndroid
import kotlinx.coroutines.runBlocking

/** Writes review PNGs with the real Android Canvas; Robolectric bitmaps are black. */
@RunWith(AndroidJUnit4::class)
class DiagramSampleDeviceTest {
    @Test fun savedDiagramFollowsAppAppearanceSetting() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        JLatexMathAndroid.init(context)
        val prefs = UserPreferencesRepository(context)
        val originalMode = prefs.current().darkMode
        val renderer = DefaultFigureRenderer(PlotImageStore(context), DiagramImageStore(context),
            prefs, context)
        fun background(path: String): Int {
            val image = BitmapFactory.decodeFile(path)
            return try { image.getPixel(0, 0) } finally { image.recycle() }
        }
        try {
            prefs.setDarkMode(DarkModePref.DARK)
            val darkPath = renderer.render(listOf(AssistantFigure.Diagram(envelopeSpec()))).single()
            prefs.setDarkMode(DarkModePref.LIGHT)
            val lightPath = renderer.render(listOf(AssistantFigure.Diagram(envelopeSpec()))).single()
            assertNotEquals(darkPath, lightPath)
            assertEquals(DiagramRenderer.DARK_BACKGROUND, background(darkPath))
            assertEquals(Color.WHITE, background(lightPath))
        } finally {
            prefs.setDarkMode(originalMode)
        }
    }

    @Test fun generateReviewSamples() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        JLatexMathAndroid.init(context)
        val directory = File(context.getExternalFilesDir(null), "diagram-samples").apply { mkdirs() }
        val samples = listOf(
            Triple("ssb_dual_branch.png", ssbSpec(false), false),
            Triple("ssb_branch_labels.png", ssbSpec(true), false),
            Triple("ssb_dual_branch_dark.png", ssbSpec(false), true),
            Triple("am_envelope.png", envelopeSpec(), false),
            Triple("am_envelope_dark.png", envelopeSpec(), true),
            Triple("multiplier_modulator.png", modulatorSpec(), false),
        )
        samples.forEach { (name, spec, dark) ->
            val image = DiagramRenderer.render(spec, widthPx = 2400, heightPx = 1400, dark = dark)
            try {
                val expectedBackground = if (dark) DiagramRenderer.DARK_BACKGROUND else Color.WHITE
                assertEquals("Android Canvas must use the selected background",
                    expectedBackground, image.getPixel(0, 0))
                val file = File(directory, name)
                file.outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                assertTrue(file.isFile && file.length() > 1000)
            } finally {
                image.recycle()
            }
        }
    }

    private fun node(id: String, label: String, shape: DiagramNodeShape, role: String) =
        DiagramNode(id, label, shape, role = role)

    private fun ssbSpec(branchLabels: Boolean): DiagramSpec {
        return DiagramSpec(
            title = "单边带信号解调结构",
            nodes = listOf(
                node("input", "s(t)", DiagramNodeShape.IO, "input"),
                node("split", "", DiagramNodeShape.JUNCTION, "split"),
                node("a", "A", DiagramNodeShape.JUNCTION, "test_a"),
                node("upper", "×", DiagramNodeShape.MIXER, "upper_mixer"),
                node("lower", "×", DiagramNodeShape.MIXER, "lower_mixer"),
                node("b", "B", DiagramNodeShape.JUNCTION, "test_b"),
                node("c", "C", DiagramNodeShape.JUNCTION, "test_c"),
                node("d", "D", DiagramNodeShape.JUNCTION, "test_d"),
                node("e", "E", DiagramNodeShape.JUNCTION, "test_e"),
                node("f", "F", DiagramNodeShape.JUNCTION, "test_f"),
                node("g", "G", DiagramNodeShape.JUNCTION, "test_g"),
                node("upper_filter", "LPF", DiagramNodeShape.BLOCK, "upper_filter"),
                node("lower_filter", "LPF", DiagramNodeShape.BLOCK, "lower_filter"),
                node("hilbert", "希尔伯特\n滤波器", DiagramNodeShape.BLOCK, "lower_hilbert"),
                node("carrier", "载波\n提取", DiagramNodeShape.BLOCK, "carrier"),
                node("phase", "移相\n−90°", DiagramNodeShape.BLOCK, "phase_shift"),
                node("sum", "求和", DiagramNodeShape.SUM, "sum"),
                node("output", "输出", DiagramNodeShape.IO, "output"),
            ),
            edges = listOf(
                DiagramEdge("input", "a"), DiagramEdge("a", "split"),
                DiagramEdge("split", "upper", fromPort = DiagramPort.TOP),
                DiagramEdge("split", "lower", fromPort = DiagramPort.BOTTOM),
                DiagramEdge("upper", "b"), DiagramEdge("b", "upper_filter"),
                DiagramEdge("upper_filter", "c", label = if (branchLabels) "上支路" else null),
                DiagramEdge("c", "sum", toPort = DiagramPort.TOP, polarity = "+"),
                DiagramEdge("lower", "d"),
                DiagramEdge("d", "lower_filter", label = if (branchLabels) "下支路" else null),
                DiagramEdge("lower_filter", "e"),
                DiagramEdge("e", "hilbert"), DiagramEdge("hilbert", "f"),
                DiagramEdge("f", "sum", toPort = DiagramPort.BOTTOM, polarity = "-"),
                DiagramEdge("sum", "g"), DiagramEdge("g", "output"),
                DiagramEdge("carrier", "upper", toPort = DiagramPort.BOTTOM, label = "cos 2πfct"),
                DiagramEdge("carrier", "phase"),
                DiagramEdge("phase", "lower", toPort = DiagramPort.BOTTOM, label = "sin 2πfct"),
            ),
            profile = DiagramLayoutProfile.TEXTBOOK_DUAL_BRANCH,
        )
    }

    private fun envelopeSpec() = DiagramSpec(
        title = "AM 包络检波",
        nodes = listOf(
            DiagramNode("in", "s_AM(t)", DiagramNodeShape.IO),
            DiagramNode("bp", "带通滤波器", DiagramNodeShape.BLOCK, subLabel = "选出 AM 频段"),
            DiagramNode("detector", "包络检波器", DiagramNodeShape.BLOCK, subLabel = "二极管 + RC"),
            DiagramNode("dc", "隔直电容", DiagramNodeShape.BLOCK),
            DiagramNode("out", "m(t)", DiagramNodeShape.IO),
        ),
        edges = listOf(DiagramEdge("in", "bp"), DiagramEdge("bp", "detector"),
            DiagramEdge("detector", "dc"), DiagramEdge("dc", "out")),
    )

    private fun modulatorSpec() = DiagramSpec(
        title = "乘法调制器",
        nodes = listOf(
            DiagramNode("source", "基带输入", DiagramNodeShape.IO, role = "input"),
            DiagramNode("mix", "×", DiagramNodeShape.MIXER, glyph = "×"),
            DiagramNode("carrier", "载波", DiagramNodeShape.IO),
            DiagramNode("filter", "带通滤波器", DiagramNodeShape.BLOCK, subLabel = "选择边带"),
            DiagramNode("out", "已调信号", DiagramNodeShape.IO, role = "output"),
        ),
        edges = listOf(DiagramEdge("source", "mix"),
            DiagramEdge("carrier", "mix", toPort = DiagramPort.BOTTOM, label = "cos(2πf_ct)"),
            DiagramEdge("mix", "filter"), DiagramEdge("filter", "out")),
    )
}
