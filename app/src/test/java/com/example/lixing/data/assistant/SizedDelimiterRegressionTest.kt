package com.example.lixing.data.assistant

import android.app.Application
import com.example.lixing.rendering.LatexRendering
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import ru.noties.jlatexmath.JLatexMathAndroid
import ru.noties.jlatexmath.JLatexMathDrawable
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class SizedDelimiterRegressionTest {
    @Before
    fun setUp() {
        JLatexMathAndroid.init(RuntimeEnvironment.getApplication())
    }

    @Test
    fun sizedOpeningAndClosingDelimiterCommandsRender() {
        for (size in listOf("big", "Big", "bigg", "Bigg")) {
            for (suffix in listOf("", "l", "r")) {
                val command = "\\$size$suffix"
                assertTrue(LatexRendering.build("$command(", 36f).intrinsicWidth > 0)
            }
        }
    }

    @Test
    fun reportedArcsineFormulaRendersAfterSanitizing() {
        val formula = """=\arcsin\Bigl(2\sqrt{(1-x)-(1-x)^{2}}\Bigr)"""
        val sanitized = sanitizeAssistantLatex(formula)
        val drawable = LatexRendering.build(sanitized, 36f)
        assertTrue(drawable.intrinsicWidth > 0)
        assertTrue(drawable.intrinsicHeight > 0)
    }

    @Test
    fun reportedArcsineRendersConcurrentlyWithAsyncAndMeasurementPaths() {
        val formula = """=\arcsin\Bigl(2\sqrt{(1-x)-(1-x)^{2}}\Bigr)"""
        val executor = Executors.newFixedThreadPool(8)
        val barrier = CyclicBarrier(8)
        val expected = LatexRendering.build(formula, 36f)
        try {
            val futures = (0 until 8).map { workerIndex ->
                executor.submit(Callable {
                    barrier.await(10, TimeUnit.SECONDS)
                    repeat(100) {
                        val drawable = if (workerIndex % 2 == 0) {
                            LatexRendering.build(formula, 36f)
                        } else {
                            LatexRendering.executor.submit(Callable {
                                JLatexMathDrawable.builder(formula).textSize(36f).build()
                            }).get(10, TimeUnit.SECONDS)
                        }
                        assertEquals(expected.intrinsicWidth, drawable.intrinsicWidth)
                        assertEquals(expected.intrinsicHeight, drawable.intrinsicHeight)
                    }
                })
            }
            futures.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }
    }
}
