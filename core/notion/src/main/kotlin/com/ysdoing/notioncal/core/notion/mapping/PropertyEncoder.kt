package com.ysdoing.notioncal.core.notion.mapping

import com.ysdoing.notioncal.core.notion.dto.DataSourceDto
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** 노션에 쓸 날짜. 날짜만이면 시간이 null이다(NS-07). */
data class WriteDate(
    val startDate: LocalDate,
    val endDate: LocalDate? = null,
    val start: Instant? = null,
    val end: Instant? = null,
)

/** 한 번에 쓸 값. null인 필드는 보내지 않는다(바뀐 속성만, §8.5-6). [selectOptionId]는 [setSelect]가 true일 때만 쓴다. */
data class PageWrite(
    val title: String? = null,
    val date: WriteDate? = null,
    val done: Boolean? = null,
    val setSelect: Boolean = false,
    val selectOptionId: String? = null,
)

/**
 * PRD §8.3 쓰기 규칙. status 완료는 완료 그룹 첫 옵션([doneOption]), 해제는 할 일 그룹 첫 옵션([undoneOption])으로 쓴다.
 *
 * properties 키: 매핑은 속성 ID로 저장하지만, 보낼 때는 [keyFor]로 **그 순간 스키마의 속성 이름**을 쓴다.
 * `e%3D~%40`처럼 퍼센트 인코딩된 ID를 키로 보내면 노션이 오류 없이 무시한다(실기기 확인: 날짜가 빠진 채 생성됨).
 * 이름은 보낼 때마다 스키마에서 다시 찾으므로 노션에서 속성 이름을 바꿔도 끊기지 않는다.
 */
class PropertyEncoder(
    private val mapping: PropertyMapping,
    private val doneOption: String?,
    private val undoneOption: String?,
    private val keyFor: (propertyId: String) -> String = { it },
) {
    /** 스키마로 ID → 이름을 찾는 인코더. 스키마에 없는 ID는 그대로 쓴다. */
    constructor(mapping: PropertyMapping, doneOption: String?, undoneOption: String?, schema: DataSourceDto) :
        this(mapping, doneOption, undoneOption, namesById(schema))

    fun encode(write: PageWrite): JsonObject = buildJsonObject {
        write.title?.let { title ->
            putJsonObject(keyFor(mapping.titlePropId)) {
                put(
                    "title",
                    buildJsonArray {
                        add(buildJsonObject { putJsonObject("text") { put("content", title.take(MAX_TITLE)) } })
                    },
                )
            }
        }
        write.date?.let { date ->
            putJsonObject(keyFor(mapping.datePropId)) {
                putJsonObject("date") {
                    // put()은 이전 값(처음엔 null)을 돌려주므로 `?.let { put } ?: put(null)` 꼴로 쓰면 종료일이 null로 덮인다.
                    if (date.start != null) {
                        put("start", offset(date.start))
                        val end = date.end
                        if (end != null) put("end", offset(end)) else put("end", JsonNull)
                    } else {
                        put("start", date.startDate.toString())
                        val end = date.endDate?.takeIf { it.isAfter(date.startDate) }
                        if (end != null) put("end", end.toString()) else put("end", JsonNull)
                    }
                }
            }
        }
        val donePropId = mapping.donePropId
        if (write.done != null && donePropId != null) {
            when (mapping.doneType) {
                DoneType.CHECKBOX -> putJsonObject(keyFor(donePropId)) { put("checkbox", write.done) }
                DoneType.STATUS -> (if (write.done) doneOption else undoneOption)?.let { option ->
                    putJsonObject(keyFor(donePropId)) { putJsonObject("status") { put("name", option) } }
                }
                null -> Unit
            }
        }
        val colorPropId = mapping.colorPropId
        if (write.setSelect && colorPropId != null) {
            putJsonObject(keyFor(colorPropId)) {
                val id = write.selectOptionId
                if (id == null) put("select", JsonNull) else putJsonObject("select") { put("id", id) }
            }
        }
    }

    private fun offset(instant: Instant): String =
        instant.atOffset(SEOUL).withNano(0).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

    companion object {
        private fun namesById(schema: DataSourceDto): (String) -> String {
            val names = schema.properties.values.associate { it.id to it.name }
            return { id -> names[id] ?: id }
        }

        /** 노션 rich text 한 조각의 최대 길이. */
        const val MAX_TITLE = 2000
        private val SEOUL = ZoneOffset.ofHours(9)
    }
}
