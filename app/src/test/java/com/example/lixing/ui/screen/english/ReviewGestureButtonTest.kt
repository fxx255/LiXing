package com.example.lixing.ui.screen.english

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.example.lixing.domain.english.ReviewGrade
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ReviewGestureButtonTest {
    @get:Rule val compose = createComposeRule()
    @Test fun `tap speaks and three swipes grade once`() {
        var spoken = 0
        val grades = mutableListOf<ReviewGrade>()
        compose.setContent { MaterialTheme { Box(Modifier.size(320.dp, 480.dp)) {
            ReviewGestureButton(.5f, .5f, true, "word", { _, _ -> }, { spoken++ }, { grades += it })
        } } }
        val button = compose.onNodeWithContentDescription("发音与评分圆钮")
        button.performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, spoken); assertTrue(grades.isEmpty()) }
        button.performTouchInput { swipe(center, center + Offset(0f, -100f), 150) }
        button.performTouchInput { swipe(center, center + Offset(100f, 0f), 150) }
        button.performTouchInput { swipe(center, center + Offset(0f, 100f), 150) }
        compose.runOnIdle { assertEquals(listOf(ReviewGrade.GOOD, ReviewGrade.HARD, ReviewGrade.AGAIN), grades) }
    }
    @Test fun `long press drag moves without speaking or grading and cancel is harmless`() {
        var moved = false
        var spoken = 0
        val grades = mutableListOf<ReviewGrade>()
        compose.setContent { MaterialTheme { Box(Modifier.size(320.dp, 480.dp)) {
            ReviewGestureButton(.5f, .5f, true, "word", { x, y -> moved = x > .5f && y > .5f }, { spoken++ }, { grades += it })
        } } }
        compose.onNodeWithContentDescription("发音与评分圆钮").performTouchInput {
            down(center); advanceEventTime(700); moveBy(Offset(80f, 80f)); up()
        }
        compose.runOnIdle { assertTrue(moved); assertEquals(0, spoken); assertTrue(grades.isEmpty()) }
        compose.onNodeWithContentDescription("发音与评分圆钮").performTouchInput {
            down(center); moveBy(Offset(0f, -100f)); cancel()
        }
        compose.runOnIdle { assertTrue(grades.isEmpty()) }
    }
    @Test fun `ambiguous diagonals and leftward drags do not grade`() {
        assertNull(gestureGrade(-100f, 0f, 36f))
        assertNull(gestureGrade(100f, 100f, 36f))
        assertNull(gestureGrade(10f, -20f, 36f))
    }
}
