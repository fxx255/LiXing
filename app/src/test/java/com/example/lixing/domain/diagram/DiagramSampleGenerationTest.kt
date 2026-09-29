package com.example.lixing.domain.diagram

import android.graphics.Bitmap
import android.app.Application
import java.io.File
import java.io.FileOutputStream
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import ru.noties.jlatexmath.JLatexMathAndroid
import com.example.lixing.ui.diagram.DiagramRenderer

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class DiagramSampleGenerationTest {
    private fun node(
        id: String,
        label: String,
        shape: DiagramNodeShape,
        role: String,
    ) = DiagramNode(id, label, shape, role = role)

    private fun spec(withBranchLabels: Boolean, explicitMath: Boolean): DiagramSpec {
        val cos = if (explicitMath) "\$\\cos 2\\pi f_c t\$" else "cos 2πfct"
        val sin = if (explicitMath) "\$\\sin 2\\pi f_c t\$" else "sin 2πfct"
        return DiagramSpec(
            title = "单边带信号解调结构",
            nodes = listOf(
                node("input", "s(t)", DiagramNodeShape.IO, "input"),
                node("split", "A", DiagramNodeShape.JUNCTION, "split"),
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
                DiagramEdge("input", "a"),
                DiagramEdge("a", "split"),
                DiagramEdge("split", "upper", fromPort = DiagramPort.TOP),
                DiagramEdge("split", "lower", fromPort = DiagramPort.BOTTOM),
                DiagramEdge("upper", "b"),
                DiagramEdge("b", "upper_filter"),
                DiagramEdge("upper_filter", "c", label = if (withBranchLabels) "上支路" else null),
                DiagramEdge("c", "sum", toPort = DiagramPort.TOP, polarity = "+"),
                DiagramEdge("lower", "d"),
                DiagramEdge("d", "lower_filter"),
                DiagramEdge("lower_filter", "e", label = if (withBranchLabels) "下支路" else null),
                DiagramEdge("e", "hilbert"),
                DiagramEdge("hilbert", "f"),
                DiagramEdge("f", "sum", toPort = DiagramPort.BOTTOM, polarity = "-"),
                DiagramEdge("sum", "g"),
                DiagramEdge("g", "output"),
                DiagramEdge("carrier", "upper", toPort = DiagramPort.BOTTOM, label = cos),
                DiagramEdge("carrier", "phase"),
                DiagramEdge("phase", "lower", toPort = DiagramPort.BOTTOM, label = sin),
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
        edges = listOf(
            DiagramEdge("in", "bp"),
            DiagramEdge("bp", "detector"),
            DiagramEdge("detector", "dc"),
            DiagramEdge("dc", "out"),
        ),
    )

    private fun modulationSpec() = DiagramSpec(
        title = "乘法调制器",
        nodes = listOf(
            DiagramNode("source", "基带输入", DiagramNodeShape.IO, role = "input"),
            DiagramNode("mix", "×", DiagramNodeShape.MIXER, glyph = "×"),
            DiagramNode("carrier", "载波", DiagramNodeShape.IO),
            DiagramNode("filter", "带通滤波器", DiagramNodeShape.BLOCK, subLabel = "选择边带"),
            DiagramNode("out", "已调信号", DiagramNodeShape.IO, role = "output"),
        ),
        edges = listOf(
            DiagramEdge("source", "mix"),
            DiagramEdge("carrier", "mix", toPort = DiagramPort.BOTTOM, label = "cos(2πf_ct)"),
            DiagramEdge("mix", "filter"),
            DiagramEdge("filter", "out"),
        ),
    )

    private fun branchSpec() = DiagramSpec(
        title = "双支路合流与反馈",
        nodes = listOf(
            DiagramNode("in", "输入", DiagramNodeShape.IO),
            DiagramNode("split", "分支", DiagramNodeShape.JUNCTION),
            DiagramNode("upper", "前向控制", DiagramNodeShape.BLOCK),
            DiagramNode("lower", "旁路处理", DiagramNodeShape.BLOCK),
            DiagramNode("sum", "合流", DiagramNodeShape.SUM, glyph = "+"),
            DiagramNode("out", "输出", DiagramNodeShape.IO),
        ),
        edges = listOf(
            DiagramEdge("in", "split"),
            DiagramEdge("split", "upper", fromPort = DiagramPort.TOP),
            DiagramEdge("split", "lower", fromPort = DiagramPort.BOTTOM),
            DiagramEdge("upper", "sum", toPort = DiagramPort.TOP, polarity = "+"),
            DiagramEdge("lower", "sum", toPort = DiagramPort.BOTTOM, polarity = "-"),
            DiagramEdge("sum", "out"),
            DiagramEdge("out", "in", fromPort = DiagramPort.BOTTOM,
                toPort = DiagramPort.BOTTOM, dashed = true, label = "反馈"),
        ),
    )

    @Test
    fun generateReviewSamples() {
        JLatexMathAndroid.init(RuntimeEnvironment.getApplication())
        val directory = File("diagram-samples/review").apply { mkdirs() }
        listOf(
            "ssb_compact_chinese.png" to spec(withBranchLabels = false, explicitMath = false),
            "ssb_compact_labels.png" to spec(withBranchLabels = true, explicitMath = false),
            "ssb_compact_formula.png" to spec(withBranchLabels = false, explicitMath = true),
            "generic_am_envelope.png" to envelopeSpec(),
            "generic_modulator.png" to modulationSpec(),
            "generic_branch_feedback.png" to branchSpec(),
        ).forEach { (name, diagram) ->
            val image = DiagramRenderer.render(diagram, widthPx = 1800, heightPx = 900)
            try {
                FileOutputStream(File(directory, name)).use { output ->
                    check(image.compress(Bitmap.CompressFormat.PNG, 100, output))
                }
            } finally {
                image.recycle()
            }
        }
    }
}
