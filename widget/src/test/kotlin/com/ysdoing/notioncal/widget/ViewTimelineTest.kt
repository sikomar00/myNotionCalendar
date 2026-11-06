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
import kotlin.time.Duration.Companion.seconds

/**
 * Phase 12e VR-TML 타임라인 보기: 이동 줄(‹ ›)·날짜 눈금·일정마다 한 줄의 막대, 막대를 누르면 그 행의 편집 시트.
 * 2×2나 날짜 열을 모르는 타임라인은 표·목록으로 대체한다. (오늘 = 2027-01-15 금요일, 서울. 주는 일요일에 시작해 1/10~1/16이다.)
 */
@Config(qualifiers = "w420dp-h900dp-xxhdpi")
@RunWith(RobolectricTestRunner::class)
class ViewTimelineTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val now = 1_800_000_000_000L
    private val compact = DpSize(234.dp, 213.dp)
    private val large = DpSize(501.dp, 458.dp)
    private val full = DpSize(501.dp, 581.dp)

    private val title = ViewColumn("t", "이름", "title")
    private val date = ViewColumn("d", "기간", "date")

    private fun day(start: String, end: String? = null) = JsonObject(buildMap { put("start", JsonPrimitive(start)); if (end != null) put("end", JsonPrimitive(end)) })

    private fun row(id: String, name: String, start: String, end: String? = null) =
        ViewRow(id, "https://notion.so/$id", listOf(name), typed = mapOf("t" to JsonPrimitive(name), "d" to day(start, end)))

    private val plan = listOf(row("a", "기획", "2027-01-11", "2027-01-13"), row("b", "디자인", "2027-01-13", "2027-01-16"), row("c", "출시", "2027-01-16"), row("d", "지난 일", "2027-01-01", "2027-01-05"))

    private fun timeline(rows: List<ViewRow> = plan, columns: List<ViewColumn> = listOf(title, date), complete: Boolean = true, config: String = "") =
        ViewBoard("v1", "계획", "timeline", listOf("이름", "기간"), rows, now, null, columns, viewConfig = config, rowsComplete = complete)

    private fun state(board: ViewBoard = timeline(), config: WidgetConfig = WidgetConfig(1, viewId = "v1"), weekStart: DayOfWeek = DayOfWeek.SUNDAY) =
        ViewWidgetState(board, config, now, CalendarInfo(weekStart))

    @Test
    fun aWeekDrawsOneBarPerEventInTheWindowWithTheRangeTitleAndWeekdays() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(large)
        provideComposable { ViewWidgetContent(state(), systemFontScale = 1f) }
        onAllNodes(hasTestTag("timelineBar")).assertCountEquals(3) // 창 밖의 '지난 일'은 없다
        onNode(hasTestTag("timelineTitle").and(hasContentDescriptionEqualTo("1/10 – 1/16"))).assertExists()
        onNode(hasTextEqualTo("기획")).assertExists()
        onNode(hasTextEqualTo("디자인")).assertExists()
        onNode(hasTextEqualTo("출시")).assertExists()
        onNode(hasTextEqualTo("금")).assertExists() // 오늘의 요일 글자
        onNode(hasTestTag("timelineAxis")).assertExists()
    }

    @Test
    fun theMonthViewDrawsTheMonthTitleAndWeeklyDateTicks() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(full)
        provideComposable { ViewWidgetContent(state(), systemFontScale = 1f) }
        onNode(hasTestTag("timelineTitle").and(hasContentDescriptionEqualTo("2027년 1월"))).assertExists()
        onNode(hasTextEqualTo("8")).assertExists() // 7일 간격의 눈금: 1·8·15·22·29
        onNode(hasTextEqualTo("29")).assertExists()
        onAllNodes(hasTestTag("timelineBar")).assertCountEquals(4) // 월 창에는 1월 1~5일의 '지난 일'도 든다
    }

    @Test
    fun aBarOpensThatRowsEditSheet() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(large)
        provideComposable { ViewWidgetContent(state(), systemFontScale = 1f) }
        onNode(hasTestTag("timelineBar").and(hasContentDescriptionEqualTo("기획, 1/11 – 1/13"))).assert(hasStartActivityClickAction(viewRowIntent(context, "v1", "a", 0)))
    }

    @Test
    fun barsThatContinueOutsideTheWindowShowAnArrow() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(large)
        val rows = listOf(row("x", "긴 일", "2027-01-05", "2027-01-20"))
        provideComposable { ViewWidgetContent(state(timeline(rows)), systemFontScale = 1f) }
        onNode(hasTestTag("timelineBar").and(hasContentDescriptionEqualTo("긴 일, 1/5 – 1/20"))).assertExists() // 창 앞뒤로 이어진다(색 아닌 글자로도 알린다)
    }

    @Test
    fun theArrowsMoveTheWindowAndAnotherWindowsTitleReturnsToToday() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(large)
        provideComposable { ViewWidgetContent(state(config = WidgetConfig(1, monthOffset = 1, viewId = "v1")), systemFontScale = 1f) }
        onNode(hasTestTag("timelinePrev")).assert(hasRunCallbackClickAction<MonthNavAction>(actionParametersOf(MonthNavAction.DeltaKey to -1)))
        onNode(hasTestTag("timelineNext")).assert(hasRunCallbackClickAction<MonthNavAction>(actionParametersOf(MonthNavAction.DeltaKey to 1)))
        onNode(hasTestTag("timelineTitle").and(hasContentDescriptionEqualTo("1/17 – 1/23, 오늘로 돌아가기")))
            .assert(hasRunCallbackClickAction<MonthNavAction>(actionParametersOf(MonthNavAction.DeltaKey to 0)))
        onNode(hasTestTag("timelineEmpty")).assertExists() // 그 주에는 일정이 없다
        onAllNodes(hasTestTag("timelineBar")).assertCountEquals(0)
    }

    @Test
    fun theWeekStartFollowsTheAppSetting() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(large)
        provideComposable { ViewWidgetContent(state(weekStart = DayOfWeek.MONDAY), systemFontScale = 1f) }
        onNode(hasTestTag("timelineTitle").and(hasContentDescriptionEqualTo("1/11 – 1/17"))).assertExists()
    }

    @Test
    fun tooManyBarsLeaveAMoreLineThatOpensTheViewer() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(large)
        val rows = (1..12).map { row("r$it", "일 $it", "2027-01-1${it % 7}") }
        provideComposable { ViewWidgetContent(state(timeline(rows)), systemFontScale = 1f) }
        onNode(hasTestTag("viewMore")).assertExists()
        onAllNodes(hasTestTag("timelineBar")).assertCountEquals(TIMELINE_MAX_ROWS.coerceAtMost(12))
    }

    @Test
    fun aPartialBoardSaysSoNextToTheTitle() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(large)
        provideComposable { ViewWidgetContent(state(timeline(complete = false)), systemFontScale = 1f) }
        onNode(hasTestTag("timelineTitle").and(hasContentDescriptionEqualTo("1/10 – 1/16, 일정이 많아 일부만 받았습니다"))).assertExists()
        onNode(hasTextEqualTo("일부만 보여요")).assertExists()
    }

    @Test
    fun theSmallestSizeFallsBackToTheList() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(compact)
        provideComposable { ViewWidgetContent(state(), systemFontScale = 1f) }
        onAllNodes(hasTestTag("timelineBar")).assertCountEquals(0)
        onAllNodes(hasTestTag("viewRow")).assertCountEquals(rowCap(SizeStep.COMPACT)) // 2×2 목록은 3행까지
    }

    @Test
    fun aTimelineWithoutADateColumnIsAList() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(large)
        provideComposable { ViewWidgetContent(state(timeline(columns = listOf(title))), systemFontScale = 1f) }
        onAllNodes(hasTestTag("timelineBar")).assertCountEquals(0)
        onAllNodes(hasTestTag("viewRow")).assertCountEquals(4)
    }
}
