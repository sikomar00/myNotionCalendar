package com.ysdoing.notioncal.widget

import android.content.Context
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.ysdoing.notioncal.core.data.WidgetConfig
import com.ysdoing.notioncal.core.data.WidgetTheme
import com.ysdoing.notioncal.core.data.view.ViewBoard
import com.ysdoing.notioncal.core.data.view.ViewChoice
import com.ysdoing.notioncal.core.data.view.ViewColumn
import com.ysdoing.notioncal.core.data.view.ViewRow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.time.Duration.Companion.minutes

/**
 * Phase 12b VR-CHT 차트 보기의 실제 RemoteViews 변환: Glance Row·Column 자식 10개 한도(PRD §13)와 위젯당 view id 500개 한도(12a 실측)를 지키는지,
 * Binder 한도에 여유가 있는지 재고, 크기 단계별 기준 이미지를 남긴다. (삼성 보고 크기: 2×2 234×213, 4×2 501×213, 4×4 501×458, 4×5 501×581.)
 */
@OptIn(ExperimentalGlanceRemoteViewsApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w420dp-h900dp-xxhdpi")
class ViewChartRemoteViewsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val now = 1_800_000_000_000L

    private val compact = DpSize(234.dp, 213.dp)
    private val wide = DpSize(501.dp, 213.dp)
    private val large = DpSize(501.dp, 458.dp)
    private val full = DpSize(501.dp, 581.dp)
    private val allSizes = listOf(compact, wide, large, full, DpSize(160.dp, 160.dp), DpSize(358.dp, 152.dp), DpSize(358.dp, 420.dp), DpSize(358.dp, 640.dp), DpSize(411.dp, 800.dp))

    private val names = listOf("식비" to "red", "교통" to "blue", "쇼핑" to "pink", "문화" to "purple", "의료" to "green", "교육" to "orange", "여행" to "yellow", "통신" to "brown", "보험" to "gray", "기타" to "default", "구독" to "red", "저축" to "blue")
    private val columns = listOf(
        ViewColumn("t", "이름", "title"),
        ViewColumn("cat", "분류", "select", names.mapIndexed { i, (n, c) -> ViewChoice("o$i", n, color = c) }),
        ViewColumn("amt", "금액", "number"),
        ViewColumn("d", "날짜", "date"),
    )

    private fun row(id: String, option: Int, amount: Double, day: Int) = ViewRow(
        id, "https://notion.so/$id", listOf(id),
        typed = mapOf("cat" to JsonPrimitive("o$option"), "amt" to JsonPrimitive(amount), "d" to JsonObject(mapOf("start" to JsonPrimitive("2027-01-%02d".format(day))))),
    )

    private fun board(rows: List<ViewRow>, config: String, complete: Boolean = true) =
        ViewBoard("v1", "이번 달 지출", "chart", listOf("이름"), rows, now, null, columns, viewConfig = config, rowsComplete = complete)

    // 예시 지출(개인 데이터 아님): 분류 6개
    private val sampleRows = listOf(
        42000.0 to 0, 18000.0 to 0, 12000.0 to 0, 9500.0 to 1, 4500.0 to 1, 7000.0 to 1, 65000.0 to 2, 24000.0 to 3, 15000.0 to 4, 8000.0 to 5,
    ).mapIndexed { i, (amount, option) -> row("s$i", option, amount, 1 + i * 2) }

    // 분류 12개 × 행 100개: 점이 가장 많은 경우('기타'로 합친다).
    private val denseRows = (0 until 100).map { i -> row("d$i", i % names.size, 1000.0 * (1 + i % 17), 1 + i % 28) }

    // 예시 일별 지출: 한 달 14일치
    private val dailyRows = (0 until 14).map { i -> row("l$i", i % 6, 8000.0 + (i * 7919) % 41000, 1 + i * 2) }

    private val lineConfig = """{"type":"chart","chart_type":"line","x_axis":{"type":"date","property_id":"d","group_by":"day","sort":{"type":"ascending"}},"y_axis":{"aggregator":"sum","property_id":"amt"}}"""

    private fun chartConfig(type: String, y: String = """{"aggregator":"sum","property_id":"amt"}""") =
        if (type == "number") """{"type":"chart","chart_type":"number","value":$y}"""
        else """{"type":"chart","chart_type":"$type","x_axis":{"type":"select","property_id":"cat","sort":{"type":"manual"}},"y_axis":$y}"""

    private fun state(board: ViewBoard, config: WidgetConfig = WidgetConfig(1, viewId = "v1")) = ViewWidgetState(board, config, now)

    private suspend fun render(size: DpSize, state: ViewWidgetState) = renderRemoteViews(context, size) { ViewWidgetContent(state) }

    private val chartTypes = listOf("column", "bar", "line", "donut", "number")

    // 렌더 하나마다 Activity와 View 트리가 생겨 시험 JVM 힙(기본 512MB)이 빨리 찬다. 조합은 대표만 돌린다.
    private val translateSizes = listOf(compact, wide, large, full, DpSize(160.dp, 160.dp), DpSize(411.dp, 800.dp))

    @Test
    fun translatesInEverySizeAndType() = runTest(timeout = 5.minutes) {
        val configs = listOf(WidgetConfig(1, viewId = "v1"), WidgetConfig(1, fontScale = 150, theme = WidgetTheme.DARK, opacity = 0, viewId = "v1"))
        val failures = mutableListOf<String>()
        for (size in translateSizes) for (type in chartTypes) for (config in configs) {
            // 한 번 실패해도 나머지 조합을 계속 돌려 어느 크기·설정이 한도를 넘는지 한꺼번에 알린다.
            runCatching { render(size, state(board(denseRows, chartConfig(type)), config)) }
                .onFailure { failures += "$type ${size.width}x${size.height} font=${config.fontScale}: ${it.message}" }
        }
        assertTrue(failures.joinToString(System.lineSeparator()), failures.isEmpty())
    }

    @Test
    fun denseChartsStayUnderTheViewIdBudget() = runTest(timeout = 5.minutes) {
        val failures = mutableListOf<String>()
        for (size in listOf(wide, large, full)) for (type in chartTypes) for (font in listOf(70, 150)) {
            val ids = glanceIdsUsed(render(size, state(board(denseRows, chartConfig(type)), WidgetConfig(1, fontScale = font, viewId = "v1"))))
            println("VIEWCHART $type ${size.width}x${size.height} font=$font: $ids view ids")
            if (ids > GLANCE_VIEW_ID_BUDGET) failures += "$type ${size.width}x${size.height} font=$font uses $ids view ids"
        }
        assertTrue(failures.joinToString(System.lineSeparator()), failures.isEmpty())
    }

    @Test
    fun denseChartsStayFarBelowTheBinderLimit() = runTest(timeout = 5.minutes) {
        for (size in listOf(large, full)) for (type in listOf("column", "donut", "line")) {
            val dense = state(board(denseRows, chartConfig(type)))
            val parcel = android.os.Parcel.obtain()
            GlanceRemoteViews().compose(context, size) { ViewWidgetContent(dense) }.remoteViews.writeToParcel(parcel, 0)
            val bytes = parcel.dataSize()
            parcel.recycle()
            println("VIEWCHART_PARCEL $type ${size.width}x${size.height} = $bytes bytes")
            // 막대는 비트맵이 없어 작다. 도넛·꺾은선은 그림 한 장(예산 한도 이하)이 더 든다.
            val limit = if (type == "column") 400_000L else 400_000L + ChartBudget.MAX_PICTURE_BYTES
            assertTrue("$type $size RemoteViews $bytes bytes", bytes < limit)
        }
    }

    @Test
    fun screenshot2x2Number() = runTest {
        render(compact, state(board(sampleRows, chartConfig("number")))).captureRoboImage("src/test/screenshots/viewchart_2x2_number.png")
    }

    @Test
    fun screenshot4x2Columns() = runTest {
        render(wide, state(board(sampleRows, chartConfig("column")))).captureRoboImage("src/test/screenshots/viewchart_4x2_columns.png")
    }

    @Test
    fun screenshot4x4Rows() = runTest {
        render(large, state(board(sampleRows, chartConfig("bar")))).captureRoboImage("src/test/screenshots/viewchart_4x4_rows.png")
    }

    @Test
    fun screenshot2x2Donut() = runTest {
        render(compact, state(board(sampleRows, chartConfig("donut")))).captureRoboImage("src/test/screenshots/viewchart_2x2_donut.png")
    }

    @Test
    fun screenshot4x4DonutWithLegend() = runTest {
        render(large, state(board(sampleRows, chartConfig("donut")))).captureRoboImage("src/test/screenshots/viewchart_4x4_donut.png")
    }

    @Test
    fun screenshot4x2Line() = runTest {
        render(wide, state(board(dailyRows, lineConfig))).captureRoboImage("src/test/screenshots/viewchart_4x2_line.png")
    }

    @Test
    fun screenshot4x4LineDark() = runTest {
        render(large, state(board(dailyRows, lineConfig), WidgetConfig(1, theme = WidgetTheme.DARK, opacity = 100, viewId = "v1")))
            .captureRoboImage("src/test/screenshots/viewchart_4x4_line_dark.png")
    }

    @Test
    fun screenshotFullColumnsWithTable() = runTest {
        render(full, state(board(sampleRows, chartConfig("column")))).captureRoboImage("src/test/screenshots/viewchart_full_columns.png")
    }

    @Test
    fun screenshot4x4DarkFont150Partial() = runTest {
        render(large, state(board(sampleRows, chartConfig("bar"), complete = false), WidgetConfig(1, fontScale = 150, theme = WidgetTheme.DARK, opacity = 100, viewId = "v1")))
            .captureRoboImage("src/test/screenshots/viewchart_4x4_dark_font150.png")
    }
}
