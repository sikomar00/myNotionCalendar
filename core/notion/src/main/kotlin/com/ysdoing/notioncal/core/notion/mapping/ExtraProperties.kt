package com.ysdoing.notioncal.core.notion.mapping

import com.ysdoing.notioncal.core.notion.dto.DataSourceDto
import com.ysdoing.notioncal.core.notion.dto.OptionDto

/** EX-07에서 보기·편집할 수 있는 속성 타입(노션 API 이름). */
object ExtraTypes {
    const val SELECT = "select"
    const val MULTI_SELECT = "multi_select"
    const val STATUS = "status"
    const val NUMBER = "number"
    const val URL = "url"

    val ALL = setOf(SELECT, MULTI_SELECT, STATUS, NUMBER, URL)
}

/**
 * 추가 속성 값 하나. 타입에 따라 한 칸만 쓴다.
 * - select·status: [optionIds]에 0~1개(빈 목록이면 값 없음), multi_select: 0개 이상
 * - number: [number], url: [text]
 */
data class PropertyValue(
    val optionIds: List<String>? = null,
    val number: Double? = null,
    val text: String? = null,
)

/** 스키마의 추가 속성 정의(이름·타입·선택지). 동기화 때마다 다시 읽는다. */
data class ExtraPropertySchema(
    val id: String,
    val name: String,
    val type: String,
    val options: List<OptionDto>,
)

/** 스키마에서 EX-07 지원 타입 속성만 고른다. */
fun DataSourceDto.extraProperties(): List<ExtraPropertySchema> = properties.values
    .filter { it.type in ExtraTypes.ALL }
    .map { prop ->
        val options = when (prop.type) {
            ExtraTypes.SELECT -> prop.select?.options
            ExtraTypes.MULTI_SELECT -> prop.multiSelect?.options
            ExtraTypes.STATUS -> prop.status?.options
            else -> null
        }.orEmpty()
        ExtraPropertySchema(prop.id, prop.name, prop.type, options)
    }
    .sortedBy { it.name }
