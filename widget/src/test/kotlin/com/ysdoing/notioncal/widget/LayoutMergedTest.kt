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
import com.ysdoing.notioncal.core.data.view.LayoutMode
import com.ysdoing.notioncal.core.data.view.ViewBoard
import com.ysdoing.notioncal.core.data.db.VIEW_ERROR_UNSUPPORTED
import com.ysdoing.notioncal.core.data.view.ViewColumn
import com.ysdoing.notioncal.core.data.view.ViewRow
import com.ysdoing.notioncal.core.data.view.WidgetLayout
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
 * Phase 12e WL-04 합쳐 보기: 보기 세 개의 일정을 한 월 격자에, 소스 이름·색 범례와 함께. 날짜 칸은 새 항목을 만들 수 있는 첫 소스의 시트를 열고,
 * 4×2의 오늘 목록 줄은 그 일정의 보기 시트를 연다. (오늘 = 2027-01-15 금요일, 서울.)
 */
@Config(qualifiers = "w420dp-h900dp-xxhdpi")
@RunWith(RobolectricTestRunner::class)
class LayoutMergedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val now = 1_800_000_000_000L
    private val wide = DpSize(501.dp, 213.dp)
    private val large = DpSize(501.dp, 458.dp)

    private val title = ViewColumn("t", "이름", "title")
    private val date = ViewColumn("d", "날짜", "date")
    private val deadline = ViewColumn("e", "마감", "date")

    private fun day(start: String) = JsonObject(mapOf("start" to JsonPrimitive(start)))

    private fun row(id: String, name: String, vararg dates: Pair<String, String>) =
        ViewRow(id, "", listOf(name), typed = mapOf("t" to JsonPrimitive(name)) + dates.associate { (prop, start) -> prop to day(start) })

    private fun board(id: String, name: String, rows: List<ViewRow>, columns: List<ViewColumn> = listOf(title, date), type: String = "table") =
        ViewBoard(id, name, type, listOf("이름"), rows, now, null, columns)

    private val todos = board("v1", "할 일", listOf(row("p1", "장보기", "d" to "2027-01-14")))
    private val meetings = board("v2", "약속", listOf(row("p2", "회의", "d" to "2027-01-15")), type = "calendar")

    // 날짜 속성이 둘인 보기: 기본(날짜)은 28일, 소스에서 '마감'을 고르면 20일에 나온다.
    private val tasks = board("v3", "마감", listOf(row("p3", "서류 제출", "d" to "2027-01-28", "e" to "2027-01-20")), listOf(title, date, deadline))

    private val layout = WidgetLayout(LayoutMode.MERGED).add("v1")!!.add("v2")!!.add("v3")!!.withDateProp("v3", "e").withColor("v2", "purple")

    private fun state(boards: Map<String, ViewBoard?> = listOf(todos, meetings, tasks).associateBy { it.viewId }, layout: WidgetLayout = this.layout) =
        LayoutWidgetState(WidgetConfig(1), layout, boards, now, DayOfWeek.SUNDAY, CalendarInfo(DayOfWeek.SUNDAY))

    @Test
    fun threeViewsFillOneMonthGridWithTheirEventsAndALegendOfNames() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(large)
        provideComposable { LayoutWidgetContent(state(), systemFontScale = 1f) }
        onAllNodes(hasTestTag("day")).assertCountEquals(42)
        onNode(hasTextEqualTo("장보기")).assertExists()
        onNode(hasTextEqualTo("회의")).assertExists()
        onNode(hasTextEqualTo("서류 제출")).assertExists()
        onNode(hasTestTag("mergedLegend")).assertExists()
        listOf("할 일", "약속", "마감").forEach { onNode(hasTextEqualTo(it)).assertExists() } // 색만이 아니라 이름으로도 구분한다
        onNode(hasTestTag("calendarTitle").and(hasContentDescriptionEqualTo("2027년 1월"))).assertExists()
    }

    @Test
    fun aSourcesChosenDateColumnPlacesItsEventsOnThatDay() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(large)
        provideComposable { LayoutWidgetContent(state(), systemFontScale = 1f) }
        onNode(hasTestTag("day").and(hasContentDescriptionEqualTo("1월 20일, 일정 1개"))).assertExists() // '마감' 속성
        onNode(hasTestTag("day").and(hasContentDescriptionEqualTo("1월 28일, 일정 0개"))).assertExists() // 기본 날짜 속성은 쓰지 않는다
    }

    @Test
    fun aDayCellOpensTheNewItemSheetOfTheFirstSourceThatCanCreateItems() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(large)
        provideComposable { LayoutWidgetContent(state(), systemFontScale = 1f) }
        onNode(hasTestTag("day").and(hasContentDescriptionEqualTo("1월 15일, 일정 1개")))
            .assert(hasStartActivityClickAction(viewAddIntent(context, "v1", 0, LocalDate.of(2027, 1, 15))))
    }

    @Test
    fun withoutACreatableSourceADayCellOpensTheFirstViewer() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(large)
        val readOnly = listOf(todos, meetings, tasks).associate { it.viewId to it.copy(columnInfo = listOf(date, deadline)) } // 제목 열이 없어 만들 수 없다
        provideComposable { LayoutWidgetContent(state(readOnly), systemFontScale = 1f) }
        onNode(hasTestTag("day").and(hasContentDescriptionEqualTo("1월 15일, 일정 1개"))).assert(hasStartActivityClickAction(viewerIntent(context, "v1")))
    }

    @Test
    fun theWeekShapeListsTodaysEventsAndARowOpensItsOwnViewsSheet() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(wide)
        provideComposable { LayoutWidgetContent(state(), systemFontScale = 1f) }
        onNode(hasTestTag("calendarToday")).assert(hasStartActivityClickAction(viewRowIntent(context, "v2", "p2", 0))) // 오늘 일정은 '약속'의 회의 하나
    }

    @Test
    fun theHeaderRefreshesAndTheMonthArrowsStillMoveTheWindow() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(large)
        provideComposable { LayoutWidgetContent(state(), systemFontScale = 1f) }
        onNode(hasTestTag("mergedHeader")).assert(hasRunCallbackClickAction<RefreshAction>())
        onNode(hasTestTag("calendarNext")).assert(hasRunCallbackClickAction<MonthNavAction>(actionParametersOf(MonthNavAction.DeltaKey to 1)))
    }

    @Test
    fun aGoneOrUnsupportedViewIsLeftOutAndTheRestStillDraw() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(large)
        val boards = mapOf("v1" to todos, "v2" to meetings.copy(lastError = VIEW_ERROR_UNSUPPORTED), "v3" to null)
        provideComposable { LayoutWidgetContent(state(boards), systemFontScale = 1f) }
        onNode(hasTextEqualTo("장보기")).assertExists()
        onAllNodes(hasTextEqualTo("회의")).assertCountEquals(0)
        onAllNodes(hasTextEqualTo("약속")).assertCountEquals(0)
    }

    @Test
    fun whenNoSourceIsLeftANoteSaysSoAndTheSmallestWidgetAsksForMoreRoom() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(large)
        provideComposable { LayoutWidgetContent(state(mapOf("v1" to null, "v2" to null, "v3" to null)), systemFontScale = 1f) }
        onNode(hasTestTag("mergedNote")).assertExists()
    }

    @Test
    fun aTooSmallWidgetSaysToMakeItBigger() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(DpSize(150.dp, 150.dp))
        provideComposable { LayoutWidgetContent(state(), systemFontScale = 1f) }
        onNode(hasTestTag("mergedTooSmall")).assertExists()
    }
}
