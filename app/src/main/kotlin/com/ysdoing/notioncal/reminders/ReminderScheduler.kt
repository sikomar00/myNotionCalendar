package com.ysdoing.notioncal.reminders

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.ysdoing.notioncal.core.data.APP_ZONE
import com.ysdoing.notioncal.core.data.EventRepository
import com.ysdoing.notioncal.core.data.SettingsRepository
import com.ysdoing.notioncal.core.data.reminders.Reminder
import com.ysdoing.notioncal.core.data.reminders.ReminderPlanner
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * EX-01 알림 예약. 다음에 울릴 알림 하나에만 알람을 건다(울리면 그때 다음 것을 건다).
 * S25-11: 정확한 알람을 쓸 수 있으면 정확히, 아니면 10분 창의 부정확 알람으로 대체한다(설정 화면에 표시).
 * 일정은 Room만 읽는다(켜 둔 DB만, 오늘부터 8일).
 */
@Singleton
class ReminderScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val events: EventRepository,
    private val notifier: ReminderNotifier,
    private val clock: Clock,
) {
    private val alarms get() = context.getSystemService(AlarmManager::class.java)
    private val lock = Mutex()

    /** 정확한 알람을 걸 수 있는지(S25-11). */
    fun exactAllowed(): Boolean = alarms?.canScheduleExactAlarms() == true

    /** 프로세스가 살아 있는 동안 설정·일정이 바뀌면 다시 건다(동기화·편집·위젯 조작 포함). */
    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    fun start(scope: CoroutineScope) {
        scope.launch {
            settings.settings
                .map { it.remindersEnabled to it.reminderRules }
                .distinctUntilChanged()
                .flatMapLatest { (enabled, _) ->
                    if (!enabled) {
                        flowOf(Unit)
                    } else {
                        val today = clock.instant().atZone(APP_ZONE).toLocalDate()
                        val (from, to) = ReminderPlanner.eventRange(today)
                        events.observeRange(from, to).map { }
                    }
                }
                .debounce(RESCHEDULE_DEBOUNCE_MS)
                .collect { runCatching { reschedule() } }
        }
    }

    /** 다음 알림 하나에 알람을 건다. 꺼져 있거나 알림 권한이 없거나 알릴 것이 없으면 취소한다. */
    suspend fun reschedule() = lock.withLock {
        val current = settings.settings.first()
        if (!current.remindersEnabled || !notifier.canNotify()) {
            cancelAlarm()
            return@withLock
        }
        val now = clock.instant()
        val next = upcoming(now, now.plus(LOOK_AHEAD)).firstOrNull()
        if (next == null) cancelAlarm() else setAlarm(next.fireAt.coerceAtLeast(now))
    }

    /** 알람이 울렸을 때: 놓친 것까지 (처리한 시각, 지금+30초]의 알림을 모두 보내고 다음 알람을 건다. */
    suspend fun fire() {
        lock.withLock {
            val current = settings.settings.first()
            if (!current.remindersEnabled) return@withLock
            val now = clock.instant()
            val due = upcoming(now, now.plus(FIRE_SLACK))
            due.forEach(notifier::show)
            val handled = (due.maxOfOrNull { it.fireAt } ?: now).coerceAtLeast(now)
            settings.setRemindersHandledUntil(handled.toEpochMilli())
        }
        reschedule()
    }

    /** 처리한 시각 이후의 알림. 1시간 넘게 지난 알림은 보내지 않는다(기기가 오래 꺼져 있던 경우). */
    private suspend fun upcoming(now: Instant, upTo: Instant): List<Reminder> {
        val current = settings.settings.first()
        val after = maxOf(Instant.ofEpochMilli(current.remindersHandledUntil), now.minus(STALE_AFTER))
        val today = now.atZone(APP_ZONE).toLocalDate()
        val (from, to) = ReminderPlanner.eventRange(today)
        return ReminderPlanner.plan(events.observeRange(from, to).first(), current.reminderRules, after, upTo)
    }

    // canScheduleExactAlarms()로 확인한 뒤에만 정확 알람을 쓴다(USE_EXACT_ALARM 선언). lint가 이 확인을 인식하지 못한다.
    @SuppressLint("MissingPermission")
    private fun setAlarm(at: Instant) {
        val manager = alarms ?: return
        val millis = at.toEpochMilli()
        if (manager.canScheduleExactAlarms()) {
            manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, fireIntent())
        } else {
            manager.setWindow(AlarmManager.RTC_WAKEUP, millis, INEXACT_WINDOW.toMillis(), fireIntent())
        }
    }

    fun cancelAlarm() {
        alarms?.cancel(fireIntent())
    }

    private fun fireIntent(): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, ReminderReceiver::class.java).setAction(ReminderReceiver.ACTION_FIRE),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        private const val RESCHEDULE_DEBOUNCE_MS = 500L
        private val LOOK_AHEAD: Duration = Duration.ofDays(8)
        private val FIRE_SLACK: Duration = Duration.ofSeconds(30)
        private val STALE_AFTER: Duration = Duration.ofHours(1)

        /** 정확한 알람을 못 쓸 때의 허용 지연(설정 화면 안내와 같다). */
        val INEXACT_WINDOW: Duration = Duration.ofMinutes(10)
    }
}
