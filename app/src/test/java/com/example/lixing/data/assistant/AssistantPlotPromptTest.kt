package com.example.lixing.data.assistant

import com.example.lixing.data.assistant.AssistantModelClient.Companion.SYSTEM_PROMPT
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 系统提示词的**契约测试**：钉住画图相关的几条关键指令。
 *
 * 为什么值得给提示词写测试：它是纯字符串，改错了编译不报错、运行时也不崩，
 * 只会让**模型画出来的图悄悄变差**——这类回归完全靠人眼在真机上才能发现。
 *
 * 本文件诞生于用户的一个真实提问（2026-09-16）：
 * 「我在 APP 里搜这道题的时候，接入 API 能不能把图画到一个合适的样子？」
 *
 * 排查结论：**「曲线画成深碗」的根因不在渲染器，而在提示词漏了一条指令。**
 *   · `PlotBitmapRenderer.balancedRange` 的行为是：模型给了 `y.min/max` 就原样使用
 *     （只扩不裁）；**没给就按数据实际跨度自动求范围、只留 6% 边距**
 *     ⇒ 曲线必然占满绘图区的约 85%，一条平缓抛物线被纵向拉伸成直立的深碗。
 *   · 而当时的提示词只说了「范围要覆盖主体特征、别裁掉峰值」，
 *     **从没说「别贴满」**；三个 JSON 示例里的 `y` 更是清一色只有 `label` 或 `ticks`，
 *     一个 `y.min/max` 的例子都没有 ⇒ 模型自然不给纵轴范围。
 *
 * 修法是**只改提示词、渲染器不动**（用户拍板的方案 A）：让模型承担「纵轴留多少白」
 * 这个本来就应该由它决定的语义 —— 它知道题目意图（比如 f_c 远大于带宽时曲线接近平缓），
 * 渲染器不知道。代价是必须把「留白比例」写成模型能直接执行的量化规则。
 *
 * 下面每条断言都对应一个**具体的历史故障**，改提示词时若去掉它们，会退回原来的观感。
 */
class AssistantPlotPromptTest {

    // ---- ① 必须有「纵轴要留白」的量化规则（核心修复）----

    @Test
    fun `提示词要求纵轴留白且给出可执行的倍数规则`() {
        // 关键指令：曲线只占纵轴的约 1/3。没有这句，模型就会给「刚好包住数据」的范围。
        assertTrue(
            "提示词必须明确要求纵轴留白、并给出「约占 1/3」这个可量化目标",
            SYSTEM_PROMPT.contains("曲线只占纵轴的约 1/3"),
        )
        // 光有定性描述模型执行不了，必须给算式口诀：y 范围 ≈ 数据跨度 × 3
        assertTrue(
            "提示词必须给出「y 范围 ≈ 数据跨度 × 3」这条可直接套用的规则",
            SYSTEM_PROMPT.contains("y 范围 ≈ 数据跨度 × 3"),
        )
        // 还要点破不这么做会怎样，否则模型容易觉得「留白＝浪费空间」而删掉
        assertTrue(
            "提示词必须解释不这么做的后果是「曲线被纵向拉伸成深碗」",
            SYSTEM_PROMPT.contains("深碗"),
        )
    }

    // ---- ② 必须点名「不给 y.min/max 是错的」----

    @Test
    fun `提示词明确指出省略纵轴范围会让曲线铺满`() {
        assertTrue(
            "提示词必须说明「不给 y.min/max 时客户端会自动求范围、把曲线铺满」" +
                "——这是模型唯一能理解「为什么必须给」的机制解释",
            SYSTEM_PROMPT.contains("自动") && SYSTEM_PROMPT.contains("铺满"),
        )
    }

    // ---- ③ JSON 示例必须真的示范 y.min/max ----

    @Test
    fun `画图示例里示范了纵轴范围而不只是 label`() {
        // 示例是模型抄写的主要来源。原来示例是 "y":{"label":"S(f)"}，
        // 模型就照着只给 label —— 示例里必须有 min/max 才有示范作用。
        assertTrue(
            "画图示例必须示范带 min/max 的 y 轴写法（形如 \"y\":{...\"min\":..,\"max\":..}）",
            Regex(""""y":\{[^}]*"min":[^}]*"max":""").containsMatchIn(SYSTEM_PROMPT),
        )
    }

    // ---- ④ x 与 y 的诉求相反，必须分开写清楚 ----

    @Test
    fun `提示词把 x 轴与 y 轴的取值范围诉求分开表述`() {
        // 历史问题：提示词原本有一句「范围太宽形状会被压平」，它说的是 x 轴；
        // 但新增「y 要留白」之后，两条指导方向相反，不加区分会让模型无所适从
        // （甚至可能把 x 也放开留白，导致曲线两侧出现无意义空白）。
        assertTrue(
            "提示词必须显式指出 x 与 y 要分开对待（x 收紧、y 留白）",
            SYSTEM_PROMPT.contains("x 与 y 要分开对待"),
        )
    }

    // ---- ⑤ 留白之后纵轴刻度是否还该标，要给结论 ----

    @Test
    fun `提示词交代留白后纵轴刻度的处理方式`() {
        assertTrue(
            "提示词必须说明留白后纵轴刻度的处理（否则模型会把刻度标在空白区里、误导读者）",
            SYSTEM_PROMPT.contains("纵轴要不要标刻度"),
        )
    }

    // ---- ⑥ 参数大小关系未定时，要求按物理常识而非自造数值 ----

    @Test
    fun `提示词要求未给定的参数按物理常识处理而非编造`() {
        assertTrue(
            "提示词必须说明「题目没给大小的参数按物理常识处理」（如 f_c 远大于带宽时曲线平缓）",
            SYSTEM_PROMPT.contains("题目没给大小的参数不要擅自定"),
        )
    }

    // ---- ⑦ 防回归：不许退回到「只要覆盖主体特征」这种含糊表述 ----

    @Test
    fun `不保留会导致贴满的旧表述`() {
        assertFalse(
            "提示词不应再出现「x 与 y 的范围都要覆盖主体特征本身」这种把 x/y 混为一谈、" +
                "且暗示「够用就行」的旧表述——它正是曲线贴满的直接诱因",
            SYSTEM_PROMPT.contains("x 与 y 的范围都要覆盖主体特征本身"),
        )
    }

    // ---- ⑧ 既有约束不能被这次改动挤掉 ----

    @Test
    fun `画图协议的关键既有约束仍然完整`() {
        // 这些是历次故障修出来的，改动提示词时容易被顺手删掉，一并钉住。
        assertTrue("x 轴 min/max 是定义域、不许外扩", SYSTEM_PROMPT.contains("不做任何外扩"))
        assertTrue("刻度文案必须用参数符号而非编造数值", SYSTEM_PROMPT.contains("坐标刻度也必须用符号"))
        assertTrue("\\frac 必须带花括号", SYSTEM_PROMPT.contains("不要写 \\frac B2"))
        assertTrue("图表要用锚点插进正文", SYSTEM_PROMPT.contains("[[FIGURE:1]]"))
        assertTrue("单张图曲线数与图数有上限", SYSTEM_PROMPT.contains("一张图最多 6 条曲线"))
    }
}
