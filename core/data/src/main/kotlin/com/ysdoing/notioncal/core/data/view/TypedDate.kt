package com.ysdoing.notioncal.core.data.view

import com.ysdoing.notioncal.core.data.APP_ZONE
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * 날짜 속성 값(`{"start","end","time_zone"}`)의 편집·계산용 순수 모델(Phase 12c). 시작·끝은 노션이 준 글자 그대로다
 * (`2026-11-04` 또는 `2026-11-04T09:00:00.000+09:00`). 종일·시간·기간 어느 모양이든 날짜 부분만 바꾸면 시각·오프셋·시간대는 그대로 남는다.
 * 행 편집 시트(날짜·시간 칩)와 액션 버튼('내일로' = [shiftedDays])이 같은 규칙을 쓴다.
 */
data class TypedDate(val start: String, val end: String? = null, val timeZone: String? = null) {
    fun startDay(): LocalDate = LocalDate.parse(start.substringBefore('T'))

    fun endDay(): LocalDate? = end?.let { LocalDate.parse(it.substringBefore('T')) }

    /** 시작에 시각이 있다(시간 일정). 없으면 종일이다. */
    val isTimed: Boolean get() = 'T' in start

    fun startTime(): LocalTime? = timeOf(start)

    fun endTime(): LocalTime? = end?.let(::timeOf)

    /** 시작 날짜를 바꾼다. 끝이 새 시작보다 앞서면 같은 날로 끌어 올린다. */
    fun withStart(day: LocalDate): TypedDate {
        val moved = copy(start = start.withDay(day))
        return (if (endDay()?.isBefore(day) == true) moved.copy(end = end?.withDay(day)) else moved).ordered()
    }

    /** 끝 날짜를 바꾼다. 시작보다 앞이면 시작과 같은 날이다. */
    fun withEnd(day: LocalDate): TypedDate = copy(end = (end ?: start).withDay(maxOf(day, startDay()))).ordered()

    fun withoutEnd(): TypedDate = copy(end = null)

    /**
     * 시각을 정한다(종일이면 시간 일정이 된다). 오프셋은 기존 시각 글자의 것을 따르고, 종일에서 바꿀 때는 앱 시간대(서울)의 그날 오프셋이다.
     * 끝은 이미 있거나 끝 시각이 시작과 다를 때만 둔다(같은 시각 하나짜리 시간 일정은 끝이 없다). 같은 날 끝 시각이 시작보다 앞서면 시작과 같게 한다.
     */
    fun withTimes(startTime: LocalTime, endTime: LocalTime): TypedDate {
        val newStart = stamp(startDay(), startTime, start)
        val newEnd = if (end != null || endTime != startTime) stamp(endDay() ?: startDay(), endTime, end ?: start) else null
        return copy(start = newStart, end = newEnd).ordered()
    }

    /** 시각을 지워 종일로 만든다. 시간대는 시각이 있을 때만 뜻이 있어 버리고, 같은 날로 줄어든 끝은 둘 필요가 없다. */
    fun asAllDay(): TypedDate = copy(start = startDay().toString(), end = endDay()?.takeIf { it.isAfter(startDay()) }?.toString(), timeZone = null)

    /** 시작·끝을 함께 [days]일 옮긴다(음수는 앞으로). 종일·시간·기간 모두 시각·오프셋·시간대는 그대로다. */
    fun shiftedDays(days: Long): TypedDate = copy(start = start.withDay(startDay().plusDays(days)), end = end?.withDay(endDay()!!.plusDays(days)))

    /** 같은 날이고 둘 다 시간이 있으면 끝 시간이 시작보다 앞서지 않게 한다(앞서면 노션이 거부한다). 시간 오프셋이 같다고 보고 글자로 견준다. */
    private fun ordered(): TypedDate {
        val e = end ?: return this
        val sameDay = e.substringBefore('T') == start.substringBefore('T')
        val timed = 'T' in e && 'T' in start
        return if (sameDay && timed && e.substringAfter('T') < start.substringAfter('T')) copy(end = start) else this
    }

    fun toJson(): JsonObject = JsonObject(
        buildMap {
            put("start", JsonPrimitive(start))
            end?.let { put("end", JsonPrimitive(it)) }
            timeZone?.let { put("time_zone", JsonPrimitive(it)) }
        },
    )

    companion object {
        private val TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

        /** 값이 날짜 객체가 아니거나 시작이 없으면 null(날짜 없음). */
        fun parse(value: JsonElement?): TypedDate? {
            val json = value as? JsonObject ?: return null
            fun text(key: String) = (json[key] as? JsonPrimitive)?.contentOrNull
            val start = text("start") ?: return null
            return TypedDate(start, text("end"), text("time_zone"))
        }

        /** 오늘(앱 시간대) 하루짜리 종일 날짜. */
        fun today(): TypedDate = TypedDate(LocalDate.now(APP_ZONE).toString())

        /** 날짜 글자의 날짜 부분만 바꾸고 시간 부분(`T…`)은 그대로 둔다. */
        private fun String.withDay(day: LocalDate): String = if ('T' in this) day.toString() + substring(indexOf('T')) else day.toString()

        private fun timeOf(text: String): LocalTime? {
            if ('T' !in text) return null
            return runCatching { LocalTime.parse(text.substringAfter('T').takeWhile { it != 'Z' && it != '+' && it != '-' }) }.getOrNull()
        }

        /** [like]가 시간 글자면 그 오프셋 꼬리(`+09:00`·`Z`, 없으면 빈 글자)를, 아니면 앱 시간대의 그날 오프셋을 붙여 새 시각 글자를 만든다. */
        private fun stamp(day: LocalDate, time: LocalTime, like: String): String {
            val tail = if ('T' in like) like.substringAfter('T').let { t -> t.indexOfFirst { it == 'Z' || it == '+' || it == '-' }.let { i -> if (i < 0) "" else t.substring(i) } }
            else ZonedDateTime.of(day, time, APP_ZONE).offset.id
            return day.toString() + "T" + time.format(TIME) + tail
        }
    }
}
