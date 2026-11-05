package com.ysdoing.notioncal.widget

import android.app.Activity
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
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
import com.ysdoing.notioncal.core.data.view.LayoutMode
import com.ysdoing.notioncal.core.data.view.ViewBoard
import com.ysdoing.notioncal.core.data.view.ViewColumn
import com.ysdoing.notioncal.core.data.view.ViewRow
import com.ysdoing.notioncal.core.data.view.WidgetLayout
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Phase 11d WL-01·02 새 위젯: 단일(보기 하나)·탭(보기 여러 개를 탭으로). 탭 줄 폭 규칙(눌림 48dp·화살표 창·이름/첫 글자)은 순수 함수로 고정하고,
 * 탭 줄·본문은 Glance 시험과 RemoteViews 변환(자식 10개 제한)으로 본다.
 */
@OptIn(ExperimentalGlanceRemoteViewsApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w420dp-h900dp-xxhdpi")
class LayoutWidgetTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val now = 1_800_000_000_000L
    private val names = listOf("할 일", "독서", "운동", "청소", "공부")

    private fun board(i: Int) = ViewBoard("v${i + 1}", names[i], "table", listOf("이름"), listOf(ViewRow("p$i", "", listOf("행 ${names[i]}"))), now, null)

    private fun denseBoard(i: Int) = board(i).copy(rows = (1..50).map { ViewRow("p$i-$it", "", listOf("행 ${names[i]} $it")) })

    private fun state(count: Int, selected: Int, mode: LayoutMode = LayoutMode.TABS, fontScale: Int = 100, weights: List<Int> = emptyList(), dense: Boolean = false): LayoutWidgetState {
        val layout = (0 until count).fold(WidgetLayout(mode)) { l, i -> l.add("v${i + 1}") ?: l }
            .let { l -> weights.withIndex().fold(l) { acc, (i, w) -> acc.withWeight("v${i + 1}", w) } }
        val boards = (0 until count).associate { "v${it + 1}" to if (dense) denseBoard(it) else board(it) }
        return LayoutWidgetState(WidgetConfig(1, fontScale = fontScale, lastTab = selected), layout, boards, now)
    }

    // --- tabWindow: 순수 계산

    @Test
    fun everyWidthAndTabCountGetsAWindowThatContainsTheSelectedTab() {
        for (width in listOf(110, 150, 234, 320, 360, 501)) for (count in 1..5) for (selected in 0 until count) {
            val w = tabWindow(selected, count, width.dp)
            val where = "폭 $width 탭 $count 선택 $selected"
            assertTrue(where, w.visible in 1..count)
            assertTrue(where, w.first >= 0 && w.first + w.visible <= count)
            assertTrue(where, selected in w.first until w.first + w.visible) // 고른 탭이 창 밖으로 밀려나지 않는다
        }
    }

    @Test
    fun tabsThatFitAt48dpEachNeedNoArrowsAndEverythingElseGetsThem() {
        assertFalse(tabWindow(0, 2, 110.dp).arrows) // 98dp 안에 48dp 둘
        assertTrue(tabWindow(0, 3, 110.dp).arrows)
        assertFalse(tabWindow(0, 5, 501.dp).arrows)
        assertTrue(tabWindow(0, 5, 234.dp).arrows) // 삼성 2×2에서 5탭은 화살표 창
        assertEquals(2, tabWindow(2, 5, 234.dp).visible) // 화살표 48dp 둘을 빼고 탭 48dp 둘
    }

    @Test
    fun arrowsPressAt48dpExceptOnTheNarrowestWidgets() {
        assertEquals(TAP_TARGET, arrowWidth(234.dp)) // 삼성 2×2
        assertEquals(TAP_TARGET, arrowWidth(200.dp))
        assertEquals(28.dp, arrowWidth(150.dp)) // 일반 런처 2×2는 탭 자리를 위해 줄인다(A11Y-02의 2×2 예외)
        assertEquals(arrowWidth(234.dp), tabWindow(0, 5, 234.dp).arrow)
    }

    @Test
    fun aTabPressesAtLeast48dpWideOnceTheWidgetIsWideEnoughForTheArrowsPlusOneTab() {
        for (width in listOf(150, 234, 360, 501)) for (count in 1..5) {
            val w = tabWindow(0, count, width.dp)
            val room = width.dp - TAB_STRIP_PADDING * 2 - if (w.arrows) w.arrow * 2 else 0.dp
            assertTrue("폭 $width 탭 $count: 탭 하나 ${room / w.visible}", room / w.visible >= TAB_MIN_WIDTH)
        }
    }

    @Test
    fun namesAreShownOnEveryTabOnlyWhenEachHasRoomForOne() {
        assertTrue(tabWindow(0, 3, 360.dp).allLabels)
        assertFalse(tabWindow(0, 5, 360.dp).allLabels) // 5탭이면 탭당 약 68dp
        assertFalse(tabWindow(0, 5, 234.dp).allLabels)
    }

    @Test
    fun windowFollowsTheSelectedTabAndStaysInsideTheList() {
        assertEquals(0, tabWindow(0, 5, 234.dp).first)
        assertEquals(2, tabWindow(2, 5, 234.dp).first) // 창이 둘이면 고른 탭이 첫 칸
        assertEquals(0, tabWindow(2, 5, 320.dp).first) // 다 들어가면 화살표 없이 처음부터
        assertEquals(3, tabWindow(4, 5, 234.dp).first) // 끝에서는 끝 칸에 붙는다
    }

    @Test
    fun theInitialOfATabNameIsItsFirstCharacterWholeEvenForEmoji() {
        assertEquals("독", tabInitial("독서"))
        assertEquals("📚", tabInitial("📚 책"))
        assertEquals("A", tabInitial("  Alpha"))
        assertEquals("?", tabInitial("  "))
    }

    // --- Glance: 탭 줄과 본문

    @Test
    fun tabsDrawOneButtonPerTabAndOnlyTheSelectedTabBody() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(DpSize(358.dp, 380.dp))
        provideComposable { LayoutWidgetContent(state(3, selected = 1), systemFontScale = 1f) }
        (0..2).forEach { onNode(hasTestTag("layoutTab_$it")).assertExists() }
        onNode(hasTestTag("layoutTab_1")).assert(hasContentDescriptionEqualTo("독서, 선택됨"))
        onNode(hasTestTag("layoutTab_0")).assert(hasContentDescriptionEqualTo("할 일"))
        onNode(hasTextEqualTo("행 독서")).assertExists()
        onNode(hasTextEqualTo("행 할 일")).assertDoesNotExist()
        onNode(hasTestTag("layoutTabPrev")).assertDoesNotExist() // 다 들어가면 화살표가 없다
    }

    @Test
    fun aTabPressSavesItsIndex() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(DpSize(358.dp, 380.dp))
        provideComposable { LayoutWidgetContent(state(3, selected = 0), systemFontScale = 1f) }
        onNode(hasTestTag("layoutTab_2")).assert(hasRunCallbackClickAction<LayoutTabAction>(actionParametersOf(LayoutTabAction.IndexKey to 2)))
    }

    @Test
    fun narrowFiveTabsShowAWindowWithArrowsAndInitialsForTheOthers() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(DpSize(234.dp, 213.dp)) // 삼성 2×2
        provideComposable { LayoutWidgetContent(state(5, selected = 2), systemFontScale = 1f) }
        listOf(2, 3).forEach { onNode(hasTestTag("layoutTab_$it")).assertExists() }
        listOf(0, 1, 4).forEach { onNode(hasTestTag("layoutTab_$it")).assertDoesNotExist() }
        onNode(hasTestTag("layoutTab_3")).assert(hasContentDescriptionEqualTo("청소")) // 첫 글자 칩도 이름을 읽어 준다
        onNode(hasTextEqualTo("청")).assertExists()
        onNode(hasTextEqualTo("청소")).assertDoesNotExist()
        onNode(hasTestTag("layoutTabPrev")).assert(hasRunCallbackClickAction<LayoutTabAction>(actionParametersOf(LayoutTabAction.IndexKey to 1)))
        onNode(hasTestTag("layoutTabNext")).assert(hasRunCallbackClickAction<LayoutTabAction>(actionParametersOf(LayoutTabAction.IndexKey to 3)))
    }

    @Test
    fun theArrowAtTheEndOfTheListDoesNothing() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(DpSize(234.dp, 213.dp))
        provideComposable { LayoutWidgetContent(state(5, selected = 0), systemFontScale = 1f) }
        onNode(hasTestTag("layoutTabPrev")).assertExists()
        onNode(hasTestTag("layoutTabPrev") and hasClickAction()).assertDoesNotExist()
        onNode(hasTestTag("layoutTabNext") and hasClickAction()).assertExists()
    }

    @Test
    fun aSavedTabNumberPastTheEndShowsTheLastTab() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(DpSize(358.dp, 380.dp))
        provideComposable { LayoutWidgetContent(state(2, selected = 9), systemFontScale = 1f) } // 탭을 줄인 뒤에도 깨지지 않는다
        onNode(hasTestTag("layoutTab_1")).assert(hasContentDescriptionEqualTo("독서, 선택됨"))
        onNode(hasTextEqualTo("행 독서")).assertExists()
    }

    @Test
    fun tabAndSectionAddButtonsCarryTheWidgetIdSoTheSheetCanReadTheFormAndDecidePresets() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        // 입력 양식(AD-02)은 칸의 보기마다 위젯에 저장되니 시트가 위젯 번호를 알아야 한다. 프리셋 저장은 시트가 배치를 보고 단일일 때만 보인다.
        setContext(context)
        setAppWidgetSize(DpSize(358.dp, 380.dp))
        val creatable = { st: LayoutWidgetState -> st.copy(boards = st.boards.mapValues { (_, b) -> b?.copy(columnInfo = listOf(ViewColumn("t", "이름", "title"))) }) }
        provideComposable { LayoutWidgetContent(creatable(state(2, 0)), systemFontScale = 1f) }
        onNode(hasTestTag("viewAdd")).assert(hasStartActivityClickAction(viewAddIntent(context, "v1", 1)))
    }

    @Test
    fun aSingleViewKeepsTheWidgetIdSoTheSheetCanSaveAPreset() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(DpSize(358.dp, 380.dp))
        val creatable = state(1, 0, LayoutMode.SINGLE).let { st -> st.copy(boards = st.boards.mapValues { (_, b) -> b?.copy(columnInfo = listOf(ViewColumn("t", "이름", "title"))) }) }
        provideComposable { LayoutWidgetContent(creatable, systemFontScale = 1f) }
        onNode(hasTestTag("viewAdd")).assert(hasStartActivityClickAction(viewAddIntent(context, "v1", 1)))
    }

    @Test
    fun singleModeHasNoTabStripAndKeepsThePresetLine() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(DpSize(358.dp, 380.dp))
        provideComposable { LayoutWidgetContent(state(1, selected = 0, mode = LayoutMode.SINGLE), systemFontScale = 1f) }
        onNode(hasTestTag("layoutTab_0")).assertDoesNotExist()
        onNode(hasTextEqualTo("행 할 일")).assertExists()
    }

    @Test
    fun anEmptyLayoutAsksToPickViewsAndOpensTheSettings() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(DpSize(358.dp, 380.dp))
        val empty = LayoutWidgetState(WidgetConfig(7), WidgetLayout(LayoutMode.TABS), emptyMap(), now)
        provideComposable { LayoutWidgetContent(empty, systemFontScale = 1f) }
        onNode(hasTestTag("layoutNote")).assert(hasStartActivityClickAction(viewConfigIntent(context, 7)))
        onNode(hasTextEqualTo(context.getString(R.string.layout_widget_choose))).assertExists()
    }

    @Test
    fun aTabWhoseViewIsGoneSaysSoInsteadOfDrawingNothing() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(DpSize(358.dp, 380.dp))
        val gone = state(2, selected = 1).let { it.copy(boards = it.boards - "v2") }
        provideComposable { LayoutWidgetContent(gone, systemFontScale = 1f) }
        onNode(hasTestTag("viewNote")).assertExists() // '등록이 지워진 보기' 안내(ViewPane)
        onNode(hasTestTag("layoutTab_1")).assert(hasContentDescriptionEqualTo("보기, 선택됨")) // 이름 없는 보기의 기본 이름
    }

    // --- WL-03 섹션: 높이는 비율대로, 낮으면 앞쪽 섹션만

    @Test
    fun sectionHeightsSplitTheRoomLeftAfterTheGapsByWeight() {
        assertEquals(listOf(150.dp, 150.dp), sectionHeights(304.dp, listOf(1, 1), 4.dp, 72.dp))
        assertEquals(listOf(200.dp, 100.dp), sectionHeights(304.dp, listOf(2, 1), 4.dp, 72.dp))
        val three = sectionHeights(500.dp, listOf(1, 2, 3), 4.dp, 72.dp)
        assertEquals(500f, three.sumOf { it.value.toDouble() }.toFloat() + 8f, 0.01f) // 섹션 높이 + 틈 = 전체
    }

    @Test
    fun sectionsTooLowToReadAreLeftOutFromTheEndButTheFirstAlwaysStays() {
        assertEquals(2, sectionHeights(150.dp, listOf(1, 1, 1, 1), 4.dp, 72.dp).size) // 네 개는 34dp씩이라 둘만
        assertEquals(listOf(40.dp), sectionHeights(40.dp, listOf(1, 1), 4.dp, 72.dp)) // 하나는 모자라도 남긴다
        assertEquals(listOf(150.dp), sectionHeights(150.dp, listOf(3, 1), 4.dp, 72.dp)) // 앞쪽이 큰 비율이어도 뒤가 못 읽히면 뺀다
        assertEquals(emptyList<androidx.compose.ui.unit.Dp>(), sectionHeights(300.dp, emptyList(), 4.dp, 72.dp))
    }

    @Test
    fun sectionsStackEveryViewWithItsOwnHeader() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(DpSize(358.dp, 600.dp))
        provideComposable { LayoutWidgetContent(state(3, 0, LayoutMode.SECTIONS), systemFontScale = 1f) }
        onAllNodes(hasTestTag("viewHeader")).assertCountEquals(3)
        onNode(hasTestTag("layoutTab_0")).assertDoesNotExist()
        listOf("행 할 일", "행 독서", "행 운동").forEach { onNode(hasTextEqualTo(it)).assertExists() }
    }

    @Test
    fun aLowWidgetDrawsOnlyTheFirstSectionAndItStillShowsARow() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(DpSize(358.dp, 170.dp)) // 안쪽 162dp: 둘이면 79dp씩이라 행 하나가 안 든다 → 첫 섹션만
        provideComposable { LayoutWidgetContent(state(2, 0, LayoutMode.SECTIONS, dense = true), systemFontScale = 1f) }
        onAllNodes(hasTestTag("viewHeader")).assertCountEquals(1)
        onAllNodes(hasTestTag("viewRow")).assertCountEquals(1) // 머리줄만 보이고 데이터가 없는 칸은 만들지 않는다
        onAllNodes(hasTestTag("viewMore")).assertCountEquals(1)
    }

    @Test
    fun aTabInAShortWidgetStillShowsOneRow() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(DpSize(358.dp, 150.dp)) // 4×2 일반 런처: 칸 높이로는 행이 0이지만 목록이라 밀어 볼 수 있으니 한 행은 둔다
        provideComposable { LayoutWidgetContent(state(2, 0, dense = true), systemFontScale = 1f) }
        onAllNodes(hasTestTag("viewRow")).assertCountEquals(1)
    }

    @Test
    fun aSmallWidgetDrawsOnlyTheSectionsThatFit() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(DpSize(234.dp, 270.dp)) // 안쪽 262dp: 둘이면 129dp씩(최소 약 122dp)
        provideComposable { LayoutWidgetContent(state(4, 0, LayoutMode.SECTIONS), systemFontScale = 1f) }
        onAllNodes(hasTestTag("viewHeader")).assertCountEquals(2)
    }

    @Test
    fun sectionsFollowTheirWeightsOnScreen() = runTest(timeout = 2.minutes) {
        suspend fun secondHeaderTop(weights: List<Int>): Float {
            val remoteViews = GlanceRemoteViews().compose(context, DpSize(360.dp, 420.dp)) { LayoutWidgetContent(state(2, 0, LayoutMode.SECTIONS, weights = weights), systemFontScale = 1f) }.remoteViews
            val activity = Robolectric.buildActivity(Activity::class.java).create().get()
            val frame = FrameLayout(activity)
            frame.addView(remoteViews.apply(activity, frame))
            val density = activity.resources.displayMetrics.density
            frame.measure(View.MeasureSpec.makeMeasureSpec((360 * density).toInt(), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec((420 * density).toInt(), View.MeasureSpec.EXACTLY))
            frame.layout(0, 0, (360 * density).toInt(), (420 * density).toInt())
            fun find(v: View): TextView? = if (v is TextView && v.text.toString() == "독서") v else (v as? ViewGroup)?.let { g -> (0 until g.childCount).firstNotNullOfOrNull { find(g.getChildAt(it)) } }
            var y = 0f
            var v: View? = find(frame)
            while (v != null && v !== frame) { y += v.top; v = v.parent as? View }
            return y / density
        }
        val even = secondHeaderTop(listOf(1, 1))
        val heavy = secondHeaderTop(listOf(2, 1))
        assertEquals("2:1이면 둘째 섹션이 (408×2/3 − 408/2) = 68dp 아래에서 시작한다", 68f, heavy - even, 4f)
    }

    // --- RemoteViews 변환(PRD §13): 자식 10개 제한 등은 실제 변환에서만 드러난다.

    private suspend fun render(size: DpSize, content: @Composable () -> Unit) {
        val remoteViews = GlanceRemoteViews().compose(context, size) { content() }.remoteViews
        val activity = Robolectric.buildActivity(Activity::class.java).create().get()
        val frame = FrameLayout(activity)
        frame.addView(remoteViews.apply(activity, frame))
        val density = activity.resources.displayMetrics.density
        val w = (size.width.value * density).toInt()
        val h = (size.height.value * density).toInt()
        frame.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        frame.layout(0, 0, w, h)
    }

    @Test
    fun tabsTranslateToRemoteViewsInEverySizeAndFont() = runTest(timeout = 3.minutes) {
        val sizes = listOf(DpSize(110.dp, 110.dp), DpSize(234.dp, 213.dp), DpSize(360.dp, 300.dp), DpSize(501.dp, 581.dp))
        for (size in sizes) for (count in listOf(1, 3, 5)) for (font in listOf(70, 150)) for (selected in listOf(0, count - 1)) {
            val s = state(count, selected, fontScale = font)
            render(size) { LayoutWidgetContent(s, systemFontScale = 1.3f) }
        }
        render(DpSize(234.dp, 213.dp)) { LayoutWidgetContent(LayoutWidgetState(WidgetConfig(1), WidgetLayout(LayoutMode.TABS), emptyMap(), now), 1f) }
    }

    @Test
    fun sectionsTranslateToRemoteViewsInEverySizeAndFont() = runTest(timeout = 4.minutes) {
        val sizes = listOf(DpSize(110.dp, 110.dp), DpSize(234.dp, 213.dp), DpSize(360.dp, 300.dp), DpSize(501.dp, 581.dp))
        for (size in sizes) for (count in listOf(1, 2, 4)) for (font in listOf(70, 150)) for (dense in listOf(false, true)) {
            val s = state(count, 0, LayoutMode.SECTIONS, fontScale = font, weights = listOf(3, 1, 2, 1).take(count), dense = dense)
            render(size) { LayoutWidgetContent(s, systemFontScale = 1.3f) }
        }
    }

    @Test
    fun fourDenseSectionsStayFarBelowTheBinderLimit() = runTest(timeout = 3.minutes) {
        // 목록(LazyColumn) 넷이 한 RemoteViews에 든다(이 저장소에서 처음). 가장 큰 경우도 Binder 한도(약 1MB, 프로세스 공용)에 여유를 둔다.
        for (size in listOf(DpSize(501.dp, 581.dp), DpSize(411.dp, 800.dp))) {
            val s = state(4, 0, LayoutMode.SECTIONS, dense = true)
            val remoteViews = GlanceRemoteViews().compose(context, size) { LayoutWidgetContent(s, systemFontScale = 1f) }.remoteViews
            val parcel = android.os.Parcel.obtain()
            remoteViews.writeToParcel(parcel, 0)
            val bytes = parcel.dataSize()
            parcel.recycle()
            println("LAYOUT_PARCEL ${size.width}x${size.height} = $bytes bytes")
            assertTrue("$size RemoteViews $bytes bytes", bytes < 400_000)
        }
    }

    /** 설정 화면 미리보기와 같은 방식(변환 → View에 적용 → 측정·배치)으로 그려, 화면에 나온 글자를 모은다. */
    private suspend fun renderedTexts(size: DpSize, state: LayoutWidgetState, staticRows: Boolean): List<String> {
        val remoteViews = GlanceRemoteViews().compose(context, size) { LayoutWidgetContent(state, systemFontScale = 1f, staticRows = staticRows) }.remoteViews
        val activity = Robolectric.buildActivity(Activity::class.java).create().get()
        val frame = FrameLayout(activity)
        frame.addView(remoteViews.apply(activity, frame))
        val density = activity.resources.displayMetrics.density
        val w = (size.width.value * density).toInt()
        val h = (size.height.value * density).toInt()
        frame.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        frame.layout(0, 0, w, h)
        fun texts(v: View): List<String> = if (v is TextView) listOf(v.text.toString()) else if (v is ViewGroup) (0 until v.childCount).flatMap { texts(v.getChildAt(it)) } else emptyList()
        return texts(frame)
    }

    @Test
    fun theTabStripShowsTheNamesOnScreen() = runTest(timeout = 2.minutes) {
        val texts = renderedTexts(DpSize(360.dp, 300.dp), state(3, 0), staticRows = false)
        assertTrue(texts.containsAll(listOf("할 일", "독서", "운동")))
    }

    @Test
    fun settingsPreviewShowsTheChosenViewNamesAndSampleRowsInTabsAndSections() = runTest(timeout = 3.minutes) {
        for (mode in listOf(LayoutMode.TABS, LayoutMode.SECTIONS)) {
            val layout = WidgetLayout(mode).add("a")!!.add("b")!!.withLabel("b", "내 독서")
            val preview = WidgetPreviews.layoutPreview(WidgetConfig(0, layoutJson = WidgetLayout.encode(layout)), mapOf("a" to "할 일", "b" to "독서"))
            val texts = renderedTexts(DpSize(330.dp, 600.dp), preview, staticRows = true)
            assertTrue("$mode $texts", texts.containsAll(listOf("할 일", "장보기"))) // 첫 칸: 이름과 예시 행(목록이 아니라 고정 Column으로)
            assertTrue("$mode $texts", if (mode == LayoutMode.TABS) "내 독서" in texts else "독서" in texts) // 탭 이름은 label, 섹션 머리는 보기 이름
        }
    }
}
