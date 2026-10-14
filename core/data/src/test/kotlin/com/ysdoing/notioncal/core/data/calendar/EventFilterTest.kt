package com.ysdoing.notioncal.core.data.calendar

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.ysdoing.notioncal.core.data.CalendarEvent
import com.ysdoing.notioncal.core.data.SettingsRepository
import com.ysdoing.notioncal.core.data.WidgetRefresher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDate

/** MV-09: DB·색 기준 값(값 없음 포함)·완료 숨김, 저장 후 다시 읽기, 줌·보기 저장. */
class EventFilterTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun event(id: String, ds: String = "cal", option: String? = null, done: Boolean = false) = CalendarEvent(
        pageId = id, dataSourceId = ds, title = id, startDate = LocalDate.of(2026, 10, 14), endDate = null,
        startTime = null, endTime = null, isAllDay = true, done = done, colorHex = "#000000", sourceColorHex = "#000000", url = "",
        colorOptionId = option,
    )

    private val events = listOf(
        event("event", option = "o-event"),
        event("learn", option = "o-learn"),
        event("habit", option = "o-habit", done = true),
        event("none"),
        event("work", ds = "work"),
    )

    private fun ids(filter: EventFilter) = filter.apply(events).map { it.pageId }

    @Test
    fun emptyFilterKeepsEverything() {
        assertFalse(EventFilter().isActive)
        assertEquals(events.map { it.pageId }, ids(EventFilter()))
    }

    @Test
    fun hidesBySourceOptionNoValueAndDone() {
        assertEquals(listOf("event", "learn", "habit", "none"), ids(EventFilter(hiddenSourceIds = setOf("work"))))
        assertEquals(listOf("event", "learn", "none", "work"), ids(EventFilter(hiddenOptionKeys = setOf("cal/o-habit"))))
        // 값 없음은 "dsId/". 다른 DB의 값 없는 일정(work)은 그대로 둔다.
        assertEquals(listOf("event", "learn", "habit", "work"), ids(EventFilter(hiddenOptionKeys = setOf(EventFilter.optionKey("cal", null)))))
        assertEquals(listOf("event", "learn", "none", "work"), ids(EventFilter(hideDone = true)))
        assertTrue(EventFilter(hideDone = true).isActive)
    }

    @Test
    fun settingsRoundTrip() = runTest {
        val scope = TestScope(UnconfinedTestDispatcher(testScheduler))
        val store = PreferenceDataStoreFactory.create(scope = scope.backgroundScope) { File(tmp.root, "settings.preferences_pb") }
        val repo = SettingsRepository(store, WidgetRefresher {})
        val filter = EventFilter(setOf("work"), setOf("cal/o-habit", "cal/"), hideDone = true)
        repo.setFilter(filter)
        repo.setZoomPercent(400)
        repo.setCalendarView(CalendarView.AGENDA)
        val saved = repo.settings.first()
        assertEquals(filter, saved.filter)
        assertTrue(saved.hideDone) // 설정 화면의 '완료 숨김'과 같은 값
        assertEquals(CalendarZoom.MAX, saved.zoomPercent)
        assertEquals(CalendarView.AGENDA, saved.calendarView)

        repo.setFilter(EventFilter())
        assertFalse(repo.settings.first().filter.isActive)
    }
}
