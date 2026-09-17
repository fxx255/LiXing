package com.example.lixing.ui.screen.assistant

import android.graphics.Bitmap
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.noties.jlatexmath.JLatexMathAndroid
import java.io.File

@RunWith(AndroidJUnit4::class)
class SavedAnswerInteractionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun imageBetweenLongFormulaAnswerAndTrailingTextReceivesTouch() = checkInteraction(false)

    @Test fun tableAfterLongFormulaAnswerScrollsHorizontally() = checkInteraction(true)

    private fun checkInteraction(includeTable: Boolean) {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        JLatexMathAndroid.init(context)
        val args = androidx.test.platform.app.InstrumentationRegistry.getArguments()
        val fixturePath = args.getString("fixturePath")
        var content = if (fixturePath != null) File(fixturePath).readText() else
            (1..35).joinToString("\n\n") {
                "第 $it 段完整推导：\n\n${'$'}${'$'}P(f)=4\\pi^2N_0\\left(f_c^2+f^2\\right)${'$'}${'$'}\n\n同相分量与正交分量的功率谱完全相同。"
            } + "\n\n[[FIGURE:1]]\n\n" + (1..8).joinToString("\n\n") { "图后第 $it 段：最低点不为零，保留实际的数学特征。" }
        if (includeTable) content = content.replace("[[FIGURE:1]]",
            "| Frequency boundary description | Power spectrum expression | Interpretation |\n" +
                "| --- | --- | --- |\n| Left boundary | Value | Notes |\n\n[[FIGURE:1]]")
        val imageFile = File(context.cacheDir, "touch-regression.png")
        Bitmap.createBitmap(1400, 900, Bitmap.Config.ARGB_8888).also { bitmap ->
            bitmap.eraseColor(android.graphics.Color.BLUE)
            imageFile.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        lateinit var state: LazyListState
        lateinit var scope: CoroutineScope
        var clicks = 0
        compose.setContent {
            var viewer by remember { mutableStateOf(false) }
            state = rememberLazyListState()
            scope = rememberCoroutineScope()
            MaterialTheme {
                LazyColumn(Modifier.fillMaxSize(), state, reverseLayout = true) {
                    item { MessageBubble("assistant", content, listOf(imageFile.absolutePath)) { _, _ -> clicks++; viewer = true } }
                }
                if (viewer) PhotoViewerDialog(listOf(imageFile.absolutePath), 0) { viewer = false }
            }
        }
        compose.waitForIdle()
        val image = compose.onNodeWithContentDescription("生成的图表")
        val targetY = compose.onRoot().fetchSemanticsNode().size.height / 3f
        repeat(3) {
            val y = image.fetchSemanticsNode().positionInRoot.y
            compose.runOnIdle { scope.launch { state.scrollBy(targetY - y) } }
            compose.waitForIdle()
        }
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { screenshot ->
            File(context.getExternalFilesDir(null), "assistant-touch-regression.png").outputStream().use {
                screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            screenshot.recycle()
        }
        if (includeTable) {
            val table = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange))
            val y = table.fetchSemanticsNode().positionInRoot.y
            compose.runOnIdle { scope.launch { state.scrollBy(targetY - y) } }
            compose.waitForIdle()
            table.assertIsDisplayed().performTouchInput { swipeLeft() }
            val range = table.fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange]
            compose.runOnIdle { assertTrue(range.value() > 0) }
        } else {
            image.assertIsDisplayed().performTouchInput { down(center); advanceEventTime(150); up() }
            compose.runOnIdle { assertEquals("Image tap below actual formula-rich text was lost", 1, clicks) }
            compose.onNodeWithContentDescription("关闭").assertExists().performClick()
            compose.onNodeWithContentDescription("关闭").assertDoesNotExist()
        }
    }
}
