package com.ysdoing.notioncal.core.sync

import com.ysdoing.notioncal.core.data.ColorOption
import com.ysdoing.notioncal.core.data.ExtraProp
import com.ysdoing.notioncal.core.data.PropValue
import com.ysdoing.notioncal.core.notion.dto.DataSourceDto
import com.ysdoing.notioncal.core.notion.mapping.PropertyValue
import com.ysdoing.notioncal.core.notion.mapping.PropertyWrite
import com.ysdoing.notioncal.core.notion.mapping.extraProperties

/** EX-07: :core:notion 값 ↔ :core:data 값. 두 모듈은 서로 모르므로 여기서 옮긴다. */
internal fun PropertyValue.toPropValue() = PropValue(optionIds = optionIds, number = number, text = text)

internal fun PropValue.toPropertyValue() = PropertyValue(optionIds = optionIds, number = number, text = text)

/** 스키마의 지원 타입 속성 목록(data_sources.propCatalog). */
internal fun DataSourceDto.propCatalog(): List<ExtraProp> = extraProperties().map { p ->
    ExtraProp(p.id, p.name, p.type, p.options.map { ColorOption(it.id, it.name, it.color) })
}

/** 바꾼 속성 값을 노션 쓰기로. 지금 목록에 없는 속성(노션에서 지움)은 보내지 않는다. */
internal fun Map<String, PropValue>.toPropertyWrites(catalog: List<ExtraProp>): Map<String, PropertyWrite> {
    val types = catalog.associate { it.id to it.type }
    return mapNotNull { (id, value) -> types[id]?.let { type -> id to PropertyWrite(type, value.toPropertyValue()) } }.toMap()
}
