package com.ysdoing.notioncal.core.notion.mapping

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.time.Instant
import java.time.LocalDate

/** PRD §8.5 조회 필터. 속성은 이름이 아니라 ID로 지정한다. */
object QueryFilters {
    /** 범위 조회: 기간 일정의 겹침은 로컬에서 판정하므로 호출부가 시작을 앞당겨 넘긴다(§8.5-1). */
    fun dateRange(datePropId: String, from: LocalDate, to: LocalDate): JsonObject = buildJsonObject {
        put(
            "and",
            buildJsonArray {
                add(dateCondition(datePropId, "on_or_after", DateCodec.filterStartOfDay(from)))
                add(dateCondition(datePropId, "on_or_before", DateCodec.filterEndOfDay(to)))
            },
        )
    }

    /** 증분 조회: last_edited_time 기준. 날짜 필터는 걸지 않는다(날짜가 지워진 페이지도 받아야 함). */
    fun editedSince(since: Instant): JsonObject = buildJsonObject {
        put("timestamp", "last_edited_time")
        putJsonObject("last_edited_time") { put("on_or_after", DateCodec.filterInstant(since)) }
    }

    private fun dateCondition(propId: String, op: String, value: String): JsonObject = buildJsonObject {
        put("property", propId)
        putJsonObject("date") { put(op, value) }
    }
}
