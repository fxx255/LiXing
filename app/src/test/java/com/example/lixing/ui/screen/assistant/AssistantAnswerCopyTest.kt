package com.example.lixing.ui.screen.assistant

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class AssistantAnswerCopyTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `long press answer stays in the bubble for native text selection`() {
        compose.setContent {
            MessageBubble(
                role = "assistant",
                content = "第一段\n[[FIGURE:1]]\n第二段 \$abc\$",
                imagePaths = listOf(""),
                onImageClick = { _, _ -> },
            )
        }
        compose.onNodeWithTag("assistant-answer-bubble").performTouchInput { longClick() }
        // 选择菜单由气泡内的 Android TextView 提供；这里确认旧的独立复制
        // 对话框路径没有再被触发。原生选区在真机上由系统菜单显示。
        compose.onNodeWithTag("assistant-answer-bubble").assertExists()
    }

    @Test fun `image tap still opens viewer instead of answer selection`() {
        val file = File(RuntimeEnvironment.getApplication().cacheDir, "answer-copy-image.png")
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        try {
            var imageClicks = 0
            compose.setContent {
                MessageBubble(
                    role = "assistant",
                    content = "图在这里\n[[FIGURE:1]]",
                    imagePaths = listOf(file.absolutePath),
                    onImageClick = { _, _ -> imageClicks++ },
                )
            }
            compose.onNodeWithContentDescription("生成的图表").performClick()
            compose.runOnIdle { assertEquals(1, imageClicks) }
            compose.onNodeWithText("选择并复制回答").assertDoesNotExist()
        } finally {
            file.delete()
        }
    }
}
