package com.ysdoing.notioncal.widget

import android.app.Activity
import android.content.Context
import android.view.View
import android.widget.FrameLayout
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.hasTestTag
import androidx.glance.testing.unit.hasTextEqualTo
import androidx.test.core.app.ApplicationProvider
import com.ysdoing.notioncal.core.data.WidgetConfig
import com.ysdoing.notioncal.core.data.WidgetTheme
import com.ysdoing.notioncal.core.data.calendar.weekNumber
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * M6 월간 위젯 주차 열(앱 설정 '주차 표시', 기본 끔). 켜도 일정 줄 수·머리 높이는 그대로이고(폭만 줄어든다),
 * 가장 빽빽한 큰 위젯도 Glance View 개수 한도 안이다.
 */
@OptIn(ExperimentalGlanceRemoteViewsApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w420dp-h900dp-xxhdpi")
class MonthWeekNumbersTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val large = DpSize(380.dp, 420.dp)

    @Test
    fun offByDefaultAndInSmallMode() {
        assertEquals(0.dp, WidgetMetrics.of(large, WidgetConfig(1), 1f).weekNumberWidth)
        assertEquals(0.dp, WidgetMetrics.of(large, WidgetConfig(1), 1f, showWeekNumbers = false).weekNumberWidth)
        // 작음(2×2)은 폭이 없어 켜도 그리지 않는다.
        assertEquals(0.dp, WidgetMetrics.of(DpSize(110.dp, 110.dp), WidgetConfig(1), 1f, showWeekNumbers = true).weekNumberWidth)
    }

    @Test
    fun theColumnTakesItsWidthFromTheDateCellsAndNothingElse() {
        for (size in listOf(DpSize(280.dp, 299.dp), large, DpSize(411.dp, 800.dp))) for (font in listOf(1f, 1.3f)) {
            val off = WidgetMetrics.of(size, WidgetConfig(1), font)
            val on = WidgetMetrics.of(size, WidgetConfig(1), font, showWeekNumbers = true)
            if (off.mode == WidgetMode.SMALL) continue
            assertTrue("$size 주차 열", on.weekNumberWidth > 0.dp)
            assertEquals("$size 폭 합", (off.cellWidth * 7).value, (on.cellWidth * 7 + on.weekNumberWidth).value, 0.01f)
            // 주차 열은 폭만 쓴다: 일정 줄 수·머리·주 높이·글자 크기는 그대로(사용자 요구: 면적당 일정 줄 수를 줄이지 않는다).
            assertEquals(off.lanes, on.lanes)
            assertEquals(off.headerHeight, on.headerHeight)
            assertEquals(off.weekHeight, on.weekHeight)
            assertEquals(off.mode, on.mode)
            assertEquals(off.chipFontSp, on.chipFontSp, 0f)
        }
    }

    private fun state(week: Boolean, config: WidgetConfig = WidgetConfig(1)) = WidgetSamples.state(config = config).copy(showWeekNumbers = week)

    @Test
    fun sixWeekNumbersFollowTheWeekStartWhenOn() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(large)
        provideComposable { WidgetContent(state(true), systemFontScale = 1f) }
        onAllNodes(hasTestTag("weekNumber")).assertCountEquals(6)
        // 2026년 10월 일요일 시작은 40~45주(앱 월 화면·분할 위젯과 같은 계산)
        val start = LocalDate.of(2026, 9, 27)
        (0 until 6).forEach { i -> onNode(hasTextEqualTo(weekNumber(start.plusWeeks(i.toLong()), DayOfWeek.SUNDAY).toString())).assertExists() }
        assertEquals(listOf(40, 41, 42, 43, 44, 45), (0 until 6).map { weekNumber(start.plusWeeks(it.toLong()), DayOfWeek.SUNDAY) })
    }

    @Test
    fun noWeekNumbersWhenOffAndDayTapsStillCoverEveryDay() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(large)
        provideComposable { WidgetContent(state(false), systemFontScale = 1f) }
        onAllNodes(hasTestTag("weekNumber")).assertCountEquals(0)
        onAllNodes(hasTestTag("day")).assertCountEquals(42)
    }

    @Test
    fun dayTapsStillCoverEveryDayWhenOn() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(large)
        provideComposable { WidgetContent(state(true), systemFontScale = 1f) }
        onAllNodes(hasTestTag("day")).assertCountEquals(42)
    }

    private suspend fun render(size: DpSize, content: @androidx.compose.runtime.Composable () -> Unit) {
        val remoteViews = GlanceRemoteViews().compose(context, size) { content() }.remoteViews
        val activity = Robolectric.buildActivity(Activity::class.java).create().get()
        val frame = FrameLayout(activity)
        frame.addView(remoteViews.apply(activity, frame))
        frame.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.AT_MOST), View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.AT_MOST))
    }

    @Test
    fun theDensestBigWidgetsStillTranslateWithTheColumnOn() = runTest(timeout = 3.minutes) {
        // 주차 숫자는 주당 View 1개만 늘린다(일정 줄은 들여쓰기만). 빽빽한 6줄·가장 큰 위젯이 View 개수 한도 안인지 변환으로 확인한다.
        val configs = listOf(WidgetConfig(1), WidgetConfig(1, fontScale = 70, maxPerCell = 6), WidgetConfig(1, theme = WidgetTheme.DARK, fontScale = 150))
        for (size in listOf(DpSize(280.dp, 299.dp), large, DpSize(411.dp, 800.dp), DpSize(358.dp, 152.dp))) for (config in configs) {
            render(size) { WidgetContent(WidgetSamples.state(WidgetSamples.dense, config).copy(showWeekNumbers = true)) }
        }
    }
}
