package com.example.lixing.ui.plot

import android.app.Application
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import ru.noties.jlatexmath.JLatexMathAndroid
import ru.noties.jlatexmath.JLatexMathDrawable

/**
 * 钉住一个库级事实：**JLatexMath 无法排版中文**。
 *
 * 它的内置字体不含 CJK 字形，汉字宽度退化为 ~0（实测「功率谱」与
 * 「功率谱功率谱功率谱」量出的宽度完全相同）。这正是「图上文字全挤在左上角、
 * 相互重叠」的根因——标签一进 TeX 排版，汉字就全部堆在同一坐标。
 *
 * 因此 [PlotBitmapRenderer] 必须先做「公式 / 普通文字」分段（见 [splitLabelPieces]），
 * 中文一律走 Canvas 文字。这个测试是那道分段逻辑的**理由**：
 * 将来若升级 JLatexMath 后此测试失败（宽度开始随汉字数增长），说明该库支持了中文，
 * 分段策略可以重新评估；反之只要它还退化，中文就绝不能进公式通道。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class JLatexCjkMetricsTest {

    private fun widthOf(latex: String): Int {
        JLatexMathAndroid.init(RuntimeEnvironment.getApplication())
        return JLatexMathDrawable.builder(latex).textSize(30f).build().intrinsicWidth
    }

    @Test
    fun `中文宽度不随字数增长因此必然堆叠`() {
        val single = widthOf("功率谱")
        val triple = widthOf("功率谱功率谱功率谱")
        assertTrue(
            "汉字数翻三倍宽度几乎不变（单组=$single 三组=$triple）说明汉字会堆在同一坐标",
            triple <= single + 4,
        )
    }

    @Test
    fun `拉丁字符的宽度正常随长度增长`() {
        val short = widthOf("abc")
        val long = widthOf("aaaaaaaa")
        assertTrue("拉丁串宽度应随长度增长（短=$short 长=$long）", long > short * 2)
    }
}
