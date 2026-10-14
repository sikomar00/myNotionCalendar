package com.ysdoing.notioncal.core.data.reminders

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.ysdoing.notioncal.core.data.CalendarEvent
import com.ysdoing.notioncal.core.data.SettingsRepository
import com.ysdoing.notioncal.core.data.WidgetRefresher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime

/** EX-01: 여러 분 전·1시간보다 이른 알림, 종일 지정 시각(당일·전날), 완료 제외, 경계, 서울 시간. */
class ReminderPlannerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun seoul(text: String): Instant = ZonedDateTime.parse("${text}+09:00[Asia/Seoul]").toInstant()

    private fun timed(id: String, start: String, done: Boolean = false) = CalendarEvent(
        pageId = id, dataSourceId = "ds", title = id, startDate = LocalDate.parse(start.take(10)), endDate = null,
        startTime = seoul(start), endTime = null, isAllDay = false, done = done, colorHex = "#000000", sourceColorHex = "#000000", url = "",
    )

    private fun allDay(id: String, date: String, end: String? = null, done: Boolean = false) = CalendarEvent(
        pageId = id, dataSourceId = "ds", title = id, startDate = LocalDate.parse(date), endDate = end?.let(LocalDate::parse),
        startTime = null, endTime = null, isAllDay = true, done = done, colorHex = "#000000", sourceColorHex = "#000000", url = "",
    )

    private val after = seoul("2026-10-14T08:00:00")
    private val upTo = seoul("2026-10-22T00:00:00")

    @Test
    fun timedEventGetsEveryOffsetIncludingEarlierThanAnHour() {
        val rules = ReminderRules(timedMinutes = listOf(10, 1440, 60, 10))
        val list = ReminderPlanner.plan(listOf(timed("회의", "2026-10-16T10:30:00")), rules, after, upTo)
        assertEquals(
            listOf(seoul("2026-10-15T10:30:00"), seoul("2026-10-16T09:30:00"), seoul("2026-10-16T10:20:00")),
            list.map { it.fireAt },
        )
        assertEquals(listOf(1440, 60, 10), list.map { it.minutesBefore })
    }

    @Test
    fun allDayAtChosenTimeSameDayOrDayBefore() {
        val events = listOf(allDay("출장", "2026-10-20", "2026-10-22"), allDay("휴가", "2026-10-15"))
        val sameDay = ReminderPlanner.plan(events, ReminderRules(), after, upTo)
        assertEquals(listOf(seoul("2026-10-15T09:00:00"), seoul("2026-10-20T09:00:00")), sameDay.map { it.fireAt })
        // 여러 날 일정은 시작일에만 한 번.
        assertEquals(1, sameDay.count { it.pageId == "출장" })

        val dayBefore = ReminderPlanner.plan(events, ReminderRules(allDayMinuteOfDay = 21 * 60, allDayDayBefore = true), after, upTo)
        assertEquals(listOf(seoul("2026-10-14T21:00:00"), seoul("2026-10-19T21:00:00")), dayBefore.map { it.fireAt })
    }

    @Test
    fun skipsDoneAndOutsideWindow() {
        val events = listOf(
            timed("끝남", "2026-10-15T10:00:00", done = true),
            timed("지난", "2026-10-14T08:05:00"), // 10분 전 = 07:55(이미 지남)
            timed("경계", "2026-10-14T08:10:00"), // 10분 전 = 08:00(after와 같음 → 제외, 이미 처리함)
            timed("다음", "2026-10-14T08:11:00"),
        )
        assertEquals(listOf("다음"), ReminderPlanner.plan(events, ReminderRules(), after, upTo).map { it.pageId })
    }

    @Test
    fun normalizeDropsOutOfRange() {
        assertEquals(listOf(0, 5, 10080), ReminderRules.normalize(listOf(10080, -1, 5, 0, 20000, 5)))
    }

    @Test
    fun settingsRoundTrip() = runTest {
        val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { File(tmp.root, "s.preferences_pb") }
        val repo = SettingsRepository(store, WidgetRefresher {})
        val defaults = repo.settings.first()
        assertFalse(defaults.remindersEnabled)
        assertEquals(ReminderRules(), defaults.reminderRules) // 10분 전, 당일 9시

        repo.setReminderRules(ReminderRules(listOf(60, 10, 60, -3), 21 * 60, allDayDayBefore = true))
        repo.setRemindersEnabled(true, now = 1_000)
        val saved = repo.settings.first()
        assertTrue(saved.remindersEnabled)
        assertEquals(ReminderRules(listOf(10, 60), 21 * 60, true), saved.reminderRules)
        assertEquals(1_000, saved.remindersHandledUntil) // 켜는 순간 이전 알림은 보내지 않음

        repo.setRemindersEnabled(true, now = 5_000) // 이미 켜져 있으면 기준 시각을 바꾸지 않는다
        assertEquals(1_000, repo.settings.first().remindersHandledUntil)
    }
}
