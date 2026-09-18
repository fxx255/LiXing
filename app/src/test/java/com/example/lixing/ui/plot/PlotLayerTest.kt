package com.example.lixing.ui.plot

import android.app.Application
import android.graphics.*
import com.example.lixing.domain.plot.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PlotLayerTest {
    private class Recorder : Canvas() {
        data class Draw(val kind: String, val paint: Paint, val x: Float = 0f, val y: Float = 0f)
        val draws = mutableListOf<Draw>()
        override fun drawLine(x0: Float, y0: Float, x1: Float, y1: Float, paint: Paint) { draws += Draw("line", Paint(paint), x0, y0) }
        override fun drawPath(path: Path, paint: Paint) { draws += Draw("path", Paint(paint)) }
        override fun drawCircle(x: Float, y: Float, radius: Float, paint: Paint) { draws += Draw("point", Paint(paint), x, y) }
    }
    @Test fun `axis point drawn after axes mark lines and every curve`() {
        val recorder = Recorder()
        val theme = PlotBitmapRenderer.Theme()
        PlotBitmapRenderer(1f).drawAll(recorder, PlotSpec(
            x = Axis(min = -1.0, max = 1.0, grid = false, ticks = emptyList()),
            y = Axis(min = 0.0, max = 2.0, grid = false, ticks = emptyList()),
            series = listOf(Series(points = listOf(0.0 to 0.0), style = "marker"), Series(expr = "x^2", colorIndex = 1)),
            markLines = listOf(MarkLine(x = 0.0)),
            shades = listOf(ShadeRegion(x0 = -1.0, x1 = 1.0, upper = "x^2")),
        ), 900f, 560f)
        val pointIndex = recorder.draws.indexOfLast { it.kind == "point" }
        val axisIndex = recorder.draws.indexOfLast { it.paint.color == theme.axis }
        val curveIndex = recorder.draws.indexOfLast { it.kind == "path" && it.paint.style == Paint.Style.STROKE }
        val fillIndex = recorder.draws.indexOfFirst { it.kind == "path" && it.paint.style == Paint.Style.FILL }
        assertTrue(fillIndex >= 0 && fillIndex < axisIndex)
        assertTrue(axisIndex < curveIndex && curveIndex < pointIndex)
        val axisY = recorder.draws.first { it.kind == "line" && it.paint.color == theme.axis }.y
        assertEquals(axisY, recorder.draws[pointIndex].y, .001f)
    }
    @Test fun `new line patterns reach canvas and colors remain distinct`() {
        assertEquals(12, PlotBitmapRenderer.Theme().seriesColors.distinct().size)
        val recorder = Recorder()
        PlotBitmapRenderer(1f).drawAll(recorder, PlotSpec(series = listOf(
            Series(expr = "x", style = "dotted", color = "#123456"),
            Series(expr = "x^2", style = "dashdot", colorIndex = 8),
        )), 900f, 560f)
        val lines = recorder.draws.filter { it.kind == "path" && it.paint.style == Paint.Style.STROKE }
        assertEquals(2, lines.size)
        assertTrue(lines.all { it.paint.pathEffect is DashPathEffect })
        assertEquals(Color.parseColor("#123456"), lines.first().paint.color)
    }
}
