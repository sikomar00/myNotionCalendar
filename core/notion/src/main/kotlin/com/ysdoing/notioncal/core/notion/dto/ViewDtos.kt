package com.ysdoing.notioncal.core.notion.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

// VW 9-A 실기기 응답(2026-10-21)에서 앱이 쓰는 필드만 정의한다.

/** `GET /v1/views?data_source_id=`는 id만 준다. 이름·종류는 [ViewDto]에서 읽는다. */
@Serializable
data class ViewListDto(
    val results: List<ViewRefDto> = emptyList(),
    @SerialName("next_cursor") val nextCursor: String? = null,
    @SerialName("has_more") val hasMore: Boolean = false,
)

@Serializable
data class ViewRefDto(val id: String)

@Serializable
data class ViewDto(
    val id: String,
    val name: String = "",
    val type: String = "",
    val filter: JsonObject? = null,
    val sorts: JsonArray? = null,
    /** `{속성 id: {타입: 조건}}`. 보기 필터와 별개라 같이 적용해야 보기와 같은 행이 나온다. */
    @SerialName("quick_filters") val quickFilters: JsonObject? = null,
    val configuration: ViewConfigDto? = null,
)

@Serializable
data class ViewConfigDto(val properties: List<ViewPropertyDto> = emptyList())

@Serializable
data class ViewPropertyDto(
    @SerialName("property_id") val propertyId: String,
    @SerialName("property_name") val propertyName: String = "",
    val visible: Boolean = true,
)
