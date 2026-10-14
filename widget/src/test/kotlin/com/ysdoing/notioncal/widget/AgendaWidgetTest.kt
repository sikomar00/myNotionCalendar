package com.ysdoing.notioncal.widget

import android.content.Context
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.hasClickAction
import androidx.glance.testing.unit.hasContentDescriptionEqualTo
import androidx.glance.testing.unit.hasTestTag
import androidx.glance.testing.unit.hasTextEqualTo
import androidx.test.core.app.ApplicationProvider
import com.ysdoing.notioncal.core.data.WidgetConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.seconds

/** WG-06 보조 위젯: 오늘부터 7일, 날짜별 묶음, 오늘 빈 날 안내, 완료 표시, 2×2~4×2에서 줄이 잘리지 않음. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w420dp-h900dp-xxhdpi")
class AgendaWidgetTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val today = WidgetSamples.today // 2026-10-13(화)

    private val events = WidgetSamples.events + listOf(
        WidgetSamples.event("late", "2026-10-25", title = "7일 밖"),
        WidgetSamples.event("done", "2026-10-16", done = true, title = "끝낸 일"),
    )

    private fun state(config: WidgetConfig = WidgetConfig(1)) = AgendaWidget.uiState(today, config, events)

    @Test
    fun groupsSevenDaysFromToday() {
        val days = state().days.map { it.date.dayOfMonth }
        // 13(영어·회의), 15(문서), 16(끝낸 일), 19일 이후는 20일 출장 시작이 7일(13~19) 밖이라 없다.
        assertEquals(listOf(13, 15, 16), days)
        assertTrue(state(WidgetConfig(1, hideDone = true)).days.none { day -> day.items.any { it.first.done } })
    }

    @Test
    fun todayWithoutEventsShowsEmpty() {
        val empty = AgendaWidget.uiState(today, WidgetConfig(1), emptyList())
        assertEquals(listOf(today), empty.days.map { it.date })
        assertTrue(empty.days.single().items.isEmpty())
    }

    @Test
    fun rendersRowsWithActions() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(DpSize(358.dp, 152.dp))
        provideComposable { AgendaWidgetContent(state()) }
        onNode(hasTextEqualTo("오늘")).assertExists()
        onAllNodes(hasTestTag("agendaItem")).assertCountEquals(4)
        onAllNodes(hasTestTag("agendaItem")).assertAll(hasClickAction())
        onAllNodes(hasTestTag("agendaDate")).assertAll(hasClickAction())
        onNode(hasTestTag("add")).assertExists()
        // A11Y-03: 완료는 체크 + 읽을 때 '완료됨'.
        onNode(hasContentDescriptionEqualTo("끝낸 일, 종일, 완료됨")).assertExists()
    }

    @Test
    fun emptyTodayRow() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(DpSize(110.dp, 110.dp))
        provideComposable { AgendaWidgetContent(AgendaWidget.uiState(today, WidgetConfig(1), emptyList())) }
        onNode(hasTestTag("agendaEmpty")).assertExists()
    }

    /** 줄 높이가 글자 칸(글자 × 1.65 × 기기 글꼴, 상한 1.3배)보다 낮지 않다. 글꼴 최대 × 위젯 배율 150%까지. */
    @Test
    fun rowHeightsFitText() {
        for (scale in listOf(70, 100, 150)) for (font in listOf(0.8f, 1f, 1.3f, 2f)) {
            val m = AgendaMetrics.of(WidgetConfig(1, fontScale = scale), font)
            // 그려지는 글자 = 넘긴 sp × 기기 글꼴. 그 글자 칸이 줄 높이 안에 들어와야 한다.
            fun box(sp: Float) = sp * font * WidgetMetrics.TEXT_BOX
            val label = "배율 $scale 글꼴 $font"
            assertTrue(label, m.itemHeight.value >= box(m.itemFontSp) - 0.01f)
            assertTrue(label, m.dateHeight.value >= box(m.dateFontSp) - 0.01f)
            assertTrue(label, m.headerHeight.value >= box(m.headerFontSp) - 0.01f)
        }
    }

    // 목록(LazyColumn)은 실제 위젯에서 컬렉션 어댑터로 채워져 정적 RemoteViews 스크린샷에는 나오지 않는다. 모습은 실기기에서 확인한다.
    /** EX-02: 공휴일만 있는 날도 목록에 나오고, 날짜 줄에 이름이 붙는다. */
    @Test
    fun holidayRows() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(DpSize(358.dp, 152.dp))
        val holidays = mapOf(today.plusDays(1) to "임시공휴일")
        provideComposable { AgendaWidgetContent(AgendaWidget.uiState(today, WidgetConfig(1), emptyList(), holidays = holidays)) }
        onNode(hasTextEqualTo("10/14(수) 임시공휴일")).assertExists()
        onAllNodes(hasTestTag("agendaEmpty")).assertCountEquals(1) // 오늘만 '일정 없음'
    }
}
