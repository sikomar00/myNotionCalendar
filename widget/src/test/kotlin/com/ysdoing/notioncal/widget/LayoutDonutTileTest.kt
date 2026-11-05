package com.ysdoing.notioncal.widget

import android.content.Context
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import androidx.glance.appwidget.testing.unit.hasStartActivityClickAction
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.hasTestTag
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.ysdoing.notioncal.core.data.WidgetConfig
import com.ysdoing.notioncal.core.data.recipe.Recipe
import com.ysdoing.notioncal.core.data.recipe.RecipePicks
import com.ysdoing.notioncal.core.data.recipe.RecipeView
import com.ysdoing.notioncal.core.data.recipe.Recipes
import com.ysdoing.notioncal.core.data.view.AggFn
import com.ysdoing.notioncal.core.data.view.AggPeriod
import com.ysdoing.notioncal.core.data.view.AggTile
import com.ysdoing.notioncal.core.data.view.LayoutMode
import com.ysdoing.notioncal.core.data.view.TileKind
import com.ysdoing.notioncal.core.data.view.ViewBoard
import com.ysdoing.notioncal.core.data.view.ViewChoice
import com.ysdoing.notioncal.core.data.view.ViewColumn
import com.ysdoing.notioncal.core.data.view.ViewRow
import com.ysdoing.notioncal.core.data.view.WidgetLayout
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.DayOfWeek
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Phase 12c RC-04 분류별 도넛 타일: 값 타일 격자 아래 전체 폭 한 줄(고리 + 범례). 높이가 남을 때만 그리고(2×2·낮은 크기는 건너뜀),
 * 누르면 뷰어, 낭독은 차트 보기와 같은 문장이다. 오늘은 2027-01-15(금)이고 이번 달은 1월이다.
 */
@OptIn(ExperimentalGlanceRemoteViewsApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w420dp-h900dp-xxhdpi")
class LayoutDonutTileTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val now = 1_800_000_000_000L // 2027-01-15 17:00 KST
    private val metrics = viewMetrics(1f, 1f)

    private val compact = DpSize(234.dp, 213.dp)
    private val wide = DpSize(501.dp, 213.dp)
    private val large = DpSize(501.dp, 458.dp)
    private val full = DpSize(501.dp, 581.dp)

    private val names = listOf("식비" to "red", "교통" to "blue", "쇼핑" to "pink", "문화" to "purple", "의료" to "green", "교육" to "orange", "여행" to "yellow")
    private val columns = listOf(
        ViewColumn("t", "이름", "title"),
        ViewColumn("cat", "분류", "select", names.mapIndexed { i, (n, c) -> ViewChoice("o$i", n, color = c) }),
        ViewColumn("amt", "금액", "number", numberFormat = "won"),
        ViewColumn("d", "날짜", "date"),
    )

    private fun row(id: String, option: Int, amount: Double, month: String = "2027-01") = ViewRow(
        id, "https://notion.so/$id", listOf(id),
        typed = mapOf("cat" to JsonPrimitive("o$option"), "amt" to JsonPrimitive(amount), "d" to JsonObject(mapOf("start" to JsonPrimitive("$month-05")))),
    )

    // 예시 지출(개인 데이터 아님): 이번 달 분류 6개 + 지난달 한 건(도넛에 없어야 한다)
    private val rows = listOf(
        42000.0 to 0, 18000.0 to 0, 9500.0 to 1, 4500.0 to 1, 65000.0 to 2, 24000.0 to 3, 15000.0 to 4, 8000.0 to 5,
    ).mapIndexed { i, (amount, option) -> row("s$i", option, amount) } + row("old", 6, 99000.0, month = "2026-12")

    private fun board(rows: List<ViewRow> = this.rows, name: String = "가계부") =
        ViewBoard("v1", name, "table", listOf("이름"), rows, now, null, columns)

    private val sum = AggTile("", "v1", AggFn.SUM, "amt", "d", AggPeriod.THIS_MONTH, label = "이번 달 합계")
    private val donut = AggTile("", "v1", AggFn.SUM, "amt", "d", AggPeriod.THIS_MONTH, label = "이번 달 분류별", kind = TileKind.DONUT, groupProp = "cat")

    private fun state(tiles: List<AggTile>, boards: Map<String, ViewBoard?> = mapOf("v1" to board()), items: List<String> = listOf("v1"), fontScale: Int = 100): LayoutWidgetState {
        var layout = items.fold(WidgetLayout(LayoutMode.SECTIONS)) { l, id -> l.add(id)!! }
        tiles.forEach { layout = layout.addTile(it)!! }
        return LayoutWidgetState(WidgetConfig(1, fontScale = fontScale), layout, boards, now, weekStart = DayOfWeek.MONDAY)
    }

    // 기준 이미지는 목록 어댑터가 Robolectric에서 채워지지 않으니 행을 고정으로 그린다(staticRows).
    private suspend fun render(size: DpSize, state: LayoutWidgetState, staticRows: Boolean = false) =
        renderRemoteViews(context, size) { LayoutWidgetContent(state, systemFontScale = 1f, staticRows = staticRows) }

    // --- 순수: 계획

    private fun cells(vararg tiles: AggTile) = state(tiles.toList()).tileCells()

    @Test
    fun theDonutFitsBelowTheValueTilesAndTakesTheRemainingHeight() {
        val plan = planTileStrip(cells(sum, donut), metrics, large, room = 220.dp)
        assertEquals(listOf(TileKind.AGG), plan.cells.map { it.tile.kind })
        val donutPlan = plan.donut!!
        assertTrue(donutPlan.legendRows in 2..DONUT_MAX_LEGEND)
        assertEquals(plan.valuesHeight + AGG_TILE_GAP + donutPlan.height, plan.height)
    }

    @Test
    fun theDonutIsDroppedAndValueTilesKeepTheRoomWhenItDoesNotFit() {
        val room = 100.dp // 값 타일 한 줄 + 범례 두 줄이 안 들어간다
        val plan = planTileStrip(cells(sum, donut), metrics, large, room)
        assertNull(plan.donut)
        assertEquals(listOf(TileKind.AGG), plan.cells.map { it.tile.kind })
        assertEquals(planTileStrip(cells(sum), metrics, large, room).height, plan.height)
    }

    @Test
    fun noDonutOnTheCompactSizeEvenWithPlentyOfRoom() {
        assertNull(planTileStrip(cells(sum, donut), metrics, compact, room = 400.dp).donut)
    }

    @Test
    fun aDonutAloneUsesTheWholeStripAndNeedsNoGap() {
        val plan = planTileStrip(cells(donut), metrics, full, room = 200.dp)
        assertTrue(plan.cells.isEmpty())
        assertEquals(plan.donut!!.height, plan.height)
    }

    @Test
    fun theLegendShrinksToWhatTheRoomHoldsButKeepsTwoRows() {
        val min = donutMinHeight(metrics)
        assertEquals(2, planDonut(cells(donut).single(), metrics, large, min)!!.legendRows)
        assertNull(planDonut(cells(donut).single(), metrics, large, min - 1.dp))
        assertEquals(DONUT_MAX_LEGEND, planDonut(cells(donut).single(), metrics, large, 400.dp)!!.legendRows)
    }

    @Test
    fun aDonutTileWithNoRowsInThePeriodIsALowNoteCard() {
        val empty = state(listOf(donut), mapOf("v1" to board(listOf(row("old", 6, 99000.0, month = "2026-12"))))).tileCells().single()
        val plan = planDonut(empty, metrics, large, 200.dp)!!
        assertEquals(0, plan.legendRows)
        assertEquals(metrics.tile, plan.height)
    }

    // --- Glance

    @Test
    fun theDonutRowShowsTheTilesNameLegendAndSpokenSummary() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(full)
        provideComposable { LayoutWidgetContent(state(listOf(sum, donut)), systemFontScale = 1f) }
        onNode(hasTestTag("layoutDonutTile")).assertExists().assert(hasStartActivityClickAction(viewerIntent(context, "v1")))
        onNode(hasTestTag("chartDonut")).assertExists()
        onAllNodes(hasTestTag("layoutTile")).assertCountEquals(1) // 값 타일은 하나 그대로다
    }

    @Test
    fun theDonutIsLeftOutOfTheCompactWidget() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(compact)
        provideComposable { LayoutWidgetContent(state(listOf(sum, donut)), systemFontScale = 1f) }
        onAllNodes(hasTestTag("layoutDonutTile")).assertCountEquals(0)
        onAllNodes(hasTestTag("layoutTile")).assertCountEquals(1)
    }

    @Test
    fun aMissingViewShowsANoteInsteadOfARing() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(full)
        provideComposable { LayoutWidgetContent(state(listOf(donut), boards = mapOf("v1" to null), items = emptyList()), systemFontScale = 1f) }
        onNode(hasTestTag("layoutDonutNote")).assertExists()
        onAllNodes(hasTestTag("chartDonut")).assertCountEquals(0)
    }

    @Test
    fun theDonutSpokenSummaryNamesTheTileAndTheCategories() {
        val cell = cells(donut).single()
        val spoken = com.ysdoing.notioncal.core.data.view.chart.ChartSummary.sentence(cell.donut!!)
        assertTrue(spoken, "식비" in spoken && "쇼핑" in spoken)
        assertTrue(spoken, "의료" in spoken || "기타" in spoken)
        assertTrue("지난달 항목(여행)은 이번 달 도넛에 없다", "여행" !in spoken)
    }

    // --- RemoteViews 변환

    @Test
    fun translatesWithTheDonutInEverySizeAndFontScale() = runTest(timeout = 5.minutes) {
        val failures = mutableListOf<String>()
        for (size in listOf(compact, wide, large, full, DpSize(411.dp, 800.dp))) for (font in listOf(70, 100, 150)) {
            runCatching {
                val ids = glanceIdsUsed(render(size, state(listOf(sum, donut), fontScale = font)))
                println("LAYOUT_DONUT ${size.width}x${size.height} font=$font: $ids view ids")
                if (ids > GLANCE_VIEW_ID_BUDGET) failures += "${size.width}x${size.height} font=$font uses $ids view ids"
            }.onFailure { failures += "${size.width}x${size.height} font=$font: ${it.message}" }
        }
        assertTrue(failures.joinToString(System.lineSeparator()), failures.isEmpty())
    }

    @Test
    fun theDonutPictureStaysInsideTheWidgetBitmapBudget() = runTest(timeout = 5.minutes) {
        val parcel = android.os.Parcel.obtain()
        GlanceRemoteViews().compose(context, full) { LayoutWidgetContent(state(listOf(sum, donut)), systemFontScale = 1f) }.remoteViews.writeToParcel(parcel, 0)
        val bytes = parcel.dataSize()
        parcel.recycle()
        assertTrue("RemoteViews $bytes bytes", bytes < 400_000L + ChartBudget.MAX_PICTURE_BYTES)
    }

    @Test
    fun theLedgerRecipeWidgetGetsTheDonutRowAtFullSize() = runTest(timeout = 5.minutes) {
        val view = RecipeView("v1", "가계부", "table", columns)
        val picks = RecipePicks(viewId = "v1", amountProp = "amt", categoryProp = "cat", budget = 500000.0)
        val layout = WidgetLayout.parse(Recipes.build(Recipe.LEDGER, picks, listOf(view))!!.layoutJson)
        val tiles = layout.tiles
        assertNotNull(tiles.firstOrNull { it.kind == TileKind.DONUT })
        val st = LayoutWidgetState(WidgetConfig(1), layout, mapOf("v1" to board()), now, weekStart = DayOfWeek.MONDAY)
        val ids = glanceIdsUsed(render(full, st))
        assertTrue("$ids view ids", ids <= GLANCE_VIEW_ID_BUDGET)
    }

    // --- 기준 이미지

    @Test
    fun screenshotFullWithValueTileAndDonut() = runTest {
        render(full, state(listOf(sum, donut)), staticRows = true).captureRoboImage("src/test/screenshots/layout_donut_tile_full.png")
    }

    @Test
    fun screenshotLedgerRecipeLarge() = runTest {
        val view = RecipeView("v1", "가계부", "table", columns)
        val picks = RecipePicks(viewId = "v1", amountProp = "amt", categoryProp = "cat", budget = 500000.0)
        val layout = WidgetLayout.parse(Recipes.build(Recipe.LEDGER, picks, listOf(view))!!.layoutJson)
        render(large, LayoutWidgetState(WidgetConfig(1), layout, mapOf("v1" to board()), now, weekStart = DayOfWeek.MONDAY), staticRows = true)
            .captureRoboImage("src/test/screenshots/layout_donut_tile_ledger_4x4.png")
    }
}
