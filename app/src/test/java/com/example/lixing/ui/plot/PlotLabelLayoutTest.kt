package com.example.lixing.ui.plot

import android.app.Application
import com.example.lixing.domain.plot.Axis
import com.example.lixing.domain.plot.PlotSpec
import com.example.lixing.domain.plot.Series
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import ru.noties.jlatexmath.JLatexMathAndroid

/**
 * 图内标签的分段排布 + 保守坐标范围（纯函数测试，不需要 Robolectric）。
 *
 * 背景（用户截图反馈）：图上文字全挤在左上角、相互重叠。根因是 JLatexMath 的字体
 * **没有 CJK 字形**——实测「功率谱」与「功率谱功率谱功率谱」量出的宽度同为 12px，
 * 即汉字宽度退化为 0，所有汉字堆在同一坐标。而标签常常是**中英混排**
 * （「功率谱密度 $S_c(f)$」），旧逻辑会把整串（含中文）丢给 JLatexMath。
 *
 * 修复后 [splitLabelPieces] 把标签切成「公式 / 普通文字」交替片段：公式交给
 * JLatexMath，中文与其余文字交给 Canvas，逐段排布。
 *
 * 说明：本文件刻意**不做像素级断言**——Robolectric（legacy graphics）不真正栅格化
 * 文字，位图上量不到文字像素（实测标题带跨度恒为 0）。文字是否真的画出来需要真机验证，
 * 这里钉住的是不会退化的核心不变量：**交给公式排版器的片段永不含中文**。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class PlotLabelLayoutTest {

    private val renderer = PlotBitmapRenderer(density = 2f)

    /**
     * 用公式排版必须先初始化 JLatexMath（与 LiXingApplication 里一样）。
     * 漏掉这一步时 TeXFormula 的静态初始化会失败，而且是**粘性**的：
     * 同一 JVM 内后续所有用到它的测试都会 `NoClassDefFoundError`。
     */
    @Before
    fun setUp() {
        JLatexMathAndroid.init(RuntimeEnvironment.getApplication())
    }

    // ---------- 分段规则（纯函数） ----------

    @Test
    fun `纯中文是单个普通文字片段`() {
        assertEquals(listOf(LabelPiece(text = "功率谱密度")), splitLabelPieces("功率谱密度"))
    }

    @Test
    fun `中文与公式混排被拆成两段`() {
        assertEquals(
            listOf(LabelPiece(text = "功率谱密度"), LabelPiece(latex = "S_c(f)")),
            splitLabelPieces("功率谱密度 \$S_c(f)\$"),
        )
    }

    @Test
    fun `公式在前中文在后也拆成两段`() {
        assertEquals(
            listOf(LabelPiece(latex = "S_c(f)"), LabelPiece(text = "的对比")),
            splitLabelPieces("\$S_c(f)\$ 的对比"),
        )
    }

    @Test
    fun `公式体内含中文时退回普通文字`() {
        // 这种写法一旦交给 JLatexMath 就会叠成一团
        assertEquals(listOf(LabelPiece(text = "功率谱")), splitLabelPieces("\$功率谱\$"))
    }

    @Test
    fun `裸命令且不含中文仍按公式处理`() {
        assertEquals(listOf(LabelPiece(latex = "\\frac{N_0}{2}")), splitLabelPieces("\\frac{N_0}{2}"))
    }

    @Test
    fun `裸命令但混了中文必须走普通文字`() {
        // 关键回归：旧逻辑把整串当公式 ⇒ 中文全部堆叠
        val pieces = splitLabelPieces("带宽 \\Delta f 与 \\sum 的关系")
        assertEquals(1, pieces.size)
        assertTrue("混中文的裸命令不能当公式（否则汉字叠成一团）", pieces[0].latex == null)
    }

    @Test
    fun `多个公式分组依次拆开`() {
        assertEquals(
            listOf(LabelPiece(latex = "a"), LabelPiece(text = "和"), LabelPiece(latex = "b")),
            splitLabelPieces("\$a\$ 和 \$b\$"),
        )
    }

    @Test
    fun `纯文本保持单段`() {
        assertEquals(listOf(LabelPiece(text = "时间 t")), splitLabelPieces("时间 t"))
    }

    @Test
    fun `空串没有片段`() {
        assertEquals(emptyList<LabelPiece>(), splitLabelPieces("   "))
    }

    @Test
    fun `中文判定覆盖全角标点`() {
        assertTrue(containsCjk("（纵轴）"))
        assertTrue(containsCjk("功率谱"))
        assertTrue(!containsCjk("S_c(f) = 2B"))
    }

    // ---------- x 轴坐标范围（覆盖数据 + 两端留白） ----------

    @Test
    fun `数据贴到边界时两端都留出边距`() {
        // 数据是 -0.08..1.08，模型给的 0..1 更窄 ⇒ 视野由数据决定；
        // 此时曲线首末点正好等于视野边界，必须外扩留白，否则看起来就像图被切断
        val (lo, hi) = conservativeRange(-0.08, 1.08, 0.0, 1.0)
        assertTrue("左端要外扩到数据之外：$lo", lo < -0.08)
        assertTrue("右端要外扩到数据之外：$hi", hi > 1.08)
        val pad = (1.08 - (-0.08)) * X_MARGIN_RATIO
        assertEquals(-0.08 - pad, lo, 1e-9)
        assertEquals(1.08 + pad, hi, 1e-9)
    }

    @Test
    fun `模型范围更宽且数据不贴边时原样保留`() {
        val (lo, hi) = conservativeRange(-0.08, 1.08, -5.0, 5.0)
        assertEquals("模型视野本来就宽松，不该再撑大", -5.0, lo, 1e-9)
        assertEquals("模型视野本来就宽松，不该再撑大", 5.0, hi, 1e-9)
    }

    @Test
    fun `模型范围比数据更窄时扩展到覆盖数据并留白`() {
        val (lo, hi) = conservativeRange(-0.08, 1.08, 0.5, 0.6)
        assertTrue("必须覆盖数据本身：$lo", lo < -0.08)
        assertTrue("必须覆盖数据本身：$hi", hi > 1.08)
    }

    @Test
    fun `没有模型范围时用数据范围并留白`() {
        val (lo, hi) = conservativeRange(-0.08, 1.08, null, null)
        assertTrue(lo < -0.08)
        assertTrue(hi > 1.08)
    }

    @Test
    fun `跨度为零时不产生 NaN 或负边距`() {
        val (lo, hi) = conservativeRange(2.0, 2.0, null, null)
        assertEquals(2.0, lo, 1e-9)
        assertEquals(2.0, hi, 1e-9)
    }

    // ---------- 平衡坐标范围（主体完整 + 边距 + 极端点折叠） ----------

    @Test
    fun `没有离群点时范围覆盖全数据并留边距`() {
        // 抛物线 3950..3987（提示词要求模型「范围给全」，这里模拟它给的就是数据极值）
        val values = (0..100).map { 3950.0 + 37.0 * (it / 100.0) * (it / 100.0) }
        val (lo, hi) = balancedRange(values, 3950.0, 3987.0).let { it.lo to it.hi }
        assertTrue("下边距应把谷底抬离轴线（lo=$lo）", lo < 3950.0)
        assertTrue("上边距应留出余量（hi=$hi）", hi > 3987.0)
        assertTrue("范围不该扩张到离谱（span=${hi - lo}）", hi - lo < 80.0)
    }

    @Test
    fun `有极端离群点时主体仍占据大部分视野且标记折叠`() {
        // 主体 0..50，另有一个 990 的尖峰
        val values = (0..100).map { 0.0 + 50.0 * (it / 100.0) } + 990.0
        val range = balancedRange(values, null, null)
        assertTrue("尖峰应被折叠（hi=${range.hi}）", range.foldedHigh)
        assertTrue("主体上界必须落在视野内（hi=${range.hi}）", range.hi > 50.0)
        assertTrue("视野不该被尖峰撑大（span=${range.hi - range.lo}）", range.hi - range.lo < 120.0)
    }

    @Test
    fun `模型范围比主体更窄时被扩展到覆盖主体`() {
        val values = listOf(0.0, 10.0, 20.0, 30.0, 40.0, 50.0)
        val range = balancedRange(values, 20.0, 30.0)
        assertTrue("下界应被扩到主体之外（lo=${range.lo}）", range.lo < 0.0 || range.lo <= 0.0)
        assertTrue("上界应被扩到主体之外（hi=${range.hi}）", range.hi >= 50.0)
    }

    @Test
    fun `模型给了更宽的视野时予以保留`() {
        val values = listOf(3950.0, 3960.0, 3990.0)
        val range = balancedRange(values, 0.0, 4000.0)
        // 模型窗口更宽时不能被收窄（可能因为「数据贴上边界」在其外侧再加一点边距）
        assertTrue("下界不该被收窄（lo=${range.lo}）", range.lo <= 0.0)
        assertTrue("上界不该被收窄（hi=${range.hi}）", range.hi >= 4000.0)
        assertTrue("模型视野更宽时不应判为折叠", !range.foldedLow && !range.foldedHigh)
    }

    @Test
    fun `没有数据时用模型范围兜底`() {
        val range = balancedRange(listOf(null, Double.NaN), -1.0, 1.0)
        assertEquals(-1.0, range.lo, 1e-9)
        assertEquals(1.0, range.hi, 1e-9)
    }

    @Test
    fun `恒定值不会产生零跨度范围`() {
        val range = balancedRange(listOf(5.0, 5.0, 5.0), null, null)
        assertTrue("恒定值也要有非零跨度（lo=${range.lo} hi=${range.hi}）", range.hi > range.lo)
    }

    // ---------- 核心不变量：任何情况下都不能把中文交给公式排版器 ----------

    /**
     * 这是本文件最重要的一条：**切成片段后，凡是交给 JLatexMath 的片段都不许含 CJK**。
     *
     * 汉字在 JLatexMath 里宽度为 0（实测「功率谱」与「功率谱功率谱功率谱」同为 12px），
     * 一旦混进公式片段就会叠成一团——用户截图里的「所有文字挤在左上角」。
     * 覆盖模型真实会写出的各种标签形态。
     */
    @Test
    fun `交给公式排版器的片段永不含中文`() {
        val labels = listOf(
            "功率谱密度",
            "功率谱密度 \$S_c(f)\$",
            "\$S_{Y_c}^2(f)\$ 与 \$S_{Y_s}^2(f)\$ 的对比",
            "带宽 \\Delta f 与 \\sum 的关系",
            "（纵轴为归一化示意值）",
            "\$B/2\$ 区间",
            "归一化 \$P_{Y_c}(f)\$（上图）",
            "时间 t (s)",
            "\$|H(f)|^2\$",
            "功率谱 \$\\frac{1}{2}N_0\$ 曲线",
            "\$功率谱\$",
            "计算式：4\\pi^2 N_0 (f^2 + f_c^2)",
        )
        labels.forEach { label ->
            splitLabelPieces(label).forEach { piece ->
                val latex = piece.latex
                if (latex != null) {
                    assertTrue(
                        "公式片段里出现中文会叠成一团：标签「$label」→ 公式片段「$latex」",
                        !containsCjk(latex),
                    )
                }
            }
        }
    }

    /** 混排标签必须同时保留中文与公式，不能悄悄丢掉一半。 */
    @Test
    fun `混排标签的中文与公式都要保留`() {
        val pieces = splitLabelPieces("功率谱密度 \$S_c(f)\$ 的对比")
        val text = pieces.mapNotNull { it.text.ifEmpty { null } }.joinToString("")
        val latex = pieces.mapNotNull { it.latex }
        assertTrue("中文部分不能丢（实际「$text」）", text.contains("功率谱密度") && text.contains("的对比"))
        assertEquals(listOf("S_c(f)"), latex)
    }

    // ---------- 图内公式：判定与规范化（v1.0.35） ----------

    /**
     * 纯符号刻度必须走公式路径。
     *
     * 用户反馈「坐标轴上的注释没有公式显示」：旧逻辑只把「含反斜杠命令」的串当公式，
     * 于是 `f_c-B/2` 这种**既没有 `$`、也没有 `\`** 的参数刻度被判为纯文本原样画出
     * （下划线原样可见、排版也不像数学式）。
     */
    @Test
    fun `含下标上标的纯符号刻度按公式排版`() {
        listOf("f_c-B/2", "f_c", "f_c+B/2", "N_0", "x^2", "-B/2").forEach { label ->
            val pieces = splitLabelPieces(label)
            assertEquals("「$label」应判为一个公式片段：$pieces", 1, pieces.size)
            assertNotNull("「$label」应走公式路径", pieces[0].latex)
        }
    }

    /** 真·纯文字不能被误判成公式（中文进公式引擎会叠成一团）。 */
    @Test
    fun `不含数学特征的纯文字仍按文字排版`() {
        val pieces = splitLabelPieces("归一化示意")
        assertEquals(1, pieces.size)
        assertNull("纯文字不该进公式引擎", pieces[0].latex)
    }

    /**
     * `\frac` 的裸参数要补花括号。
     *
     * JLatexMath 不接受 `\frac B2`（标准 LaTeX 可以），会抛 ParseException，
     * 整条公式退化成「公式无法渲染」的占位 —— 用户截图里那条
     * `\frac B2<|f|<f_c+\frac B2` 就是整条失败的。
     */
    @Test
    fun `frac 的裸参数补上花括号`() {
        assertEquals("""\frac{B}{2}""", normalizeLatexFractions("""\frac B2"""))
        assertEquals("本来就带花括号时幂等", """\frac{B}{2}""", normalizeLatexFractions("""\frac{B}{2}"""))
        assertEquals(
            "整条区间表达式都要被修好",
            """f_c-\frac{B}{2}<|f|<f_c+\frac{B}{2}""",
            normalizeLatexFractions("""f_c-\frac B2<|f|<f_c+\frac B2"""),
        )
        assertEquals(
            "`\\dfrac` 不该被误伤（正则要求反斜杠后紧跟 frac）",
            """\dfrac{a}{b}""",
            normalizeLatexFractions("""\dfrac{a}{b}"""),
        )
    }

    /** 规范化要真的生效在切片路径里，而不是只作为一个孤立工具函数存在。 */
    @Test
    fun `切片时已对公式做过 frac 规范化`() {
        val pieces = splitLabelPieces("""${'$'}${'$'}f_c-\frac B2${'$'}${'$'}""")
        assertEquals("""f_c-\frac{B}{2}""", pieces.single().latex)
    }

    /** 录制型 Canvas：记下每次平移，用来验证「标签画在哪里」。 */
    private class RecordingCanvas(width: Int, height: Int) :
        android.graphics.Canvas(android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)) {
        val translations = mutableListOf<Pair<Float, Float>>()
        val texts = mutableListOf<Pair<String, Float>>()
        override fun translate(dx: Float, dy: Float) {
            translations += dx to dy
            super.translate(dx, dy)
        }
        override fun drawText(text: String, x: Float, y: Float, paint: android.graphics.Paint) {
            texts += text to x
            super.drawText(text, x, y, paint)
        }
    }

    /**
     * 关键回归：图内公式标签必须**按计算出的位置平移后再画**。
     *
     * 根因（v1.0.30 定位）：`JLatexMathDrawable.draw()` 只按 `bounds` 的**宽高**算居中偏移，
     * `translate` 的参数里**完全没有 `bounds.left/top`**（反编译确认）——也就是说
     * `setBounds(left, top, …)` 对位置毫无作用，**公式一律画在画布原点**，
     * 于是所有公式标签都堆在图的左上角、相互重叠（用户反馈的「文字固定在左上角」）。
     * 修复方式是自己 `canvas.translate(位置)` 后再给 drawable 原点 bounds。
     *
     * 这条测试验证的正是「有按位置平移」这件事：公式存在时，必须出现一次横向平移
     * 到标签起点（> 0）；修复前这里只会看到 (0,0)（即不产生平移）。
     */
    @Test
    fun `图内公式标签必须按位置平移后再绘制`() {
        val canvas = RecordingCanvas(900, 540)
        renderer.drawAll(
            canvas,
            PlotSpec(
                title = "\$P_{Y_c}(f)\$",
                x = Axis(min = -1.0, max = 1.0),
                y = Axis(),
                series = listOf(Series(label = "\$S_c(f)\$", expr = "x^2")),
            ),
            width = 900f,
            height = 540f,
        )
        val horizontalOffsets = canvas.translations.map { it.first }
        assertTrue(
            "公式标签必须被平移到自己的位置（实际平移 x 值：$horizontalOffsets）",
            horizontalOffsets.any { it > 100f },
        )
    }

    // ---------- 标签落位/夹取（纯函数，见 planLabelLayout） ----------
    //
    // 为什么是纯函数测试而不是「画出来看」：Robolectric 的 legacy graphics 里
    // `Paint.measureText` **每个字符恒返回 1px**（实测：`a`=1、300 个 a=300、
    // `汉字测试`=4），根本量不出真实字宽 ⇒ 任何「文字是否越界」的像素断言都是假绿。
    // 把落位决策抽成 planLabelLayout 后，可以用受控的宽度列表精确验证几何规则。

    private val bounds = 100f to 400f // leftLimit..rightLimit，可用 300

    @Test
    fun `装得下时居中原样画`() {
        val plan = planLabelLayout(
            widths = listOf(100f, 100f),
            gap = 10f,
            ellipsisWidth = 10f,
            anchorX = 250f,
            alignCenter = true,
            leftLimit = bounds.first,
            rightLimit = bounds.second,
        )
        assertEquals(2, plan.keepCount)
        assertTrue(!plan.truncated)
        // 总宽 100+10+100 = 210，居中于 250 ⇒ 起点 145
        assertEquals(145f, plan.startX, 0.01f)
    }

    @Test
    fun `左对齐时起点等于锚点`() {
        val plan = planLabelLayout(
            widths = listOf(50f),
            gap = 0f,
            ellipsisWidth = 10f,
            anchorX = 150f,
            alignCenter = false,
            leftLimit = bounds.first,
            rightLimit = bounds.second,
        )
        assertEquals(150f, plan.startX, 0.01f)
        assertTrue(!plan.truncated)
    }

    /** 关键回归：贴着右边界的内容必须被左移收进来（用户反馈「注释右侧溢出图片」）。 */
    @Test
    fun `靠右放置时整体被左移收进边界`() {
        val plan = planLabelLayout(
            widths = listOf(120f),
            gap = 0f,
            ellipsisWidth = 10f,
            anchorX = 390f, // 起点 390 + 120 = 510 > 400 ⇒ 必须左移
            alignCenter = false,
            leftLimit = bounds.first,
            rightLimit = bounds.second,
        )
        assertEquals(400f - 120f, plan.startX, 0.01f)
        assertTrue("左移后右缘不得越界", plan.startX + 120f <= bounds.second + 0.01f)
    }

    /** 靠左放置时被右移到左边界。 */
    @Test
    fun `靠左放置时整体被右移收进边界`() {
        val plan = planLabelLayout(
            widths = listOf(80f),
            gap = 0f,
            ellipsisWidth = 10f,
            anchorX = 10f,
            alignCenter = false,
            leftLimit = bounds.first,
            rightLimit = bounds.second,
        )
        assertEquals(bounds.first, plan.startX, 0.01f)
    }

    /** 装不下时必须截断，并给省略号留出宽度。 */
    @Test
    fun `装不下时截断并留出省略号`() {
        // 可用 300；4 段各 100（+gap 10×3）= 430 > 300
        val plan = planLabelLayout(
            widths = listOf(100f, 100f, 100f, 100f),
            gap = 10f,
            ellipsisWidth = 12f,
            anchorX = 100f,
            alignCenter = false,
            leftLimit = bounds.first,
            rightLimit = bounds.second,
        )
        assertTrue("必须截断", plan.truncated)
        // 第 1 段 100 + 省略号 12 = 112 ≤ 300 ✓；再加第 2 段 110 ⇒ 222+12=234 ≤ 300 ✓；
        // 再加第 3 段 110 ⇒ 332+12=344 > 300 ✗ ⇒ 保留 2 段
        assertEquals(2, plan.keepCount)
    }

    /** 连一段都放不下时只画省略号（不能画出任何越界内容）。 */
    @Test
    fun `一段都放不下时只画省略号`() {
        val plan = planLabelLayout(
            widths = listOf(500f),
            gap = 0f,
            ellipsisWidth = 12f,
            anchorX = 100f,
            alignCenter = false,
            leftLimit = bounds.first,
            rightLimit = bounds.second,
        )
        assertEquals(0, plan.keepCount)
        assertTrue("必须标记为截断（只余省略号）", plan.truncated)
        assertTrue("省略号也要落在边界内", plan.startX >= bounds.first - 0.01f)
    }

    /** 边界无效（右 ≤ 左）时直接判为不可见，避免画到奇怪的位置。 */
    @Test
    fun `边界无效时不可见`() {
        val plan = planLabelLayout(
            widths = listOf(10f),
            gap = 0f,
            ellipsisWidth = 10f,
            anchorX = 50f,
            alignCenter = false,
            leftLimit = 100f,
            rightLimit = 100f,
        )
        assertTrue(!plan.visible)
    }

    @Test
    fun `没有片段时不可见`() {
        val plan = planLabelLayout(
            widths = emptyList(),
            gap = 0f,
            ellipsisWidth = 10f,
            anchorX = 50f,
            alignCenter = true,
            leftLimit = 0f,
            rightLimit = 100f,
        )
        assertTrue(!plan.visible)
    }
}
