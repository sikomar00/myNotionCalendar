package com.ysdoing.notioncal.core.data.calendar

import com.ysdoing.notioncal.core.data.CalendarEvent
import java.time.LocalDate

/** 목록 보기의 하루. [items]의 두 번째 값은 '전날부터 이어지는 일정'인지(여러 날 일정의 둘째 날부터). */
data class AgendaDay(val date: LocalDate, val items: List<Pair<CalendarEvent, Boolean>>)

/**
 * MV-06 목록(아젠다) 보기: [from]~[to]에서 일정이 있는 날만 날짜순으로 묶는다.
 * 오늘과 [anchor](보기를 바꾸거나 이동해 온 기준 날짜)는 일정이 없어도 넣어, 그 날짜로 스크롤할 자리가 늘 있게 한다.
 * 여러 날 일정은 걸친 날마다 넣고, 둘째 날부터는 이어짐 표시를 한다. 하루 안 순서는 받은 순서(Room 정렬)를 따른다.
 */
object Agenda {
    fun build(events: List<CalendarEvent>, from: LocalDate, to: LocalDate, today: LocalDate, anchor: LocalDate? = null): List<AgendaDay> {
        val byDate = sortedMapOf<LocalDate, MutableList<Pair<CalendarEvent, Boolean>>>()
        for (event in events) {
            var date = maxOf(event.startDate, from)
            val last = minOf(event.lastDate, to)
            while (!date.isAfter(last)) {
                byDate.getOrPut(date) { mutableListOf() } += event to date.isAfter(event.startDate)
                date = date.plusDays(1)
            }
        }
        listOfNotNull(today, anchor).filter { it in from..to }.forEach { byDate.getOrPut(it) { mutableListOf() } }
        return byDate.map { (date, items) -> AgendaDay(date, items) }
    }
}
