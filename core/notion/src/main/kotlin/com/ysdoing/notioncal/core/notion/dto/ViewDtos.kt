package com.ysdoing.notioncal.core.notion.dto

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.jsonObject
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

/**
 * 보기 설정(`configuration`). [properties]는 보이는 속성(VW), [raw]는 유형별 키(보드의 `group_by` 등)를 앱이 나중에 읽도록 받은 원본 그대로다.
 */
@Serializable(with = ViewConfigSerializer::class)
data class ViewConfigDto(val properties: List<ViewPropertyDto> = emptyList(), val raw: JsonObject = JsonObject(emptyMap()))

/** `configuration` 객체를 통째로 받아 [ViewConfigDto.raw]에 두고 `properties`만 풀어 낸다. 객체가 아니면 던진다(보기 전체가 읽기 실패로 처리됨). */
object ViewConfigSerializer : KSerializer<ViewConfigDto> {
    override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor

    override fun deserialize(decoder: Decoder): ViewConfigDto {
        val input = decoder as JsonDecoder
        val raw = input.decodeJsonElement().jsonObject
        val properties = raw["properties"]?.let { input.json.decodeFromJsonElement(ListSerializer(ViewPropertyDto.serializer()), it) }.orEmpty()
        return ViewConfigDto(properties, raw)
    }

    override fun serialize(encoder: Encoder, value: ViewConfigDto) = (encoder as JsonEncoder).encodeJsonElement(value.raw)
}

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
