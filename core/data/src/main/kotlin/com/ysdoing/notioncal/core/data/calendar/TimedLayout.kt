package com.ysdoing.notioncal.core.data.calendar

import com.ysdoing.notioncal.core.data.APP_ZONE
import com.ysdoing.notioncal.core.data.CalendarEvent
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId

/**
 * 시간표 보기(MV-06)에서 하루 칸 안의 시간 일정 블록. 분은 그날 0시부터.
 * [column]번째 열에 놓이고, 같이 겹친 묶음의 열 수가 [columns]다(블록 폭 = 칸 폭 / columns).
 */
data class TimedBlock(
    val event: CalendarEvent,
    val startMinute: Int,
    val endMinute: Int,
    val column: Int,
    val columns: Int,
)

/**
 * MV-06 시간 일정 배치.
 * - 종일 일정과 여러 날에 걸친 일정은 맨 위 종일 줄(WeekLayout)에 두므로 여기서는 하루 안의 시간 일정만 다룬다.
 * - 자정을 넘기면 그날 범위로 자른다. 아주 짧은 일정도 누를 수 있게 최소 [MIN_MINUTES]분 높이로 그린다.
 * - 겹치는 일정끼리 한 묶음으로 보고, 묶음 안에서 앞 열부터 빈 곳에 놓는다(구글·노션 캘린더와 같은 방식).
 */
object TimedLayout {
    const val MIN_MINUTES = 30
    private const val DAY_MINUTES = 24 * 60

    fun layout(date: LocalDate, events: List<CalendarEvent>, zone: ZoneId = APP_ZONE): List<TimedBlock> {
        val dayStart = date.atStartOfDay(zone).toInstant()
        val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant()
        val spans = events
            .filter { it.startTime != null && !it.isAllDay && !it.isMultiDay }
            .mapNotNull { event ->
                val start = event.startTime!!
                val end = event.endTime?.takeIf { it.isAfter(start) } ?: start.plusSeconds(3600)
                if (!end.isAfter(dayStart) || !start.isBefore(dayEnd)) return@mapNotNull null
                val from = Duration.between(dayStart, maxOf(start, dayStart)).toMinutes().toInt()
                val to = Duration.between(dayStart, minOf(end, dayEnd)).toMinutes().toInt()
                val shown = maxOf(to, from + MIN_MINUTES).coerceAtMost(DAY_MINUTES)
                Span(event, from.coerceAtMost(DAY_MINUTES - MIN_MINUTES), shown)
            }
            .sortedWith(compareBy<Span> { it.start }.thenByDescending { it.end })

        val result = ArrayList<TimedBlock>(spans.size)
        val cluster = mutableListOf<Pair<Span, Int>>()
        val columnEnds = mutableListOf<Int>()
        var clusterEnd = -1
        fun flush() {
            val count = columnEnds.size
            cluster.forEach { (span, col) -> result += TimedBlock(span.event, span.start, span.end, col, count) }
            cluster.clear()
            columnEnds.clear()
        }
        for (span in spans) {
            if (span.start >= clusterEnd) flush()
            var col = columnEnds.indexOfFirst { it <= span.start }
            if (col < 0) {
                columnEnds += span.end
                col = columnEnds.lastIndex
            } else {
                columnEnds[col] = span.end
            }
            cluster += span to col
            clusterEnd = maxOf(clusterEnd, span.end)
        }
        flush()
        return result
    }

    private data class Span(val event: CalendarEvent, val start: Int, val end: Int)
}
