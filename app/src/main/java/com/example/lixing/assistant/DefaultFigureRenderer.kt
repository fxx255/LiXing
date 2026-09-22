package com.example.lixing.assistant

import com.example.lixing.data.assistant.AssistantFigure
import com.example.lixing.data.diagram.DiagramImageStore
import com.example.lixing.data.plot.PlotImageStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 把图表槽位渲染成 PNG 路径的默认实现。
 *
 * 两条规则：
 * - **失败槽位返回空串而不是被丢弃**：位置必须保留，否则正文里
 *   `[[FIGURE:n]]` 的指向会整体错位（第 2 张图画失败，第 3 张图就顶到了第 2 位）；
 * - **画不出来绝不能影响文字回答**：两个 store 内部都吞掉异常并返回 null，
 *   这里只负责保持顺序。
 */
@Singleton
class DefaultFigureRenderer @Inject constructor(
    private val plotImageStore: PlotImageStore,
    private val diagramImageStore: DiagramImageStore,
) : FigureRenderer {

    override suspend fun render(figures: List<AssistantFigure>): List<String> =
        withContext(Dispatchers.Default) {
            figures.map { figure ->
                when (figure) {
                    is AssistantFigure.Plot -> plotImageStore.render(figure.spec)
                    is AssistantFigure.Diagram -> diagramImageStore.render(figure.spec)
                    AssistantFigure.Missing -> null
                }.orEmpty()
            }
        }
}
