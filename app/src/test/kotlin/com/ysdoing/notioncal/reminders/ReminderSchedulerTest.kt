package com.ysdoing.notioncal.reminders

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ysdoing.notioncal.core.data.EventRepository
import com.ysdoing.notioncal.core.data.SettingsRepository
import com.ysdoing.notioncal.core.data.WidgetRefresher
import com.ysdoing.notioncal.core.data.db.DataSourceEntity
import com.ysdoing.notioncal.core.data.db.EventEntity
import com.ysdoing.notioncal.core.data.db.NotionCalDatabase
import com.ysdoing.notioncal.core.data.reminders.ReminderRules
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlarmManager
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

/** EX-01·S25-11: 가장 이른 알림 하나에 정확 알람, 정확 알람이 안 되면 10분 창, 끄면 취소, 울리면 놓친 것까지 보내고 다음 알람. */
@RunWith(RobolectricTestRunner::class)
class ReminderSchedulerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: NotionCalDatabase
    private val alarms get() = shadowOf(context.getSystemService(AlarmManager::class.java))
    private val notifications get() = shadowOf(context.getSystemService(NotificationManager::class.java))

    /** 테스트가 시각을 옮길 수 있는 시계. */
    private class MovableClock(var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = now
    }

    private fun seoul(text: String): Instant = ZonedDateTime.parse("${text}+09:00[Asia/Seoul]").toInstant()

    private val clock = MovableClock(seoul("2026-10-14T10:00:00"))

    @Before
    fun setUp() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        db = Room.inMemoryDatabaseBuilder(context, NotionCalDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    private suspend fun seed() {
        db.dataSourceDao().upsert(
            DataSourceEntity(
                dataSourceId = "ds", databaseId = "db", name = "Calendar", colorHex = "#0B6E99", titlePropId = "title",
                datePropId = "d", donePropId = null, doneType = null, doneOption = null, undoneOption = null,
                colorPropId = null, sortPropId = null,
            ),
        )
        fun timed(id: String, start: String) = EventEntity(
            pageId = id, dataSourceId = "ds", title = id, startDate = LocalDate.parse(start.take(10)), endDate = null,
            startTime = seoul(start).toEpochMilli(), endTime = null, isAllDay = false, done = false, colorKey = null,
            sortKey = null, url = "", lastEditedTime = 0,
        )
        db.eventDao().upsert(listOf(timed("회의", "2026-10-14T10:30:00"), timed("저녁", "2026-10-14T19:00:00")))
    }

    private fun TestScope.scheduler(): Pair<ReminderScheduler, SettingsRepository> {
        val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { File(tmp.root, "s.preferences_pb") }
        val settings = SettingsRepository(store, WidgetRefresher {})
        val scheduler = ReminderScheduler(context, settings, EventRepository(db.eventDao()), ReminderNotifier(context, clock), clock)
        return scheduler to settings
    }

    @Test
    fun schedulesEarliestReminderExactly() = runTest {
        seed()
        val (scheduler, settings) = scheduler()
        settings.setRemindersEnabled(true, clock.millis())
        scheduler.reschedule()
        val alarm = alarms.peekNextScheduledAlarm()!!
        assertEquals(seoul("2026-10-14T10:20:00").toEpochMilli(), alarm.triggerAtMs) // 회의 10분 전
        assertTrue(alarm.isAllowWhileIdle)
        assertEquals(0L, alarm.windowLengthMs)
    }

    @Test
    fun fallsBackToInexactWindowWithoutExactPermission() = runTest {
        seed()
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        val (scheduler, settings) = scheduler()
        settings.setRemindersEnabled(true, clock.millis())
        scheduler.reschedule()
        assertEquals(ReminderScheduler.INEXACT_WINDOW.toMillis(), alarms.peekNextScheduledAlarm()!!.windowLengthMs)
    }

    @Test
    fun disabledCancels() = runTest {
        seed()
        val (scheduler, settings) = scheduler()
        settings.setRemindersEnabled(true, clock.millis())
        scheduler.reschedule()
        settings.setRemindersEnabled(false, clock.millis())
        scheduler.reschedule()
        assertNull(alarms.peekNextScheduledAlarm())
    }

    @Test
    fun firePostsMissedRemindersAndSchedulesNext() = runTest {
        seed()
        val (scheduler, settings) = scheduler()
        settings.setReminderRules(ReminderRules(timedMinutes = listOf(10, 20)))
        settings.setRemindersEnabled(true, clock.millis())
        // 기기가 늦게 깨어나 10:10·10:20 알림을 모두 놓쳤다.
        clock.now = seoul("2026-10-14T10:25:00")
        scheduler.fire()
        assertEquals(2, notifications.allNotifications.size)
        assertEquals(clock.millis(), settings.settings.first().remindersHandledUntil)
        // 다음은 저녁 20분 전.
        assertEquals(seoul("2026-10-14T18:40:00").toEpochMilli(), alarms.peekNextScheduledAlarm()!!.triggerAtMs)
        // 같은 시각에 다시 울려도 중복으로 보내지 않는다.
        scheduler.fire()
        assertEquals(2, notifications.allNotifications.size)
    }
}
