package com.ysdoing.notioncal.core.notion.mapping

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
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
    val typed = viewValue(type, value) ?: return null
    return PropertyCodec.encode(typed)?.let { buildJsonObject { put(key, it) } }
}

/** 보기 행에 둔 값 JSON(체크 bool·선택지 ID·숫자·글자·ID 목록·날짜 객체, 비우기는 JsonNull)을 코덱 값으로. 모르는 종류는 null. */
private fun viewValue(type: String, value: JsonElement?): NotionValue? {
    val primitive = value as? JsonPrimitive
    val text = primitive?.contentOrNull
    return when (type) {
        "checkbox" -> NotionValue.Check(primitive?.booleanOrNull ?: false)
        "select", "status" -> NotionValue.Choice(type, text?.let { NotionValue.Option(it, null, null) })
        "number" -> NotionValue.Num(primitive?.doubleOrNull)
        "title", "rich_text" -> NotionValue.Text(type, text.orEmpty())
        "url", "email", "phone_number" -> NotionValue.Str(type, text)
        "multi_select" -> NotionValue.Choices((value as? JsonArray).orEmpty().mapNotNull(::viewOption))
        "date" -> (value as? JsonObject)?.let { NotionValue.DateRange(it.text("start"), it.text("end"), it.text("time_zone")) } ?: NotionValue.DateRange(null, null, null)
        else -> null
    }
}

private fun viewOption(item: JsonElement): NotionValue.Option? = when (item) {
    is JsonObject -> item.text("name")?.let { NotionValue.Option(null, it, null) }
    is JsonPrimitive -> item.contentOrNull?.let { NotionValue.Option(it, null, null) }
    else -> null
}

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

/**
 * 새 항목(`POST /pages`)의 properties. [titleKey]·[dateKey]는 보내는 순간 스키마의 속성 이름이다. [date]는 yyyy-MM-dd이고
 * [dateKey]가 없으면 넣지 않는다. [extra]는 값 묶음(프리셋·맥락 채움)을 [encodeViewValue]로 바꾼 속성들이고 제목·날짜 뒤에 그대로 합친다.
 */
fun encodeViewCreate(titleKey: String, title: String, dateKey: String?, date: String?, extra: JsonObject = JsonObject(emptyMap())): JsonObject = buildJsonObject {
    putJsonObject(titleKey) {
        put("title", buildJsonArray { add(buildJsonObject { putJsonObject("text") { put("content", title.take(PropertyEncoder.MAX_TITLE)) } }) })
    }
    if (dateKey != null && date != null) {
        putJsonObject(dateKey) { putJsonObject("date") { put("start", date) } }
    }
    val used = setOfNotNull(titleKey, dateKey.takeIf { date != null })
    extra.forEach { (name, property) -> if (name !in used) put(name, property) } // 제목·날짜가 이미 정한 속성은 덮지 않는다
}
