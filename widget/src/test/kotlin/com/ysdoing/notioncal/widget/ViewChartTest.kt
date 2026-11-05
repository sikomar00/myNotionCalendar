package com.ysdoing.notioncal.widget

import android.content.Context
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.testing.unit.hasStartActivityClickAction
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.hasContentDescriptionEqualTo
import androidx.glance.testing.unit.hasTestTag
import androidx.glance.testing.unit.hasTextEqualTo
import androidx.test.core.app.ApplicationProvider
import com.ysdoing.notioncal.core.data.WidgetConfig
import com.ysdoing.notioncal.core.data.view.LayoutMode
import com.ysdoing.notioncal.core.data.view.ViewBoard
import com.ysdoing.notioncal.core.data.view.ViewChoice
import com.ysdoing.notioncal.core.data.view.ViewColumn
import com.ysdoing.notioncal.core.data.view.ViewRow
import com.ysdoing.notioncal.core.data.view.WidgetLayout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.seconds

/**
 * Phase 12b VR-CHT 차트 보기: 숫자·세로 막대·가로 막대·값 표, 차트를 누르면 뷰어, 이름과 값을 읽는 한 문장 낭독,
 * 일부 행만 받았다는 표시, 못 그리는 설정은 한 줄 안내 + 표·목록으로 대체. (삼성 보고 크기: 2×2 234×213, 4×2 501×213, 4×4 501×458, 4×5 501×581.)
 */
@Config(qualifiers = "w420dp-h900dp-xxhdpi")
@RunWith(RobolectricTestRunner::class)
class ViewChartTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val now = 1_800_000_000_000L
    private val compact = DpSize(234.dp, 213.dp)
    private val wide = DpSize(501.dp, 213.dp)
    private val large = DpSize(501.dp, 458.dp)
    private val full = DpSize(501.dp, 581.dp)

    private val columns = listOf(
        ViewColumn("t", "이름", "title"),
        ViewColumn("cat", "분류", "select", listOf(ViewChoice("o1", "식비", color = "red"), ViewChoice("o2", "교통", color = "blue"), ViewChoice("o3", "취미", color = "gray"))),
        ViewColumn("amt", "금액", "number"),
        ViewColumn("f", "수식", "formula"),
        ViewColumn("d", "날짜", "date"),
    )

    private fun row(id: String, option: String?, amount: Double) =
        ViewRow(id, "https://notion.so/$id", listOf(id), typed = buildMap { put("amt", JsonPrimitive(amount)); option?.let { put("cat", JsonPrimitive(it)) } })

    // 식비 3건(합 25,000), 교통 2건(합 5,000), 분류 없음 1건(700)
    private val rows = listOf(
        row("1", "o1", 12000.0), row("2", "o1", 8000.0), row("3", "o1", 5000.0), row("4", "o2", 3500.0), row("5", "o2", 1500.0), row("6", null, 700.0),
    )

    private fun config(type: String, y: String = """{"aggregator":"count"}""", extra: String = "") =
        """{"type":"chart","chart_type":"$type","x_axis":{"type":"select","property_id":"cat","sort":{"type":"manual"}},"y_axis":$y$extra}"""

    private val sum = """{"aggregator":"sum","property_id":"amt"}"""

    private fun chart(config: String, rows: List<ViewRow> = this.rows, complete: Boolean = true) =
        ViewBoard("v1", "지출", "chart", listOf("이름"), rows, now, null, columns, viewConfig = config, rowsComplete = complete)

    private fun state(board: ViewBoard) = ViewWidgetState(board, WidgetConfig(1, viewId = "v1"), now)

    @Test
    fun aColumnChartDrawsOneBarPerGroupAndReadsItsNamesAndValues() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(wide)
        provideComposable { ViewWidgetContent(state(chart(config("column"))), systemFontScale = 1f) }
        onAllNodes(hasTestTag("chartBar")).assertCountEquals(4) // 식비·교통·취미(0건)·없음
        onNode(hasTestTag("chart").and(hasContentDescriptionEqualTo("개수: 식비 3, 교통 2, 취미 0, 없음 1"))).assertExists()
        listOf("식비", "교통", "취미", "없음", "3", "2", "1").forEach { onNode(hasTextEqualTo(it)).assertExists() }
        onAllNodes(hasTestTag("viewRow")).assertCountEquals(0) // 표·목록이 아니다
    }

    @Test
    fun aDonutAtTwoByTwoHasTheTotalInTheMiddleAndNoLegend() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(compact)
        provideComposable { ViewWidgetContent(state(chart(config("donut", sum))), systemFontScale = 1f) }
        onNode(hasTestTag("chartDonut")).assertExists()
        onNode(hasTextEqualTo("30,700")).assertExists() // 고리 가운데 전체 값(글자는 그림에 그리지 않는다)
        onAllNodes(hasTextEqualTo("식비")).assertCountEquals(0) // 범례 없음
        onNode(hasTestTag("chart").and(hasContentDescriptionEqualTo("합계 금액 비율: 식비 81%, 교통 16%, 취미 0%, 없음 2%"))).assertExists()
    }

    @Test
    fun aDonutAtLargeSizeHasALegendWithNamesValuesAndShares() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(large)
        provideComposable { ViewWidgetContent(state(chart(config("donut", sum))), systemFontScale = 1f) }
        onNode(hasTestTag("chartDonut")).assertExists()
        listOf("식비", "교통", "취미", "없음", "25,000", "5,000", "700", "81%", "16%", "2%").forEach { onNode(hasTextEqualTo(it)).assertExists() }
        onNode(hasTextEqualTo("30,700")).assertExists() // 가운데 전체 값
    }

    @Test
    fun aDonutWhoseLegendNotionTurnedOffIsJustTheRingWithTheTotal() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(large)
        provideComposable { ViewWidgetContent(state(chart(config("donut", sum, extra = ""","legend_position":"off""""))), systemFontScale = 1f) }
        onNode(hasTestTag("chartDonut")).assertExists()
        onAllNodes(hasTextEqualTo("식비")).assertCountEquals(0)
    }

    private val dated = listOf(
        ViewRow("1", "", listOf("1"), typed = mapOf("d" to JsonObject(mapOf("start" to JsonPrimitive("2027-01-04"))), "amt" to JsonPrimitive(10.0))),
        ViewRow("2", "", listOf("2"), typed = mapOf("d" to JsonObject(mapOf("start" to JsonPrimitive("2027-01-06"))), "amt" to JsonPrimitive(20.0))),
        ViewRow("3", "", listOf("3"), typed = mapOf("d" to JsonObject(mapOf("start" to JsonPrimitive("2027-01-13"))), "amt" to JsonPrimitive(50.0))),
        ViewRow("4", "", listOf("4"), typed = mapOf("d" to JsonObject(mapOf("start" to JsonPrimitive("2027-01-20"))), "amt" to JsonPrimitive(30.0))),
    )

    private val lineConfig = """{"type":"chart","chart_type":"line","x_axis":{"type":"date","property_id":"d","group_by":"week","sort":{"type":"ascending"},"start_day_of_week":1},"y_axis":$sum}"""

    @Test
    fun aLineChartHasTheLinePictureThePeakValueAndTheFirstMiddleAndLastNames() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(wide)
        provideComposable { ViewWidgetContent(state(chart(lineConfig, rows = dated)), systemFontScale = 1f) }
        onNode(hasTestTag("chartLine")).assertExists()
        onNode(hasTestTag("chartPeak")).assertExists()
        onNode(hasTextEqualTo("50")).assertExists() // 가장 큰 값: 1/11~ 주의 50
        listOf("1/4~", "1/11~", "1/18~").forEach { onNode(hasTextEqualTo(it)).assertExists() }
        onNode(hasTestTag("chart").and(hasContentDescriptionEqualTo("합계 금액: 1/4~ 30, 1/11~ 50, 1/18~ 30"))).assertExists()
    }

    @Test
    fun aLineChartAtTwoByTwoShrinksToTheOverallValue() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(compact)
        provideComposable { ViewWidgetContent(state(chart(lineConfig, rows = dated)), systemFontScale = 1f) }
        onAllNodes(hasTestTag("chartLine")).assertCountEquals(0)
        onNode(hasTextEqualTo("110")).assertExists()
        // 보이는 값(전체 110)과 읽는 문장이 같다: 첫 점(1/4~ 30)만 읽지 않는다
        onNode(hasTestTag("chart").and(hasContentDescriptionEqualTo("합계 금액 110"))).assertExists()
    }

    @Test
    fun theChartOpensTheViewerWhenTapped() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(wide)
        provideComposable { ViewWidgetContent(state(chart(config("column"))), systemFontScale = 1f) }
        onNode(hasTestTag("chart")).assert(hasStartActivityClickAction(viewerIntent(context, "v1")))
    }

    @Test
    fun aBarChartDrawsOneRowPerGroupWithItsSummedValue() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(large)
        provideComposable { ViewWidgetContent(state(chart(config("bar", sum))), systemFontScale = 1f) }
        onAllNodes(hasTestTag("chartBar")).assertCountEquals(4)
        listOf("25,000", "5,000", "700", "0").forEach { onNode(hasTextEqualTo(it)).assertExists() }
        onNode(hasTestTag("chart").and(hasContentDescriptionEqualTo("합계 금액: 식비 25,000, 교통 5,000, 취미 0, 없음 700"))).assertExists()
    }

    @Test
    fun aNumberChartShowsTheOverallValueWithWhatWasCounted() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(compact)
        provideComposable { ViewWidgetContent(state(chart("""{"type":"chart","chart_type":"number","value":$sum}""")), systemFontScale = 1f) }
        onNode(hasTextEqualTo("30,700")).assertExists()
        onNode(hasTextEqualTo("합계 금액")).assertExists()
        onNode(hasTestTag("chart").and(hasContentDescriptionEqualTo("합계 금액 30,700"))).assertExists()
        onAllNodes(hasTestTag("chartBar")).assertCountEquals(0)
    }

    @Test
    fun theFullSizeAddsAValueTableWithShares() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(full)
        provideComposable { ViewWidgetContent(state(chart(config("column"))), systemFontScale = 1f) }
        onAllNodes(hasTestTag("chartBar")).assertCountEquals(4)
        // 값 표: 이름이 막대 아래와 표에 한 번씩, 비율은 점들의 합(6)에 대한 몫
        onAllNodes(hasTextEqualTo("식비")).assertCountEquals(2)
        listOf("50%", "33%", "0%", "17%").forEach { onNode(hasTextEqualTo(it)).assertExists() }
    }

    @Test
    fun aPartiallyReceivedChartSaysSoAndReadsItAloud() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(wide)
        provideComposable { ViewWidgetContent(state(chart(config("column"), complete = false)), systemFontScale = 1f) }
        onNode(hasTestTag("chartPartial")).assertExists()
        onNode(hasTextEqualTo(context.getString(R.string.view_chart_partial))).assertExists()
        onNode(hasTestTag("chart").and(hasContentDescriptionEqualTo("개수: 식비 3, 교통 2, 취미 0, 없음 1 (일부 행만 반영)"))).assertExists()
    }

    @Test
    fun hidingEmptyGroupsDropsTheZeroBar() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(wide)
        provideComposable { ViewWidgetContent(state(chart(config("column", extra = ""","hide_empty_groups":true"""))), systemFontScale = 1f) }
        onAllNodes(hasTestTag("chartBar")).assertCountEquals(3)
    }

    @Test
    fun aSettingTheWidgetCannotDrawShowsAOneLineNoteAndTheListInstead() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(large)
        // 수식 속성이 x축: 값이 없어 못 그린다
        val formulaAxis = """{"type":"chart","chart_type":"column","x_axis":{"type":"formula","property_id":"f"},"y_axis":{"aggregator":"count"}}"""
        provideComposable { ViewWidgetContent(state(chart(formulaAxis)), systemFontScale = 1f) }
        onNode(hasTestTag("chartNote")).assertExists()
        onNode(hasTextEqualTo(context.getString(R.string.view_chart_unsupported))).assertExists()
        onAllNodes(hasTestTag("chart")).assertCountEquals(0)
        onAllNodes(hasTestTag("viewRow")).assertCountEquals(6)
    }

    @Test
    fun anUnreadableConfigurationAlsoFallsBackWithTheNote() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(large)
        provideComposable { ViewWidgetContent(state(chart("")), systemFontScale = 1f) }
        onNode(hasTestTag("chartNote")).assertExists()
        onAllNodes(hasTestTag("viewRow")).assertCountEquals(6)
    }

    @Test
    fun anEmptyChartViewShowsTheUsualEmptyState() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(large)
        provideComposable { ViewWidgetContent(state(chart(config("column"), rows = emptyList())), systemFontScale = 1f) }
        onAllNodes(hasTestTag("chart")).assertCountEquals(0)
        onNode(hasTextEqualTo(context.getString(R.string.view_widget_empty))).assertExists()
    }

    @Test
    fun aWidgetTooShortForAChartFallsBackToTheListWithoutANote() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(DpSize(234.dp, 100.dp))
        provideComposable { ViewWidgetContent(state(chart(config("column"))), systemFontScale = 1f) }
        onAllNodes(hasTestTag("chart")).assertCountEquals(0)
        onAllNodes(hasTestTag("chartNote")).assertCountEquals(0) // 설정이 아니라 높이가 모자란 것이라 안내는 없다
        onAllNodes(hasTestTag("viewRow")).assertCountEquals(3)
    }

    private fun layoutState(mode: LayoutMode, vararg boards: ViewBoard): LayoutWidgetState {
        val layout = boards.fold(WidgetLayout(mode)) { l, b -> l.add(b.viewId) ?: l }
        return LayoutWidgetState(WidgetConfig(1), layout, boards.associateBy { it.viewId }, now)
    }

    @Test
    fun aChartViewWorksInATabAndInSections() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(large)
        provideComposable { LayoutWidgetContent(layoutState(LayoutMode.TABS, chart(config("column"))), systemFontScale = 1f) }
        onAllNodes(hasTestTag("chartBar")).assertCountEquals(4)
    }

    @Test
    fun twoChartViewsStackAsSectionsAndEachDrawsItsChart() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(large)
        val first = chart(config("column"))
        val second = chart(config("bar", sum)).copy(viewId = "v2", name = "합계")
        provideComposable { LayoutWidgetContent(layoutState(LayoutMode.SECTIONS, first, second), systemFontScale = 1f) }
        onAllNodes(hasTestTag("chart")).assertCountEquals(2)
        onAllNodes(hasTestTag("chartBar")).assertCountEquals(8)
    }

    @Test
    fun theSettingsPreviewDrawsTheSameChart() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(wide)
        provideComposable { ViewWidgetContent(state(chart(config("column"))), systemFontScale = 1f, staticRows = true) }
        onAllNodes(hasTestTag("chartBar")).assertCountEquals(4)
    }
}
