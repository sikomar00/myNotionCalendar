package com.ysdoing.notioncal.widget

import android.app.Activity
import android.content.Context
import android.view.View
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import androidx.glance.appwidget.testing.unit.hasRunCallbackClickAction
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.hasTestTag
import androidx.glance.testing.unit.hasTextEqualTo
import androidx.test.core.app.ApplicationProvider
import com.ysdoing.notioncal.core.data.WidgetConfig
import com.ysdoing.notioncal.core.data.WidgetTab
import com.ysdoing.notioncal.core.data.WidgetTheme
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * WG-11 통합 탭 위젯: 위쪽 탭(📅 일정 · ✅ 습관)으로 내용을 바꾸고, 탭은 위젯마다 저장(TabAction)한다.
 * 📅 탭은 월간 위젯 내용, ✅ 탭은 습관 위젯 내용을 그대로 쓴다. 4×2·4×4에서 RemoteViews 변환(자식 10개 제한) 통과.
 */
@OptIn(ExperimentalGlanceRemoteViewsApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w420dp-h900dp-xxhdpi")
class TabbedWidgetTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val large = DpSize(358.dp, 380.dp)
    private val medium = DpSize(358.dp, 168.dp)

    @Composable
    private fun Tabbed(tab: WidgetTab, config: WidgetConfig = WidgetConfig(1, selectedTab = tab), fontScale: Float = 1f) {
        TabbedWidgetContent(config, systemFontScale = fontScale) { selected ->
            when (selected) {
                WidgetTab.CALENDAR, WidgetTab.LEDGER -> WidgetContent(WidgetSamples.state(config = config), systemFontScale = fontScale, framed = false)
                WidgetTab.HABIT -> HabitWidgetContent(HabitWidgetTest.state(config = config), systemFontScale = fontScale, framed = false, showHeader = false)
            }
        }
    }

    @Test
    fun calendarTabShowsMonthAndTabsSwitchViaAction() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(large)
        provideComposable { Tabbed(WidgetTab.CALENDAR) }
        onNode(hasTextEqualTo("2026년 10월")).assertExists()
        onAllNodes(hasTestTag("habitRow")).assertCountEquals(0)
        onNode(hasTestTag("tab_HABIT"))
            .assert(hasRunCallbackClickAction<TabAction>(actionParametersOf(TabAction.TabKey to WidgetTab.HABIT.name)))
        onNode(hasTestTag("tab_CALENDAR"))
            .assert(hasRunCallbackClickAction<TabAction>(actionParametersOf(TabAction.TabKey to WidgetTab.CALENDAR.name)))
    }

    @Test
    fun habitTabShowsHabitRows() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(large)
        provideComposable { Tabbed(WidgetTab.HABIT) }
        onAllNodes(hasTestTag("habitRow")).assertCountEquals(5)
        onAllNodes(hasTestTag("habitGrass")).assertCountEquals(1) // 4×4는 탭 줄을 빼고도 잔디가 들어간다
        onAllNodes(hasTestTag("day")).assertCountEquals(0)
    }

    /** 탭 줄 높이를 뺀 크기로 안쪽 내용의 크기 단계를 정한다(검증 메모 11). */
    @Test
    fun contentSizeExcludesTabRow() {
        val inner = TabbedWidget.contentSize(large, WidgetConfig(1), systemFontScale = 1f)
        assertEquals(large.width, inner.width)
        assertTrue(inner.height < large.height)
        assertEquals(WidgetMode.LARGE, widgetMode(inner))
        assertEquals(HabitWidgetMode.MEDIUM, habitWidgetMode(TabbedWidget.contentSize(medium, WidgetConfig(1), 1f)))
    }

    private suspend fun render(size: DpSize, content: @Composable () -> Unit) {
        val remoteViews = GlanceRemoteViews().compose(context, size) { content() }.remoteViews
        val activity = Robolectric.buildActivity(Activity::class.java).create().get()
        val frame = FrameLayout(activity)
        frame.addView(remoteViews.apply(activity, frame))
        frame.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.AT_MOST), View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.AT_MOST))
    }

    /** PRD §13: Row·Column 자식 10개 제한. 습관 위젯 세 크기 + 통합 위젯 두 탭을 실제 RemoteViews로 바꿔 본다(4×2·4×4 잘림 확인용). */
    @Test
    fun translatesToRemoteViewsInEverySize() = runTest(timeout = 3.minutes) {
        val configs = listOf(WidgetConfig(1), WidgetConfig(1, fontScale = 150, theme = WidgetTheme.DARK, opacity = 60))
        for (config in configs) {
            for (size in listOf(HabitWidgetTest.SMALL, HabitWidgetTest.MEDIUM, HabitWidgetTest.LARGE)) {
                render(size) { HabitWidgetContent(HabitWidgetTest.state(8, config), systemFontScale = 1.3f) }
            }
            for (size in listOf(medium, large)) {
                for (tab in listOf(WidgetTab.CALENDAR, WidgetTab.HABIT)) render(size) { Tabbed(tab, config.copy(selectedTab = tab), fontScale = 1.3f) }
            }
        }
    }
}
