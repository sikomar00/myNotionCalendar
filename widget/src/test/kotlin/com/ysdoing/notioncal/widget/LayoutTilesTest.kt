package com.ysdoing.notioncal.widget

import android.app.Activity
import android.content.Context
import android.widget.FrameLayout
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import androidx.glance.appwidget.testing.unit.hasStartActivityClickAction
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.hasContentDescriptionEqualTo
import androidx.glance.testing.unit.hasTestTag
import androidx.glance.testing.unit.hasTextEqualTo
import androidx.test.core.app.ApplicationProvider
import com.ysdoing.notioncal.core.data.WidgetConfig
import com.ysdoing.notioncal.core.data.db.VIEW_ERROR_NOT_FOUND
import com.ysdoing.notioncal.core.data.db.VIEW_ERROR_UNSUPPORTED
import com.ysdoing.notioncal.core.data.view.AggFn
import com.ysdoing.notioncal.core.data.view.AggPeriod
import com.ysdoing.notioncal.core.data.view.AggTile
import com.ysdoing.notioncal.core.data.view.LayoutMode
import com.ysdoing.notioncal.core.data.view.ViewBoard
import com.ysdoing.notioncal.core.data.view.ViewColumn
import com.ysdoing.notioncal.core.data.view.ViewRow
import com.ysdoing.notioncal.core.data.view.WidgetLayout
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.DayOfWeek
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Phase 11e AG-01·02 집계 타일 줄: 크기 단계별 타일 수(2×2=1·4×2=2·4×4=4·전체=6), 값·목표 글자·'일부만 계산' 표시, 누르면 뷰어,
 * 섹션 위에 끼워 넣기, RemoteViews 변환(자식 10개 제한)을 본다. 오늘은 2027-01-15(금).
 */
@OptIn(ExperimentalGlanceRemoteViewsApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w420dp-h900dp-xxhdpi")
class LayoutTilesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val now = 1_800_000_000_000L // 2027-01-15 17:00 KST
    private val amount = ViewColumn("n", "금액", "number")
    private val done = ViewColumn("c", "완료", "checkbox")
    private val date = ViewColumn("d", "날짜", "date")
    private val metrics = viewMetrics(1f, 1f)

    private fun row(id: String, number: Double, on: String = "2027-01-15", checked: Boolean = true): ViewRow {
        val typed: Map<String, JsonElement> = mapOf(
            "n" to JsonPrimitive(number), "c" to JsonPrimitive(checked), "d" to JsonObject(mapOf("start" to JsonPrimitive(on))),
        )
        return ViewRow(id, "", listOf("행 $id"), typed = typed)
    }

    private fun board(viewId: String = "v1", name: String = "가계", complete: Boolean = true, rows: List<ViewRow> = listOf(row("a", 500.0), row("b", 1000.0))) =
        ViewBoard(viewId, name, "table", listOf("이름"), rows, now, null, columnInfo = listOf(amount, done, date), rowsComplete = complete)

    private fun tile(fn: AggFn = AggFn.SUM, label: String = "", goal: Double? = null, period: AggPeriod = AggPeriod.ALL, view: String = "v1") =
        AggTile("", view, fn, propId = if (fn == AggFn.COUNT) "" else if (fn == AggFn.RATIO || fn == AggFn.STREAK) "c" else "n", dateProp = if (period == AggPeriod.ALL && fn != AggFn.STREAK) "" else "d", period = period, goal = goal, label = label)

    private fun state(tiles: List<AggTile>, boards: Map<String, ViewBoard?> = mapOf("v1" to board()), items: List<String> = listOf("v1"), fontScale: Int = 100): LayoutWidgetState {
        var layout = items.fold(WidgetLayout(LayoutMode.SECTIONS)) { l, id -> l.add(id)!! }
        tiles.forEach { layout = layout.addTile(it)!! }
        return LayoutWidgetState(WidgetConfig(1, fontScale = fontScale), layout, boards, now, weekStart = DayOfWeek.MONDAY)
    }

    @Test
    fun aColoredTileGetsADotBeforeItsLabelAndAPlainOneDoesNot() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(DpSize(501.dp, 581.dp))
        provideComposable { LayoutWidgetContent(state(listOf(tile(label = "식비").copy(color = "blue"), tile(label = "교통"))), systemFontScale = 1f) }
        onAllNodes(hasTestTag("layoutTile")).assertCountEquals(2)
        onAllNodes(hasTestTag("tileColor")).assertCountEquals(1)
        onNode(hasTextEqualTo("식비")).assertExists() // 라벨 글자는 그대로 있다(색만으로 알리지 않는다)
        onNode(hasTextEqualTo("교통")).assertExists()
    }

    // --- 순수: 타일 격자

    @Test
    fun tileCountFollowsTheSizeStepAndNeverExceedsWhatFits() {
        assertEquals(1, tileGrid(234.dp, SizeStep.COMPACT, count = 6, rowsFit = 3).visible) // 2×2
        assertEquals(2, tileGrid(501.dp, SizeStep.WIDE, count = 6, rowsFit = 1).visible) // 4×2
        assertEquals(4, tileGrid(501.dp, SizeStep.LARGE, count = 6, rowsFit = 3).visible) // 4×4
        assertEquals(6, tileGrid(501.dp, SizeStep.FULL, count = 6, rowsFit = 3).visible) // 전체
        assertEquals(3, tileGrid(501.dp, SizeStep.FULL, count = 3, rowsFit = 3).visible) // 타일이 적으면 그만큼
        assertEquals(3, tileGrid(501.dp, SizeStep.FULL, count = 6, rowsFit = 1).visible) // 3열 × 1줄
        assertEquals(0, tileGrid(501.dp, SizeStep.FULL, count = 0, rowsFit = 3).visible)
    }

    @Test
    fun columnsFollowTheWidthAndFourTilesFormATwoByTwo() {
        assertEquals(1, tileGrid(234.dp, SizeStep.COMPACT, 6, 3).columns)
        assertEquals(2, tileGrid(360.dp, SizeStep.FULL, 6, 3).columns) // 6개 → 2열 3줄
        assertEquals(3, tileGrid(360.dp, SizeStep.FULL, 6, 3).rows)
        assertEquals(3, tileGrid(501.dp, SizeStep.FULL, 6, 3).columns) // 6개 → 3열 2줄
        assertEquals(2, tileGrid(501.dp, SizeStep.FULL, 6, 3).rows)
        assertEquals(2, tileGrid(501.dp, SizeStep.LARGE, 6, 3).columns) // 4개는 2×2
        assertEquals(2, tileGrid(501.dp, SizeStep.LARGE, 6, 3).rows)
        assertEquals(2, tileGrid(501.dp, SizeStep.WIDE, 6, 1).columns) // 4×2: 두 개가 한 줄
    }

    @Test
    fun theStripNeverTakesMoreThanTheRoomItIsGiven() {
        val m = metrics
        val plan = planTileStrip(List(6) { TileCell(tile(), AggResultSample.plain) }, m, DpSize(501.dp, 581.dp), room = 100.dp)
        assertTrue("${plan.height} ≤ 100dp", plan.height <= 100.dp || plan.grid.rows == 1)
        assertEquals(tileHeight(m, extras = false), plan.height) // 한 줄은 늘 둔다
        val none = planTileStrip(emptyList(), m, DpSize(501.dp, 581.dp), room = 300.dp)
        assertEquals(0.dp, none.height)
    }

    @Test
    fun extraLinesMakeTheTilesTaller() {
        assertTrue(tileHeight(metrics, extras = true) > tileHeight(metrics, extras = false))
        val big = viewMetrics(1.5f, 1.3f)
        assertTrue(tileHeight(big, extras = false) > tileHeight(metrics, extras = false)) // 글꼴이 커지면 높이도 큰다
    }

    // --- 라벨

    @Test
    fun theDefaultLabelNamesWhatIsCountedAndWhen() {
        val b = board()
        assertEquals("식비", tileLabel(context, tile(label = "식비"), b))
        assertEquals("금액 합계", tileLabel(context, tile(AggFn.SUM), b))
        assertEquals("가계 개수", tileLabel(context, tile(AggFn.COUNT), b))
        assertEquals("이번 주 금액 합계", tileLabel(context, tile(AggFn.SUM, period = AggPeriod.THIS_WEEK), b))
        assertEquals("오늘 완료 비율", tileLabel(context, tile(AggFn.RATIO, period = AggPeriod.TODAY), b))
        assertEquals("완료 연속 달성", tileLabel(context, tile(AggFn.STREAK), b))
        assertEquals("속성 합계", tileLabel(context, tile(AggFn.SUM).copy(propId = "zzz"), b)) // 사라진 속성
        assertEquals("보기 개수", tileLabel(context, tile(AggFn.COUNT), null)) // 사라진 보기
    }

    // --- Glance

    @Test
    fun aTileShowsItsLabelAndValueAndOpensTheViewerOnTap() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(DpSize(501.dp, 581.dp))
        provideComposable { LayoutWidgetContent(state(listOf(tile(label = "식비"))), systemFontScale = 1f) }
        onNode(hasTextEqualTo("식비")).assertExists()
        onNode(hasTextEqualTo("1,500")).assertExists()
        onNode(hasTestTag("layoutTile")).assert(hasStartActivityClickAction(viewerIntent(context, "v1")))
        onNode(hasTestTag("layoutTile")).assert(hasContentDescriptionEqualTo("식비 1,500, 눌러서 보기 열기"))
        onNode(hasTestTag("tileProgress")).assertDoesNotExist() // 목표가 없으면 막대도 없다
    }

    @Test
    fun aGoalDrawsABarAndSaysPercentOrOverInWords() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(DpSize(501.dp, 581.dp))
        provideComposable { LayoutWidgetContent(state(listOf(tile(label = "식비", goal = 3000.0), tile(label = "저축", goal = 1000.0))), systemFontScale = 1f) }
        onAllNodes(hasTestTag("tileProgress")).assertCountEquals(2)
        onNode(hasTextEqualTo("목표 50%")).assertExists()
        onNode(hasTextEqualTo("목표 50% 초과")).assertExists()
        onNode(hasContentDescriptionEqualTo("저축 1,500, 목표 50% 초과, 눌러서 보기 열기")).assertExists()
    }

    @Test
    fun aPartialValueSaysSoInWords() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(DpSize(501.dp, 581.dp))
        provideComposable { LayoutWidgetContent(state(listOf(tile()), mapOf("v1" to board(complete = false))), systemFontScale = 1f) }
        onNode(hasTextEqualTo("일부만 계산")).assertExists()
    }

    @Test
    fun aTileWhoseViewIsGoneOrHasNoValueExplainsWhy() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(DpSize(501.dp, 581.dp))
        val gone = tile(label = "사라짐", view = "v9")
        val empty = tile(AggFn.AVG, label = "빈 평균")
        provideComposable { LayoutWidgetContent(state(listOf(gone, empty), mapOf("v1" to board(rows = emptyList()))), systemFontScale = 1f) }
        onNode(hasTextEqualTo(context.getString(R.string.layout_tile_missing))).assertExists()
        onNode(hasTextEqualTo(context.getString(R.string.layout_tile_empty))).assertExists()
        onAllNodes(hasTextEqualTo("—")).assertCountEquals(2)
    }

    private fun tilesAt(size: DpSize, expected: Int) = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(size)
        provideComposable { LayoutWidgetContent(state(List(6) { tile(AggFn.COUNT, label = "개수 $it") }), systemFontScale = 1f) }
        onAllNodes(hasTestTag("layoutTile")).assertCountEquals(expected)
    }

    @Test
    fun tileCountIsOneOnATwoByTwo() = tilesAt(DpSize(234.dp, 213.dp), 1)

    @Test
    fun tileCountIsTwoOnAFourByTwo() = tilesAt(DpSize(501.dp, 213.dp), 2)

    @Test
    fun tileCountIsFourOnAFourByFour() = tilesAt(DpSize(501.dp, 458.dp), 4)

    @Test
    fun tileCountIsSixOnAFullSizeWidget() = tilesAt(DpSize(501.dp, 581.dp), 6)

    @Test
    fun tilesAloneFillTheWidgetWithoutTheEmptyNote() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(DpSize(501.dp, 581.dp))
        provideComposable { LayoutWidgetContent(state(listOf(tile(label = "식비")), items = emptyList()), systemFontScale = 1f) }
        onNode(hasTestTag("layoutTile")).assertExists()
        onNode(hasTestTag("layoutNote")).assertDoesNotExist()
        onAllNodes(hasTestTag("viewHeader")).assertCountEquals(0)
    }

    @Test
    fun theTileRowSitsAboveTheViewSections() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(DpSize(501.dp, 581.dp))
        provideComposable { LayoutWidgetContent(state(listOf(tile(label = "식비")), items = listOf("v1")), systemFontScale = 1f) }
        onNode(hasTestTag("layoutTile")).assertExists()
        onAllNodes(hasTestTag("viewHeader")).assertCountEquals(1)
    }

    @Test
    fun tilesAreNotDrawnOutsideSectionsMode() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(DpSize(501.dp, 581.dp))
        val sections = state(listOf(tile(label = "식비")))
        val tabs = sections.copy(layout = sections.layout.withMode(LayoutMode.TABS))
        provideComposable { LayoutWidgetContent(tabs, systemFontScale = 1f) }
        onNode(hasTestTag("layoutTile")).assertDoesNotExist()
    }

    // --- RemoteViews 변환(PRD §13)

    @Test
    fun tilesTranslateToRemoteViewsInEverySizeAndFont() = runTest(timeout = 4.minutes) {
        val sizes = listOf(DpSize(110.dp, 110.dp), DpSize(234.dp, 213.dp), DpSize(360.dp, 300.dp), DpSize(501.dp, 581.dp))
        val all = listOf(tile(AggFn.SUM, goal = 100.0), tile(AggFn.COUNT), tile(AggFn.AVG), tile(AggFn.RATIO, goal = 50.0, period = AggPeriod.THIS_WEEK), tile(AggFn.STREAK, label = "아주 긴 이름의 타일입니다 정말 길어요"), tile(AggFn.MAX, view = "v9"))
        for (size in sizes) for (font in listOf(70, 150)) for (items in listOf(emptyList(), listOf("v1"), listOf("v1", "v2", "v3"))) {
            val s = state(all, mapOf("v1" to board(), "v2" to board("v2", "독서", complete = false), "v3" to board("v3", "운동")), items, font)
            val remoteViews = GlanceRemoteViews().compose(context, size) { LayoutWidgetContent(s, systemFontScale = 1.3f) }.remoteViews
            val activity = Robolectric.buildActivity(Activity::class.java).create().get()
            remoteViews.apply(activity, FrameLayout(activity))
        }
    }

    @Test
    fun sixTilesAndThreeDenseSectionsStayFarBelowTheBinderLimit() = runTest(timeout = 3.minutes) {
        val dense = (1..50).map { row("r$it", it.toDouble()) }
        val boards = mapOf("v1" to board(rows = dense), "v2" to board("v2", rows = dense), "v3" to board("v3", rows = dense))
        val s = state(List(6) { tile(AggFn.SUM, goal = 100.0) }, boards, listOf("v1", "v2", "v3"))
        for (size in listOf(DpSize(501.dp, 581.dp), DpSize(411.dp, 800.dp))) {
            val remoteViews = GlanceRemoteViews().compose(context, size) { LayoutWidgetContent(s, systemFontScale = 1f) }.remoteViews
            val parcel = android.os.Parcel.obtain()
            remoteViews.writeToParcel(parcel, 0)
            val bytes = parcel.dataSize()
            parcel.recycle()
            println("TILES_PARCEL ${size.width}x${size.height} = $bytes bytes")
            assertTrue("$size RemoteViews $bytes bytes", bytes < 400_000)
        }
    }

    @Test
    fun theSettingsPreviewComputesTilesFromTheRealBoardsAndLeavesUnloadedOnesEmpty() {
        val layout = WidgetLayout(LayoutMode.SECTIONS).add("v1")!!.addTile(tile(label = "합"))!!.addTile(tile(label = "없음", view = "v9"))!!
        val config = WidgetConfig(0, layoutJson = WidgetLayout.encode(layout))
        val preview = WidgetPreviews.layoutPreview(config, mapOf("v1" to "가계"), now, boards = mapOf("v1" to board()), weekStart = DayOfWeek.MONDAY)
        val cells = preview.tileCells()
        assertEquals("1,500", cells[0].result.text)
        assertEquals("—", cells[1].result.text) // 아직 읽지 못한 보기
        assertEquals(DayOfWeek.MONDAY, preview.weekStart)
        assertEquals("—", WidgetPreviews.layoutPreview(config, emptyMap(), now).tileCells()[0].result.text) // 실제 행이 없으면 예시 행에는 속성이 없어 값 없음
    }

    @Test
    fun aHiddenTilesGoalDoesNotMakeTheVisibleTilesTaller() {
        val plain = TileCell(tile(), AggResultSample.plain)
        val goal = TileCell(tile(goal = 100.0), com.ysdoing.notioncal.core.data.view.AggResult(50.0, "50", false, 0.5f, "목표 50%"))
        val plan = planTileStrip(listOf(plain, goal), metrics, DpSize(234.dp, 213.dp), room = 200.dp) // 2×2는 한 개만 그린다
        assertEquals(1, plan.grid.visible)
        assertFalse(plan.extras)
        assertEquals(tileHeight(metrics, extras = false), plan.height)
        assertTrue(planTileStrip(listOf(goal, plain), metrics, DpSize(234.dp, 213.dp), room = 200.dp).extras) // 보이는 쪽에 목표가 있으면 늘어난다
    }

    @Test
    fun viewHasStatusAgreesWithTheStatusTextForEveryBoardHealth() {
        val boards = listOf(
            board(), board().copy(syncedAt = 0L), board().copy(lastError = "boom"), board().copy(lastError = VIEW_ERROR_NOT_FOUND),
            board().copy(lastError = VIEW_ERROR_UNSUPPORTED), board().copy(syncedAt = now - 13 * 60 * 60 * 1000L),
        )
        boards.forEach { assertEquals(viewStatusText(context, it, now) != null, viewHasStatus(it, now)) }
        assertFalse(viewHasStatus(boards[0], now))
    }

    @Test
    fun aTileOnAnUnsyncedViewSaysSoAndOnADeletedViewHasNoValue() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(DpSize(501.dp, 581.dp))
        val unsynced = board("v1").copy(syncedAt = 0L)
        val deleted = board("v2").copy(lastError = VIEW_ERROR_NOT_FOUND)
        provideComposable { LayoutWidgetContent(state(listOf(tile(AggFn.COUNT, label = "처음"), tile(AggFn.SUM, label = "삭제", view = "v2")), mapOf("v1" to unsynced, "v2" to deleted), items = emptyList()), systemFontScale = 1f) }  // 칸 없이 타일만(칸의 상태 줄과 겹치지 않게)
        onNode(hasTextEqualTo(context.getString(R.string.view_widget_not_synced))).assertExists()
        onNode(hasTextEqualTo(context.getString(R.string.view_widget_not_found))).assertExists()
        onAllNodes(hasTextEqualTo("—")).assertCountEquals(1) // 삭제된 보기는 마지막 값이 아니라 값 없음
    }

    @Test
    fun aSmallWidgetLetsTheTileRowTakeTheRoomInsteadOfSqueezingASectionToASliver() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(DpSize(234.dp, 213.dp)) // 삼성 2×2: 타일 줄(막대 포함 약 88dp) + 섹션 최소 높이가 한꺼번에 안 든다
        provideComposable { LayoutWidgetContent(state(listOf(tile(goal = 3000.0)), items = listOf("v1")), systemFontScale = 1f) }
        onAllNodes(hasTestTag("layoutTile")).assertCountEquals(1)
        onAllNodes(hasTestTag("viewHeader")).assertCountEquals(0) // 읽을 수 없는 조각 대신 섹션은 뺀다
    }

    @Test
    fun aTallEnoughWidgetShowsTheTileRowAndTheSection() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(DpSize(501.dp, 458.dp))
        provideComposable { LayoutWidgetContent(state(listOf(tile(goal = 3000.0)), items = listOf("v1")), systemFontScale = 1f) }
        onAllNodes(hasTestTag("layoutTile")).assertCountEquals(1)
        onAllNodes(hasTestTag("viewHeader")).assertCountEquals(1)
    }

    @Test
    fun theCellsOfAStateAreComputedFromTheirOwnViewsAndAnUnknownViewHasNoValue() {
        val s = state(listOf(tile(label = "합"), tile(label = "없는 보기", view = "v9")))
        val cells = s.tileCells()
        assertEquals("1,500", cells[0].result.text)
        assertEquals("—", cells[1].result.text)
        assertFalse(cells[1].result.partial)
        assertEquals(DayOfWeek.MONDAY, s.weekStart)
    }
}

private object AggResultSample {
    val plain = com.ysdoing.notioncal.core.data.view.AggResult(1.0, "1", false, null, null)
}
