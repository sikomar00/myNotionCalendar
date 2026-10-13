package com.ysdoing.notioncal.core.data.calendar

import com.ysdoing.notioncal.core.data.APP_ZONE
import com.ysdoing.notioncal.core.data.CalendarEvent
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

// 앱 월 화면과 위젯이 같은 그리드·표시 규칙을 쓰도록 :core:data에 둔다(위젯은 :feature 모듈에 의존할 수 없음).

/** MV-01: 항상 6주. 첫 칸은 주 시작 요일에 맞춘다. */
fun gridStart(month: YearMonth, weekStart: DayOfWeek): LocalDate =
    month.atDay(1).with(TemporalAdjusters.previousOrSame(weekStart))

const val GRID_DAYS = 42L

private val chipTimeFormat = DateTimeFormatter.ofPattern("H:mm")

/** 시간 일정은 시작 시각을 앞에 붙인다(예: "9:00 회의"). 이전 주에서 이어진 막대에는 붙이지 않는다. */
fun chipLabel(event: CalendarEvent, continuesBefore: Boolean): String {
    val start = event.startTime
    if (event.isAllDay || start == null || continuesBefore) return event.title
    return "${start.atZone(APP_ZONE).format(chipTimeFormat)} ${event.title}"
}
