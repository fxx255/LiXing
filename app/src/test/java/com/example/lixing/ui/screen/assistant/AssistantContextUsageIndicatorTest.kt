package com.example.lixing.ui.screen.assistant

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import com.example.lixing.data.assistant.AssistantContextUsage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class AssistantContextUsageIndicatorTest {
    @get:Rule
    val compose = createComposeRule()

    private fun render(usage: AssistantContextUsage) {
        compose.setContent {
            MaterialTheme {
                AssistantContextUsageIndicator(usage)
            }
        }
    }

    @Test
    fun `configured window shows occupied percentage`() {
        render(AssistantContextUsage(windowTokens = 100_000, estimatedInputTokens = 25_000, reservedOutputTokens = 10_000))

        compose.onNodeWithText("25%").assertExists()
        compose.onNodeWithContentDescription("上下文占用 25%").assertExists()
    }

    @Test
    fun `unknown window does not invent percentage`() {
        render(AssistantContextUsage(estimatedInputTokens = 25_000))

        compose.onNodeWithText("—").assertExists()
        compose.onNodeWithContentDescription("上下文窗口未知").assertExists()
    }

    @Test
    fun `small nonzero usage does not look empty`() {
        render(AssistantContextUsage(windowTokens = 1_000_000, estimatedInputTokens = 100))

        compose.onNodeWithText("<1%").assertExists()
    }

    @Test
    fun `empty usage shows zero percent`() {
        render(AssistantContextUsage(windowTokens = 100_000))

        compose.onNodeWithText("0%").assertExists()
    }

    @Test
    fun `over capacity usage is not hidden by full ring`() {
        render(AssistantContextUsage(windowTokens = 100_000, estimatedInputTokens = 120_000))

        compose.onNodeWithText("120%").assertExists()
    }

    @Test
    fun `ring refreshes for streamed and reported usage`() {
        val usage = mutableStateOf(AssistantContextUsage(windowTokens = 100_000, estimatedInputTokens = 25_000))
        compose.setContent {
            MaterialTheme {
                AssistantContextUsageIndicator(usage.value)
            }
        }

        compose.onNodeWithText("25%").assertExists()
        compose.runOnIdle { usage.value = usage.value.copy(estimatedOutputTokens = 5_000) }
        compose.onNodeWithText("30%").assertExists()
        compose.runOnIdle { usage.value = usage.value.copy(serverInputTokens = 80_000, serverOutputTokens = 10_000) }
        compose.onNodeWithText("90%").assertExists()
    }
}
