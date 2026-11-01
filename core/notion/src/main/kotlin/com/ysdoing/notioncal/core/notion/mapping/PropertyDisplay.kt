package com.ysdoing.notioncal.core.notion.mapping

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * VW-02 페이지 속성 하나를 위젯에 보일 글자로 바꾼다(읽기 전용). 값이 없거나 모르는 타입이면 빈 글자라
 * 어떤 DB의 행이 와도 깨지지 않는다.
 */
fun displayValue(property: JsonObject): String {
    val type = (property["type"] as? JsonPrimitive)?.contentOrNull ?: return ""
    return fromType(type, property[type])
}

private fun fromType(type: String, value: JsonElement?): String = when (type) {
    "title", "rich_text" -> (value as? JsonArray).orEmpty().joinToString("") { it.str("plain_text") }
    "number" -> number(value)
    "select", "status" -> (value as? JsonObject).str("name")
    "multi_select" -> (value as? JsonArray).orEmpty().joinToString(", ") { it.str("name") }
    "date" -> date(value)
    "checkbox", "boolean" -> if ((value as? JsonPrimitive)?.booleanOrNull == true) "✓" else ""
    "url", "email", "phone_number", "string", "created_time", "last_edited_time" -> (value as? JsonPrimitive)?.contentOrNull.orEmpty().let { if (type.endsWith("_time")) it.take(10) else it }
    "people", "created_by", "last_edited_by" -> people(value)
    "formula" -> (value as? JsonObject)?.let { fromType(it.str("type"), it[it.str("type")]) }.orEmpty()
    "rollup" -> rollup(value)
    "relation" -> count(value)
    "files" -> count(value)
    "unique_id" -> (value as? JsonObject)?.let { listOf(it.str("prefix"), number(it["number"])).filter(String::isNotEmpty).joinToString("-") }.orEmpty()
    else -> ""
}

private fun JsonElement?.str(key: String): String = ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()

private fun number(value: JsonElement?): String {
    val n = (value as? JsonPrimitive)?.doubleOrNull ?: return ""
    return if (n % 1.0 == 0.0) n.toLong().toString() else n.toString()
}

private fun date(value: JsonElement?): String {
    val start = value.str("start")
    val end = value.str("end")
    return if (end.isEmpty()) start else "$start~$end"
}

private fun people(value: JsonElement?): String = when (value) {
    is JsonArray -> value.joinToString(", ") { it.str("name") }
    else -> value.str("name")
}

private fun count(value: JsonElement?): String = (value as? JsonArray)?.takeIf { it.isNotEmpty() }?.let { "${it.size}개" }.orEmpty()

/** 롤업은 숫자·날짜면 그 값, 목록이면 개수로 요약한다. */
private fun rollup(value: JsonElement?): String {
    val type = value.str("type")
    return if (type == "array") count((value as JsonObject)["array"]) else fromType(type, (value as? JsonObject)?.get(type))
}
