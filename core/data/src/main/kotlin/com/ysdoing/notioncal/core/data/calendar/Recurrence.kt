package com.ysdoing.notioncal.core.data.calendar

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

enum class RepeatFreq { DAILY, WEEKLY, MONTHLY, YEARLY }

/**
 * NS-10 반복 규칙. 노션 항목은 반복이 없으므로 앱이 날짜를 펼쳐 페이지를 하나씩 만든다(만든 뒤에는 서로 독립).
 * - [interval]: N일·주·달·해마다(1~12).
 * - [weekdays]: 매주일 때 요일들(비면 시작일의 요일).
 * - 끝은 [until](그날까지) 또는 [count](몇 개)로 정한다. 둘 다 없으면 [Recurrence.MAX]개까지.
 */
data class RepeatRule(
    val freq: RepeatFreq,
    val interval: Int = 1,
    val weekdays: Set<DayOfWeek> = emptySet(),
    val until: LocalDate? = null,
    val count: Int? = null,
) {
    /** 저장·복원용 글자("WEEKLY|1|MONDAY,WEDNESDAY|2026-12-31|"). */
    fun encode(): String = listOf(
        freq.name,
        interval.toString(),
        weekdays.sorted().joinToString(",") { it.name },
        until?.toString().orEmpty(),
        count?.toString().orEmpty(),
    ).joinToString("|")

    companion object {
        fun decode(text: String): RepeatRule? = runCatching {
            val (freq, interval, days, until, count) = text.split('|')
            RepeatRule(
                freq = RepeatFreq.valueOf(freq),
                interval = interval.toInt(),
                weekdays = days.split(',').filter { it.isNotBlank() }.map { DayOfWeek.valueOf(it) }.toSet(),
                until = until.takeIf { it.isNotBlank() }?.let(LocalDate::parse),
                count = count.takeIf { it.isNotBlank() }?.toInt(),
            )
        }.getOrNull()
    }
}

/** NS-10 반복 펼치기(순수 함수). 시작일을 포함하고, 한 번에 [MAX]개를 넘지 않는다. */
object Recurrence {
    const val MAX = 100
    const val MAX_INTERVAL = 12

    /** 무한 반복을 막는 안전장치(규칙이 날짜를 거의 못 만드는 경우, 예: 매년 2/29). */
    private const val MAX_STEPS = 5_000

    fun expand(start: LocalDate, rule: RepeatRule): List<LocalDate> {
        val interval = rule.interval.coerceIn(1, MAX_INTERVAL)
        val limit = (rule.count ?: MAX).coerceIn(1, MAX)
        val result = mutableListOf<LocalDate>()
        fun accept(date: LocalDate): Boolean {
            if (rule.until != null && date.isAfter(rule.until)) return false
            if (!date.isBefore(start)) result += date
            return result.size < limit
        }
        when (rule.freq) {
            RepeatFreq.DAILY -> {
                var step = 0
                while (step < MAX_STEPS && accept(start.plusDays(step.toLong() * interval))) step++
            }
            RepeatFreq.WEEKLY -> {
                val days = rule.weekdays.ifEmpty { setOf(start.dayOfWeek) }.sorted()
                val firstWeek = start.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                var week = 0
                outer@ while (week < MAX_STEPS) {
                    val monday = firstWeek.plusWeeks(week.toLong() * interval)
                    for (day in days) {
                        val date = monday.plusDays((day.value - 1).toLong())
                        if (!accept(date)) break@outer
                    }
                    week++
                }
            }
            RepeatFreq.MONTHLY, RepeatFreq.YEARLY -> {
                val months = if (rule.freq == RepeatFreq.MONTHLY) interval.toLong() else interval * 12L
                var step = 0
                while (step < MAX_STEPS) {
                    val month = start.withDayOfMonth(1).plusMonths(step * months)
                    step++
                    // 그 날짜가 없는 달(31일·2월 29일)은 건너뛴다.
                    if (start.dayOfMonth > month.lengthOfMonth()) {
                        if (rule.until != null && month.isAfter(rule.until)) break
                        continue
                    }
                    if (!accept(month.withDayOfMonth(start.dayOfMonth))) break
                }
            }
        }
        return result
    }

    /** 미리보기용: 두 날짜 사이 날 수(기간 일정 길이 유지에 쓴다). */
    fun spanDays(start: LocalDate, end: LocalDate?): Long = end?.let { ChronoUnit.DAYS.between(start, it) } ?: 0
}
