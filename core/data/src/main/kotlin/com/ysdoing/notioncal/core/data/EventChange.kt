package com.ysdoing.notioncal.core.data

import com.ysdoing.notioncal.core.data.db.EventEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
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

    /**
     * MV-07: 일정을 [days]일 옮긴다. 기간 일정은 시작·끝을 함께 옮기고, 시간 일정은 Asia/Seoul 달력 기준으로 옮겨
     * 시각과 길이를 그대로 둔다.
     */
    fun shiftedDays(days: Long): DateValue {
        if (days == 0L) return this
        fun shiftEpoch(ms: Long) = Instant.ofEpochMilli(ms).atZone(APP_ZONE).plusDays(days).toInstant().toEpochMilli()
        return DateValue(
            startDate = LocalDate.parse(startDate).plusDays(days).toString(),
            endDate = endDate?.let { LocalDate.parse(it).plusDays(days).toString() },
            startTime = startTime?.let(::shiftEpoch),
            endTime = endTime?.let(::shiftEpoch),
        )
    }

    companion object {
        fun allDay(date: LocalDate, end: LocalDate? = null) = DateValue(date.toString(), end?.toString())
    }
}

/** 화면 모델의 날짜를 저장용 값으로 바꾼다. */
fun CalendarEvent.dateValue() = DateValue(
    startDate = startDate.toString(),
    endDate = endDate?.toString(),
    startTime = startTime?.toEpochMilli(),
    endTime = endTime?.toEpochMilli(),
)

/** 색 기준 select 값. [optionId]가 null이면 값을 비운다. */
@Serializable
data class SelectValue(val optionId: String?)

/**
 * EX-07 추가 속성 값. 타입에 따라 한 칸만 쓴다(select·status는 [optionIds]에 0~1개, multi_select는 0개 이상,
 * number는 [number], url은 [text]). 빈 목록·null은 '값 없음'이다.
 */
@Serializable
data class PropValue(
    val optionIds: List<String>? = null,
    val number: Double? = null,
    val text: String? = null,
)

/** EX-07 보기·편집할 수 있는 속성(data_sources.propCatalog). [type]은 노션 API 타입 이름. */
@Serializable
data class ExtraProp(
    val id: String,
    val name: String,
    val type: String,
    val options: List<ColorOption> = emptyList(),
)

/** 쓰기 1건에서 바뀐 필드만 담는다(PRD §8.5-6: 바뀐 속성만 전송). [props]는 EX-07 추가 속성(키는 속성 ID). */
@Serializable
data class EventChange(
    val title: String? = null,
    val date: DateValue? = null,
    val done: Boolean? = null,
    val select: SelectValue? = null,
    val props: Map<String, PropValue>? = null,
) {
    fun mergedWith(later: EventChange) = EventChange(
        title = later.title ?: title,
        date = later.date ?: date,
        done = later.done ?: done,
        select = later.select ?: select,
        props = if (props == null && later.props == null) null else props.orEmpty() + later.props.orEmpty(),
    )

    val isEmpty: Boolean get() = title == null && date == null && done == null && select == null && props.isNullOrEmpty()
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
    val extraValues: String? = null,
)

/** outbox JSON 직렬화. 모르는 키는 무시해 이후 필드 추가에도 이전 행을 읽을 수 있게 한다. */
val OutboxJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

fun EventEntity.snapshot() = EventSnapshot(
    pageId, dataSourceId, title, startDate.toString(), endDate?.toString(), startTime, endTime, isAllDay, done,
    colorKey, colorOptionId, sortKey, url, lastEditedTime, extraValues,
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
    extraValues = extraValues,
)

fun EventEntity.dateValue() = DateValue(startDate.toString(), endDate?.toString(), startTime, endTime)

/** [change]에 들어 있는 필드에 대해, 지금 값([this])을 같은 모양으로 담는다(충돌 판정용 기준값). */
fun EventEntity.valuesFor(change: EventChange) = EventChange(
    title = title.takeIf { change.title != null },
    date = dateValue().takeIf { change.date != null },
    done = done.takeIf { change.done != null },
    select = SelectValue(colorOptionId).takeIf { change.select != null },
    props = change.props?.let { wanted ->
        val current = parseExtraValues(extraValues)
        wanted.keys.associateWith { current[it] ?: PropValue() }
    },
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
    change.props?.let { props -> next = next.copy(extraValues = encodeExtraValues(parseExtraValues(next.extraValues) + props)) }
    return next
}

fun parseColorOptions(json: String?): List<ColorOption> =
    json?.let { runCatching { OutboxJson.decodeFromString<List<ColorOption>>(it) }.getOrNull() }.orEmpty()

fun parseExtraValues(json: String?): Map<String, PropValue> =
    json?.let { runCatching { OutboxJson.decodeFromString<Map<String, PropValue>>(it) }.getOrNull() }.orEmpty()

fun encodeExtraValues(values: Map<String, PropValue>): String? =
    values.takeIf { it.isNotEmpty() }?.let { OutboxJson.encodeToString(it) }

fun parsePropCatalog(json: String?): List<ExtraProp> =
    json?.let { runCatching { OutboxJson.decodeFromString<List<ExtraProp>>(it) }.getOrNull() }.orEmpty()

fun encodePropCatalog(props: List<ExtraProp>): String = OutboxJson.encodeToString(props)

fun parseIdList(json: String?): List<String> =
    json?.let { runCatching { OutboxJson.decodeFromString<List<String>>(it) }.getOrNull() }.orEmpty()

fun encodeIdList(ids: List<String>): String? = ids.takeIf { it.isNotEmpty() }?.let { OutboxJson.encodeToString(it) }
