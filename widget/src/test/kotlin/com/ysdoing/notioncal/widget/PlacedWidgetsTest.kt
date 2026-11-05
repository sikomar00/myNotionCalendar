package com.ysdoing.notioncal.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ysdoing.notioncal.core.data.WidgetConfig
import com.ysdoing.notioncal.core.data.view.AggFn
import com.ysdoing.notioncal.core.data.view.AggTile
import com.ysdoing.notioncal.core.data.view.LayoutMode
import com.ysdoing.notioncal.core.data.view.WidgetLayout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Phase 11f ST-07(최소판) 설정의 '위젯' 목록: 위젯 종류 이름과 고른 보기·배치 요약, 눌러서 위젯 설정 열기. */
@RunWith(RobolectricTestRunner::class)
class PlacedWidgetsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val names = mapOf("a" to "할 일", "b" to "독서", "c" to "")

    private fun config(layout: WidgetLayout) = WidgetConfig(1, layoutJson = WidgetLayout.encode(layout))

    private fun summary(receiver: Class<*>, config: WidgetConfig) = widgetSummary(context, receiver.name, config, names)

    @Test
    fun aTabLayoutNamesItsTabs() {
        val layout = WidgetLayout(LayoutMode.TABS).add("a")!!.add("b")!!.withLabel("b", "읽는 중")
        assertEquals("탭 2개: 할 일, 읽는 중", summary(LayoutWidgetReceiver::class.java, config(layout)))
    }

    @Test
    fun aSectionLayoutNamesItsSectionsAndCountsTheTiles() {
        val sections = WidgetLayout(LayoutMode.SECTIONS).add("a")!!.add("b")!!
        assertEquals("섹션 2개: 할 일, 독서", summary(LayoutWidgetReceiver::class.java, config(sections)))
        val withTiles = sections.addTile(AggTile("", "a", AggFn.COUNT))!!
        assertEquals("섹션 2개: 할 일, 독서 · 집계 타일 1개", summary(LayoutWidgetReceiver::class.java, config(withTiles)))
        val tilesOnly = WidgetLayout(LayoutMode.SECTIONS).addTile(AggTile("", "a", AggFn.COUNT))!!
        assertEquals("집계 타일 1개", summary(LayoutWidgetReceiver::class.java, config(tilesOnly)))
    }

    @Test
    fun aSingleLayoutShowsItsViewAndAnEmptyOneSaysNothingIsChosen() {
        assertEquals("독서", summary(LayoutWidgetReceiver::class.java, config(WidgetLayout().add("b")!!)))
        assertEquals("보기를 고르지 않음", summary(LayoutWidgetReceiver::class.java, config(WidgetLayout(LayoutMode.TABS))))
        assertEquals("보기를 고르지 않음", summary(LayoutWidgetReceiver::class.java, WidgetConfig(1)))
    }

    @Test
    fun aViewWidgetShowsItsViewNameAndUnnamedViewsAreStillSpoken() {
        assertEquals("할 일", summary(ViewWidgetReceiver::class.java, WidgetConfig(1, viewId = "a")))
        assertEquals("이름 없는 보기", summary(ViewWidgetReceiver::class.java, WidgetConfig(1, viewId = "c")))
        assertEquals("보기를 고르지 않음", summary(ViewWidgetReceiver::class.java, WidgetConfig(1)))
        assertEquals("독서", summary(BookWidgetReceiver::class.java, WidgetConfig(1, viewId = "b")))
    }

    @Test
    fun widgetsWithoutAChosenViewHaveNoSummary() {
        assertEquals("", summary(NotionCalWidgetReceiver::class.java, WidgetConfig(1)))
        assertEquals("", summary(HabitWidgetReceiver::class.java, WidgetConfig(1)))
    }

    @Test
    fun theSettingsIntentOpensTheWidgetConfigurationForThatWidget() {
        val intent = AllWidgets.configIntent(context, 42)
        assertEquals(WidgetConfigActivity::class.java.name, intent.component?.className)
        assertEquals(42, intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1))
    }

    @Test
    fun widgetsOfTheSameKindGetANumberSoTheRowsCanBeTold() {
        val widgets = listOf(PlacedWidget(3, "디데이 목록", ""), PlacedWidget(5, "월간 달력", ""), PlacedWidget(8, "디데이 목록", ""))
        assertEquals(listOf("디데이 목록 1", "월간 달력", "디데이 목록 2"), numberDuplicates(widgets).map { it.kindLabel })
        assertEquals(listOf(3, 5, 8), numberDuplicates(widgets).map { it.appWidgetId })
        assertEquals(widgets.take(2).drop(1), numberDuplicates(widgets.take(2).drop(1))) // 하나뿐이면 그대로
    }
}
