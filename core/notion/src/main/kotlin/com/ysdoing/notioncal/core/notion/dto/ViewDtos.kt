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

/** `POST /v1/views/{id}/queries` 본문. 쪽 크기만 받는다(필터·정렬은 보기가 갖고 있다). */
@Serializable
data class ViewQueryRequestDto(@SerialName("page_size") val pageSize: Int = 100)

/**
 * 보기 쿼리 응답(첫 쪽과 `GET …/queries/{query_id}`의 다음 쪽). [results]는 `{object:"page", id}`뿐이고 값은 주지 않는다(9-A).
 * 결과는 15분 뒤 만료되고, 쿼리당 1만 건을 넘으면 [requestStatus]가 incomplete다.
 */
@Serializable
data class ViewQueryDto(
    val id: String = "",
    val results: List<ViewRefDto> = emptyList(),
    @SerialName("next_cursor") val nextCursor: String? = null,
    @SerialName("has_more") val hasMore: Boolean = false,
    @SerialName("request_status") val requestStatus: RequestStatusDto? = null,
)
