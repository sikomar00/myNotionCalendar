package com.ysdoing.notioncal.core.data.habit

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/** 최근 7일 칸 하나(HB-03). [beforeStart]면 습관 시작 전이라 비어 있어도 '안 함'이 아니다. */
data class DayMark(val date: LocalDate, val done: Boolean, val beforeStart: Boolean)

/** HB-04 상태 문구의 종류. 연속이 끊겨도 0으로 보여 주지 않는다. */
sealed interface HabitMessage {
    /** 매일 습관을 이어가는 중(오늘 했거나, 어제까지 해서 오늘은 아직). [days]는 이어 온 동안 한 날 수. */
    data class Streak(val days: Int) : HabitMessage

    /** 어제 하루 쉼: 오늘 하면 이어진다(오늘 칸 강조). */
    data object MissedYesterday : HabitMessage

    /** 이틀 이상 쉼. [days]는 쉰 날 수(오늘 제외). */
    data class Resting(val days: Int) : HabitMessage

    /** 시작일 뒤로 아직 한 번도 안 했다. */
    data object NotStarted : HabitMessage

    /** 주 목표가 7 미만인 습관(HB-05): 이번 주 [count]/[target], 지난주까지 연속 달성한 주 [streakWeeks]. */
    data class Weekly(val count: Int, val target: Int, val streakWeeks: Int) : HabitMessage {
        val achieved: Boolean get() = count >= target
        val remaining: Int get() = (target - count).coerceAtLeast(0)
    }
}

/** 습관 하나의 화면 상태. */
data class HabitStatus(
    /** 오늘-6일 ~ 오늘(왼쪽이 옛날). */
    val last7: List<DayMark>,
    val weekCount: Int,
    val weekTarget: Int,
    /** 이번 달 한 날 수(늘 표시, HB-04). */
    val monthCount: Int,
    val message: HabitMessage,
) {
    val doneToday: Boolean get() = last7.last().done
    val weekAchieved: Boolean get() = weekCount >= weekTarget

    /** HB-04: 하루 놓친 매일 습관은 오늘 칸을 강조한다. */
    val highlightToday: Boolean get() = message == HabitMessage.MissedYesterday
}

/**
 * HB-04·HB-05 계산(순수 함수). [done]은 그 습관을 한 날(같은 날 기록이 여러 개여도 하나). 주 경계는 [weekStart](MV-01).
 *
 * - 매일 습관(주 목표 7): 오늘부터 거슬러 올라가며 한 날을 센다. 하루 쉰 날은 건너뛰고 이어 세고(오늘 하면 이어짐),
 *   이틀 이상 쉬면 끊는다. 오늘 아직 안 했으면 어제까지의 상태를 보여 준다.
 * - 주 목표 N(1~6): 그 주에 N번 채우면 나머지 날을 쉬어도 달성. 지난주부터 거슬러 연속 달성한 주를 센다.
 * - 시작일 이전은 공백으로 세지 않는다(그 전 기록은 연속·쉼 계산에서 뺀다. 칸·한 날 수에는 보인다).
 */
object HabitStats {
    const val DAILY = 7

    /** 연속 달성 주를 거슬러 셀 최대 주 수(기록은 400일까지 받아 둔다). */
    private const val MAX_WEEKS = 60

    fun weekStartOf(date: LocalDate, weekStart: DayOfWeek): LocalDate = date.with(TemporalAdjusters.previousOrSame(weekStart))

    fun status(done: Set<LocalDate>, today: LocalDate, weekStart: DayOfWeek, weeklyTarget: Int, startDate: LocalDate?): HabitStatus {
        val target = weeklyTarget.coerceIn(1, DAILY)
        val last7 = (6 downTo 0).map { back ->
            val date = today.minusDays(back.toLong())
            DayMark(date, date in done, startDate != null && date.isBefore(startDate))
        }
        val thisWeek = weekStartOf(today, weekStart)
        val weekCount = done.count { !it.isBefore(thisWeek) && !it.isAfter(today) }
        val monthCount = done.count { !it.isBefore(today.withDayOfMonth(1)) && !it.isAfter(today) }
        val counted = done.filterTo(HashSet()) { !it.isAfter(today) && (startDate == null || !it.isBefore(startDate)) }
        val message = if (target < DAILY) {
            HabitMessage.Weekly(weekCount, target, weekStreak(counted, thisWeek, target, startDate))
        } else {
            dailyMessage(counted, today)
        }
        return HabitStatus(last7, weekCount, target, monthCount, message)
    }

    private fun dailyMessage(done: Set<LocalDate>, today: LocalDate): HabitMessage {
        val last = done.maxOrNull() ?: return HabitMessage.NotStarted
        val rested = ChronoUnit.DAYS.between(last, today) - 1 // 오늘 빼고 쉰 날
        return when {
            last == today || rested == 0L -> HabitMessage.Streak(chain(done, last))
            rested == 1L -> HabitMessage.MissedYesterday
            else -> HabitMessage.Resting(rested.toInt())
        }
    }

    /** [from]에서 거슬러 올라가며 한 날 수. 하루 쉼은 건너뛰고, 이틀 연속 쉬면 멈춘다. */
    private fun chain(done: Set<LocalDate>, from: LocalDate): Int {
        var day = from
        var count = 0
        var gap = 0
        val earliest = done.min()
        while (!day.isBefore(earliest)) {
            if (day in done) {
                count++
                gap = 0
            } else if (++gap >= 2) {
                break
            }
            day = day.minusDays(1)
        }
        return count
    }

    /** 지난주부터 연속으로 목표를 채운 주. 시작일이 든 주는 남은 날 수만큼만 요구한다. */
    private fun weekStreak(done: Set<LocalDate>, thisWeek: LocalDate, target: Int, startDate: LocalDate?): Int {
        var streak = 0
        var week = thisWeek.minusWeeks(1)
        repeat(MAX_WEEKS) {
            val end = week.plusDays(6)
            if (startDate != null && end.isBefore(startDate)) return streak
            val from = if (startDate != null && startDate.isAfter(week)) startDate else week
            val needed = minOf(target, (ChronoUnit.DAYS.between(from, end) + 1).toInt())
            val count = done.count { !it.isBefore(from) && !it.isAfter(end) }
            if (count < needed) return streak
            streak++
            week = week.minusWeeks(1)
        }
        return streak
    }

    /** HB-04 상태 문구(앱·위젯 공용). */
    fun label(status: HabitStatus): String = when (val m = status.message) {
        is HabitMessage.Streak -> "${m.days}일째"
        HabitMessage.MissedYesterday -> "어제 쉼 · 오늘 하면 이어짐"
        is HabitMessage.Resting -> "${m.days}일 쉼 · 오늘 다시"
        HabitMessage.NotStarted -> "오늘 시작"
        is HabitMessage.Weekly -> buildString {
            append("이번 주 ${m.count}/${m.target}")
            append(if (m.achieved) " 달성" else " · ${m.remaining}번 남음")
            if (m.streakWeeks > 0) append(" · ${m.streakWeeks}주 연속 달성")
        }
    }
}
