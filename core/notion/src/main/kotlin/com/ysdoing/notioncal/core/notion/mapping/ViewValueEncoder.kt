package com.ysdoing.notioncal.core.notion.mapping

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * 보기 행의 속성 하나([type]은 체크·선택·상태·숫자)를 노션 `PATCH /pages`의 properties JSON으로 바꾼다.
 * [key]는 보내는 순간 스키마의 속성 **이름**이다(퍼센트 인코딩된 ID를 키로 보내면 노션이 오류 없이 무시한다, PropertyEncoder 참고).
 * [value]는 체크 bool·선택지 ID·숫자이고 JsonNull이면 비운다(status는 비울 수 없어 호출하지 않는다). 모르는 종류는 null.
 */
fun encodeViewValue(key: String, type: String, value: JsonElement?): JsonObject? {
    val primitive = value as? JsonPrimitive
    return when (type) {
        "checkbox" -> buildJsonObject { putJsonObject(key) { put("checkbox", primitive?.booleanOrNull ?: false) } }
        "select", "status" -> buildJsonObject {
            putJsonObject(key) {
                val id = primitive?.contentOrNull
                if (id == null) put(type, JsonNull) else putJsonObject(type) { put("id", id) }
            }
        }
        "number" -> buildJsonObject {
            putJsonObject(key) {
                val number = primitive?.doubleOrNull
                if (number == null) put("number", JsonNull) else put("number", number)
            }
        }
        else -> null
    }
}
