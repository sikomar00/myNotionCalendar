package com.ysdoing.notioncal.core.notion.mapping

import com.ysdoing.notioncal.core.notion.dto.DataSourceDto
import com.ysdoing.notioncal.core.notion.dto.PageDto
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.LocalDate

enum class DoneType { CHECKBOX, STATUS }

/** 등록 화면에서 정한 속성 매핑. 모두 속성 ID다(PRD §8.3). */
data class PropertyMapping(
    val titlePropId: String,
    val datePropId: String,
    val donePropId: String?,
    val doneType: DoneType?,
    val colorPropId: String?,
    val sortPropId: String?,
)

/** 노션 페이지 1개를 앱 일정으로 옮긴 값. */
data class RemoteEvent(
    val pageId: String,
    val title: String,
    val startDate: LocalDate,
    val endDate: LocalDate?,
    val startTime: Instant?,
    val endTime: Instant?,
    val isAllDay: Boolean,
    val done: Boolean,
    /** 노션 색 이름(yellow, blue ...). null이면 DB 색을 쓴다. */
    val colorKey: String?,
    /** 정렬용 select 옵션 순서. null이면 맨 뒤. */
    val sortKey: Int?,
    /** 색 기준 select에서 고른 옵션 ID. */
    val colorOptionId: String? = null,
    val url: String,
    val lastEditedTime: Instant,
)

/** 페이지 해석 결과. 날짜가 없으면 [event]가 null이고 캘린더에서 빠진다. */
data class MappedPage(val pageId: String, val event: RemoteEvent?)

/**
 * 동기화 시점의 스키마(select 옵션 순서, status 그룹)를 함께 써서 페이지를 해석한다.
 * 매핑한 속성이 없거나 유형이 바뀌어도 예외 대신 기본값을 쓴다.
 */
class NotionPageMapper(
    private val mapping: PropertyMapping,
    schema: DataSourceDto,
) {
    private val completeStatusOptionIds: Set<String> = schema.properties.values
        .firstOrNull { it.id == mapping.donePropId && it.type == "status" }
        ?.status?.groups?.filter { it.name.equals(COMPLETE_GROUP, ignoreCase = true) }
        ?.flatMap { it.optionIds }?.toSet()
        .orEmpty()

    private val sortOrder: Map<String, Int> = schema.properties.values
        .firstOrNull { it.id == mapping.sortPropId }
        ?.select?.options?.mapIndexed { index, option -> option.id to index }?.toMap()
        .orEmpty()

    fun map(page: PageDto): MappedPage {
        val props = page.properties.values.mapNotNull { it as? JsonObject }
        fun prop(id: String?): JsonObject? = id?.let { wanted -> props.firstOrNull { it.str("id") == wanted } }

        val dateValue = prop(mapping.datePropId)?.obj("date")
        val date = DateCodec.parse(dateValue?.str("start"), dateValue?.str("end"), dateValue?.str("time_zone"))
        if (page.inTrash || date == null) return MappedPage(page.id, null)

        val (startDate, endDate) = DateCodec.displayDates(date)
        val title = prop(mapping.titlePropId)?.get("title").let { it as? JsonArray }
            ?.joinToString("") { (it as? JsonObject)?.str("plain_text").orEmpty() }
            .orEmpty()
        val done = when (mapping.doneType) {
            DoneType.CHECKBOX -> prop(mapping.donePropId)?.get("checkbox")?.jsonPrimitiveOrNull()?.booleanOrNull ?: false
            DoneType.STATUS -> prop(mapping.donePropId)?.obj("status")?.str("id") in completeStatusOptionIds
            null -> false
        }
        val colorValue = prop(mapping.colorPropId)?.let { it.obj("select") ?: it.obj("status") }
        val color = colorValue?.str("color")
        val sortKey = prop(mapping.sortPropId)?.obj("select")?.str("id")?.let { sortOrder[it] }

        return MappedPage(
            page.id,
            RemoteEvent(
                pageId = page.id,
                title = title,
                startDate = startDate,
                endDate = endDate,
                startTime = (date as? NotionDate.Timed)?.start,
                endTime = (date as? NotionDate.Timed)?.end,
                isAllDay = date is NotionDate.AllDay,
                done = done,
                colorKey = color?.takeIf { it != "default" },
                sortKey = sortKey,
                colorOptionId = colorValue?.str("id"),
                url = page.url,
                lastEditedTime = Instant.parse(page.lastEditedTime),
            ),
        )
    }

    companion object {
        /** status 속성의 완료 그룹 이름(API 응답 기준 "Complete"). */
        const val COMPLETE_GROUP = "Complete"
    }

    private fun JsonObject.obj(key: String): JsonObject? = get(key) as? JsonObject

    private fun JsonObject.str(key: String): String? = get(key)?.jsonPrimitiveOrNull()?.contentOrNull

    private fun JsonElement.jsonPrimitiveOrNull() = if (this is JsonNull || this is JsonObject || this is JsonArray) null else jsonPrimitive
}
