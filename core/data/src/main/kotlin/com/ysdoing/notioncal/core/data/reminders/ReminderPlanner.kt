package com.ysdoing.notioncal.core.data.reminders

import com.ysdoing.notioncal.core.data.APP_ZONE
import com.ysdoing.notioncal.core.data.CalendarEvent
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * EX-01 알림 규칙(전체 공통). 시간 일정은 시작 [timedMinutes]분 전마다(여러 개, 1시간보다 이른 알림 가능),
 * 종일·여러 날 일정은 시작일(또는 전날)의 [allDayMinuteOfDay] 시각에 한 번.
 */
data class ReminderRules(
    val timedMinutes: List<Int> = listOf(DEFAULT_TIMED_MINUTES),
    val allDayMinuteOfDay: Int = DEFAULT_ALL_DAY_MINUTE,
    val allDayDayBefore: Boolean = false,
) {
    companion object {
        const val DEFAULT_TIMED_MINUTES = 10
        const val DEFAULT_ALL_DAY_MINUTE = 9 * 60

        /** 가장 이른 알림: 7일 전. */
        const val MAX_MINUTES = 7 * 24 * 60

        /** 범위 밖 값을 빼고 중복 없이 가까운 것부터. */
        fun normalize(minutes: List<Int>): List<Int> = minutes.filter { it in 0..MAX_MINUTES }.distinct().sorted()
    }
}

/** 알림 하나. [minutesBefore]는 시간 일정의 몇 분 전 알림인지(종일 일정은 null). */
data class Reminder(
    val pageId: String,
    val title: String,
    val fireAt: Instant,
    val startDate: LocalDate,
    val startTime: Instant?,
    val minutesBefore: Int?,
) {
    /** 알림 ID(같은 일정·같은 시각은 한 번만). */
    val key: String get() = "$pageId@${fireAt.toEpochMilli()}"
}

/** EX-01 알림 계산(순수 함수). 완료한 일정은 알리지 않는다. 시각은 서울 기준이다. */
object ReminderPlanner {
    /** [events]에서 (after, upTo] 사이에 울릴 알림을 시각 순으로. */
    fun plan(events: List<CalendarEvent>, rules: ReminderRules, after: Instant, upTo: Instant): List<Reminder> =
        events.asSequence()
            .filterNot { it.done }
            .flatMap { event -> remindersOf(event, rules) }
            .filter { it.fireAt > after && it.fireAt <= upTo }
            .distinctBy { it.key }
            .sortedWith(compareBy({ it.fireAt }, { it.title }))
            .toList()

    private fun remindersOf(event: CalendarEvent, rules: ReminderRules): Sequence<Reminder> {
        val start = event.startTime
        return if (start != null && !event.isAllDay) {
            ReminderRules.normalize(rules.timedMinutes).asSequence().map { minutes ->
                Reminder(event.pageId, event.title, start.minusSeconds(minutes * 60L), event.startDate, start, minutes)
            }
        } else {
            val day = if (rules.allDayDayBefore) event.startDate.minusDays(1) else event.startDate
            val minute = rules.allDayMinuteOfDay.coerceIn(0, 24 * 60 - 1)
            val fireAt = day.atTime(LocalTime.of(minute / 60, minute % 60)).atZone(APP_ZONE).toInstant()
            sequenceOf(Reminder(event.pageId, event.title, fireAt, event.startDate, null, null))
        }
    }

    /** 알림을 계산할 때 읽을 일정 범위(오늘부터 8일). 7일 전 알림까지 잡는다. */
    fun eventRange(today: LocalDate): Pair<LocalDate, LocalDate> = today to today.plusDays(8)
}
