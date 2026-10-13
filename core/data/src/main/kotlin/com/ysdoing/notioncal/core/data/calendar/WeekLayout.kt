package com.ysdoing.notioncal.core.data.calendar

import com.ysdoing.notioncal.core.data.CalendarEvent
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** 한 주 안에 놓인 일정 막대. [startCol]~[startCol]+[span]-1 칸, [lane]번째 줄. */
data class PlacedEvent(
    val event: CalendarEvent,
    val startCol: Int,
    val span: Int,
    val lane: Int,
    /** 막대가 이전 주에서 이어지는지·다음 주로 이어지는지(모서리 표시용). */
    val continuesBefore: Boolean,
    val continuesAfter: Boolean,
)

data class WeekLayoutResult(
    val placed: List<PlacedEvent>,
    /** 요일 칸별로 표시하지 못한 일정 수(+N). */
    val hiddenCount: IntArray,
    /** 칸별로 +N을 그릴 줄 번호. 숨긴 일정이 없으면 -1. */
    val overflowLane: IntArray,
)

/**
 * MV-02 주 단위 배치.
 * 1. 여러 날 일정을 먼저 시작일 → 긴 순서로 가장 낮은 빈 줄에 놓고, 하루 일정은 받은 순서(DB·정렬 키·시간)대로 채운다.
 * 2. 칸에 [maxLanes]줄이 넘치면 그 칸의 마지막 줄을 +N 자리로 비운다. 막대가 걸친 어느 칸에서든 자리를 못 얻으면
 *    막대 전체를 숨기고, 걸친 모든 칸의 +N에 센다. 그래서 일정이 화면에서 빠지거나 겹치는 경우가 없다.
 */
object WeekLayout {
    fun layout(weekStart: LocalDate, events: List<CalendarEvent>, maxLanes: Int): WeekLayoutResult {
        val weekEnd = weekStart.plusDays(6)
        val inWeek = events.filter { !it.startDate.isAfter(weekEnd) && !it.lastDate.isBefore(weekStart) }
        val ordered = inWeek.filter { it.isMultiDay }
            .sortedWith(compareBy<CalendarEvent> { it.startDate }.thenByDescending { ChronoUnit.DAYS.between(it.startDate, it.lastDate) }) +
            inWeek.filterNot { it.isMultiDay }

        // 1단계: 줄 제한 없이 배치
        val occupied = mutableListOf<BooleanArray>()
        val all = ordered.map { event ->
            val start = maxOf(0, ChronoUnit.DAYS.between(weekStart, event.startDate).toInt())
            val end = minOf(6, ChronoUnit.DAYS.between(weekStart, event.lastDate).toInt())
            var lane = occupied.indexOfFirst { row -> (start..end).none { row[it] } }
            if (lane < 0) {
                occupied += BooleanArray(7)
                lane = occupied.lastIndex
            }
            (start..end).forEach { occupied[lane][it] = true }
            PlacedEvent(
                event = event,
                startCol = start,
                span = end - start + 1,
                lane = lane,
                continuesBefore = event.startDate.isBefore(weekStart),
                continuesAfter = event.lastDate.isAfter(weekEnd),
            )
        }

        // 2단계: 칸별 허용 줄 수를 줄여 가며 숨길 일정을 정한다(줄어들기만 하므로 반드시 끝난다).
        val lanes = maxLanes.coerceAtLeast(1)
        val limit = IntArray(7) { col -> if (all.count { it.covers(col) } > lanes) lanes - 1 else lanes }
        var visible: List<PlacedEvent>
        while (true) {
            visible = all.filter { p -> p.cols().all { p.lane < limit[it] } }
            var changed = false
            for (col in 0..6) {
                val hiddenHere = all.count { it.covers(col) } - visible.count { it.covers(col) }
                if (hiddenHere > 0 && limit[col] == lanes) {
                    limit[col] = lanes - 1
                    changed = true
                }
            }
            if (!changed) break
        }

        val hidden = IntArray(7) { col -> all.count { it.covers(col) } - visible.count { it.covers(col) } }
        val overflowLane = IntArray(7) { col -> if (hidden[col] > 0) limit[col] else -1 }
        return WeekLayoutResult(visible, hidden, overflowLane)
    }

    private fun PlacedEvent.cols() = startCol until startCol + span

    private fun PlacedEvent.covers(col: Int) = col in cols()
}
