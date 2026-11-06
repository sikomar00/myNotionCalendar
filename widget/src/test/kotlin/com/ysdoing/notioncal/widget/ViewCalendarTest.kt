package com.ysdoing.notioncal.widget

import android.content.Context
import android.content.Intent
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.testing.unit.hasRunCallbackClickAction
import androidx.glance.appwidget.testing.unit.hasStartActivityClickAction
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.hasAnyDescendant
import androidx.glance.testing.unit.hasContentDescriptionEqualTo
import androidx.glance.testing.unit.hasTestTag
import androidx.glance.testing.unit.hasTextEqualTo
import androidx.test.core.app.ApplicationProvider
import com.ysdoing.notioncal.core.data.WidgetConfig
import com.ysdoing.notioncal.core.data.view.ViewBoard
import com.ysdoing.notioncal.core.data.view.ViewColumn
import com.ysdoing.notioncal.core.data.view.ViewRow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.time.Duration.Companion.seconds

/**
 * Phase 12a VR-CAL 캘린더 보기를 월 격자로 그린다: 날짜 칸 42개, 일정은 막대·점, 날짜 칸을 누르면 그 날짜의 새 항목 시트, ‹ ›로 달 이동.
 * 2×2는 점만, 4×2는 이번 주 + 오늘 목록. 날짜 열을 모르는 캘린더는 표·목록으로 대체한다. (오늘 = 2027-01-15 금요일, 서울.)
 */
@Config(qualifiers = "w420dp-h900dp-xxhdpi")
@RunWith(RobolectricTestRunner::class)
class ViewCalendarTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val now = 1_800_000_000_000L
    // 삼성 런처가 보고하는 크기(S25 실측 2×2 234×213, 4×2 501×213, 4×4 501×458, 4×5 501×581). 150dp대 2×2에서는 v1 월 위젯처럼 점을 그릴 높이가 없다.
    private val compact = DpSize(234.dp, 213.dp)
    private val wide = DpSize(501.dp, 213.dp)
    private val large = DpSize(501.dp, 458.dp)
    private val full = DpSize(501.dp, 581.dp)

    private val title = ViewColumn("t", "이름", "title")
    private val date = ViewColumn("d", "날짜", "date")

    private fun day(start: String, end: String? = null) = JsonObject(buildMap { put("start", JsonPrimitive(start)); if (end != null) put("end", JsonPrimitive(end)) })

    private fun row(id: String, name: String, start: String, end: String? = null) =
        ViewRow(id, "https://notion.so/$id", listOf(name), typed = mapOf("t" to JsonPrimitive(name), "d" to day(start, end)))

    private val events = listOf(
        row("a", "장보기", "2027-01-15"), row("b", "회의", "2027-01-15T10:00:00+09:00"), row("c", "여행", "2027-01-20", "2027-01-22"),
    )

    private fun calendar(rows: List<ViewRow> = events, columns: List<ViewColumn> = listOf(title, date), complete: Boolean = true) =
        ViewBoard("v1", "일정", "calendar", listOf("이름", "날짜"), rows, now, null, columns, rowsComplete = complete)

    private fun state(board: ViewBoard = calendar(), config: WidgetConfig = WidgetConfig(1, viewId = "v1"), info: CalendarInfo = CalendarInfo()) =
        ViewWidgetState(board, config, now, info)

    @Test
    fun aLargeCalendarDrawsSixWeeksOfDayCellsWithTheTitleAndMonthNavigation() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(full)
        provideComposable { ViewWidgetContent(state(), systemFontScale = 1f) }
        onAllNodes(hasTestTag("day")).assertCountEquals(42)
        onNode(hasTestTag("calendarTitle").and(hasContentDescriptionEqualTo("2027년 1월"))).assertExists()
        onNode(hasTestTag("calendarPrev")).assert(hasRunCallbackClickAction<MonthNavAction>(actionParametersOf(MonthNavAction.DeltaKey to -1)))
        onNode(hasTestTag("calendarNext")).assert(hasRunCallbackClickAction<MonthNavAction>(actionParametersOf(MonthNavAction.DeltaKey to 1)))
        onAllNodes(hasTestTag("chip")).assertCountEquals(3) // 장보기·회의는 15일, 여행은 20~22일 한 막대
        onNode(hasTextEqualTo("장보기")).assertExists()
        onNode(hasTextEqualTo("10:00 회의")).assertExists()
        onNode(hasTextEqualTo("여행")).assertExists()
    }

    @Test
    fun aDayCellOpensTheNewItemSheetForThatDate() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(large)
        provideComposable { ViewWidgetContent(state(), systemFontScale = 1f) }
        onNode(hasTestTag("day").and(hasContentDescriptionEqualTo("1월 15일, 일정 2개")))
            .assert(hasStartActivityClickAction(viewAddIntent(context, "v1", 1, LocalDate.of(2027, 1, 15))))
        onNode(hasTestTag("day").and(hasContentDescriptionEqualTo("1월 21일, 일정 1개")))
            .assert(hasStartActivityClickAction(viewAddIntent(context, "v1", 1, LocalDate.of(2027, 1, 21)))) // 여러 날 일정은 걸친 날마다 센다
    }

    @Test
    fun aViewThatCannotCreateItemsOpensTheViewerFromADayCell() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(large)
        provideComposable { ViewWidgetContent(state(calendar(columns = listOf(date))), systemFontScale = 1f) }
        val viewer = Intent(Intent.ACTION_VIEW, "notioncal://view/v1".toUri()).setPackage(context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        onAllNodes(hasTestTag("day")).assertCountEquals(42)
        onNode(hasTestTag("day").and(hasContentDescriptionEqualTo("1월 15일, 일정 2개"))).assert(hasStartActivityClickAction(viewer)) // 제목 열이 없어 이름은 '제목 없음'이지만 날짜 칸은 그린다
    }

    @Test
    fun anotherMonthShowsItsTitleAndTheTitleReturnsToToday() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(large)
        provideComposable { ViewWidgetContent(state(config = WidgetConfig(1, monthOffset = 1, viewId = "v1")), systemFontScale = 1f) }
        // 어느 달인지 읽히고(스크린 리더), 제목을 누르면 오늘로 돌아간다.
        onNode(hasTestTag("calendarTitle").and(hasContentDescriptionEqualTo("2027년 2월, 오늘로 돌아가기")))
            .assert(hasRunCallbackClickAction<MonthNavAction>(actionParametersOf(MonthNavAction.DeltaKey to 0)))
        onNode(hasTextEqualTo("2027년 2월")).assertExists()
        onAllNodes(hasTestTag("day")).assertCountEquals(42)
    }

    @Test
    fun weekStartAndWeekNumbersFollowTheAppSettingsAndHolidaysAreOnTheCell() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(full)
        val info = CalendarInfo(DayOfWeek.MONDAY, showWeekNumbers = true, holidays = mapOf(LocalDate.of(2027, 1, 1) to "신정"))
        provideComposable { ViewWidgetContent(state(info = info), systemFontScale = 1f) }
        onAllNodes(hasTestTag("weekNumber")).assertCountEquals(6)
        onNode(hasTestTag("day").and(hasContentDescriptionEqualTo("1월 1일, 신정, 일정 0개"))).assertExists()
    }

    @Test
    fun twoByTwoDrawsTheMonthAsDotsWithoutBars() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(compact)
        provideComposable { ViewWidgetContent(state(), systemFontScale = 1f) }
        onAllNodes(hasTestTag("day")).assertCountEquals(42)
        onNode(hasTestTag("calendarTitle").and(hasContentDescriptionEqualTo("1월"))).assertExists()
        onAllNodes(hasTestTag("chip")).assertCountEquals(0)
        onAllNodes(hasTestTag("dot")).assertCountEquals(5) // 15일 2개 + 여행 20~22일 3개
    }

    @Test
    fun fourByTwoDrawsThisWeekAndTodaysItemsThatOpenTheirEditSheet() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(wide)
        provideComposable { ViewWidgetContent(state(), systemFontScale = 1f) }
        onAllNodes(hasTestTag("day")).assertCountEquals(7)
        onAllNodes(hasTestTag("calendarTitle")).assertCountEquals(0) // 이번 주 모양은 달 이동 줄이 없다
        onAllNodes(hasTestTag("calendarToday")).assertCountEquals(2)
        onAllNodes(hasTestTag("calendarToday"))[0].assert(hasStartActivityClickAction(viewRowIntent(context, "v1", "a")))
        onAllNodes(hasTestTag("calendarToday"))[1].assert(hasStartActivityClickAction(viewRowIntent(context, "v1", "b")))
    }

    @Test
    fun fourByTwoWithNothingTodaySaysSo() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(wide)
        provideComposable { ViewWidgetContent(state(calendar(listOf(row("c", "여행", "2027-01-20")))), systemFontScale = 1f) }
        onAllNodes(hasTestTag("calendarToday")).assertCountEquals(0)
        onNode(hasTestTag("calendarTodayEmpty")).assertExists()
    }

    @Test
    fun anEmptyCalendarStillDrawsTheGridSoDaysCanBeTappedToAdd() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(large)
        provideComposable { ViewWidgetContent(state(calendar(emptyList())), systemFontScale = 1f) }
        onAllNodes(hasTestTag("day")).assertCountEquals(42)
        onAllNodes(hasTestTag("viewEmpty")).assertCountEquals(0)
    }

    @Test
    fun theHeaderAddOpensTheSheetWithoutADateSoTheTitleCanStillSetOne() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(large)
        provideComposable { ViewWidgetContent(state(), systemFontScale = 1f) }
        // 날짜를 싣지 않는다: 직접 고른 날짜는 제목의 '내일' 같은 글을 날짜로 읽지 않게 한다. 날짜가 필요하면 날짜 칸을 누른다.
        onNode(hasTestTag("viewAdd")).assert(hasStartActivityClickAction(viewAddIntent(context, "v1", 1)))
    }

    @Test
    fun aTimedEventContinuingFromYesterdayShowsNoStartTimeInTodaysList() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(wide)
        val rows = listOf(row("m", "긴 회의", "2027-01-14T09:00:00+09:00", "2027-01-16T18:00:00+09:00"), row("a", "장보기", "2027-01-15"))
        provideComposable { ViewWidgetContent(state(calendar(rows)), systemFontScale = 1f) }
        onNode(hasTestTag("calendarToday").and(hasAnyDescendant(hasTextEqualTo("긴 회의")))).assertExists() // '9:00 긴 회의'가 아니다
    }

    @Test
    fun aTinyTwoByTwoWithAStatusLineIsTooShortForAGridSoItFallsBackToTheList() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(DpSize(150.dp, 150.dp))
        provideComposable { ViewWidgetContent(state(calendar().copy(lastError = "boom")), systemFontScale = 1f) }
        onAllNodes(hasTestTag("day")).assertCountEquals(0)
        onAllNodes(hasTestTag("viewRow")).assertCountEquals(3)
    }

    @Test
    fun aCalendarWithoutAKnownDateColumnFallsBackToTheTable() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(large)
        provideComposable { ViewWidgetContent(state(calendar(columns = listOf(title))), systemFontScale = 1f) }
        onAllNodes(hasTestTag("day")).assertCountEquals(0)
        onAllNodes(hasTestTag("viewRow")).assertCountEquals(3)
    }

    @Test
    fun aPartiallyReceivedCalendarSaysSoNextToTheTitle() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(large)
        provideComposable { ViewWidgetContent(state(calendar(complete = false)), systemFontScale = 1f) }
        onNode(hasTextEqualTo(context.getString(R.string.view_calendar_partial))).assertExists()
        onNode(hasTestTag("calendarTitle").and(hasContentDescriptionEqualTo("2027년 1월, ${context.getString(R.string.view_calendar_partial_description)}"))).assertExists()
    }

    @Test
    fun theSettingsPreviewDrawsTheSameGrid() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(large)
        provideComposable { ViewWidgetContent(state(), systemFontScale = 1f, staticRows = true) }
        onAllNodes(hasTestTag("day")).assertCountEquals(42)
    }
}
