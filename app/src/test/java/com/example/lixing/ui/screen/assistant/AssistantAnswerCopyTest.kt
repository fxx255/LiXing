package com.example.lixing.ui.screen.assistant

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
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

    @Test fun `long press answer opens text selection and copies answer without figure marker`() {
        compose.setContent {
            MessageBubble(
                role = "assistant",
                content = "第一段\n[[FIGURE:1]]\n第二段 \$abc\$",
                imagePaths = listOf(""),
                onImageClick = { _, _ -> },
            )
        }
        compose.onNodeWithTag("assistant-answer-bubble").performTouchInput { longClick() }
        compose.onNodeWithText("选择并复制回答").assertExists()
        compose.onNodeWithText("复制全文").performClick()
        val clipboard = RuntimeEnvironment.getApplication()
            .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertEquals("第一段\n\n第二段 \$abc\$", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
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
