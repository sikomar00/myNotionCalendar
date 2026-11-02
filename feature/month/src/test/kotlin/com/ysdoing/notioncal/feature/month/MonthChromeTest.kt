package com.ysdoing.notioncal.feature.month

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.ysdoing.notioncal.core.data.calendar.CalendarView
import com.ysdoing.notioncal.feature.quicksheet.QuickSheetStart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/** MV-11: 상단바·서랍 연결과, 회전 뒤에도 새 일정 시트가 남는지(SheetSaver). */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h760dp-xxhdpi")
class MonthChromeTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private fun text(id: Int) = context.getString(id)

    private val selected = mutableListOf<CalendarView>()
    private var filterTaps = 0
    private var settingsTaps = 0

    private fun showDrawer(filterActive: Boolean = false) {
        compose.setContent {
            MaterialTheme {
                MonthDrawerContent(
                    view = CalendarView.MONTH,
                    filterActive = filterActive,
                    onSelectView = { selected += it },
                    onFilter = { filterTaps++ },
                    onSettings = { settingsTaps++ },
                )
            }
        }
    }

    @Test
    fun drawerSwitchesViewsAndOpensFilterAndSettings() {
        showDrawer()
        compose.onNodeWithText(text(R.string.view_week)).performClick()
        compose.onNodeWithText(text(R.string.view_agenda)).performClick()
        compose.onNodeWithText(text(R.string.filter_title)).performClick()
        compose.onNodeWithText(text(R.string.month_settings)).performClick()
        assertEquals(listOf(CalendarView.WEEK, CalendarView.AGENDA), selected)
        assertEquals(1, filterTaps)
        assertEquals(1, settingsTaps)
    }

    @Test
    fun drawerShowsActiveFilter() {
        showDrawer(filterActive = true)
        compose.onNodeWithText(text(R.string.filter_active)).assertExists()
    }

    private val taps = mutableListOf<String>()

    private fun showTopBar(pickable: Boolean = true, filterActive: Boolean = false) {
        compose.setContent {
            MaterialTheme {
                MonthTopBar(
                    title = "2026년 11월", pickable = pickable, filterActive = filterActive, today = LocalDate.of(2026, 11, 2),
                    onMenu = { taps += "menu" }, onPickMonth = { taps += "pick" }, onSearch = { taps += "search" }, onToday = { taps += "today" },
                )
            }
        }
    }

    @Test
    fun topBarButtonsCallBack() {
        showTopBar()
        compose.onNodeWithContentDescription(text(R.string.month_menu)).performClick()
        compose.onNodeWithText("2026년 11월").performClick()
        compose.onNodeWithContentDescription(text(R.string.search_title)).performClick()
        compose.onNodeWithContentDescription(text(R.string.month_today)).performClick()
        assertEquals(listOf("menu", "pick", "search", "today"), taps)
        compose.onNodeWithText("2").assertExists() // 오늘 아이콘 안의 날짜 숫자
    }

    @Test
    fun titleIsNotPickableOutsideMonthView() {
        showTopBar(pickable = false)
        compose.onNodeWithText("2026년 11월").performClick()
        assertEquals(emptyList<String>(), taps)
    }

    @Test
    fun menuTellsWhenFilterHidesEvents() {
        showTopBar(filterActive = true)
        compose.onNodeWithContentDescription(text(R.string.month_menu_filter_active)).assertExists()
    }

    private val scope = SaverScope { true }
    private fun roundTrip(start: QuickSheetStart?): QuickSheetStart? = with(SheetSaver) { restore(scope.save(start)!!) }

    @Test
    fun sheetStartsSurviveRotation() {
        val date = LocalDate.of(2026, 11, 2)
        assertEquals(QuickSheetStart.Add(date), roundTrip(QuickSheetStart.Add(date)))
        assertEquals(QuickSheetStart.Day(date), roundTrip(QuickSheetStart.Day(date)))
        assertEquals(QuickSheetStart.Edit("page-1"), roundTrip(QuickSheetStart.Edit("page-1")))
        assertNull(roundTrip(null))
    }
}
