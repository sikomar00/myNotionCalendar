package com.ysdoing.notioncal.core.data.habit

import com.ysdoing.notioncal.core.data.habit.HabitMessage.MissedYesterday
import com.ysdoing.notioncal.core.data.habit.HabitMessage.NotStarted
import com.ysdoing.notioncal.core.data.habit.HabitMessage.Resting
import com.ysdoing.notioncal.core.data.habit.HabitMessage.Streak
import com.ysdoing.notioncal.core.data.habit.HabitMessage.Weekly
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

/** HB-04 '다시 시작' 표시, HB-05 주 목표. 날짜는 2026-10(10/18 일요일, 10/19 월요일). */
class HabitStatsTest {
    private val sunday = DayOfWeek.SUNDAY
    private fun d(day: Int) = LocalDate.of(2026, 10, day)
    private fun days(vararg day: Int) = day.map(::d).toSet()

    private fun daily(done: Set<LocalDate>, today: LocalDate, start: LocalDate? = null) =
        HabitStats.status(done, today, sunday, weeklyTarget = 7, startDate = start)

    // §12: 연속 5일 → 1일 공백 → 4일 공백에서 '5일째' → '어제 쉼 · 오늘 하면 이어짐' → '4일 쉼 · 오늘 다시'.
    @Test
    fun streakThenOneDayGapThenLongGap() {
        val five = days(15, 16, 17, 18, 19)
        assertEquals(Streak(5), daily(five, d(19)).message)
        assertEquals("5일째", HabitStats.label(daily(five, d(19))))

        val missed = daily(five, d(21))
        assertEquals(MissedYesterday, missed.message)
        assertTrue("오늘 칸을 강조한다", missed.highlightToday)
        assertEquals("어제 쉼 · 오늘 하면 이어짐", HabitStats.label(missed))

        val resting = daily(five, d(24))
        assertEquals(Resting(4), resting.message)
        assertEquals("4일 쉼 · 오늘 다시", HabitStats.label(resting))
        assertFalse(resting.highlightToday)
    }

    @Test
    fun streakIsNotResetToZeroBeforeTodaysCheck() {
        // 어제까지 5일, 오늘은 아직: 여전히 '5일째'(0으로 되돌리지 않는다).
        assertEquals(Streak(5), daily(days(14, 15, 16, 17, 18), d(19)).message)
    }

    @Test
    fun checkingTodayAfterOneDayGapContinuesStreak() {
        // 5일 → 하루 쉼 → 오늘 체크: 이어져서 6일째.
        assertEquals(Streak(6), daily(days(13, 14, 15, 16, 17, 19), d(19)).message)
        // 이틀 쉬고 오늘 체크하면 다시 1일째.
        assertEquals(Streak(1), daily(days(13, 14, 15, 16, 19), d(19)).message)
    }

    @Test
    fun firstDayAndNeverDone() {
        val first = daily(emptySet(), d(19), start = d(19))
        assertEquals(NotStarted, first.message)
        assertEquals("오늘 시작", HabitStats.label(first))
        // 시작한 지 며칠이 지났어도 한 번도 안 했으면 '쉼'이라 하지 않는다.
        assertEquals(NotStarted, daily(emptySet(), d(25), start = d(19)).message)
    }

    @Test
    fun daysBeforeStartAreNotCountedAsGaps() {
        // 옛 기록(10/1~10/5) 뒤 시작일을 10/18로 다시 잡았다: 그 사이 공백을 세지 않고 새로 시작.
        val restarted = daily(days(1, 2, 3, 4, 5), d(19), start = d(18))
        assertEquals(NotStarted, restarted.message)
        val marks = restarted.last7
        assertEquals((13..19).map(::d), marks.map { it.date })
        assertEquals(listOf(true, true, true, true, true, false, false), marks.map { it.beforeStart })
        // 시작일 이후 기록만 이어서 센다.
        assertEquals(Streak(2), daily(days(1, 2, 18, 19), d(19), start = d(18)).message)
    }

    @Test
    fun monthCountIsAlwaysShownAndDuplicatesCountOnce() {
        val status = daily(days(1, 2, 15, 19, 30), d(19))
        assertEquals(4, status.monthCount) // 30일은 아직 오지 않음
        assertEquals(listOf(false, false, true, false, false, false, true), status.last7.map { it.done })
    }

    // HB-05: 주 3회 습관을 월·수·금 체크 → 같은 주 목·토가 비어도 달성.
    @Test
    fun weeklyTargetIsMetWithRestDays() {
        val monWedFri = days(19, 21, 23)
        val saturday = HabitStats.status(monWedFri, d(24), sunday, weeklyTarget = 3, startDate = d(1))
        assertTrue(saturday.weekAchieved)
        assertEquals(3, saturday.weekCount)
        assertEquals(3, saturday.weekTarget)
        assertEquals(Weekly(count = 3, target = 3, streakWeeks = 0), saturday.message)
        assertEquals("이번 주 3/3 달성", HabitStats.label(saturday))

        val wednesday = HabitStats.status(days(19, 21), d(21), sunday, weeklyTarget = 3, startDate = d(1))
        assertEquals("이번 주 2/3 · 1번 남음", HabitStats.label(wednesday))
        assertFalse(wednesday.highlightToday) // 주 단위 습관은 날짜 단위 '쉼'을 쓰지 않는다
    }

    @Test
    fun weeklyStreakCountsPastWeeksUntilLastWeek() {
        // 일요일 시작 주: 10/4~10/10, 10/11~10/17 모두 3회 이상, 9/27~10/3은 1회.
        val done = days(2, 5, 7, 9, 12, 14, 16)
        val status = HabitStats.status(done, d(19), sunday, weeklyTarget = 3, startDate = null)
        assertEquals(Weekly(count = 0, target = 3, streakWeeks = 2), status.message)
        assertEquals("이번 주 0/3 · 3번 남음 · 2주 연속 달성", HabitStats.label(status))
    }

    @Test
    fun weekBoundaryFollowsWeekStartSetting() {
        val done = days(18, 19) // 일요일·월요일
        assertEquals(2, HabitStats.status(done, d(19), DayOfWeek.SUNDAY, 3, null).weekCount)
        assertEquals(1, HabitStats.status(done, d(19), DayOfWeek.MONDAY, 3, null).weekCount)
    }

    @Test
    fun partialStartWeekNeedsOnlyAvailableDays() {
        // 10/15(목) 시작, 그 주(10/11~10/17)에 남은 날은 목·금·토 3일. 주 5회 목표라도 3번 하면 달성으로 친다.
        val status = HabitStats.status(days(15, 16, 17), d(19), sunday, weeklyTarget = 5, startDate = d(15))
        assertEquals(1, (status.message as Weekly).streakWeeks)
    }

    @Test
    fun longStreakBeyondSixWeeks() {
        // 검증 메모 6: 42일을 넘는 연속도 끊기지 않고 센다.
        val done = (0L until 60L).map { d(19).minusDays(it) }.toSet()
        assertEquals(Streak(60), daily(done, d(19)).message)
    }
}
