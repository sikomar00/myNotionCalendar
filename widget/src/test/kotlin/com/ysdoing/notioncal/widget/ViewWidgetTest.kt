package com.ysdoing.notioncal.widget

import com.ysdoing.notioncal.core.data.AppIntents
import com.ysdoing.notioncal.core.data.view.ViewColumn
import com.ysdoing.notioncal.core.data.view.ViewChoice
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import androidx.glance.appwidget.testing.unit.hasRunCallbackClickAction
import androidx.glance.appwidget.testing.unit.hasStartActivityClickAction
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.hasClickAction
import androidx.glance.testing.unit.hasContentDescriptionEqualTo
import androidx.glance.testing.unit.hasTestTag
import androidx.glance.testing.unit.hasTextEqualTo
import androidx.test.core.app.ApplicationProvider
import com.ysdoing.notioncal.core.data.WidgetConfig
import com.ysdoing.notioncal.core.data.db.VIEW_ERROR_NOT_FOUND
import com.ysdoing.notioncal.core.data.db.VIEW_ERROR_UNSUPPORTED
import com.ysdoing.notioncal.core.data.view.ViewBoard
import com.ysdoing.notioncal.core.data.view.ViewPreset
import com.ysdoing.notioncal.core.data.view.ViewPresets
import com.ysdoing.notioncal.core.data.view.ViewRow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * VW-03 넓으면 표(열 머리 + 행)·좁으면 목록(제목 + 둘째 줄), 행 탭 → 노션 페이지, 머리 탭 → 새로고침.
 * VW-06 고르지 않음·보기 없음·빈 보기·실패·오래됨 상태를 글자로 알린다.
 */
@OptIn(ExperimentalGlanceRemoteViewsApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w420dp-h900dp-xxhdpi")
class ViewWidgetTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val now = 1_800_000_000_000L
    private val wide = DpSize(320.dp, 200.dp)
    private val narrow = DpSize(150.dp, 200.dp)

    private fun board(rows: List<ViewRow> = listOf(ViewRow("p1", "https://notion.so/p1", listOf("장보기", "집", "10/21"))), syncedAt: Long = now, error: String? = null) =
        ViewBoard("v1", "할 일", "table", listOf("이름", "분류", "날짜"), rows, syncedAt, error)

    private fun state(board: ViewBoard?, viewId: String? = "v1") = ViewWidgetState(board, WidgetConfig(1, viewId = viewId), now)

    @Test
    fun wideDrawsTableWithColumnHeaders() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(wide)
        provideComposable { ViewWidgetContent(state(board()), systemFontScale = 1f) }
        listOf("이름", "분류", "날짜", "장보기", "집", "10/21").forEach { onNode(hasTextEqualTo(it)).assertExists() }
    }

    @Test
    fun narrowDrawsTitleWithSecondLineOnly() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(narrow)
        provideComposable { ViewWidgetContent(state(board()), systemFontScale = 1f) }
        onNode(hasTextEqualTo("장보기")).assertExists()
        onNode(hasTextEqualTo("집")).assertExists()
        onNode(hasTextEqualTo("이름")).assertDoesNotExist()
        onNode(hasTextEqualTo("10/21")).assertDoesNotExist()
    }

    @Test
    fun rowOpensTheEditSheetAndHeaderRefreshes() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(wide)
        provideComposable { ViewWidgetContent(state(board()), systemFontScale = 1f) }
        onNode(hasTestTag("viewRow")).assert(hasStartActivityClickAction(viewRowIntent(context, "v1", "p1"))) // ED-01: 행을 누르면 편집 시트
        onNode(hasTestTag("viewHeader")).assert(hasRunCallbackClickAction<RefreshAction>(actionParametersOf()))
    }

    @Test
    fun aRowWithoutAnAddressCanStillBeEdited() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(wide)
        provideComposable { ViewWidgetContent(state(board(listOf(ViewRow("p9", "", listOf("주소 없음", "집", "10/21"))))), systemFontScale = 1f) }
        onNode(hasTestTag("viewRow")).assert(hasStartActivityClickAction(viewRowIntent(context, "v1", "p9")))
    }

    @Test
    fun theRowIntentNamesTheViewAndThePageAndDiffersPerRow() {
        val one = viewRowIntent(context, "v1", "p1")
        assertEquals(AppIntents.ACTION_VIEW_ROW, one.action)
        assertEquals(context.packageName, one.`package`)
        assertEquals(listOf("v1", "p1"), listOf(one.getStringExtra(AppIntents.EXTRA_VIEW_ID), one.getStringExtra(AppIntents.EXTRA_PAGE_ID)))
        // PendingIntent는 extras를 보지 않는다. 행마다 data URI가 달라야 위젯의 행끼리 서로 덮지 않는다.
        assertFalse(one.filterEquals(viewRowIntent(context, "v1", "p2")))
        assertFalse(one.filterEquals(viewRowIntent(context, "v2", "p1")))
    }

    @Test
    fun boardCardsOpenTheEditSheetToo() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(large)
        provideComposable { ViewWidgetContent(boardView(3, 0, "o1", "o2"), systemFontScale = 1f) }
        onAllNodes(hasTestTag("boardCard"))[0].assert(hasStartActivityClickAction(viewRowIntent(context, "v1", "p0")))
    }

    @Test
    fun statesAreSpelledOutInText() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(wide)
        provideComposable { ViewWidgetContent(state(null, viewId = null), systemFontScale = 1f) }
        onNode(hasTextEqualTo(context.getString(R.string.view_widget_choose))).assertExists()
    }

    @Test
    fun missingEmptyFailedAndStaleBoards() {
        // 보기를 지웠거나 노션에서 사라짐: 설정은 있고 스냅샷이 없다.
        assertEquals(null, state(null).board)
        // 상태 한 줄: 정상이면 없음, 못 받음·실패·12시간 지남이면 글자로 알린다.
        assertNull(viewStatusText(context, board(), now))
        assertEquals(context.getString(R.string.view_widget_not_synced), viewStatusText(context, board(syncedAt = 0), now))
        val failed = viewStatusText(context, board(error = "Network"), now)!!
        assertEquals(true, failed.startsWith("동기화 실패"))
        val stale = viewStatusText(context, board(syncedAt = now - 13 * 60 * 60 * 1000L), now)!!
        assertEquals(true, stale.startsWith("오래됨"))
    }

    @Test
    fun goneViewTellsInTextAndTapOpensWidgetSettings() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        // VW-06: 노션에서 보기가 지워졌거나(404) 권한이 없으면 글자로 알리고, 눌러서 위젯 설정을 연다(다시 고르기).
        setContext(context)
        setAppWidgetSize(wide)
        val gone = board(error = VIEW_ERROR_NOT_FOUND)
        provideComposable { ViewWidgetContent(state(gone), systemFontScale = 1f) }
        assertTrue(viewStatusText(context, gone, now)!!.startsWith("노션에서 보기를 찾을 수 없음"))
        onNode(hasTestTag("viewStatus")).assert(hasStartActivityClickAction(viewConfigIntent(context, 1)))
    }

    @Test
    fun unsupportedViewTellsInTextAndTapOpensWidgetSettings() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        // SY-07: 노션이 보기 설정을 읽을 수 없는 보기(400)는 일시 오류가 아니라, 글자로 알리고 눌러서 다른 보기를 고르게 한다.
        setContext(context)
        setAppWidgetSize(wide)
        val unsupported = board(error = VIEW_ERROR_UNSUPPORTED)
        provideComposable { ViewWidgetContent(state(unsupported), systemFontScale = 1f) }
        assertTrue(viewStatusText(context, unsupported, now)!!.startsWith("이 보기는 앱이 읽을 수 없음"))
        onNode(hasTestTag("viewStatus")).assert(hasStartActivityClickAction(viewConfigIntent(context, 1)))
    }

    @Test
    fun transientFailureStaysARefreshHeaderNotASettingsLink() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(wide)
        provideComposable { ViewWidgetContent(state(board(error = "Network")), systemFontScale = 1f) }
        onNode(hasTestTag("viewStatus")).assertExists()
        onNode(hasTestTag("viewStatus")).assert(!hasClickAction())
    }

    @Test
    fun notChosenViewOpensWidgetSettings() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(wide)
        provideComposable { ViewWidgetContent(state(null, viewId = null), systemFontScale = 1f) }
        onNode(hasTestTag("viewNote")).assert(hasStartActivityClickAction(viewConfigIntent(context, 1)))
    }

    @Test
    fun unregisteredViewOpensWidgetSettings() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(wide)
        provideComposable { ViewWidgetContent(state(null), systemFontScale = 1f) }
        onNode(hasTestTag("viewNote")).assert(hasStartActivityClickAction(viewConfigIntent(context, 1)))
    }

    @Test
    fun headerNamesTheViewForScreenReaders() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        // 접근성 설명이 글자를 대신하므로 '새로고침'만 읽히면 어느 보기인지 알 수 없다.
        setContext(context)
        setAppWidgetSize(wide)
        provideComposable { ViewWidgetContent(state(board()), systemFontScale = 1f) }
        onNode(hasTestTag("viewHeader")).assert(hasContentDescriptionEqualTo("할 일, 새로고침"))
    }

    @Test
    fun untitledViewShowsTheDefaultLabelInTheHeader() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(wide)
        provideComposable { ViewWidgetContent(state(board().copy(name = "")), systemFontScale = 1f) }
        onNode(hasTextEqualTo(context.getString(R.string.view_widget_label))).assertExists()
        onNode(hasTestTag("viewHeader")).assert(hasContentDescriptionEqualTo("${context.getString(R.string.view_widget_label)}, 새로고침"))
    }

    private fun fiftyRows() = (1..50).map { ViewRow("p$it", "https://notion.so/p$it", listOf("할 일 $it", "집", "10/21")) }

    @Test
    fun fiftyRowsAreCappedByTheDensityTableAndAllShownRowsAreTappable() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        // Phase 11a: 50행이어도 단계의 행 상한(4×4 단계는 8행)까지만 그리고 나머지는 '+42 더 보기'로 뷰어에서 본다.
        setContext(context)
        setAppWidgetSize(large)
        provideComposable { ViewWidgetContent(state(board(fiftyRows())), systemFontScale = 1f) }
        onAllNodes(hasTestTag("viewRow")).assertCountEquals(8)
        onAllNodes(hasTestTag("viewRow")).assertAll(hasClickAction())
        onNode(hasTextEqualTo("+42 더 보기")).assertExists()
    }

    // --- Phase 11c IX-02·03: 선택지 4개 이상 select는 선택 시트, 상태는 순환, 숫자 열은 − + 단계 버튼.

    private fun optionsBoard(type: String, count: Int) = ViewBoard(
        "v1", "분류", "table", listOf("이름", "분류"),
        listOf(ViewRow("p1", "https://notion.so/p1", listOf("장보기", "옵션1"), typed = mapOf("c" to kotlinx.serialization.json.JsonPrimitive("o1")))),
        now, null, listOf(ViewColumn("t", "이름", "title"), ViewColumn("c", "분류", type, (1..count).map { ViewChoice("o$it", "옵션$it") })),
    )

    @Test
    fun aSelectWithFourOrMoreOptionsOpensTheChooserInsteadOfCycling() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(wide)
        provideComposable { ViewWidgetContent(state(optionsBoard("select", 4)), systemFontScale = 1f) }
        onNode(hasTestTag("viewCellTap")).assert(hasStartActivityClickAction(viewChoiceIntent(context, "v1", "p1", "c")))
        onNode(hasContentDescriptionEqualTo("장보기, 분류 옵션1, 눌러서 고르기")).assertExists()
    }

    @Test
    fun aSelectWithThreeOptionsAndAStatusWithManyStillCycle() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(wide)
        provideComposable { ViewWidgetContent(state(optionsBoard("select", 3)), systemFontScale = 1f) }
        onNode(hasTestTag("viewCellTap")).assert(hasRunCallbackClickAction<ViewCellAction>(tap("c", "select", "p1")))
    }

    @Test
    fun aStatusCyclesInGroupOrderNoMatterHowManyOptions() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(wide)
        provideComposable { ViewWidgetContent(state(optionsBoard("status", 6)), systemFontScale = 1f) }
        onNode(hasTestTag("viewCellTap")).assert(hasRunCallbackClickAction<ViewCellAction>(tap("c", "status", "p1"))) // 상태는 시트 없이 순환
    }

    private fun numberBoard() = ViewBoard(
        "v1", "책", "table", listOf("이름", "쪽수"), listOf(ViewRow("p1", "u", listOf("데미안", "12"), typed = mapOf("pg" to kotlinx.serialization.json.JsonPrimitive(12.0)))),
        now, null, listOf(ViewColumn("t", "이름", "title"), ViewColumn("pg", "쪽수", "number")),
    )

    private fun step(delta: Double) = actionParametersOf(
        ViewCellAction.ViewKey to "v1", ViewCellAction.PageKey to "p1", ViewCellAction.PropKey to "pg", NumberStepAction.DeltaKey to delta,
    )

    @Test
    fun aNumberCellGetsMinusAndPlusButtonsThatMoveByTheWidgetStep() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(wide)
        provideComposable { ViewWidgetContent(ViewWidgetState(numberBoard(), WidgetConfig(1, viewId = "v1", numberStep = 5.0), now), systemFontScale = 1f) }
        onNode(hasTestTag("numberPlus")).assert(hasRunCallbackClickAction<NumberStepAction>(step(5.0)))
        onNode(hasTestTag("numberMinus")).assert(hasRunCallbackClickAction<NumberStepAction>(step(-5.0)))
        onNode(hasTextEqualTo("12")).assertExists() // 값은 그대로 보인다
        onNode(hasContentDescriptionEqualTo("데미안, 쪽수 12, 5 늘리기")).assertExists()
        onNode(hasContentDescriptionEqualTo("데미안, 쪽수 12, 5 줄이기")).assertExists()
    }

    @Test
    fun noStepButtonsOnTheSmallestWidget() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(narrow)
        provideComposable { ViewWidgetContent(state(numberBoard()), systemFontScale = 1f) }
        onAllNodes(hasTestTag("numberPlus")).assertCountEquals(0) // 2×2는 자리가 없다. 행을 눌러 시트에서 고친다
        onAllNodes(hasTestTag("numberMinus")).assertCountEquals(0)
    }

    @Test
    fun theChooserIntentNamesTheViewPageAndPropertyAndDiffersPerCell() {
        val one = viewChoiceIntent(context, "v1", "p1", "c")
        assertEquals(AppIntents.ACTION_VIEW_CHOICE, one.action)
        assertEquals(listOf("v1", "p1", "c"), listOf(one.getStringExtra(AppIntents.EXTRA_VIEW_ID), one.getStringExtra(AppIntents.EXTRA_PAGE_ID), one.getStringExtra(AppIntents.EXTRA_PROP_ID)))
        assertFalse(one.filterEquals(viewChoiceIntent(context, "v1", "p1", "d")))
        assertFalse(one.filterEquals(viewChoiceIntent(context, "v1", "p2", "c")))
    }

    // --- Phase 11c IX-06: 바꿔서 보기 필터에서 빠지는 행은 흐림 + 취소선으로 그린다. 바꾸지 않은 행·판정 불가는 그대로다.

    private fun leavingBoard(filter: String, pending: Set<String>) = ViewBoard(
        "v1", "할 일", "table", listOf("이름", "완료"),
        listOf(
            ViewRow("p1", "u1", listOf("장보기", "✓"), typed = mapOf("dn" to kotlinx.serialization.json.JsonPrimitive(true))),
            ViewRow("p2", "u2", listOf("보고서", "✓"), typed = mapOf("dn" to kotlinx.serialization.json.JsonPrimitive(true))),
        ),
        now, null, listOf(ViewColumn("t", "이름", "title"), doneColumn), filterJson = filter, pendingPageIds = pending,
    )

    @Test
    fun aRowThatLeftTheFilterAfterAChangeIsDrawnAsLeaving() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(wide)
        provideComposable { ViewWidgetContent(state(leavingBoard("""{"property":"dn","checkbox":{"equals":false}}""", setOf("p1"))), systemFontScale = 1f) }
        onAllNodes(hasTestTag("viewRowLeaving")).assertCountEquals(1) // 방금 바꾼 p1만. p2도 필터에 안 맞지만 바꾸지 않았다
        onAllNodes(hasTestTag("viewRow")).assertCountEquals(1)
    }

    @Test
    fun noRowIsLeavingWhenTheFilterIsMetOrCannotBeJudged() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(wide)
        provideComposable { ViewWidgetContent(state(leavingBoard("""{"property":"dn","checkbox":{"equals":true}}""", setOf("p1", "p2"))), systemFontScale = 1f) }
        onAllNodes(hasTestTag("viewRowLeaving")).assertCountEquals(0)
    }

    // --- Phase 11c 프리셋 줄(IX-04·AD-05): 눌러서 값 묶음으로 새 항목을 만든다(4초 지연, 되돌릴 수 있다).

    private fun presetState(vararg presets: ViewPreset, board: ViewBoard = todoBoard()) =
        ViewWidgetState(board, WidgetConfig(1, viewId = "v1", presetsJson = ViewPresets.encode(presets.toList())), now)

    @Test
    fun aPresetLineShowsEachPresetAndTappingAddsThatItem() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(wide)
        provideComposable { ViewWidgetContent(presetState(ViewPreset("점심"), ViewPreset("커피")), systemFontScale = 1f) }
        onAllNodes(hasTestTag("viewPreset")).assertCountEquals(2)
        onNode(hasTextEqualTo("점심")).assertExists()
        onAllNodes(hasTestTag("viewPreset"))[0].assert(hasRunCallbackClickAction<PresetAddAction>(actionParametersOf(ViewCellAction.ViewKey to "v1", PresetAddAction.PresetKey to "점심")))
        onNode(hasContentDescriptionEqualTo("점심 프리셋으로 새 항목 추가")).assertExists()
    }

    @Test
    fun noPresetLineWithoutPresets() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(wide)
        provideComposable { ViewWidgetContent(presetState(), systemFontScale = 1f) }
        onAllNodes(hasTestTag("viewPreset")).assertCountEquals(0)
    }

    @Test
    fun noPresetLineOnTheSmallestWidget() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(narrow)
        provideComposable { ViewWidgetContent(presetState(ViewPreset("점심")), systemFontScale = 1f) }
        onAllNodes(hasTestTag("viewPreset")).assertCountEquals(0) // 2×2는 자리가 없다. '+'로 연 시트에서 만든다
    }

    @Test
    fun noPresetLineWhenTheViewCannotCreate() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(wide)
        provideComposable { ViewWidgetContent(presetState(ViewPreset("점심"), board = board()), systemFontScale = 1f) } // 쓰기 정보(제목 열)가 없는 보기
        onAllNodes(hasTestTag("viewPreset")).assertCountEquals(0)
    }

    // --- Phase 11c 되돌리기 줄(IX-05): 지연 중인 쓰기가 있을 때만, 눌러서 되돌린다.

    private fun withPending(vararg sendAfter: Long) = board().copy(pendingSendAfter = sendAfter.toList())

    @Test
    fun anUndoLineAppearsWhileAWriteIsInItsWindowAndRunsTheUndoAction() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(wide)
        provideComposable { ViewWidgetContent(state(withPending(now + 3_000)), systemFontScale = 1f) }
        onNode(hasTestTag("viewUndo")).assert(hasRunCallbackClickAction<UndoAction>(actionParametersOf(ViewCellAction.ViewKey to "v1")))
        onNode(hasTextEqualTo("방금 바꾼 것 되돌리기")).assertExists()
    }

    @Test
    fun noUndoLineWhenNothingIsPendingOrTheWindowHasPassed() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(wide)
        provideComposable { ViewWidgetContent(state(withPending(now - 1, now)), systemFontScale = 1f) } // 이미 보낼 때가 된 쓰기
        onAllNodes(hasTestTag("viewUndo")).assertCountEquals(0)
    }

    @Test
    fun headerAndOneRowFitTheSmallestWidgetAtNormalFonts() {
        // 2×2(110dp)에서 머리와 목록 한 줄이 통째로 보인다. 글자 배율 100%에서 시스템 글꼴이 상한(1.3)을 넘어도 같다.
        // 배율 150% + 시스템 1.3에서는 첫 줄이 일부만 보이고 스크롤된다(한계, PRD VW-03에 적음).
        listOf(1.0f, 1.15f, 1.3f, 2.0f).forEach { system ->
            val m = viewMetrics(userScale = 1f, systemFontScale = system)
            assertTrue("시스템 글꼴 $system", m.header + m.listRow <= 110.dp - VIEW_PADDING_V * 2)
        }
    }

    private suspend fun render(size: DpSize, content: @Composable () -> Unit) {
        val remoteViews = GlanceRemoteViews().compose(context, size) { content() }.remoteViews
        val activity = Robolectric.buildActivity(Activity::class.java).create().get()
        val frame = FrameLayout(activity)
        frame.addView(remoteViews.apply(activity, frame))
        frame.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.AT_MOST), View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.AT_MOST))
    }

    private fun textsIn(view: View): List<String> = when (view) {
        is TextView -> listOf(view.text.toString())
        is ViewGroup -> (0 until view.childCount).flatMap { textsIn(view.getChildAt(it)) }
        else -> emptyList()
    }

    /** 위젯 설정 화면의 미리보기와 같은 방식(변환 → View에 적용 → 측정·배치)으로 그려, 화면에 나온 글자를 모은다. */
    private suspend fun renderedTexts(size: DpSize, state: ViewWidgetState, staticRows: Boolean): List<String> {
        val remoteViews = GlanceRemoteViews().compose(context, size) { ViewWidgetContent(state, systemFontScale = 1f, staticRows = staticRows) }.remoteViews
        val activity = Robolectric.buildActivity(Activity::class.java).create().get()
        val frame = FrameLayout(activity)
        frame.addView(remoteViews.apply(activity, frame))
        val density = activity.resources.displayMetrics.density
        val w = (size.width.value * density).toInt()
        val h = (size.height.value * density).toInt()
        frame.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        frame.layout(0, 0, w, h)
        return textsIn(frame)
    }

    @Test
    fun settingsPreviewShowsTheRowsNotOnlyTheHeaders() = runTest(timeout = 3.minutes) {
        // 기기에서 위젯 설정 화면의 미리보기는 머리와 열 머리만 나오고 행이 비었다. 설정 화면은 변환 결과를 View에 직접 적용하는데
        // 그 방식에서는 목록(LazyColumn)의 행이 그려지지 않는다(Agenda도 같게 재현). 그래서 미리보기는 고정 Column으로 행을 그린다.
        val texts = renderedTexts(DpSize(330.dp, 380.dp), WidgetPreviews.viewState(), staticRows = true)
        assertTrue("열 머리가 나온다: $texts", "이름" in texts && "분류" in texts)
        assertTrue("행이 나온다: $texts", "장보기" in texts && "보고서 제출" in texts)
    }

    @Test
    fun staticPreviewKeepsAtMostEightRowsSoGlanceChildLimitHolds() = runTest(timeout = 3.minutes) {
        val texts = renderedTexts(DpSize(330.dp, 380.dp), state(board(fiftyRows())), staticRows = true)
        assertTrue("앞 8행은 나온다: $texts", "할 일 1" in texts && "할 일 8" in texts)
        assertTrue("9번째 행부터는 미리보기에 없다", "할 일 9" !in texts)
    }

    @Test
    fun translatesToRemoteViewsInEverySizeAndFont() = runTest(timeout = 3.minutes) {
        // Row·Column 자식 10개 제한 등은 실제 RemoteViews 변환에서만 드러난다(PRD §13).
        val sizes = listOf(DpSize(110.dp, 110.dp), DpSize(280.dp, 110.dp), DpSize(280.dp, 299.dp), DpSize(411.dp, 800.dp))
        val boards = listOf(board(fiftyRows()), board(emptyList()), board(fiftyRows(), error = VIEW_ERROR_NOT_FOUND), null)
        for (size in sizes) for (b in boards) for (font in listOf(70, 150)) {
            val s = ViewWidgetState(b, WidgetConfig(1, fontScale = font, viewId = "v1"), now)
            render(size) { ViewWidgetContent(s, systemFontScale = 1.3f) }
        }
    }

    @Test
    fun tableColumnsFitTheWidth() {
        assertEquals(1, tableColumnCount(80.dp, 5))
        assertEquals(4, tableColumnCount(320.dp, 5)) // (320-20)/72 = 4
        assertEquals(2, tableColumnCount(320.dp, 2)) // 열이 적으면 열 수까지만
        assertEquals(1, tableColumnCount(320.dp, 0)) // 보이는 열이 없어도 깨지지 않는다
    }

    // --- VW-08 체크·상태 칸은 눌러서 바꾼다(탭은 보기 전용 쓰기 큐에만 쌓고 노션은 부르지 않는다).

    private val doneColumn = ViewColumn("dn", "완료", "checkbox")
    private val statusColumn = ViewColumn("st", "상태", "status", listOf(ViewChoice("o1", "할 일"), ViewChoice("o2", "진행 중")))

    private fun todoBoard() = ViewBoard(
        "v1", "투두", "table", listOf("이름", "완료", "상태"),
        listOf(ViewRow("p1", "https://notion.so/p1", listOf("장보기", "", "할 일")), ViewRow("p2", "https://notion.so/p2", listOf("보고서", "✓", "진행 중"))),
        now, null, listOf(ViewColumn("t", "이름", "title"), doneColumn, statusColumn),
    )

    private fun tap(propId: String, type: String, pageId: String) = actionParametersOf(
        ViewCellAction.ViewKey to "v1", ViewCellAction.PageKey to pageId, ViewCellAction.PropKey to propId, ViewCellAction.TypeKey to type,
    )

    @Test
    fun checkAndStatusCellsAreTapTargetsInTheTable() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(wide)
        provideComposable { ViewWidgetContent(state(todoBoard()), systemFontScale = 1f) }
        onAllNodes(hasTestTag("viewCellTap")).assertCountEquals(4) // 두 행 x (체크 + 상태)
        listOf("할 일", "진행 중").forEach { onNode(hasTextEqualTo(it)).assertExists() } // 체크는 글자가 아니라 원형 벡터라 낭독 문구로 찾는다
        onNode(hasContentDescriptionEqualTo("장보기, 완료 체크 안 됨, 눌러서 체크"))
            .assert(hasRunCallbackClickAction<ViewCellAction>(tap("dn", "checkbox", "p1")))
        onNode(hasContentDescriptionEqualTo("보고서, 완료 체크됨, 눌러서 해제"))
            .assert(hasRunCallbackClickAction<ViewCellAction>(tap("dn", "checkbox", "p2")))
        onNode(hasContentDescriptionEqualTo("보고서, 상태 진행 중, 눌러서 다음 값으로"))
            .assert(hasRunCallbackClickAction<ViewCellAction>(tap("st", "status", "p2")))
        // 행을 누르면 노션 페이지를 여는 것은 그대로다.
        onAllNodes(hasTestTag("viewRow")).assertCountEquals(2)
        onAllNodes(hasTestTag("viewRow")).assertAll(hasClickAction())
    }

    @Test
    fun narrowListPutsTheCheckboxFirstAndTheStatusOnTheSecondLine() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(narrow)
        provideComposable { ViewWidgetContent(state(todoBoard()), systemFontScale = 1f) }
        onAllNodes(hasTestTag("viewCellTap")).assertCountEquals(4) // 체크박스 + 둘째 줄(상태)
        listOf("장보기", "보고서", "할 일", "진행 중").forEach { onNode(hasTextEqualTo(it)).assertExists() }
        onNode(hasTextEqualTo("완료")).assertDoesNotExist() // 열 이름은 표에서만
    }

    @Test
    fun aBoardWithoutWriteInfoHasNoTapTargets() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(wide)
        provideComposable { ViewWidgetContent(state(board()), systemFontScale = 1f) }
        onAllNodes(hasTestTag("viewCellTap")).assertCountEquals(0)
    }

    @Test
    fun numbersAndTitlesAreNotTapTargets() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(wide)
        val withNumber = ViewBoard(
            "v1", "책", "table", listOf("이름", "쪽수"), listOf(ViewRow("p1", "u", listOf("데미안", "12"))), now, null,
            listOf(ViewColumn("t", "이름", "title"), ViewColumn("pg", "쪽수", "number")),
        )
        provideComposable { ViewWidgetContent(state(withNumber), systemFontScale = 1f) }
        onAllNodes(hasTestTag("viewCellTap")).assertCountEquals(0) // 숫자는 M5, 제목은 눌러서 바꾸지 않는다
    }

    @Test
    fun headerHasAnAddButtonOnlyWhenTheViewCanCreate() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(wide)
        provideComposable { ViewWidgetContent(state(todoBoard()), systemFontScale = 1f) }
        onNode(hasTestTag("viewAdd")).assert(hasStartActivityClickAction(viewAddIntent(context, "v1", 1)))
        onNode(hasContentDescriptionEqualTo("투두, 새 항목 추가")).assertExists()
        onNode(hasTestTag("viewHeader")).assert(hasRunCallbackClickAction<RefreshAction>(actionParametersOf())) // 이름을 누르면 새로고침은 그대로
    }

    @Test
    fun noAddButtonWithoutWriteInfo() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(wide)
        provideComposable { ViewWidgetContent(state(board()), systemFontScale = 1f) }
        onNode(hasTestTag("viewAdd")).assertDoesNotExist()
    }

    @Test
    fun addIntentOpensTheSheetForThatViewOnly() {
        val intent = viewAddIntent(context, "v1", 7)
        assertEquals(7, intent.getIntExtra(AppIntents.EXTRA_APPWIDGET_ID, -1)) // 프리셋은 위젯마다라 어느 위젯의 '+'인지 싣는다
        assertNotEquals(viewAddIntent(context, "v1", 8).data, intent.data) // 같은 보기의 두 위젯도 PendingIntent가 따로다
        assertEquals(com.ysdoing.notioncal.core.data.AppIntents.ACTION_VIEW_ADD, intent.action)
        assertEquals("v1", intent.getStringExtra(com.ysdoing.notioncal.core.data.AppIntents.EXTRA_VIEW_ID))
        assertEquals(context.packageName, intent.`package`) // 같은 앱의 시트만 연다
        assertNotEquals(viewAddIntent(context, "v2").data, intent.data) // 보기마다 PendingIntent가 따로다
    }

    @Test
    fun listWithOnlyTitleAndCheckboxDrawsTheCheckboxOnce() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(narrow)
        val titleAndCheck = ViewBoard(
            "v1", "투두", "list", listOf("이름", "완료"),
            listOf(ViewRow("p1", "u1", listOf("장보기", "")), ViewRow("p2", "u2", listOf("보고서", "✓"))), now, null,
            listOf(ViewColumn("t", "이름", "title"), doneColumn),
        )
        provideComposable { ViewWidgetContent(state(titleAndCheck), systemFontScale = 1f) }
        onAllNodes(hasTestTag("viewCellTap")).assertCountEquals(2) // 행마다 하나(둘째 줄에 같은 칸이 또 나오지 않는다)
        onAllNodes(hasContentDescriptionEqualTo("장보기, 완료 체크 안 됨, 눌러서 체크")).assertCountEquals(1)
        onAllNodes(hasContentDescriptionEqualTo("보고서, 완료 체크됨, 눌러서 해제")).assertCountEquals(1)
    }

    // --- 2026-11-03 새 디자인: 머리 부제(완료 n/m)·새로고침·빈 상태 CTA·상태 알약 색.

    private fun groupedStatusBoard() = ViewBoard(
        "v1", "작업", "table", listOf("이름", "상태"),
        listOf(ViewRow("p1", "u1", listOf("a", "할 일")), ViewRow("p2", "u2", listOf("b", "진행 중")), ViewRow("p3", "u3", listOf("c", "완료"))), now, null,
        listOf(
            ViewColumn("t", "이름", "title"),
            ViewColumn("st", "상태", "status", listOf(ViewChoice("o1", "할 일", "To-do"), ViewChoice("o2", "진행 중", "In progress"), ViewChoice("o3", "완료", "Complete"))),
        ),
    )

    @Test
    fun progressPrefersTheCompleteGroupThenNamesTheCheckboxAndIsNullWithoutEither() {
        assertEquals(ViewProgress(null, 1, 3), viewProgress(groupedStatusBoard()))
        assertEquals(ViewProgress("완료", 1, 2), viewProgress(todoBoard())) // 상태에 완료 그룹이 없으면 체크박스를 열 이름과 함께 센다
        // 체크박스와 완료 그룹 상태가 함께 있으면 상태를 센다('보관' 같은 체크박스를 완료로 부르지 않는다).
        val both = groupedStatusBoard().let {
            it.copy(
                columns = it.columns + "보관", columnInfo = it.columnInfo + ViewColumn("pin", "보관", "checkbox"),
                rows = it.rows.map { r -> r.copy(cells = r.cells + "✓") },
            )
        }
        assertEquals(ViewProgress(null, 1, 3), viewProgress(both))
        assertEquals(ViewProgress("보관", 1, 2), viewProgress(todoBoard().copy(columns = listOf("이름", "보관", "상태"), columnInfo = listOf(ViewColumn("t", "이름", "title"), ViewColumn("pin", "보관", "checkbox"), statusColumn))))
        assertNull(viewProgress(board())) // 체크·상태 열이 없는 보기는 진행이라는 개념이 없다
        assertNull(viewProgress(todoBoard().copy(rows = emptyList())))
    }

    @Test
    fun statusToneFollowsTheGroupInEnglishOrKorean() {
        val status = groupedStatusBoard().columnInfo[1]
        assertEquals(StatusTone.NEUTRAL, status.toneOf("할 일"))
        assertEquals(StatusTone.ACTIVE, status.toneOf("진행 중"))
        assertEquals(StatusTone.DONE, status.toneOf("완료"))
        assertEquals(StatusTone.NEUTRAL, status.toneOf("없는 값"))
        assertEquals(StatusTone.DONE, ViewColumn("s", "상태", "status", listOf(ViewChoice("o", "끝", "완료"))).toneOf("끝"))
    }

    @Test
    fun headerShowsProgressWhenTallEnoughAndRefreshWhenWideEnough() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(wide)
        provideComposable { ViewWidgetContent(state(todoBoard()), systemFontScale = 1f) }
        onNode(hasTextEqualTo("완료 1/2")).assertExists()
        // 제목 영역의 설명이 안의 글자를 대신하므로 부제도 함께 읽힌다.
        onNode(hasTestTag("viewHeader")).assert(hasContentDescriptionEqualTo("투두, 완료 1/2, 새로고침"))
        onNode(hasTestTag("viewRefresh")).assert(hasRunCallbackClickAction<RefreshAction>(actionParametersOf()))
        onNode(hasContentDescriptionEqualTo(context.getString(R.string.widget_refresh))).assertExists()
    }

    @Test
    fun smallWidgetKeepsOnlyTheTitleAndAddInTheHeader() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        // 2×2에서는 머리와 한 줄이 함께 보여야 해서 부제·새로고침 버튼을 뺀다(머리 이름을 누르면 새로고침은 그대로).
        setContext(context)
        setAppWidgetSize(DpSize(110.dp, 110.dp))
        provideComposable { ViewWidgetContent(state(todoBoard()), systemFontScale = 1f) }
        onNode(hasTextEqualTo("완료 1/2")).assertDoesNotExist()
        onNode(hasTestTag("viewRefresh")).assertDoesNotExist()
        onNode(hasTestTag("viewAdd")).assertExists()
        onNode(hasTestTag("viewHeader")).assert(hasRunCallbackClickAction<RefreshAction>(actionParametersOf()))
    }

    @Test
    fun emptyViewOffersCreatingAnItemWhenTheViewCanCreate() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(DpSize(320.dp, 240.dp))
        provideComposable { ViewWidgetContent(state(todoBoard().copy(rows = emptyList())), systemFontScale = 1f) }
        onNode(hasTextEqualTo(context.getString(R.string.view_widget_empty))).assertExists()
        onNode(hasTestTag("viewEmpty")).assert(hasStartActivityClickAction(viewAddIntent(context, "v1", 1)))
    }

    @Test
    fun emptyViewWithoutWriteInfoOrInASmallWidgetHasNoCreateButton() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(DpSize(320.dp, 240.dp))
        provideComposable { ViewWidgetContent(state(board(emptyList())), systemFontScale = 1f) }
        onNode(hasTextEqualTo(context.getString(R.string.view_widget_empty))).assertExists()
        onNode(hasTestTag("viewEmpty")).assertDoesNotExist()
    }

    @Test
    fun emptyViewInASmallWidgetIsOneTappableLineSoItIsNotCutOff() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(DpSize(150.dp, 110.dp))
        provideComposable { ViewWidgetContent(state(todoBoard().copy(rows = emptyList())), systemFontScale = 1f) }
        val line = "${context.getString(R.string.view_widget_empty)}. ${context.getString(R.string.view_widget_empty_cta)}"
        onNode(hasTextEqualTo(line)).assertExists()
        onNode(hasTestTag("viewEmpty")).assert(hasStartActivityClickAction(viewAddIntent(context, "v1", 1))) // 줄을 누르면 새 항목 시트
    }

    @Test
    fun failedSyncIsWrittenWithAWarningLineNotColorOnly() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(wide)
        provideComposable { ViewWidgetContent(state(board(error = "Network")), systemFontScale = 1f) }
        onNode(hasTestTag("viewStatus")).assertExists()
        onNode(hasTextEqualTo(viewStatusText(context, board(error = "Network"), now)!!)).assertExists()
    }

    @Test
    fun rowsStayCompactForDensity() {
        // 밀도 우선(사용자 결정 2026-11-03): 눌러 바꿀 칸이 없는 행은 이전(약 26dp)에서 크게 늘지 않고, 있는 행도 이전(32dp)보다 조금만 높다.
        val m = viewMetrics(userScale = 1f, systemFontScale = 1f)
        assertTrue("읽기 전용 표 행 ${m.tableRow}", m.tableRow <= 32.dp)
        assertTrue("눌러 바꾸는 행 $TAP_ROW_MIN", TAP_ROW_MIN <= 36.dp)
    }

    @Test
    fun singleOptionStatusIsNotATapTarget() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(wide)
        val one = ViewBoard(
            "v1", "투두", "table", listOf("이름", "상태"), listOf(ViewRow("p1", "u1", listOf("장보기", "할 일"))), now, null,
            listOf(ViewColumn("t", "이름", "title"), ViewColumn("st", "상태", "status", listOf(ViewChoice("o1", "할 일")))),
        )
        provideComposable { ViewWidgetContent(state(one), systemFontScale = 1f) }
        onAllNodes(hasTestTag("viewCellTap")).assertCountEquals(0)
    }

    // --- Phase 11a: 밀도 상한 + '더 보기', 보드, 칩

    private val large = DpSize(358.dp, 330.dp)
    private val fourByTwo = DpSize(358.dp, 152.dp)

    private fun listBoard(count: Int, complete: Boolean = true) = board(
        (1..count).map { ViewRow("p$it", "https://notion.so/p$it", listOf("행 $it", "집", "10/21")) },
    ).copy(rowsComplete = complete)

    private val viewerIntent get() = Intent(Intent.ACTION_VIEW, "notioncal://view/v1".toUri()).setPackage(context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    @Test
    fun rowsAboveTheDensityCapEndWithAMoreRowThatOpensTheViewer() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(fourByTwo) // 4×2: 3행
        provideComposable { ViewWidgetContent(state(listBoard(10)), systemFontScale = 1f) }
        onAllNodes(hasTestTag("viewRow")).assertCountEquals(3)
        onNode(hasTestTag("viewMore")).assert(hasStartActivityClickAction(viewerIntent))
        onNode(hasTextEqualTo("+7 더 보기")).assertExists()
    }

    @Test
    fun anIncompleteRowSetSaysNPlusAndAFullSetHasNoMoreRow() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(large) // 4×4: 8행
        provideComposable { ViewWidgetContent(state(listBoard(100, complete = false)), systemFontScale = 1f) }
        onNode(hasTextEqualTo("92+ 더 보기")).assertExists() // 100행을 받았지만 노션에 더 있을 수 있다
    }

    @Test
    fun noMoreRowWhenEverythingFits() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(large)
        provideComposable { ViewWidgetContent(state(listBoard(5)), systemFontScale = 1f) }
        onAllNodes(hasTestTag("viewRow")).assertCountEquals(5)
        onAllNodes(hasTestTag("viewMore")).assertCountEquals(0)
    }

    private val groupColumn = ViewColumn(
        "st", "Status", "status", listOf(ViewChoice("o1", "할 일", color = "gray"), ViewChoice("o2", "진행 중", color = "blue"), ViewChoice("o3", "완료", color = "green"), ViewChoice("o4", "보류")),
        role = ViewColumn.ROLE_GROUP,
    )

    private fun boardView(groupOptions: Int = 4, page: Int = 0, vararg groups: String): ViewWidgetState {
        val rows = groups.mapIndexed { i, g -> ViewRow("p$i", "https://notion.so/p$i", listOf("카드 $i"), typed = mapOf("st" to kotlinx.serialization.json.JsonPrimitive(g))) }
        val column = groupColumn.copy(options = groupColumn.options.take(groupOptions))
        val board = ViewBoard("v1", "보드", "board", listOf("Name"), rows, now, null, listOf(ViewColumn("t", "Name", "title"), column))
        return ViewWidgetState(board, WidgetConfig(1, viewId = "v1", viewPage = page), now)
    }

    @Test
    fun aBoardDrawsOneColumnPerGroupWithItsCards() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(large) // 4×4, 폭 358: 3열
        provideComposable { ViewWidgetContent(boardView(3, 0, "o1", "o1", "o2", "o3"), systemFontScale = 1f) }
        onAllNodes(hasTestTag("boardColumn")).assertCountEquals(3)
        onAllNodes(hasTestTag("boardCard")).assertCountEquals(4)
        onNode(hasTextEqualTo("할 일")).assertExists()
        onAllNodes(hasTestTag("boardNext")).assertCountEquals(0) // 열이 3개라 넘길 것이 없다
    }

    @Test
    fun moreGroupsThanColumnsAreReachedWithTheArrows() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(large)
        provideComposable { ViewWidgetContent(boardView(4, 0, "o1", "o4"), systemFontScale = 1f) }
        onAllNodes(hasTestTag("boardColumn")).assertCountEquals(3)
        onNode(hasTestTag("boardNext")).assert(hasRunCallbackClickAction<BoardPageAction>(actionParametersOf(BoardPageAction.PageKey to 1)))
        onAllNodes(hasTestTag("boardPrev")).assertCountEquals(0)
    }

    @Test
    fun theSecondPageShowsTheLastGroupAndALeftArrow() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(large)
        provideComposable { ViewWidgetContent(boardView(4, 1, "o1", "o4"), systemFontScale = 1f) }
        onNode(hasTextEqualTo("보류")).assertExists() // 4번째 열이 보인다
        onNode(hasTestTag("boardPrev")).assert(hasRunCallbackClickAction<BoardPageAction>(actionParametersOf(BoardPageAction.PageKey to 0)))
        onAllNodes(hasTestTag("boardNext")).assertCountEquals(0)
    }

    @Test
    fun aStoredPageBeyondTheEndStillPagesBackByOne() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(large)
        // 열 수가 줄거나 위젯이 커져 저장된 위치(5)가 끝(1)을 넘어도, 화면에 보이는 위치에서 한 칸 뒤로 간다(눌러도 그대로인 버튼이 없도록).
        provideComposable { ViewWidgetContent(boardView(4, 5, "o1", "o4"), systemFontScale = 1f) }
        onNode(hasTestTag("boardPrev")).assert(hasRunCallbackClickAction<BoardPageAction>(actionParametersOf(BoardPageAction.PageKey to 0)))
    }

    @Test
    fun anIncompleteBoardMarksTheColumnCountsWithAPlus() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(large)
        val state = boardView(3, 0, "o1", "o1", "o2")
        provideComposable { ViewWidgetContent(state.copy(board = state.board!!.copy(rowsComplete = false)), systemFontScale = 1f) }
        onNode(hasTextEqualTo("2+")).assertExists() // 100행만 받았다면 열마다 '적어도 N'이다
        onNode(hasTextEqualTo("1+")).assertExists()
    }

    @Test
    fun aBoardFallsBackToAListWhenTooSmallForColumns() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(narrow)
        provideComposable { ViewWidgetContent(boardView(3, 0, "o1", "o2"), systemFontScale = 1f) }
        onAllNodes(hasTestTag("boardColumn")).assertCountEquals(0)
        onAllNodes(hasTestTag("viewRow")).assertCountEquals(2) // 2×2는 목록으로 대체
    }

    @Test
    fun cardsAboveTheColumnCapShowAMoreLineThatOpensTheViewer() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(fourByTwo) // 4×2: 2열 × 2장
        provideComposable { ViewWidgetContent(boardView(3, 0, "o1", "o1", "o1", "o1", "o2"), systemFontScale = 1f) }
        onAllNodes(hasTestTag("boardColumn")).assertCountEquals(2)
        onAllNodes(hasTestTag("boardCard")).assertCountEquals(3) // 첫 열 2장 + 둘째 열 1장
        onNode(hasTestTag("viewMore")).assert(hasStartActivityClickAction(viewerIntent))
    }

    @Test
    fun aBoardGivesUpACardPerColumnForTheUndoLineSoNothingIsClipped() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(fourByTwo) // 4×2: 2열 × 2장
        val plain = boardView(3, 0, "o1", "o1", "o1", "o1", "o2")
        val withUndo = plain.copy(board = plain.board!!.copy(pendingSendAfter = listOf(now + 3_000)))
        provideComposable { ViewWidgetContent(withUndo, systemFontScale = 1f) }
        // 되돌리기 줄이 높이를 차지하므로 열마다 카드를 한 장 줄인다(보드는 목록과 달리 스크롤이 없어 넘치면 잘린다): 첫 열 1장 + 둘째 열 1장.
        onAllNodes(hasTestTag("boardCard")).assertCountEquals(2)
    }

    @Test
    fun multiSelectCellsShowAtMostThreeChipsAndACount() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(wide)
        val tags = ViewColumn("tg", "Tags", "multi_select", listOf("a", "b", "c", "d", "e").map { ViewChoice(it, "태그$it", color = "blue") })
        val board = ViewBoard(
            "v1", "태그", "table", listOf("이름", "Tags"),
            listOf(ViewRow("p1", "u1", listOf("행", "태그a, 태그b, 태그c, 태그d, 태그e"), typed = mapOf("tg" to kotlinx.serialization.json.JsonArray(listOf("a", "b", "c", "d", "e").map { kotlinx.serialization.json.JsonPrimitive(it) })))),
            now, null, listOf(ViewColumn("t", "이름", "title"), tags),
        )
        provideComposable { ViewWidgetContent(state(board), systemFontScale = 1f) }
        listOf("태그a", "태그b", "태그c", "+2").forEach { onNode(hasTextEqualTo(it)).assertExists() }
        onNode(hasTextEqualTo("태그d")).assertDoesNotExist()
    }

    @Test
    fun boardsAndCapsTranslateToRemoteViewsInEverySizeAndFont() = runTest(timeout = 3.minutes) {
        val sizes = listOf(DpSize(110.dp, 110.dp), DpSize(358.dp, 152.dp), large, DpSize(411.dp, 800.dp))
        for (size in sizes) for (font in listOf(70, 150)) for (system in listOf(1f, 1.3f)) {
            for (state in listOf(boardView(4, 0, "o1", "o1", "o1", "o1", "o1", "o1", "o1", "o1", "o2", "o3", "o4"), state(listBoard(100, complete = false)))) {
                render(size) { ViewWidgetContent(state.copy(config = state.config.copy(fontScale = font)), systemFontScale = system) }
            }
        }
    }
}
