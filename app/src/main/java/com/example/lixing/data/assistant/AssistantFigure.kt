package com.example.lixing.data.assistant

import com.example.lixing.domain.diagram.DiagramSpec
import com.example.lixing.domain.plot.PlotSpec

/**
 * 一轮回答里的一个图表槽位。
 *
 * [Missing] 是**占位**：模型给了一个无法解析的图（缺标题、参数非法），
 * 这一格仍然要占号 —— 否则后面 `[[FIGURE:n]]` 的锚点会整体错位，
 * 正文说「见下图」而图指向了别的图。
 */
sealed interface AssistantFigure {
    data class Plot(val spec: PlotSpec) : AssistantFigure
    data class Diagram(val spec: DiagramSpec) : AssistantFigure
    data object Missing : AssistantFigure
}

/** 顺序是各轮内部的局部顺序：先 plots 后 diagrams，**包含失败槽位**。 */
fun ParsedAssistantReply.orderedFigures(): List<AssistantFigure> =
    plotSlots.map { it?.let(AssistantFigure::Plot) ?: AssistantFigure.Missing } +
        diagramSlots.map { it?.let(AssistantFigure::Diagram) ?: AssistantFigure.Missing }
