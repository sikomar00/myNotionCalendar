package com.ysdoing.notioncal.core.data.view

import com.ysdoing.notioncal.core.data.CalendarEvent
import com.ysdoing.notioncal.core.data.NotionColors
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * Phase 12a VR-CAL 캘린더 보기의 행을 월 격자가 그리는 [CalendarEvent]로 바꾼다(순수). 날짜는 보기의 날짜 열([ViewBoard.dateColumn], 캘린더의
 * `date_property_id`는 숨긴 열이어도)에서 읽는다. 날짜만 있으면 종일(끝 날짜 포함), 시각이 있으면 시간 일정이고 시각의 날짜는 Asia/Seoul 기준이다
 * (노션 `date` 객체 해석은 `:core:notion`의 `DateCodec`과 같은 규칙이지만 `:widget`이 그 모듈에 의존할 수 없어 여기에 작게 둔다).
 */
object CalendarRows {
    /** 색 기준 속성이 없거나 값이 없을 때의 일정 색(노션 기본 회색). */
    const val FALLBACK_HEX = "#9B9A97"

    private const val UNTITLED = "제목 없음"
    private val SEOUL = ZoneId.of("Asia/Seoul")
    private val DONE_GROUPS = setOf("complete", "completed", "done", "완료")

    private sealed interface Parsed {
        data class AllDay(val start: LocalDate, val end: LocalDate?) : Parsed

        data class Timed(val start: Instant, val end: Instant?) : Parsed
    }

    /** 합쳐 보기의 소스 하나: 보기, 기준 날짜 속성 ID(비면 보기의 기본 날짜 열), 일정 색 `#RRGGBB`(비면 그 일정의 옵션 색). */
    data class Source(val board: ViewBoard, val dateColumnId: String = "", val colorHex: String = "")

    /**
     * Phase 12e WL-04 합쳐 보기: 소스들의 일정을 한 목록으로. 순서는 시작일, 시각 없는 것 먼저, 시각, 소스 순서, 소스 안의 순서다(같은 날 같은 시각이면
     * 소스 순서가 결정적이다). 소스의 날짜 속성이 보기에 없거나 날짜 열이 아니면 그 보기의 기본 날짜 열로 읽는다.
     */
    fun merged(sources: List<Source>): List<CalendarEvent> =
        sources.flatMap { source -> events(source.board, source.dateColumnId).map { if (source.colorHex.isEmpty()) it else it.copy(colorHex = source.colorHex) } }
            .sortedWith(compareBy({ it.startDate }, { it.startTime != null }, { it.startTime }))

    /** 날짜가 있는 행만. 순서는 v1과 같게 시작일, 시각 없는 것 먼저, 시각, 보기의 원래 순서다. */
    fun events(board: ViewBoard, dateColumnId: String = ""): List<CalendarEvent> {
        val dateColumn = board.columnInfo.firstOrNull { it.id == dateColumnId && it.type == "date" } ?: board.dateColumn ?: return emptyList()
        val titleId = board.columnInfo.firstOrNull { it.type == "title" }?.id
        val coloring = board.columnInfo.firstOrNull { (it.type == "status" || it.type == "select") && it.options.isNotEmpty() }
        val box = board.columnInfo.firstOrNull { it.type == "checkbox" }
        val status = board.columnInfo.firstOrNull { it.type == "status" && it.options.any { option -> option.group?.lowercase() in DONE_GROUPS } }
        return board.rows.mapIndexedNotNull { index, row ->
            val parsed = parse(row.typed[dateColumn.id]) ?: return@mapIndexedNotNull null
            val option = coloring?.let { column -> optionOf(column, row) }
            val checked = box?.let { (row.typed[it.id] as? JsonPrimitive)?.booleanOrNull } == true
            val complete = status?.let { optionOf(it, row)?.group?.lowercase() in DONE_GROUPS } == true
            val hex = option?.color?.takeIf { it != "default" }?.let(NotionColors::hexFor) ?: FALLBACK_HEX
            val title = titleId?.let { (row.typed[it] as? JsonPrimitive)?.contentOrNull }?.trim().orEmpty().ifEmpty { UNTITLED }
            index to event(row, board.viewId, title, parsed, checked || complete, hex, option?.id, row.pageId in board.pendingPageIds)
        }.sortedWith(compareBy({ it.second.startDate }, { it.second.startTime != null }, { it.second.startTime }, { it.first })).map { it.second }
    }

    private fun optionOf(column: ViewColumn, row: ViewRow): ViewChoice? =
        (row.typed[column.id] as? JsonPrimitive)?.contentOrNull?.let { id -> column.options.firstOrNull { it.id == id } }

    private fun event(row: ViewRow, viewId: String, title: String, parsed: Parsed, done: Boolean, hex: String, colorOptionId: String?, pending: Boolean): CalendarEvent = when (parsed) {
        is Parsed.AllDay -> CalendarEvent(
            row.pageId, viewId, title, parsed.start, parsed.end?.takeIf { it.isAfter(parsed.start) }, null, null, true, done, hex, FALLBACK_HEX, row.url,
            pending, colorOptionId,
        )
        is Parsed.Timed -> {
            val start = parsed.start.atZone(SEOUL).toLocalDate()
            val end = parsed.end?.atZone(SEOUL)?.toLocalDate()?.takeIf { it.isAfter(start) }
            CalendarEvent(row.pageId, viewId, title, start, end, parsed.start, parsed.end, false, done, hex, FALLBACK_HEX, row.url, pending, colorOptionId)
        }
    }

    /** `{"start":…,"end":…,"time_zone":…}`. 시작이 없거나 날짜로 읽히지 않으면 null이다. */
    private fun parse(value: JsonElement?): Parsed? {
        val date = value as? JsonObject ?: return null
        fun text(key: String) = (date[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        val start = text("start") ?: return null
        val end = text("end")
        return runCatching {
            if ('T' !in start) {
                Parsed.AllDay(LocalDate.parse(start), end?.let { LocalDate.parse(it.take(DATE_LENGTH)) })
            } else {
                val zone = text("time_zone")?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: SEOUL
                Parsed.Timed(instant(start, zone), end?.let { instant(it, zone) })
            }
        }.getOrNull()
    }

    private fun instant(value: String, zone: ZoneId): Instant =
        if ('T' !in value) LocalDate.parse(value).atStartOfDay(zone).toInstant()
        else runCatching { OffsetDateTime.parse(value).toInstant() }.getOrElse { LocalDateTime.parse(value).atZone(zone).toInstant() }

    private const val DATE_LENGTH = 10
}
