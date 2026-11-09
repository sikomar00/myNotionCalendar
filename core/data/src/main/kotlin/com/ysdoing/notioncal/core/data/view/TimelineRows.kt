package com.ysdoing.notioncal.core.data.view

import com.ysdoing.notioncal.core.data.CalendarEvent
import com.ysdoing.notioncal.core.data.habit.HabitStats
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

/** 타임라인 확대 단계: 한 주(7일) 또는 한 달. [AUTO]는 노션 설정이 없거나 모르는 값이라 위젯 크기로 정한다. */
enum class TimelineZoom { AUTO, WEEK, MONTH }

/**
 * Phase 12e VR-TML: 노션 타임라인 보기 설정(`configuration.preference.zoom_level`)에서 확대 단계만 읽는다. 실제 값은 가려져 있어 **실측 전**이다:
 * day·week 계열은 [TimelineZoom.WEEK], month·quarter·year 계열은 [TimelineZoom.MONTH], 그 밖은 [TimelineZoom.AUTO]다.
 */
object TimelineConfig {
    private val json = Json { ignoreUnknownKeys = true }

    fun zoomOf(raw: JsonObject?): TimelineZoom {
        val level = ((raw?.get("preference") as? JsonObject)?.get("zoom_level") as? JsonPrimitive)?.contentOrNull.orEmpty().lowercase()
        return when {
            "month" in level || "quarter" in level || "year" in level -> TimelineZoom.MONTH
            "day" in level || "week" in level || "hour" in level -> TimelineZoom.WEEK
            else -> TimelineZoom.AUTO
        }
    }

    /** 저장된 `viewConfig` 글자에서(위젯). 빈 글자·깨진 글자는 자동이다. */
    fun zoomOf(text: String): TimelineZoom =
        zoomOf(text.takeIf { it.isNotBlank() }?.let { runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull() })
}

/** 타임라인 창: [start]부터 [days]일. */
data class TimelineWindow(val start: LocalDate, val days: Int) {
    val last: LocalDate get() = start.plusDays(days - 1L)
}

/** 줄 하나의 막대: 창 안의 시작 칸 [startCol]과 폭 [span](칸 수), 창 앞·뒤로 이어지는지. */
data class TimelineBar(val event: CalendarEvent, val startCol: Int, val span: Int, val continuesBefore: Boolean, val continuesAfter: Boolean)

/**
 * Phase 12e VR-TML 타임라인 막대(순수). 일정 변환은 캘린더와 같은 [CalendarRows.events]를 쓰고(날짜가 없는 행은 이미 빠진다),
 * 창과 겹치는 일정을 보기 순서대로 한 줄씩 막대로 만든다(줄 하나에 일정 하나, 겹침 계산 없음). 창 밖은 뺀다.
 */
object TimelineRows {
    /** 이번 주(위젯의 [offset]주 뒤) 또는 이번 달([offset]달 뒤)의 창. 주는 [weekStart] 요일에 시작한다. */
    fun window(zoom: TimelineZoom, today: LocalDate, weekStart: DayOfWeek, offset: Int): TimelineWindow = when (zoom) {
        TimelineZoom.MONTH -> YearMonth.from(today).plusMonths(offset.toLong()).let { TimelineWindow(it.atDay(1), it.lengthOfMonth()) }
        else -> TimelineWindow(HabitStats.weekStartOf(today, weekStart).plusWeeks(offset.toLong()), WEEK_DAYS)
    }

    fun bars(board: ViewBoard, window: TimelineWindow): List<TimelineBar> = bars(CalendarRows.events(board), window)

    fun bars(events: List<CalendarEvent>, window: TimelineWindow): List<TimelineBar> = events.mapNotNull { event ->
        val last = event.lastDate
        if (last.isBefore(window.start) || event.startDate.isAfter(window.last)) return@mapNotNull null
        val from = maxOf(0L, ChronoUnit.DAYS.between(window.start, event.startDate)).toInt()
        val to = minOf(window.days - 1L, ChronoUnit.DAYS.between(window.start, last)).toInt()
        TimelineBar(event, from, to - from + 1, event.startDate.isBefore(window.start), last.isAfter(window.last))
    }

    private const val WEEK_DAYS = 7
}
