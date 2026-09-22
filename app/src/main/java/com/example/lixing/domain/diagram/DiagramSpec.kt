package com.example.lixing.domain.diagram

import kotlinx.serialization.Serializable

/**
 * 结构化框图 spec：由模型给出的**拓扑**（节点 / 连线），客户端负责布局与绘制。
 *
 * 设计边界：
 * - 模型**只给拓扑与语义**，不给坐标 —— 坐标由本地布局器计算，
 *   因此不需要远程 Mermaid / WebView / JS，也不依赖模型画图能力；
 * - 规模有硬上限（见 [DiagramLimits]），超出即拒收该图并给警告，
 *   坏图只丢自己，绝不影响正文；
 * - 标签可以是纯中文，也可以含 `$...$` 公式，渲染时统一交给 JLatexMath。
 */

/** 盒子 / 圆形 / 端口记号等形状。 */
@Serializable
enum class DiagramNodeShape {
    /** 矩形功能框（低通滤波器、放大器、检波器…）。 */
    BLOCK,

    /** 圆形乘法器（⊗）或相加器（Σ，用 [DiagramNode.glyph] 区分）。 */
    MIXER,

    /** 求和 / 合流点。 */
    SUM,

    /** 无框标注：输入、输出、以及任意说明文字。 */
    IO,

    /** 分支 / 交点。 */
    JUNCTION,
}

/** 连线两侧挂接的端口（不需要模型给绝对坐标）。 */
@Serializable
enum class DiagramPort {
    LEFT, RIGHT, TOP, BOTTOM, AUTO,
}

@Serializable
data class DiagramNode(
    val id: String,
    val label: String,
    val shape: DiagramNodeShape,
    /** MIXER 里的符号：默认 `×`；相加器可写 `+`。 */
    val glyph: String? = null,
    /** 可选副标题（如型号、参数，写在框内第二行）。 */
    val subLabel: String? = null,
    /** 该节点的分支提示：用于把某条支路放到主链下方（例如本地载波支路）。 */
    val row: Int? = null,
    /** 主链上的次序提示（越大越靠右）。 */
    val column: Int? = null,
)

@Serializable
data class DiagramEdge(
    val from: String,
    val to: String,
    val label: String? = null,
    val fromPort: DiagramPort = DiagramPort.AUTO,
    val toPort: DiagramPort = DiagramPort.AUTO,
    /** 虚线：可选、备注性质的连接。 */
    val dashed: Boolean = false,
)

@Serializable
data class DiagramSpec(
    val title: String,
    val nodes: List<DiagramNode>,
    val edges: List<DiagramEdge>,
    /** 期望流向：row/column 缺失用于主推断。 */
    val direction: DiagramDirection = DiagramDirection.LR,
)

@Serializable
enum class DiagramDirection { LR, TB }

/** 规模上限。超出的图拒收，而不是画成一团看不清的东西。 */
object DiagramLimits {
    const val MAX_DIAGRAMS = 4
    const val MAX_NODES = 24
    const val MAX_EDGES = 40
    const val MAX_LABEL_CHARS = 60
    const val MAX_SUB_LABEL_CHARS = 40
    const val MAX_TITLE_CHARS = 40
    const val MAX_ID_CHARS = 24
    const val MAX_ROWS = 4
    const val MAX_COLUMNS = 16
}
