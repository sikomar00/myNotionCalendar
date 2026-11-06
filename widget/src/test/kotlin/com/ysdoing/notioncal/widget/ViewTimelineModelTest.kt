package com.ysdoing.notioncal.widget

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.ysdoing.notioncal.core.data.WidgetConfig
import com.ysdoing.notioncal.core.data.view.TimelineZoom
import com.ysdoing.notioncal.core.data.view.ViewBoard
import com.ysdoing.notioncal.core.data.view.ViewColumn
import com.ysdoing.notioncal.core.data.view.ViewRow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * Phase 12e VR-TML 타임라인 배치(순수): 2×2·날짜 열 없음·낮은 칸은 표·목록으로 대체, 확대는 노션 설정 → 없으면 크기로, 창은 위젯의 월 이동만큼 옮기고,
 * 막대 줄은 높이에 들어가는 만큼(최대 7줄)이며 넘치면 '더 보기'. (오늘 = 2027-01-15 금요일, 서울.)
 */
class ViewTimelineModelTest {
    private val now = 1_800_000_000_000L
    private val compact = DpSize(234.dp, 213.dp)
    private val wide = DpSize(501.dp, 213.dp)
    private val large = DpSize(501.dp, 458.dp)
    private val full = DpSize(501.dp, 581.dp)
    private val metrics = viewMetrics(1f, 1f)

    private val title = ViewColumn("t", "이름", "title")
    private val date = ViewColumn("d", "기간", "date")

    private fun row(id: String, start: String, end: String? = null) = ViewRow(
        id, "", listOf(id),
        typed = mapOf("t" to JsonPrimitive("일 $id"), "d" to JsonObject(buildMap { put("start", JsonPrimitive(start)); end?.let { put("end", JsonPrimitive(it)) } })),
    )

    private fun board(rows: List<ViewRow>, type: String = "timeline", columns: List<ViewColumn> = listOf(title, date), config: String = "", complete: Boolean = true) =
        ViewBoard("v1", "계획", type, listOf("이름", "기간"), rows, now, null, columns, viewConfig = config, rowsComplete = complete)

    private fun state(board: ViewBoard, offset: Int = 0, weekStart: DayOfWeek = DayOfWeek.SUNDAY) =
        ViewWidgetState(board, WidgetConfig(1, timelineOffset = offset, viewId = "v1"), now, CalendarInfo(weekStart))

    private fun layout(board: ViewBoard, size: DpSize, state: ViewWidgetState = state(board), height: androidx.compose.ui.unit.Dp? = null) =
        timelineLayoutFor(board, state, size, PaneSpec(height = height), metrics, 0.dp)

    private val january = (1..12).map { row("r$it", "2027-01-1${it % 7}") } // 1월 10일(일)~16일(토)에 12줄

    @Test
    fun onlyATimelineViewWithADateColumnDrawsAsATimeline() {
        assertTrue(board(january).drawsTimeline())
        assertFalse(board(january, type = "calendar").drawsTimeline())
        assertFalse(board(january, columns = listOf(title)).drawsTimeline()) // 날짜 열을 모르면 지금처럼 표·목록
        assertTrue(board(january, type = "calendar").needsCalendarInfo()) // 캘린더와 타임라인만 주 시작 요일을 읽는다
        assertTrue(board(january).needsCalendarInfo())
        assertFalse(board(january, type = "table").needsCalendarInfo())
    }

    @Test
    fun theSmallestSizeAndNonTimelineBoardsFallBackToTheList() {
        assertNull(layout(board(january), compact))
        assertNull(layout(board(january, type = "table"), large))
        assertNull(layout(board(january, columns = listOf(title)), large))
    }

    @Test
    fun aPaneTooShortForTheNavigationAxisAndOneBarFallsBack() {
        assertNull(layout(board(january), large, height = 80.dp))
        assertNull(layout(board(january), large, height = 160.dp)) // 넘치는 보기는 '더 보기' 줄 몫까지 필요하다
        assertNotNull(layout(board(january.take(1)), large, height = 160.dp)) // 한 줄이면 더 보기 없이 들어간다
        assertNotNull(layout(board(january), large, height = 200.dp))
    }

    @Test
    fun zoomFollowsTheNotionSettingElseTheSize() {
        assertEquals(TimelineZoom.WEEK, layout(board(january), large)?.zoom)
        assertEquals(TimelineZoom.MONTH, layout(board(january), full)?.zoom)
        val month = board(january, config = """{"preference":{"zoom_level":"month"}}""")
        assertEquals(TimelineZoom.MONTH, layout(month, large)?.zoom)
        val week = board(january, config = """{"preference":{"zoom_level":"week"}}""")
        assertEquals(TimelineZoom.WEEK, layout(week, full)?.zoom)
    }

    @Test
    fun theWindowStartsOnTheAppWeekStartAndMovesWithTheWidgetOffset() {
        val b = board(january)
        assertEquals(LocalDate.of(2027, 1, 10), layout(b, large, state(b))?.window?.start) // 일요일 시작
        assertEquals(LocalDate.of(2027, 1, 11), layout(b, large, state(b, weekStart = DayOfWeek.MONDAY))?.window?.start)
        assertEquals(LocalDate.of(2027, 1, 24), layout(b, large, state(b, offset = 2))?.window?.start)
        assertEquals(LocalDate.of(2027, 2, 1), layout(b, full, state(b, offset = 1))?.window?.start) // 월은 달 단위
    }

    @Test
    fun barRowsFitTheHeightWithAMoreLineWhenSomeAreLeftOut() {
        val l = layout(board(january), wide)!!
        assertTrue(l.bars.size in 1..TIMELINE_MAX_ROWS)
        assertEquals(12 - l.bars.size, l.more?.remaining) // 줄이 모자라면 '더 보기'가 남은 수를 센다
        val roomy = layout(board(january), full)!!
        assertEquals(TIMELINE_MAX_ROWS, roomy.bars.size) // 열 개 자식 한도 안에서 최대 7줄
        assertEquals(12 - TIMELINE_MAX_ROWS, roomy.more?.remaining)
    }

    @Test
    fun fewBarsShowEverythingWithoutAMoreLineUnlessTheRowsWereCappedAtTheNotionLimit() {
        val few = board(january.take(2))
        assertNull(layout(few, large)?.more)
        assertNotNull(layout(board(january.take(2), complete = false), large)?.more) // 노션에 행이 더 있을 수 있다
        assertEquals(2, layout(few, large)?.bars?.size)
    }

    @Test
    fun anEmptyWindowStillDrawsTheNavigationSoOtherPeriodsCanBeBrowsed() {
        val l = layout(board(emptyList()), large)!!
        assertTrue(l.bars.isEmpty())
        assertNull(l.more)
    }

    @Test
    fun aCellIsOneDayWideAcrossTheWholeBodyWidth() {
        assertEquals((501.dp - PADDING_H * 2) / 7, layout(board(january), large)!!.cellWidth)
        val month = layout(board(january), full)!!
        assertEquals(31, month.window.days)
        assertEquals((501.dp - PADDING_H * 2) / 31, month.cellWidth)
    }
}
