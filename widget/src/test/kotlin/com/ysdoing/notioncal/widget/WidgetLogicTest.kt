package com.ysdoing.notioncal.widget

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.ysdoing.notioncal.core.data.APP_ZONE
import com.ysdoing.notioncal.core.data.AppSettings
import com.ysdoing.notioncal.core.data.LineSpacing
import com.ysdoing.notioncal.core.data.WidgetConfig
import com.ysdoing.notioncal.core.data.calendar.WeekLayout
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime
import java.util.TimeZone

/** 위젯의 순수 계산: 크기 단계, 레인 셀 묶음(자식 ≤7), 갱신 디바운스, 자정 계산, 위젯별 필터. */
class WidgetLogicTest {
    private val originalZone = TimeZone.getDefault()

    @After
    fun restore() = TimeZone.setDefault(originalZone)

    @Test
    fun sizeModes() {
        assertEquals(WidgetMode.SMALL, widgetMode(DpSize(110.dp, 110.dp)))
        assertEquals(WidgetMode.SMALL, widgetMode(DpSize(380.dp, 150.dp)))
        assertEquals(WidgetMode.MEDIUM, widgetMode(DpSize(250.dp, 250.dp)))
        assertEquals(WidgetMode.LARGE, widgetMode(DpSize(380.dp, 400.dp)))
    }

    @Test
    fun laneSegmentsStayWithinSevenCellsAndCoverTheWeek() {
        val weekStart = LocalDate.of(2026, 10, 11)
        for (lanes in 1..WidgetMetrics.MAX_LANES) {
            val layout = WeekLayout.layout(weekStart, WidgetSamples.dense, lanes)
            for (lane in 0 until lanes) {
                val segments = laneSegments(layout, lane)
                assertTrue("레인 $lane 셀 ${segments.size}개", segments.size <= 7)
                assertEquals("레인 $lane 폭 합", 7, segments.sumOf { it.span })
                // 셀이 빈틈·겹침 없이 이어진다.
                segments.zipWithNext().forEach { (a, b) -> assertEquals(a.startCol + a.span, b.startCol) }
                // 연속 빈칸은 하나로 합쳐져 있다.
                segments.zipWithNext().forEach { (a, b) ->
                    assertTrue(!(a.placed == null && a.hiddenCount == 0 && b.placed == null && b.hiddenCount == 0))
                }
            }
        }
    }

    @Test
    fun metricsRespectModeAndSettings() {
        val large = DpSize(380.dp, 420.dp)
        val auto = WidgetMetrics.of(large, WidgetConfig(1), systemFontScale = 1f)
        assertTrue(auto.lanes in 1..WidgetMetrics.MAX_LANES)
        assertEquals(2, WidgetMetrics.of(large, WidgetConfig(1, maxPerCell = 2), 1f).lanes)
        assertTrue(WidgetMetrics.of(DpSize(280.dp, 299.dp), WidgetConfig(1), 1f).lanes <= 2) // 중간: 제목 1줄 + N
        // 글자를 키우거나 줄 간격을 넓히면 줄 수가 줄어든다(잘림 대신 +N).
        val big = WidgetMetrics.of(large, WidgetConfig(1, fontScale = 150, lineSpacing = LineSpacing.WIDE), 1.3f)
        assertTrue(big.lanes <= auto.lanes)
        // 칸 폭 7개 = 내용 폭
        assertEquals(380f - 12f, auto.cellWidth.value * 7, 0.01f)
        // 4×2(S25 실측 358×152dp): 낮지만 날짜와 점이 함께 들어간다.
        val wideShort = WidgetMetrics.of(DpSize(358.dp, 152.dp), WidgetConfig(1), 1f)
        assertEquals(WidgetMode.SMALL, wideShort.mode)
        assertTrue(wideShort.showDots)
        assertTrue(wideShort.dateLabelHeight.value + 6f <= wideShort.weekHeight.value + 0.01f)
        // 최소 크기(2×2 = 110dp)에서도 날짜 줄 높이가 주 한 줄 높이를 넘지 않는다(겹침 없음).
        for (font in listOf(1f, 1.3f)) {
            val tiny = WidgetMetrics.of(DpSize(110.dp, 110.dp), WidgetConfig(1, fontScale = 150), font)
            assertTrue("글꼴 $font", tiny.dateLabelHeight.value <= tiny.weekHeight.value + 0.01f)
        }
    }

    @Test
    fun systemFontScaleIsCappedSoRowsDoNotClip() {
        // S25-06: 기기 글꼴 최대(2.0)에서도 위젯 글자는 1.3배까지만 커지고, 날짜·요일·머리줄이 줄 높이 안에 들어간다.
        val large = DpSize(380.dp, 420.dp)
        val atCap = WidgetMetrics.of(large, WidgetConfig(1), 1.3f)
        val huge = WidgetMetrics.of(large, WidgetConfig(1), 2.0f)
        assertEquals(atCap.chipFontSp * 1.3f, huge.chipFontSp * 2.0f, 0.01f) // 실제로 그려지는 크기가 같다
        assertEquals(atCap.dateFontSp * 1.3f, huge.dateFontSp * 2.0f, 0.01f)
        assertEquals(atCap.weekdayHeight, huge.weekdayHeight)
        assertEquals(atCap.lanes, huge.lanes)
        for (font in listOf(0.8f, 1f, 1.3f, 2f)) {
            val m = WidgetMetrics.of(large, WidgetConfig(1), font)
            val rendered = minOf(font, WidgetMetrics.MAX_SYSTEM_TEXT_SCALE)
            // 글자 칸 높이(S25 실측 글자 크기의 1.65배)보다 날짜·요일 줄이 낮으면 잘린다. 일정 줄은 글자 획(1.4배)만 들어가면 된다.
            assertTrue("요일 $font", m.weekdayHeight.value >= m.weekdayFontSp * font * 1.65f)
            assertTrue("머리줄 $font", m.headerHeight.value >= m.headerFontSp * font * 1.4f)
            assertTrue("날짜 $font", m.dateLabelHeight.value >= m.dateFontSp * font * 1.65f)
            assertTrue("일정 $font", m.laneHeight.value >= m.chipFontSp * font * 1.4f)
            assertTrue("배율 $font", m.chipFontSp * font <= 10f * rendered + 0.01f)
        }
    }

    @Test
    fun perWidgetFilterAndHideDone() {
        val settings = AppSettings()
        val all = NotionCalWidget.uiState(WidgetSamples.october, WidgetSamples.today, settings, WidgetConfig(1), WidgetSamples.events)
        assertEquals(WidgetSamples.events.size, all.events.size)
        val onlyDs = NotionCalWidget.uiState(
            WidgetSamples.october, WidgetSamples.today, settings,
            WidgetConfig(1, dataSourceIds = setOf("other")), WidgetSamples.events,
        )
        assertEquals(listOf("7"), onlyDs.events.map { it.pageId })
        val hide = NotionCalWidget.uiState(
            WidgetSamples.october, WidgetSamples.today, settings, WidgetConfig(1, hideDone = true), WidgetSamples.events,
        )
        assertTrue(hide.events.none { it.done })
    }

    @Test
    fun nextMidnightIsSeoulMidnightRegardlessOfDeviceZone() {
        for (zone in listOf("UTC", "America/Los_Angeles", "Asia/Seoul")) {
            TimeZone.setDefault(TimeZone.getTimeZone(zone))
            val at = DayChangeScheduler.nextMidnight(Instant.parse("2026-10-13T14:59:00Z")) // 서울 23:59
            assertEquals(ZonedDateTime.of(2026, 10, 14, 0, 0, 1, 0, APP_ZONE).toInstant(), at)
        }
        // 서울 자정 직후면 다음 날 자정
        assertEquals(
            ZonedDateTime.of(2026, 10, 15, 0, 0, 1, 0, APP_ZONE).toInstant(),
            DayChangeScheduler.nextMidnight(Instant.parse("2026-10-13T15:00:30Z")),
        )
        // 연말
        assertEquals(
            ZonedDateTime.of(2027, 1, 1, 0, 0, 1, 0, APP_ZONE).toInstant(),
            DayChangeScheduler.nextMidnight(Instant.parse("2026-12-31T10:00:00Z")),
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun debouncerCollapsesBurstIntoOneUpdate() = runTest {
        var runs = 0
        val debouncer = Debouncer(backgroundScope, 300) { runs++ }
        repeat(10) {
            debouncer.request()
            advanceTimeBy(100)
        }
        assertEquals(0, runs)
        advanceTimeBy(301)
        runCurrent()
        assertEquals(1, runs)
        debouncer.request()
        advanceTimeBy(301)
        runCurrent()
        assertEquals(2, runs)
    }
    /** WS-05: 위젯 배율 × 기기 글꼴을 곱해 적용하고, 넘치면 한 단계 작은 레이아웃으로 바꾼다(넘침 0). */
    @Test
    fun textScaleNeverOverflowsAndStepsDown() {
        val sizes = listOf(
            DpSize(110.dp, 110.dp), DpSize(180.dp, 190.dp), DpSize(358.dp, 152.dp), DpSize(250.dp, 220.dp),
            DpSize(358.dp, 250.dp), DpSize(358.dp, 350.dp), DpSize(380.dp, 420.dp), DpSize(411.dp, 800.dp),
        )
        for (size in sizes) for (scale in listOf(70, 100, 150)) for (font in listOf(0.8f, 1f, 1.3f, 2f)) {
            val m = WidgetMetrics.of(size, WidgetConfig(1, fontScale = scale), font)
            val label = "$size 배율 $scale 글꼴 $font → ${m.mode}"
            assertTrue(label, !m.overflows)
            assertTrue(label, m.dateLabelHeight.value <= m.weekHeight.value + 0.01f)
            // 고른 단계는 크기로 정한 단계보다 크지 않다.
            assertTrue(label, m.mode.ordinal <= widgetMode(size).ordinal)
        }
        // 기본 설정에서는 크기대로, 글자를 최대로 키우면 한 단계 내려간다.
        val mediumSize = DpSize(280.dp, 299.dp)
        assertEquals(WidgetMode.MEDIUM, WidgetMetrics.of(mediumSize, WidgetConfig(1), 1f).mode)
        // 중간 크기라도 낮으면(250×250) 일정 한 줄이 칸을 넘쳐 작음으로 그린다.
        assertEquals(WidgetMode.SMALL, WidgetMetrics.of(DpSize(250.dp, 250.dp), WidgetConfig(1), 1f).mode)
        assertEquals(WidgetMode.SMALL, WidgetMetrics.of(mediumSize, WidgetConfig(1, fontScale = 150), 2f).mode)
    }

    /** A11Y-02: 큼은 머리줄(머리 버튼 높이)이 48dp 이상, 중간·큼 버튼 폭은 48dp(WidgetContent.HEADER_BUTTON_WIDTH). */
    @Test
    fun largeHeaderIsTouchSized() {
        for (font in listOf(0.8f, 1f, 2f)) {
            val m = WidgetMetrics.of(DpSize(380.dp, 420.dp), WidgetConfig(1), font)
            assertEquals(WidgetMode.LARGE, m.mode)
            assertTrue("글꼴 $font", m.headerHeight.value >= 48f)
        }
    }
}
