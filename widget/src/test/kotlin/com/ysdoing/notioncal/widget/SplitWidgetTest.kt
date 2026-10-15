package com.ysdoing.notioncal.widget

import android.content.Context
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.hasClickAction
import androidx.glance.testing.unit.hasTestTag
import androidx.glance.testing.unit.hasTextEqualTo
import androidx.test.core.app.ApplicationProvider
import com.ysdoing.notioncal.core.data.AppSettings
import com.ysdoing.notioncal.core.data.WidgetConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.time.Duration.Companion.seconds

/** WG-09 '달력 + 일정': 주차 번호, 오늘 강조, 높으면 두 달, 오늘부터 일정 목록, 날짜·일정·'+' 탭. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w420dp-h900dp-xxhdpi")
class SplitWidgetTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val today = WidgetSamples.today // 2026-10-13(화)
    private val sunday = AppSettings(weekStart = DayOfWeek.SUNDAY)

    private fun state(config: WidgetConfig = WidgetConfig(1)) = SplitWidget.uiState(today, sunday, config, WidgetSamples.events)

    @Test
    fun weekNumbersFollowWeekStart() {
        // 2026년 10월 달력(일요일 시작)의 여섯 주: 참고한 노션 캘린더 위젯과 같은 40~45주.
        val rows = (0 until 6).map { LocalDate.of(2026, 9, 27).plusWeeks(it.toLong()) }
        assertEquals(listOf(40, 41, 42, 43, 44, 45), rows.map { SplitWidget.weekNumber(it, DayOfWeek.SUNDAY) })
        assertEquals(1, SplitWidget.weekNumber(LocalDate.of(2025, 12, 28), DayOfWeek.SUNDAY)) // 1월 1일이 든 주
    }

    @Test
    fun metricsPickOneOrTwoMonths() {
        val small = SplitMetrics.of(DpSize(358.dp, 168.dp), WidgetConfig(1), 1f)
        val tall = SplitMetrics.of(DpSize(358.dp, 380.dp), WidgetConfig(1), 1f)
        assertEquals(1, small.months)
        assertEquals(2, tall.months)
        // 날짜 글자 칸이 줄 높이를 넘지 않는다(잘리지 않음).
        for (m in listOf(small, tall)) assertTrue(m.dateFontSp * WidgetMetrics.TEXT_BOX <= m.rowHeight.value)
    }

    @Test
    fun rendersCalendarAndTodayList() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(DpSize(358.dp, 168.dp))
        provideComposable { SplitWidgetContent(state(), systemFontScale = 1f) }
        onNode(hasTextEqualTo("10월")).assertExists()
        onNode(hasTextEqualTo("42주차")).assertExists()
        onAllNodes(hasTestTag("splitWeekNumber")).assertCountEquals(6)
        onAllNodes(hasTestTag("splitDay")).assertCountEquals(41)
        onNode(hasTestTag("splitToday")).assert(hasClickAction())
        // 오늘(13일)부터 일정이 있는 날만: 13일 영어 스터디·회의, 15일 문서정리.
        onAllNodes(hasTestTag("splitDayHeader")).assertCountEquals(2)
        onNode(hasTextEqualTo("영어 스터디")).assertExists()
        onNode(hasTextEqualTo("문서정리 및 발표")).assertExists()
        onAllNodes(hasTestTag("splitItem")).assertAll(hasClickAction())
        onNode(hasTestTag("add")).assert(hasClickAction())
    }

    @Test
    fun tallWidgetShowsTwoMonths() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(DpSize(358.dp, 380.dp))
        provideComposable { SplitWidgetContent(state(), systemFontScale = 1f) }
        onNode(hasTextEqualTo("10월")).assertExists()
        onNode(hasTextEqualTo("11월")).assertExists()
        onAllNodes(hasTestTag("splitWeekNumber")).assertCountEquals(12)
        onAllNodes(hasTestTag("splitToday")).assertCountEquals(1)
    }

    @Test
    fun widgetFilterAppliesToList() {
        val onlyOther = state(WidgetConfig(1, dataSourceIds = setOf("other")))
        // 'other' DB 일정(10/5 운동)은 7일 범위 밖이라 오늘만 '일정 없음'으로 남는다.
        assertEquals(listOf(today), onlyOther.agenda.days.map { it.date })
        assertTrue(onlyOther.agenda.days.single().items.isEmpty())
    }
}
