package com.ysdoing.notioncal.core.notion.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

// Notion-Version 2026-03-11 응답 중 앱이 쓰는 필드만 정의한다. 나머지는 ignoreUnknownKeys로 무시한다.

@Serializable
data class UserDto(
    val id: String,
    val name: String? = null,
    val type: String? = null,
)

@Serializable
data class RichTextDto(
    @SerialName("plain_text") val plainText: String = "",
)

@Serializable
data class DataSourceRefDto(
    val id: String,
    val name: String = "",
)

/** DB의 부모(LD-01: 거래내역과 같은 페이지의 예산·카테고리 마스터를 찾는다). */
@Serializable
data class ParentRefDto(
    val type: String = "",
    @SerialName("page_id") val pageId: String? = null,
)

@Serializable
data class DatabaseDto(
    val id: String,
    val title: List<RichTextDto> = emptyList(),
    @SerialName("data_sources") val dataSources: List<DataSourceRefDto> = emptyList(),
    val parent: ParentRefDto? = null,
)

@Serializable
data class ChildDatabaseDto(val title: String = "")

/** 블록 하나. 앱은 자식 DB(child_database)만 쓴다. */
@Serializable
data class BlockDto(
    val id: String,
    val type: String = "",
    @SerialName("child_database") val childDatabase: ChildDatabaseDto? = null,
)

@Serializable
data class BlockListDto(
    val results: List<BlockDto> = emptyList(),
    @SerialName("next_cursor") val nextCursor: String? = null,
    @SerialName("has_more") val hasMore: Boolean = false,
)

@Serializable
data class OptionDto(
    val id: String,
    val name: String,
    val color: String = "default",
)

@Serializable
data class SelectSchemaDto(
    val options: List<OptionDto> = emptyList(),
)

@Serializable
data class StatusGroupDto(
    val id: String = "",
    val name: String,
    val color: String = "default",
    @SerialName("option_ids") val optionIds: List<String> = emptyList(),
)

@Serializable
data class StatusSchemaDto(
    val options: List<OptionDto> = emptyList(),
    val groups: List<StatusGroupDto> = emptyList(),
)

/** relation 속성의 대상 DB(HB-01: ✅ 습관 기록의 '습관' → 🌱 습관). */
@Serializable
data class RelationSchemaDto(
    @SerialName("data_source_id") val dataSourceId: String? = null,
    @SerialName("database_id") val databaseId: String? = null,
)

@Serializable
data class PropertySchemaDto(
    val id: String,
    val name: String,
    val type: String,
    val select: SelectSchemaDto? = null,
    val status: StatusSchemaDto? = null,
    @SerialName("multi_select") val multiSelect: SelectSchemaDto? = null,
    val relation: RelationSchemaDto? = null,
)

@Serializable
data class DataSourceDto(
    val id: String,
    val title: List<RichTextDto> = emptyList(),
    val properties: Map<String, PropertySchemaDto> = emptyMap(),
)

@Serializable
data class PageDto(
    val id: String,
    val url: String = "",
    @SerialName("created_time") val createdTime: String? = null,
    @SerialName("last_edited_time") val lastEditedTime: String,
    @SerialName("in_trash") val inTrash: Boolean = false,
    val properties: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class RequestStatusDto(
    val type: String = "complete",
)

@Serializable
data class QueryResponseDto(
    val results: List<PageDto> = emptyList(),
    @SerialName("next_cursor") val nextCursor: String? = null,
    @SerialName("has_more") val hasMore: Boolean = false,
    @SerialName("request_status") val requestStatus: RequestStatusDto? = null,
)

@Serializable
data class QueryRequestDto(
    val filter: JsonObject? = null,
    @SerialName("start_cursor") val startCursor: String? = null,
    @SerialName("page_size") val pageSize: Int = 100,
)

@Serializable
data class ParentDto(
    val type: String = "data_source_id",
    @SerialName("data_source_id") val dataSourceId: String,
)

/** POST v1/pages. template을 보내지 않으면 노션이 템플릿을 적용하지 않는다(기본 none). */
@Serializable
data class CreatePageDto(
    val parent: ParentDto,
    val properties: JsonObject,
)

/** PATCH v1/pages/{id}. null인 필드는 보내지 않는다(NotionJson.explicitNulls = false). */
@Serializable
data class UpdatePageDto(
    val properties: JsonObject? = null,
    @SerialName("in_trash") val inTrash: Boolean? = null,
)

@Serializable
data class ErrorDto(
    val status: Int? = null,
    val code: String? = null,
)
