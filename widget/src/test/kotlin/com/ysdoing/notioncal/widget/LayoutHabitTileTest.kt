package com.ysdoing.notioncal.widget

import android.content.Context
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.testing.unit.hasRunCallbackClickAction
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.hasContentDescriptionEqualTo
import androidx.glance.testing.unit.hasTestTag
import androidx.glance.testing.unit.hasTextEqualTo
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
import com.ysdoing.notioncal.core.data.db.VIEW_ERROR_NOT_FOUND
import com.ysdoing.notioncal.core.data.view.ViewBoard
import com.ysdoing.notioncal.core.data.view.ViewColumn
import com.ysdoing.notioncal.core.data.view.ViewRow
import com.ysdoing.notioncal.core.data.view.WidgetLayout
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
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
import java.time.LocalDate
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Phase 12e RC-03 해빗 7일 격자 타일: 습관마다 오늘까지 최근 7일 칸, 칸을 누르면 체크(첫 체크는 그날 페이지를 만든다), 해빗 격자가 값 타일보다 먼저
 * 자리를 잡고 2×2는 건너뛴다. 오늘은 2027-01-15(금).
 */
@OptIn(ExperimentalGlanceRemoteViewsApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w420dp-h900dp-xxhdpi")
class LayoutHabitTileTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val now = 1_800_000_000_000L // 2027-01-15 17:00 KST
    private val today = LocalDate.of(2027, 1, 15)
    private val metrics = viewMetrics(1f, 1f)

    private val compact = DpSize(234.dp, 213.dp)
    private val wide = DpSize(501.dp, 213.dp)
    private val large = DpSize(501.dp, 458.dp)
    private val full = DpSize(501.dp, 581.dp)

    private val columns = listOf(
        ViewColumn("t", "이름", "title"), ViewColumn("d", "날짜", "date"),
        ViewColumn("c1", "운동", "checkbox"), ViewColumn("c2", "독서", "checkbox"), ViewColumn("c3", "영어", "checkbox"),
    )

    private fun row(daysAgo: Int, vararg checked: String) = ViewRow(
        "p$daysAgo", "", listOf("행"),
        typed = mapOf<String, JsonElement>("d" to JsonObject(mapOf("start" to JsonPrimitive(today.minusDays(daysAgo.toLong()).toString())))) +
            listOf("c1", "c2", "c3").associateWith { JsonPrimitive(it in checked) },
    )

    // 예시 습관 기록(개인 데이터 아님): 운동은 5일, 독서는 3일, 영어는 오늘만
    private val rows = listOf(row(0, "c1", "c3"), row(1, "c1", "c2"), row(2, "c1"), row(3, "c1", "c2"), row(4), row(5, "c1", "c2"), row(6))
    private fun board(rows: List<ViewRow> = this.rows) = ViewBoard("v1", "습관", "table", listOf("이름"), rows, now, null, columns)

    private fun habitsTile(vararg props: String) = AggTile("", "v1", dateProp = "d", kind = TileKind.HABITS, habitProps = props.toList())
    private val streak = AggTile("", "v1", AggFn.STREAK, "c1", "d", label = "연속")
    private val ratio = AggTile("", "v1", AggFn.RATIO, "c1", "d", AggPeriod.THIS_WEEK, 80.0, label = "이번 주")

    private fun state(tiles: List<AggTile>, boards: Map<String, ViewBoard?> = mapOf("v1" to board()), items: List<String> = listOf("v1")): LayoutWidgetState {
        var layout = items.fold(WidgetLayout(LayoutMode.SECTIONS)) { l, id -> l.add(id)!! }
        tiles.forEach { layout = layout.addTile(it)!! }
        return LayoutWidgetState(WidgetConfig(1), layout, boards, now, weekStart = DayOfWeek.MONDAY)
    }

    private fun cells(vararg tiles: AggTile) = state(tiles.toList()).tileCells()

    // --- 순수: 계획

    @Test
    fun theHabitGridTakesItsHeightFirstEvenWhenValueTilesWouldNotFitBesideIt() {
        val all = cells(streak, ratio, habitsTile("c1", "c2", "c3"))
        val plan = planTileStrip(all, metrics, wide, room = 160.dp)
        assertEquals(3, plan.habits!!.lines)
        assertTrue("값 타일은 남는 높이가 없어 빠진다", plan.cells.isEmpty())
        assertEquals(plan.habits!!.height, plan.height)
    }

    @Test
    fun valueTilesWhoseBarLinesWouldOverflowTheLeftoverHeightAreDroppedSoTheGridIsNeverClipped() {
        // 남는 높이가 값 타일 한 줄(막대 없음 64dp)은 되지만 막대·글자 줄까지(92dp)는 안 되는 경우
        val room = habitHeight(metrics, 3) + AGG_TILE_GAP + 80.dp
        val plan = planTileStrip(cells(ratio, habitsTile("c1", "c2", "c3")), metrics, wide, room)
        assertTrue(plan.cells.isEmpty())
        assertTrue(plan.height <= room)
    }

    @Test
    fun valueTilesAreAddedAboveTheGridWhenThereIsRoomForThem() {
        val plan = planTileStrip(cells(streak, ratio, habitsTile("c1", "c2")), metrics, large, room = 330.dp)
        assertNotNull(plan.habits)
        assertTrue(plan.cells.isNotEmpty())
        assertEquals(plan.valuesHeight + AGG_TILE_GAP + plan.habits!!.height, plan.height)
    }

    @Test
    fun fewerHabitRowsAreDrawnWhenTheRoomIsShortAndNoneBelowOne() {
        val grid = cells(habitsTile("c1", "c2", "c3"))
        assertEquals(1, planHabits(grid.single(), metrics, large, habitHeight(metrics, 1))!!.lines)
        assertEquals(2, planHabits(grid.single(), metrics, large, habitHeight(metrics, 2) + 2.dp)!!.lines)
        assertNull(planHabits(grid.single(), metrics, large, habitHeight(metrics, 1) - 1.dp))
    }

    @Test
    fun theCompactSizeSkipsTheGridAndTheDonutCanFollowIt() {
        assertNull(planTileStrip(cells(habitsTile("c1")), metrics, compact, room = 400.dp).habits)
        val donut = AggTile("", "v1", AggFn.COUNT, dateProp = "d", kind = TileKind.DONUT, groupProp = "g")
        assertTrue(WidgetLayout(LayoutMode.SECTIONS).addTile(habitsTile("c1"))!!.addTile(donut) != null) // 종류마다 하나씩이라 함께 둘 수 있다
    }

    @Test
    fun aMissingBoardShowsAnAspectNoteCardNotAGrid() {
        val missing = state(listOf(habitsTile("c1")), boards = mapOf("v1" to null), items = emptyList()).tileCells().single()
        assertNull(missing.habits)
        val plan = planHabits(missing, metrics, large, 200.dp)!!
        assertEquals(0, plan.lines)
        assertEquals(metrics.tile, plan.height)
    }

    // --- Glance

    @Test
    fun theGridDrawsSevenCellsPerHabitWithTodayMarkedAndTheCountOnTheRight() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(full)
        provideComposable { LayoutWidgetContent(state(listOf(habitsTile("c1", "c2", "c3"))), systemFontScale = 1f) }
        onNode(hasTestTag("layoutHabitGrid")).assertExists()
        onAllNodes(hasTestTag("layoutHabitRow")).assertCountEquals(3)
        onAllNodes(hasTestTag("layoutHabitCell")).assertCountEquals(18) // 3습관 × 어제까지 6일
        onAllNodes(hasTestTag("layoutHabitToday")).assertCountEquals(3)
        onNode(hasContentDescriptionEqualTo("운동, 오늘, 함")).assertExists()
        onNode(hasContentDescriptionEqualTo("독서, 오늘, 안 함")).assertExists()
        onNode(hasContentDescriptionEqualTo("영어, 오늘, 함")).assertExists()
    }

    @Test
    fun pressingACellRunsTheCheckActionWithTheViewHabitAndDay() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(full)
        provideComposable { LayoutWidgetContent(state(listOf(habitsTile("c1", "c2"))), systemFontScale = 1f) }
        val todayCheck = hasRunCallbackClickAction<HabitCheckAction>(
            actionParametersOf(ViewCellAction.ViewKey to "v1", ViewCellAction.PropKey to "c2", HabitCheckAction.DayKey to today.toEpochDay(), HabitCheckAction.DateKey to "d"),
        )
        onNode(hasContentDescriptionEqualTo("독서, 오늘, 안 함")).assert(todayCheck)
        val yesterday = hasRunCallbackClickAction<HabitCheckAction>(
            actionParametersOf(ViewCellAction.ViewKey to "v1", ViewCellAction.PropKey to "c1", HabitCheckAction.DayKey to today.minusDays(1).toEpochDay(), HabitCheckAction.DateKey to "d"),
        )
        onAllNodes(hasTestTag("layoutHabitCell"))[5].assert(yesterday) // 운동 줄의 마지막 지난날(어제)
    }

    @Test
    fun theGridIsLeftOutOfTheCompactWidget() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(compact)
        provideComposable { LayoutWidgetContent(state(listOf(streak, habitsTile("c1"))), systemFontScale = 1f) }
        onAllNodes(hasTestTag("layoutHabitGrid")).assertCountEquals(0)
        onAllNodes(hasTestTag("layoutTile")).assertCountEquals(1)
    }

    @Test
    fun aMissingViewShowsANote() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(full)
        provideComposable { LayoutWidgetContent(state(listOf(habitsTile("c1")), boards = mapOf("v1" to null), items = emptyList()), systemFontScale = 1f) }
        onNode(hasTestTag("layoutHabitNote")).assertExists()
        onAllNodes(hasTestTag("layoutHabitCell")).assertCountEquals(0)
    }

    @Test
    fun aPartlyReceivedBoardSaysSoOnTheGridHeader() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(full)
        // 100행 상한에 걸려 일부만 받았다: 못 받은 날은 비어 보이니 그렇다고 알린다(눌러서 같은 날 페이지가 또 생기지 않게 사용자가 알 수 있다).
        val partial = mapOf("v1" to board().copy(rowsComplete = false))
        provideComposable { LayoutWidgetContent(state(listOf(habitsTile("c1")), boards = partial), systemFontScale = 1f) }
        onNode(hasTextEqualTo("일부만 계산")).assertExists()
        onAllNodes(hasTestTag("layoutHabitToday")).assertCountEquals(1)
    }

    @Test
    fun aViewThatIsGoneOrUnsupportedSaysSoInsteadOfBlamingTheHabitProperty() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(full)
        val gone = mapOf("v1" to board().copy(lastError = VIEW_ERROR_NOT_FOUND))
        provideComposable { LayoutWidgetContent(state(listOf(habitsTile("c1")), boards = gone, items = emptyList()), systemFontScale = 1f) }
        onNode(hasTestTag("layoutHabitNote")).assertExists()
        onAllNodes(hasTextEqualTo("습관 속성을 찾을 수 없습니다")).assertCountEquals(0)
        onNode(hasTextEqualTo(context.getString(R.string.view_widget_not_found))).assertExists()
    }

    @Test
    fun aTileThatHasNoRoomAtAllShowsAHintInsteadOfABlankWidget() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(compact)
        provideComposable { LayoutWidgetContent(state(listOf(habitsTile("c1")), items = emptyList()), systemFontScale = 1f) }
        onNode(hasTestTag("layoutTilesTooSmall")).assertExists()
    }

    // --- 변환·기준 이미지

    private suspend fun render(size: DpSize, state: LayoutWidgetState, staticRows: Boolean = false) =
        renderRemoteViews(context, size) { LayoutWidgetContent(state, systemFontScale = 1f, staticRows = staticRows) }

    @Test
    fun translatesInEverySizeAndFontScaleWithinTheViewIdBudget() = runTest(timeout = 5.minutes) {
        val failures = mutableListOf<String>()
        for (size in listOf(compact, wide, large, full, DpSize(411.dp, 800.dp))) for (font in listOf(70, 100, 150)) {
            val st = state(listOf(streak, ratio, habitsTile("c1", "c2", "c3"))).let { it.copy(config = WidgetConfig(1, fontScale = font)) }
            runCatching { glanceIdsUsed(render(size, st)) }
                .onSuccess {
                    println("HABIT_GRID ${size.width}x${size.height} font=$font: $it view ids")
                    if (it > GLANCE_VIEW_ID_BUDGET) failures += "${size.width}x${size.height} font=$font uses $it view ids"
                }
                .onFailure { failures += "${size.width}x${size.height} font=$font: ${it.message}" }
        }
        assertTrue(failures.joinToString(System.lineSeparator()), failures.isEmpty())
    }

    private fun recipeState(): LayoutWidgetState {
        val recipeView = RecipeView("v1", "습관", "table", columns)
        val config = Recipes.build(Recipe.HABITS, RecipePicks(viewId = "v1", checkProps = listOf("c1", "c2", "c3")), listOf(recipeView))!!
        return LayoutWidgetState(WidgetConfig(1), WidgetLayout.parse(config.layoutJson), mapOf("v1" to board()), now, weekStart = DayOfWeek.MONDAY)
    }

    @Test
    fun screenshotHabitRecipeWide() = runTest {
        render(wide, recipeState(), staticRows = true).captureRoboImage("src/test/screenshots/layout_habit_grid_4x2.png")
    }

    @Test
    fun screenshotHabitRecipeLarge() = runTest {
        render(large, recipeState(), staticRows = true).captureRoboImage("src/test/screenshots/layout_habit_grid_4x4.png")
    }
}
