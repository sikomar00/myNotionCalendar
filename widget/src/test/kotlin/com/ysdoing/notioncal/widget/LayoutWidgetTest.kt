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

    private fun state(count: Int, selected: Int, mode: LayoutMode = LayoutMode.TABS, fontScale: Int = 100): LayoutWidgetState {
        val layout = (0 until count).fold(WidgetLayout(mode)) { l, i -> l.add("v${i + 1}") ?: l }
        val boards = (0 until count).associate { "v${it + 1}" to board(it) }
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
        assertEquals(3, tabWindow(2, 5, 234.dp).visible)
    }

    @Test
    fun aTabPressesAtLeast48dpWideOnceTheWidgetIsWideEnoughForTheArrowsPlusOneTab() {
        for (width in listOf(150, 234, 360, 501)) for (count in 1..5) {
            val w = tabWindow(0, count, width.dp)
            val room = width.dp - TAB_STRIP_PADDING * 2 - if (w.arrows) ARROW_WIDTH * 2 else 0.dp
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
        assertEquals(1, tabWindow(2, 5, 234.dp).first) // 가운데 정렬
        assertEquals(2, tabWindow(4, 5, 234.dp).first) // 끝에서는 끝 칸에 붙는다
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
        listOf(1, 2, 3).forEach { onNode(hasTestTag("layoutTab_$it")).assertExists() }
        listOf(0, 4).forEach { onNode(hasTestTag("layoutTab_$it")).assertDoesNotExist() }
        onNode(hasTestTag("layoutTab_1")).assert(hasContentDescriptionEqualTo("독서")) // 첫 글자 칩도 이름을 읽어 준다
        onNode(hasTextEqualTo("독")).assertExists()
        onNode(hasTextEqualTo("청")).assertExists()
        onNode(hasTextEqualTo("독서")).assertDoesNotExist()
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
    fun theTabStripShowsTheNamesOnScreen() = runTest(timeout = 2.minutes) {
        val remoteViews = GlanceRemoteViews().compose(context, DpSize(360.dp, 300.dp)) { LayoutWidgetContent(state(3, 0), systemFontScale = 1f) }.remoteViews
        val activity = Robolectric.buildActivity(Activity::class.java).create().get()
        val frame = FrameLayout(activity)
        frame.addView(remoteViews.apply(activity, frame))
        fun texts(v: View): List<String> = if (v is TextView) listOf(v.text.toString()) else if (v is ViewGroup) (0 until v.childCount).flatMap { texts(v.getChildAt(it)) } else emptyList()
        assertTrue(texts(frame).containsAll(listOf("할 일", "독서", "운동")))
    }
}
