package com.ysdoing.notioncal.core.notion.mapping

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** 노션 날짜 속성 값. 날짜만 있는 값과 시간 포함 값을 섞지 않는다(NS-07, PRD §10). */
sealed interface NotionDate {
    /** 날짜만. 시간대 변환을 하지 않는다. end는 포함(inclusive). */
    data class AllDay(val start: LocalDate, val end: LocalDate?) : NotionDate

    data class Timed(val start: Instant, val end: Instant?) : NotionDate
}

object DateCodec {
    /** PRD §4: 표시·필터 기준 시간대. */
    val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
    private val SEOUL_OFFSET: ZoneOffset = ZoneOffset.ofHours(9)

    /**
     * 노션 `date` 객체의 start/end/time_zone을 해석한다. 형식이 틀리면 null.
     * 오프셋 없는 시간 값은 time_zone, 없으면 Asia/Seoul로 해석한다.
     */
    fun parse(start: String?, end: String?, timeZone: String? = null): NotionDate? {
        if (start.isNullOrBlank()) return null
        return runCatching {
            if (!start.contains('T')) {
                NotionDate.AllDay(LocalDate.parse(start), end?.takeIf { it.isNotBlank() }?.let { LocalDate.parse(it.take(10)) })
            } else {
                val zone = timeZone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: SEOUL
                NotionDate.Timed(parseInstant(start, zone), end?.takeIf { it.isNotBlank() }?.let { parseInstant(it, zone) })
            }
        }.getOrNull()
    }

    private fun parseInstant(value: String, zone: ZoneId): Instant =
        if (!value.contains('T')) {
            LocalDate.parse(value).atStartOfDay(zone).toInstant()
        } else {
            runCatching { OffsetDateTime.parse(value).toInstant() }
                .getOrElse { LocalDateTime.parse(value).atZone(zone).toInstant() }
        }

    /** 화면에 놓을 날짜 범위(시작일, 종료일 또는 null). 시간 일정은 Asia/Seoul 기준 날짜. */
    fun displayDates(date: NotionDate): Pair<LocalDate, LocalDate?> = when (date) {
        is NotionDate.AllDay -> date.start to date.end?.takeIf { it.isAfter(date.start) }
        is NotionDate.Timed -> {
            val s = date.start.atZone(SEOUL).toLocalDate()
            val e = date.end?.atZone(SEOUL)?.toLocalDate()?.takeIf { it.isAfter(s) }
            s to e
        }
    }

    /** 필터 값에는 항상 +09:00을 붙인다(§8.5-4). 시간대가 없으면 노션이 UTC로 해석한다. */
    fun filterStartOfDay(date: LocalDate): String =
        date.atStartOfDay().atOffset(SEOUL_OFFSET).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

    fun filterEndOfDay(date: LocalDate): String =
        date.atTime(23, 59, 59).atOffset(SEOUL_OFFSET).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

    fun filterInstant(instant: Instant): String =
        instant.atOffset(SEOUL_OFFSET).withNano(0).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
}
