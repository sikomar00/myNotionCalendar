package com.ysdoing.notioncal.feature.month

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.sp
import com.ysdoing.notioncal.core.data.calendar.CalendarZoom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** MV-08: 두 손가락으로만 배율이 바뀌고, 손을 떼면 한 번 저장하며, 70~150%를 넘지 않는다. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h760dp-xxhdpi")
class PinchToZoomTest {
    @get:Rule
    val compose = createComposeRule()

    private var percent by mutableFloatStateOf(100f)
    private val saved = mutableListOf<Float>()

    private fun setUp() {
        compose.setContent {
            MaterialTheme {
                Box(
                    Modifier.fillMaxSize().testTag("zoom").pinchToZoom(
                        onZoom = { percent = CalendarZoom.clamp(percent * it) },
                        onEnd = { saved += percent },
                    ),
                ) {
                    TextZoom(percent / 100f) { Text("글자", fontSize = 10.sp, modifier = Modifier.testTag("text")) }
                }
            }
        }
    }

    private fun pinchOut(from: Float, to: Float) = compose.onNodeWithTag("zoom").performTouchInput {
        pinch(
            start0 = center - Offset(from, 0f), end0 = center - Offset(to, 0f),
            start1 = center + Offset(from, 0f), end1 = center + Offset(to, 0f),
        )
    }

    @Test
    fun pinchChangesZoomAndSavesOnce() {
        setUp()
        val before = compose.onNodeWithTag("text").fetchSemanticsNode().size.height
        pinchOut(100f, 120f) // 1.2배 벌림
        assertTrue("벌리면 커진다: $percent", percent in 110f..130f)
        assertEquals(listOf(percent), saved)
        val after = compose.onNodeWithTag("text").fetchSemanticsNode().size.height
        assertTrue("글자 높이도 커진다 $before → $after", after > before)
    }

    @Test
    fun clampsToRange() {
        setUp()
        pinchOut(100f, 400f)
        assertEquals(CalendarZoom.MAX.toFloat(), percent)
        pinchOut(400f, 50f)
        assertEquals(CalendarZoom.MIN.toFloat(), percent)
        assertEquals(listOf(150f, 70f), saved)
    }

    @Test
    fun singleFingerSwipeDoesNotZoom() {
        setUp()
        compose.onNodeWithTag("zoom").performTouchInput { swipeLeft() }
        assertEquals(100f, percent)
        assertEquals(emptyList<Float>(), saved)
        compose.onNodeWithText("글자").assertExists()
    }
}
