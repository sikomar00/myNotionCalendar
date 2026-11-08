package com.ysdoing.notioncal.widget

import android.content.Context
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.testing.unit.hasRunCallbackClickAction
import androidx.glance.appwidget.testing.unit.hasStartActivityClickAction
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.hasContentDescriptionEqualTo
import androidx.glance.testing.unit.hasTestTag
import androidx.glance.testing.unit.hasTextEqualTo
import androidx.test.core.app.ApplicationProvider
import com.ysdoing.notioncal.core.data.AppIntents
import com.ysdoing.notioncal.core.data.LineSpacing
import com.ysdoing.notioncal.core.data.WidgetConfig
import com.ysdoing.notioncal.core.data.WidgetTheme
import com.ysdoing.notioncal.core.data.calendar.WeekLayout
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** WG-13 3일 위젯: 오늘부터 세 칸, 월 위젯의 주 한 줄을 세 칸으로 그린다(막대·칩·+N·칸 누르면 그날 퀵 시트). */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w420dp-h900dp-xxhdpi")
class ThreeDayWidgetTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val today = WidgetSamples.today // 2026-10-13(화)
    private val wide = DpSize(358.dp, 152.dp)
    private val large = DpSize(358.dp, 380.dp)

    @Test
    fun stateKeepsThreeDaysFiltersByWidgetAndDoesNotDimTheNextMonth() {
        val state = ThreeDayWidget.uiState(today, WidgetConfig(1, dataSourceIds = setOf("ds"), hideDone = true), WidgetSamples.events)
        assertEquals(LocalDate.of(2026, 10, 15), ThreeDayWidget.lastDay(today))
        assertTrue(state.events.none { it.dataSourceId == "other" || it.done })
        assertEquals(today.dayOfWeek, state.weekStart) // 요일 줄이 오늘부터 시작한다
        assertFalse(state.dimOutsideMonth) // 10/31·11/1·11/2처럼 달을 넘는 사흘도 흐리게 그리지 않는다
    }

    @Test
    fun aWideShortWidgetStillDrawsTitlesInThreeColumns() {
        val m = ThreeDayWidget.gridMetrics(wide, WidgetConfig(1), 1f, headerHeight = 32.dp)
        assertEquals(3, m.days)
        assertNotEquals(WidgetMode.SMALL, m.mode) // 월 위젯 4×2는 점만 그리지만 3일은 주 한 줄이 높아 제목을 그린다
        assertTrue("$m", m.lanes >= 2)
        assertEquals(((wide.width - WidgetMetrics.PADDING * 2) / 3).value, m.cellWidth.value, 0.01f)
        // 글자 150%·넓은 줄 간격·큰 기기 글꼴에서도 날짜와 한 줄이 칸 안에 든다(넘치면 한 단계 내린다).
        val crowded = ThreeDayWidget.gridMetrics(wide, WidgetConfig(1, fontScale = 150, lineSpacing = LineSpacing.WIDE), 2f, headerHeight = 40.dp)
        assertFalse("$crowded", crowded.overflows)
    }

    @Test
    fun laneSegmentsCoverExactlyThreeColumns() {
        val layout = WeekLayout.layout(today, WidgetSamples.dense, maxLanes = 3, days = 3)
        repeat(3) { lane -> assertEquals(3, laneSegments(layout, lane).sumOf { it.span }) }
    }

    @Test
    fun daysOpenTheirSheetAndHeaderHasRefreshAndAdd() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(wide)
        provideComposable { ThreeDayWidgetContent(ThreeDayWidget.uiState(today, WidgetConfig(1), WidgetSamples.events), systemFontScale = 1f) }
        onNode(hasTextEqualTo("10/13 – 10/15")).assertExists()
        onAllNodes(hasTestTag("day")).assertCountEquals(3)
        onNode(hasContentDescriptionEqualTo("10월 13일, 일정 2개"))
            .assert(hasStartActivityClickAction(quickSheetIntent(context, AppIntents.MODE_DAY, today)))
        onNode(hasTextEqualTo("영어 스터디")).assertExists()
        onNode(hasTestTag("refresh")).assert(hasRunCallbackClickAction<RefreshAction>(actionParametersOf()))
        onNode(hasTestTag("add")).assert(hasStartActivityClickAction(quickSheetIntent(context, AppIntents.MODE_ADD, today)))
    }

    @Test
    fun newWidgetIsKnownToSettingsRedrawAndThePicker() {
        assertEquals(ConfigKind.THREE_DAY, configKindFor(ThreeDayWidgetReceiver::class.java.name))
        assertTrue(ConfigKind.THREE_DAY in CALENDAR_KINDS) // DB 고르기·완료 숨김
        assertTrue(WidgetKinds.forProvider(ThreeDayWidgetReceiver::class.java.name) is ThreeDayWidget)
    }

    /** PRD §13: 자식 10개·view id 500개 한도. 빽빽한 일정·큰 글자·여러 크기에서 실제 RemoteViews로 바꿔 본다. */
    @Test
    fun translatesToRemoteViewsWithinTheViewBudget() = runTest(timeout = 3.minutes) {
        val configs = listOf(WidgetConfig(1), WidgetConfig(1, fontScale = 150, theme = WidgetTheme.DARK, opacity = 60, lineSpacing = LineSpacing.COMPACT))
        for (config in configs) {
            for (size in listOf(DpSize(250.dp, 110.dp), wide, large, DpSize(411.dp, 600.dp))) {
                val state = ThreeDayWidget.uiState(today, config, WidgetSamples.dense)
                val view = renderRemoteViews(context, size) { ThreeDayWidgetContent(state, systemFontScale = 1.3f) }
                assertTrue("$size $config", glanceIdsUsed(view) <= GLANCE_VIEW_ID_BUDGET)
            }
        }
    }
}
