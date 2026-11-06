package com.ysdoing.notioncal.widget

import android.content.Context
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.ysdoing.notioncal.core.data.AppIntents
import com.ysdoing.notioncal.core.data.CalendarEvent
import com.ysdoing.notioncal.core.data.WidgetConfig
import com.ysdoing.notioncal.core.data.view.CalendarRows
import com.ysdoing.notioncal.core.data.view.ViewBoard
import com.ysdoing.notioncal.core.data.view.ViewColumn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/** Phase 12a VR-CAL 캘린더 본문의 순수 계산: 크기 단계별 모양, 높이 나누기, 달·주 고르기, 날짜를 실은 새 항목 인텐트. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w420dp-h900dp-xxhdpi")
class ViewCalendarModelTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val metrics = viewMetrics(1f, 1f)

    // 1_800_000_000_000ms = 2027-01-15 08:00Z = 서울 17:00(금요일)
    private val now = 1_800_000_000_000L
    private val today = LocalDate.of(2027, 1, 15)

    private fun state(offset: Int = 0, nowMillis: Long = now) = ViewWidgetState(null, WidgetConfig(1, monthOffset = offset, viewId = "v1"), nowMillis)

    @Test
    fun onlyTheFourByTwoStepDrawsTheWeekShapeAndTheOthersDrawAMonth() {
        assertEquals(
            listOf(CalendarMode.MONTH, CalendarMode.WEEK, CalendarMode.MONTH, CalendarMode.MONTH),
            listOf(SizeStep.COMPACT, SizeStep.WIDE, SizeStep.LARGE, SizeStep.FULL).map(::calendarMode),
        )
    }

    @Test
    fun aMonthGivesTheGridWhatTheNavRowLeavesAndTwoByTwoOnlyDrawsDots() {
        val large = calendarLayout(SizeStep.LARGE, 300.dp, 400.dp)
        assertEquals(NAV_HEIGHT, large.navHeight)
        assertEquals(400.dp - NAV_HEIGHT, large.grid.height)
        assertEquals(listOf(6, WidgetMode.LARGE, 300.dp), listOf(large.grid.weeks, large.grid.maxMode, large.grid.width))
        assertEquals(0.dp, large.listHeight)
        val compact = calendarLayout(SizeStep.COMPACT, 130.dp, 140.dp)
        assertEquals(32.dp, compact.navHeight) // 2×2는 눌림 영역을 48dp로 줄 높이가 없다(v1 작음 단계와 같은 예외)
        assertEquals(WidgetMode.SMALL, compact.grid.maxMode)
        assertEquals(140.dp - 32.dp, compact.grid.height)
    }

    @Test
    fun theWeekShapeSplitsTheBodyBetweenOneWeekAndTodaysList() {
        val wide = calendarLayout(SizeStep.WIDE, 300.dp, 160.dp)
        assertEquals(CalendarMode.WEEK, wide.mode)
        assertEquals(0.dp, wide.navHeight) // 이번 주는 달을 넘기지 않으니 이동 줄이 없다
        assertEquals(1, wide.grid.weeks)
        assertEquals(wide.grid.height + wide.listHeight, 160.dp)
    }

    @Test
    fun aBodyTooShortForAnythingNeverGoesNegative() {
        assertEquals(0.dp, calendarLayout(SizeStep.LARGE, 300.dp, 20.dp).grid.height)
        assertEquals(0.dp, calendarLayout(SizeStep.WIDE, 300.dp, (-5).dp).listHeight)
        assertEquals(0.dp, paneBodyHeight(30.dp, subtitleShown = false, m = metrics, linesHeight = 50.dp))
    }

    @Test
    fun theBodyIsWhatIsLeftBelowTheHeaderAndTheLinesAboveIt() {
        assertEquals(300.dp - metrics.header - 36.dp, paneBodyHeight(300.dp, subtitleShown = false, m = metrics, linesHeight = 36.dp))
        assertEquals(300.dp - metrics.headerWithSub, paneBodyHeight(300.dp, subtitleShown = true, m = metrics, linesHeight = 0.dp))
    }

    @Test
    fun presetLinesTakeOneChipRowPerThree() {
        assertEquals(0.dp, presetLinesHeight(0))
        assertEquals(presetLinesHeight(1), presetLinesHeight(3))
        assertEquals(presetLinesHeight(1) * 2, presetLinesHeight(4))
    }

    @Test
    fun todaysListShowsAllWhenTheyFitElseLeavesRoomForTheMoreLine() {
        val row = 40.dp
        assertEquals(2, todayRowLimit(100.dp, row, 2))
        assertEquals(0, todayRowLimit(100.dp, row, 0))
        // 3개는 100dp(2줄)에 안 들어간다 → '더 보기' 줄(32dp)을 빼고 남는 68dp에 1줄
        assertEquals(1, todayRowLimit(100.dp, row, 3))
        assertEquals(1, todayRowLimit(20.dp, row, 5)) // 아무리 낮아도 한 줄은 둔다
        // 일정을 일부만 받았으면 다 보여도 '더 보기' 줄이 붙으니 그 자리를 늘 뺀다.
        assertEquals(1, todayRowLimit(100.dp, row, 2, complete = false))
        assertEquals(2, todayRowLimit(140.dp, row, 2, complete = false))
        assertEquals(0, todayRowLimit(140.dp, row, 0, complete = false))
    }

    @Test
    fun todayIsReadInSeoulAndTheMonthFollowsTheOffsetOnlyInTheMonthShape() {
        // 2026-12-31 16:00Z는 서울로 2027-01-01 01:00
        val newYear = state(nowMillis = 1_798_732_800_000L)
        assertEquals(LocalDate.of(2027, 1, 1), calendarToday(newYear))
        assertEquals(YearMonth.of(2027, 1), calendarMonth(newYear, CalendarMode.MONTH))
        assertEquals(YearMonth.of(2027, 3), calendarMonth(state(offset = 2), CalendarMode.MONTH))
        assertEquals(YearMonth.of(2026, 12), calendarMonth(state(offset = -1), CalendarMode.MONTH))
        assertEquals(YearMonth.of(2027, 1), calendarMonth(state(offset = 2), CalendarMode.WEEK)) // 이번 주 모양은 오늘이 든 달
    }

    @Test
    fun theHolidayRangeCoversBothTheThisMonthAndTheShownMonthGrid() {
        val (from, to) = calendarRange(today, 0, DayOfWeek.SUNDAY)
        assertEquals(LocalDate.of(2026, 12, 27), from) // 2027-01-01(금)이 든 주의 일요일
        assertEquals(from.plusDays(41), to)
        val (f2, t2) = calendarRange(today, 2, DayOfWeek.SUNDAY) // 3월을 보고 있어도 이번 달 주는 계속 보인다
        assertEquals(from, f2)
        assertEquals(LocalDate.of(2027, 2, 28).plusDays(41), t2)
        assertEquals(calendarRange(today, -1, DayOfWeek.SUNDAY).first, LocalDate.of(2026, 11, 29))
    }

    @Test
    fun thisWeeksFirstDayFollowsTheWeekStartSetting() {
        assertEquals(LocalDate.of(2027, 1, 10), weekStartOf(today, DayOfWeek.SUNDAY))
        assertEquals(LocalDate.of(2027, 1, 11), weekStartOf(today, DayOfWeek.MONDAY))
        assertEquals(today, weekStartOf(today, DayOfWeek.FRIDAY)) // 이미 주 첫날이면 그날
    }

    @Test
    fun anEventIsOnEveryDayItSpans() {
        fun event(start: String, end: String?) = CalendarEvent(
            "p", "v", "t", LocalDate.parse(start), end?.let(LocalDate::parse), null, null, true, false, "#9B9A97", "#9B9A97", "",
        )
        val trip = event("2027-01-14", "2027-01-16")
        val single = event("2027-01-15", null)
        assertEquals(listOf(trip, single), eventsOn(listOf(trip, single), today))
        assertEquals(listOf(trip), eventsOn(listOf(trip, single), LocalDate.of(2027, 1, 16)))
        assertEquals(emptyList<CalendarEvent>(), eventsOn(listOf(trip, single), LocalDate.of(2027, 1, 17)))
    }

    @Test
    fun theCalendarStateIsTheSameShapeAsTheV1MonthWidgetsAndCarriesTheViewsEvents() {
        val columns = listOf(ViewColumn("t", "이름", "title"), ViewColumn("d", "날짜", "date"))
        val row = com.ysdoing.notioncal.core.data.view.ViewRow(
            "p1", "", listOf("장보기"),
            typed = mapOf(
                "t" to kotlinx.serialization.json.JsonPrimitive("장보기"),
                "d" to kotlinx.serialization.json.JsonObject(mapOf("start" to kotlinx.serialization.json.JsonPrimitive("2027-01-15"))),
            ),
        )
        val board = ViewBoard("v1", "일정", "calendar", listOf("이름"), listOf(row), now, null, columns)
        val info = CalendarInfo(DayOfWeek.MONDAY, true, mapOf(LocalDate.of(2027, 1, 1) to "신정"))
        val ui = calendarUi(CalendarRows.events(board), ViewWidgetState(board, WidgetConfig(1, monthOffset = 1, viewId = "v1"), now, info), CalendarMode.MONTH)
        assertEquals(YearMonth.of(2027, 2), ui.month)
        assertEquals(today, ui.today)
        assertEquals(listOf(DayOfWeek.MONDAY, true, "신정"), listOf(ui.weekStart, ui.showWeekNumbers, ui.holidays[LocalDate.of(2027, 1, 1)]))
        assertEquals(listOf("장보기"), ui.events.map { it.title })
    }

    @Test
    fun theWeekShapeDoesNotDimDaysOfTheNextMonthButTheMonthGridDoes() {
        val board = ViewBoard("v", "보기", "calendar", emptyList(), emptyList(), now, null, listOf(ViewColumn("t", "이름", "title"), ViewColumn("d", "날짜", "date")))
        assertTrue(calendarUi(CalendarRows.events(board), state(), CalendarMode.MONTH).dimOutsideMonth)
        assertFalse(calendarUi(CalendarRows.events(board), state(), CalendarMode.WEEK).dimOutsideMonth)
    }

    private fun calendarBoard(type: String = "calendar") =
        ViewBoard("v", "보기", type, emptyList(), emptyList(), now, null, listOf(ViewColumn("t", "이름", "title"), ViewColumn("d", "날짜", "date")))

    @Test
    fun theCalendarLayoutExistsOnlyWhereTheGridIsReadable() {
        // 삼성 보고 크기: 2×2는 점 격자, 4×2는 이번 주 + 오늘 목록
        val twoByTwo = calendarLayoutFor(calendarBoard(), DpSize(234.dp, 213.dp), PaneSpec(), metrics, 0.dp)!!
        assertEquals(listOf(CalendarMode.MONTH, true), listOf(twoByTwo.mode, twoByTwo.compact))
        assertEquals(CalendarMode.WEEK, calendarLayoutFor(calendarBoard(), DpSize(501.dp, 213.dp), PaneSpec(), metrics, 0.dp)!!.mode)
        // 150dp대 2×2에 상태 줄이 붙으면 주 한 줄이 날짜 숫자보다 낮아져 못 읽는다 → 표·목록으로 대체
        assertNull(calendarLayoutFor(calendarBoard(), DpSize(150.dp, 150.dp), PaneSpec(), metrics, 24.dp))
        // 낮은 탭·섹션 칸
        assertNull(calendarLayoutFor(calendarBoard(), DpSize(501.dp, 458.dp), PaneSpec(height = 100.dp), metrics, 0.dp))
        assertEquals(CalendarMode.MONTH, calendarLayoutFor(calendarBoard(), DpSize(501.dp, 458.dp), PaneSpec(height = 300.dp), metrics, 0.dp)!!.mode)
        // 캘린더가 아니면 늘 null
        assertNull(calendarLayoutFor(calendarBoard("table"), DpSize(501.dp, 458.dp), PaneSpec(), metrics, 0.dp))
    }

    @Test
    fun onlyACalendarViewWithADateColumnDrawsTheCalendar() {
        val title = ViewColumn("t", "이름", "title")
        val date = ViewColumn("d", "날짜", "date")
        fun board(type: String, columns: List<ViewColumn>) = ViewBoard("v", "보기", type, emptyList(), emptyList(), now, null, columns)
        assertTrue(board("calendar", listOf(title, date)).drawsCalendar())
        assertFalse(board("calendar", listOf(title)).drawsCalendar()) // 날짜 열을 모르면 지금처럼 표·목록
        assertFalse(board("table", listOf(title, date)).drawsCalendar())
    }

    @Test
    fun aMetricsGridAreaReplacesTheV1HeaderAndSixWeekAssumptions() {
        val config = WidgetConfig(1)
        val size = DpSize(320.dp, 420.dp)
        val v1 = WidgetMetrics.of(size, config, 1f)
        val area = WidgetMetrics.of(size, config, 1f, grid = GridArea(width = 280.dp, height = 300.dp))
        assertEquals((300.dp - area.weekdayHeight) / 6, area.weekHeight)
        assertEquals(280.dp / 7, area.cellWidth)
        assertEquals(v1.mode, area.mode) // 상한을 두지 않으면 v1과 같은 단계 규칙
        assertEquals(WidgetMode.SMALL, WidgetMetrics.of(size, config, 1f, grid = GridArea(130.dp, 150.dp, maxMode = WidgetMode.SMALL)).mode)
        val week = WidgetMetrics.of(size, config, 1f, grid = GridArea(280.dp, 80.dp, weeks = 1))
        assertEquals(80.dp - week.weekdayHeight, week.weekHeight) // 한 주면 요일 줄을 뺀 나머지가 모두 그 주
    }

    private fun event(id: String, start: LocalDate, end: LocalDate? = null) =
        CalendarEvent(id, "v", "일정 $id", start, end, null, null, true, false, "#9B9A97", "#9B9A97", "")

    private val gridWeeks = (0 until 6).map { LocalDate.of(2026, 12, 27).plusWeeks(it.toLong()) }

    // 글자 70%의 큰 위젯: 칸당 줄 수가 많아진다(Glance 뷰 개수 한도에 가장 가까운 설정).
    private val tallSmallFont = WidgetMetrics.of(DpSize(501.dp, 581.dp), WidgetConfig(1, fontScale = 70), 1f, grid = GridArea(481.dp, 480.dp))

    @Test
    fun aSparseCalendarKeepsEveryLaneTheSizeAllows() {
        assertTrue(tallSmallFont.lanes > 2)
        assertEquals(tallSmallFont.lanes, fitLanes(gridWeeks, listOf(event("a", today), event("b", today)), tallSmallFont).lanes)
        assertEquals(tallSmallFont.lanes, fitLanes(gridWeeks, emptyList(), tallSmallFont).lanes)
    }

    @Test
    fun aDenseCalendarLosesLanesUntilItsViewsFitUnderGlancesLimit() {
        // 날마다 서로 다른 일정이 여럿: 모든 칸의 모든 줄이 막대로 찬다.
        val dense = (0 until 42).flatMap { d -> (0 until 6).map { n -> event("$d-$n", gridWeeks.first().plusDays(d.toLong())) } }
        val fitted = fitLanes(gridWeeks, dense, tallSmallFont)
        assertTrue("${fitted.lanes} < ${tallSmallFont.lanes}", fitted.lanes in 1 until tallSmallFont.lanes)
        // 어림이 한도에 들어오거나 더 줄일 수 없는 한 줄이고, 한 줄 더는 넘친다(필요한 만큼만 줄였다).
        assertTrue(fitted.lanes == 1 || viewEstimate(gridWeeks, dense, fitted) <= GLANCE_VIEW_LIMIT)
        assertTrue(viewEstimate(gridWeeks, dense, fitted.copy(lanes = fitted.lanes + 1)) > GLANCE_VIEW_LIMIT)
    }

    @Test
    fun dotsAndTheOneLineRowAreNeverReduced() {
        val dense = (0 until 42).flatMap { d -> (0 until 6).map { n -> event("$d-$n", gridWeeks.first().plusDays(d.toLong())) } }
        val dots = WidgetMetrics.of(DpSize(234.dp, 213.dp), WidgetConfig(1), 1f, grid = GridArea(214.dp, 120.dp, maxMode = WidgetMode.SMALL))
        assertEquals(WidgetMode.SMALL, dots.mode)
        assertEquals(dots.lanes, fitLanes(gridWeeks, dense, dots).lanes)
        val oneLine = tallSmallFont.copy(lanes = 1)
        assertEquals(1, fitLanes(gridWeeks, dense, oneLine).lanes)
    }

    @Test
    fun theAddIntentCarriesTheTappedDayAndDiffersPerDay() {
        val plain = viewAddIntent(context, "v1", 7)
        assertNull(plain.getStringExtra(AppIntents.EXTRA_DATE)) // 날짜를 안 주면 지금과 같다
        assertEquals("notioncal://view/add/v1/7", plain.data.toString())
        val d15 = viewAddIntent(context, "v1", 7, LocalDate.of(2027, 1, 15))
        assertEquals("2027-01-15", d15.getStringExtra(AppIntents.EXTRA_DATE))
        assertEquals("v1", d15.getStringExtra(AppIntents.EXTRA_VIEW_ID))
        assertEquals(7, d15.getIntExtra(AppIntents.EXTRA_APPWIDGET_ID, 0))
        // PendingIntent는 extras를 보지 않는다. 날짜마다 data URI가 달라야 칸끼리 덮지 않는다.
        assertFalse(d15.filterEquals(viewAddIntent(context, "v1", 7, LocalDate.of(2027, 1, 16))))
        assertFalse(d15.filterEquals(plain))
        assertTrue(d15.filterEquals(viewAddIntent(context, "v1", 7, LocalDate.of(2027, 1, 15))))
    }
}
