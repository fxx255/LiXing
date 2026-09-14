package com.example.lixing.ui.plot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
class PlotLabelLayoutTest {

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

    // ---------- 保守坐标范围 ----------

    @Test
    fun `模型范围恰好等于数据时被边距撑开`() {
        // 数据实际是 0..1，autoRange 已给出 -0.08..1.08；模型给 0..1 不能裁剪它
        val (lo, hi) = conservativeRange(-0.08, 1.08, 0.0, 1.0)
        assertEquals(-0.08, lo, 1e-9)
        assertEquals(1.08, hi, 1e-9)
    }

    @Test
    fun `模型范围更宽时保留模型视野`() {
        val (lo, hi) = conservativeRange(-0.08, 1.08, -5.0, 5.0)
        assertEquals(-5.0, lo, 1e-9)
        assertEquals(5.0, hi, 1e-9)
    }

    @Test
    fun `模型范围比数据更窄时扩展到覆盖数据`() {
        val (lo, hi) = conservativeRange(-0.08, 1.08, 0.5, 0.6)
        assertEquals(-0.08, lo, 1e-9)
        assertEquals(1.08, hi, 1e-9)
    }

    @Test
    fun `没有模型范围时用数据范围`() {
        val (lo, hi) = conservativeRange(-0.08, 1.08, null, null)
        assertEquals(-0.08, lo, 1e-9)
        assertEquals(1.08, hi, 1e-9)
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
}
