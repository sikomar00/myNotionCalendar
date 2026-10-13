package com.ysdoing.notioncal.core.data

import com.ysdoing.notioncal.core.data.db.EventEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDate

/**
 * 일정 날짜 값. 날짜만 있는 일정은 시간이 null이고, 시간 일정은 Instant(epoch ms)로 둔다(NS-07).
 * [endDate]는 여러 날 일정의 마지막 날(포함).
 */
@Serializable
data class DateValue(
    val startDate: String,
    val endDate: String? = null,
    val startTime: Long? = null,
    val endTime: Long? = null,
) {
    val isTimed: Boolean get() = startTime != null

    companion object {
        fun allDay(date: LocalDate, end: LocalDate? = null) = DateValue(date.toString(), end?.toString())
    }
}

/** 색 기준 select 값. [optionId]가 null이면 값을 비운다. */
@Serializable
data class SelectValue(val optionId: String?)

/** 쓰기 1건에서 바뀐 필드만 담는다(PRD §8.5-6: 바뀐 속성만 전송). */
@Serializable
data class EventChange(
    val title: String? = null,
    val date: DateValue? = null,
    val done: Boolean? = null,
    val select: SelectValue? = null,
) {
    fun mergedWith(later: EventChange) = EventChange(
        title = later.title ?: title,
        date = later.date ?: date,
        done = later.done ?: done,
        select = later.select ?: select,
    )

    val isEmpty: Boolean get() = title == null && date == null && done == null && select == null
}

/** 색 기준 select 옵션(data_sources.colorOptions JSON). */
@Serializable
data class ColorOption(val id: String, val name: String, val color: String)

/** 삭제를 되돌릴 때 쓰는 일정 전체 사본(TRASH의 baseValues). */
@Serializable
data class EventSnapshot(
    val pageId: String,
    val dataSourceId: String,
    val title: String,
    val startDate: String,
    val endDate: String?,
    val startTime: Long?,
    val endTime: Long?,
    val isAllDay: Boolean,
    val done: Boolean,
    val colorKey: String?,
    val colorOptionId: String?,
    val sortKey: Int?,
    val url: String,
    val lastEditedTime: Long,
)

/** outbox JSON 직렬화. 모르는 키는 무시해 이후 필드 추가에도 이전 행을 읽을 수 있게 한다. */
val OutboxJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

fun EventEntity.snapshot() = EventSnapshot(
    pageId, dataSourceId, title, startDate.toString(), endDate?.toString(), startTime, endTime, isAllDay, done,
    colorKey, colorOptionId, sortKey, url, lastEditedTime,
)

fun EventSnapshot.toEntity() = EventEntity(
    pageId = pageId,
    dataSourceId = dataSourceId,
    title = title,
    startDate = LocalDate.parse(startDate),
    endDate = endDate?.let(LocalDate::parse),
    startTime = startTime,
    endTime = endTime,
    isAllDay = isAllDay,
    done = done,
    colorKey = colorKey,
    sortKey = sortKey,
    url = url,
    lastEditedTime = lastEditedTime,
    colorOptionId = colorOptionId,
)

fun EventEntity.dateValue() = DateValue(startDate.toString(), endDate?.toString(), startTime, endTime)

/** [change]에 들어 있는 필드에 대해, 지금 값([this])을 같은 모양으로 담는다(충돌 판정용 기준값). */
fun EventEntity.valuesFor(change: EventChange) = EventChange(
    title = title.takeIf { change.title != null },
    date = dateValue().takeIf { change.date != null },
    done = done.takeIf { change.done != null },
    select = SelectValue(colorOptionId).takeIf { change.select != null },
)

/** 일정에 변경을 적용한다. select 색은 DB의 옵션 목록에서 찾는다. */
fun EventEntity.applying(change: EventChange, colorOptions: List<ColorOption>): EventEntity {
    var next = this
    change.title?.let { next = next.copy(title = it) }
    change.date?.let { d ->
        next = next.copy(
            startDate = LocalDate.parse(d.startDate),
            endDate = d.endDate?.let(LocalDate::parse)?.takeIf { it.isAfter(LocalDate.parse(d.startDate)) },
            startTime = d.startTime,
            endTime = d.endTime,
            isAllDay = !d.isTimed,
        )
    }
    change.done?.let { next = next.copy(done = it) }
    change.select?.let { s ->
        val option = colorOptions.firstOrNull { it.id == s.optionId }
        next = next.copy(colorOptionId = s.optionId, colorKey = option?.color?.takeIf { it != "default" })
    }
    return next
}

fun parseColorOptions(json: String?): List<ColorOption> =
    json?.let { runCatching { OutboxJson.decodeFromString<List<ColorOption>>(it) }.getOrNull() }.orEmpty()
