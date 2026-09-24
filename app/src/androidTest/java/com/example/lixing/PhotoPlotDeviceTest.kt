package com.example.lixing

import android.content.Context
import android.graphics.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.lixing.domain.plot.*
import com.example.lixing.ui.photo.*
import com.example.lixing.ui.plot.PlotBitmapRenderer
import com.example.lixing.ui.screen.today.dialog.PhotoCropDialog
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.noties.jlatexmath.JLatexMathAndroid
import java.io.File

@RunWith(AndroidJUnit4::class)
class PhotoPlotDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun cropRotationSavesTheDisplayedOrientation() {
        val source = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888)
        Canvas(source).apply {
            drawColor(Color.RED)
            drawRect(0f, 40f, 60f, 80f, Paint().apply { color = Color.BLUE })
            drawRect(60f, 40f, 120f, 80f, Paint().apply { color = Color.YELLOW })
            drawRect(60f, 0f, 120f, 40f, Paint().apply { color = Color.GREEN })
        }
        val file = File(context.cacheDir, "rotation-device.png")
        file.outputStream().use { source.compress(Bitmap.CompressFormat.PNG, 100, it) }
        var saved = false
        compose.setContent { MaterialTheme { PhotoCropDialog(file.path, { saved = true }, {}, { saved = true }) } }
        compose.onNodeWithText("逆时针 90°").performClick()
        compose.onNodeWithText("使用整张").performScrollTo().assertIsDisplayed().performClick()
        try { compose.waitUntil(10_000) { saved } }
        catch (failure: Throwable) { throw AssertionError(compose.onRoot().printToString(), failure) }
        val rotated = decodeUprightPhoto(file.path)!!
        assertEquals(80, rotated.width)
        assertEquals(120, rotated.height)
        assertEquals(Color.GREEN, rotated.getPixel(20, 20))
        assertEquals(Color.YELLOW, rotated.getPixel(60, 20))
        assertEquals(Color.RED, rotated.getPixel(20, 100))
        file.delete()
    }

    @Test fun realCanvasKeepsOriginMarkerAboveAxesAndRendersShading() {
        JLatexMathAndroid.init(context)
        val bitmap = Bitmap.createBitmap(1400, 900, Bitmap.Config.ARGB_8888)
        var pointX = 0f; var pointY = 0f
        val canvas = object : Canvas(bitmap) {
            override fun drawCircle(x: Float, y: Float, radius: Float, paint: Paint) {
                super.drawCircle(x, y, radius, paint)
                if (paint.color == Color.parseColor("#FF7EB6")) { pointX = x; pointY = y }
            }
        }
        val theme = PlotBitmapRenderer.Theme()
        canvas.drawColor(theme.background)
        val spec = PlotSpec(
            title = "曲线之间的区域与坐标轴上的点",
            x = Axis("x", min = 0.0, max = 1.2, grid = false),
            y = Axis("y", min = 0.0, max = 1.5, grid = false),
            series = listOf(Series(expr = "x", label = "y=x", colorIndex = 0, style = "dashed"),
                Series(expr = "x^2", label = "y=x²", colorIndex = 2),
                Series(points = listOf(0.0 to 0.0), style = "marker", color = "#FF7EB6", markerSize = 7f)),
            shades = listOf(ShadeRegion(x0 = 0.0, x1 = 1.0, upper = "x", lower = "x^2", colorIndex = 6, opacity = .28)),
        )
        PlotBitmapRenderer(1.5f).drawAll(canvas, spec, 1400f, 900f)
        assertTrue(pointX > 0 && pointY > 0)
        assertEquals(Color.parseColor("#FF7EB6"), bitmap.getPixel(pointX.toInt(), pointY.toInt()))
        val output = File(context.getExternalFilesDir(null), "plot-shading-solid.png")
        output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val patterns = spec.copy(title = "斜线阴影与曲线样式", shades = listOf(spec.shades.single().copy(pattern = "hatched", opacity = .5)),
            series = spec.series.take(2) + Series(points = listOf(.5 to .25, 1.0 to 1.0), style = "marker", markerShape = "diamond", colorIndex = 3))
        val second = PlotBitmapRenderer(1.5f).render(patterns, 1400, 900)
        File(context.getExternalFilesDir(null), "plot-shading-hatched.png").outputStream().use { second.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val styles = spec.copy(title = "六条曲线的颜色与样式", x = Axis("x", min = 0.0, max = 6.0), y = Axis("y", min = 0.0, max = 7.0),
            series = (0..5).map { Series(expr = "sin(x)*0.3+${it + 1}", label = "${it + 1}", style = listOf("line", "dashed", "dotted", "dashdot")[it % 4], colorIndex = it) }, shades = emptyList())
        val third = PlotBitmapRenderer(1.5f).render(styles, 1400, 900)
        File(context.getExternalFilesDir(null), "plot-line-styles.png").outputStream().use { third.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
