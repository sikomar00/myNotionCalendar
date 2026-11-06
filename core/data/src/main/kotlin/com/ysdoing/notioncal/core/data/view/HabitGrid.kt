package com.ysdoing.notioncal.core.data.view

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.time.LocalDate

/** 습관 하루 칸: 그날 체크했는가(받아 둔 행의 체크박스, 또는 아직 안 보낸 그날 새 항목이 체크한 것). */
data class HabitCell(val date: LocalDate, val done: Boolean)

/** 습관(체크박스 속성) 하나의 7일 줄. [count]는 7일 중 체크한 날 수다. */
data class HabitLine(val propId: String, val name: String, val cells: List<HabitCell>) {
    val count: Int get() = cells.count { it.done }
}

/** 7일 격자: [days]는 오래된 날부터 오늘까지이고 [partial]이면 행을 일부만 받았다(100행 상한). */
data class HabitGridResult(val days: List<LocalDate>, val lines: List<HabitLine>, val partial: Boolean)

/**
 * Phase 12e RC-03 해빗 7일 격자(순수): 보기의 날짜 열과 습관 체크박스 열로, 오늘까지 최근 7일을 칸으로 만든다.
 * 같은 날 행이 여럿이면 하나라도 체크면 체크다. 열이 사라졌거나 체크박스가 아니면 그 습관은 뺀다(하나도 없으면 null).
 */
object HabitGrid {
    const val DAYS = 7

    fun compute(board: ViewBoard, dateProp: String, habitProps: List<String>, today: LocalDate): HabitGridResult? {
        val date = board.columnInfo.firstOrNull { it.id == dateProp && it.type == "date" } ?: return null
        val habits = habitProps.mapNotNull { id -> board.columnInfo.firstOrNull { it.id == id && it.type == "checkbox" } }
        if (habits.isEmpty()) return null
        val days = (DAYS - 1 downTo 0).map { today.minusDays(it.toLong()) }
        val rowsByDay = board.rows.groupBy { it.typed[date.id].startDate() }
        val lines = habits.map { habit ->
            val cells = days.map { day ->
                val checked = rowsByDay[day].orEmpty().any { (it.typed[habit.id] as? JsonPrimitive)?.booleanOrNull == true }
                HabitCell(day, checked || habit.id in board.pendingCreateChecks[day].orEmpty())
            }
            HabitLine(habit.id, habit.name, cells)
        }
        return HabitGridResult(days, lines, partial = !board.rowsComplete)
    }
}
