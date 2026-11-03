package com.ysdoing.notioncal.core.notion.mapping

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * 노션 속성 값 하나의 타입 있는 모양(Phase 10-3, PRD §5.8 속성 24종). 모르는 유형과 값 코덱이 필요 없는 유형(확인·버튼·장소)은
 * [Raw]가 원본 JSON을 그대로 쥐어 잃지 않는다. [type]은 노션 속성 유형 이름이다.
 */
sealed interface NotionValue {
    val type: String

    data class Option(val id: String?, val name: String?, val color: String?)
    data class User(val id: String, val name: String?)
    data class FileRef(val name: String, val url: String, val external: Boolean)

    /** title·rich_text. 서식·언급은 글자만 남는다. */
    data class Text(override val type: String, val text: String) : NotionValue
    data class Num(val value: Double?) : NotionValue { override val type get() = "number" }

    /** select·status. 값이 없으면 [option]이 null. */
    data class Choice(override val type: String, val option: Option?) : NotionValue
    data class Choices(val options: List<Option>) : NotionValue { override val type get() = "multi_select" }
    data class DateRange(val start: String?, val end: String?, val timeZone: String?) : NotionValue { override val type get() = "date" }

    /** people·created_by·last_edited_by. */
    data class Users(override val type: String, val users: List<User>) : NotionValue
    data class Files(val files: List<FileRef>) : NotionValue { override val type get() = "files" }
    data class Check(val checked: Boolean) : NotionValue { override val type get() = "checkbox" }

    /** url·email·phone_number·created_time·last_edited_time, 수식 결과의 string. */
    data class Str(override val type: String, val value: String?) : NotionValue
    data class Formula(val result: NotionValue?) : NotionValue { override val type get() = "formula" }
    data class Relation(val ids: List<String>, val hasMore: Boolean) : NotionValue { override val type get() = "relation" }

    /** 롤업: 숫자·날짜는 [result], 목록은 [items]. */
    data class Rollup(val function: String?, val result: NotionValue?, val items: List<NotionValue>?) : NotionValue { override val type get() = "rollup" }
    data class UniqueId(val prefix: String?, val number: Long?) : NotionValue { override val type get() = "unique_id" }
    data class Raw(override val type: String, val json: JsonObject) : NotionValue
}

/**
 * 노션 속성 JSON ↔ [NotionValue]. [decode]는 어떤 모양이 와도 던지지 않는다(깨진 값은 빈 값). [encode]는 `PATCH /pages`의
 * 속성 하나(`{"유형": 값}`)이고, 읽기 전용이거나 쓸 수 없는 값이면 null이다(PRD §8.3). 키는 호출하는 쪽이 속성 이름으로 감싼다.
 */
object PropertyCodec {
    fun decode(property: JsonObject): NotionValue {
        val type = property.str("type")
        return decodeValue(type, property[type], property)
    }

    private fun decodeValue(type: String, value: JsonElement?, whole: JsonObject): NotionValue = when (type) {
        "title", "rich_text" -> NotionValue.Text(type, richText(value))
        "number" -> NotionValue.Num(value.number())
        "select", "status" -> NotionValue.Choice(type, option(value))
        "multi_select" -> NotionValue.Choices(options(value))
        "date" -> date(value)
        "people" -> NotionValue.Users(type, users(value))
        "created_by", "last_edited_by" -> NotionValue.Users(type, listOfNotNull(user(value)))
        "files" -> NotionValue.Files(files(value))
        "checkbox", "boolean" -> NotionValue.Check((value as? JsonPrimitive)?.booleanOrNull == true)
        "url", "email", "phone_number", "created_time", "last_edited_time", "string" -> NotionValue.Str(type, (value as? JsonPrimitive)?.contentOrNull)
        "formula" -> NotionValue.Formula(nested(value))
        "relation" -> NotionValue.Relation(relationIds(value), (whole["has_more"] as? JsonPrimitive)?.booleanOrNull == true)
        "rollup" -> rollup(value)
        "unique_id" -> uniqueId(value)
        else -> NotionValue.Raw(type, whole) // 확인·버튼·장소와 모르는 유형은 원본 그대로
    }

    private fun richText(value: JsonElement?): String =
        (value as? JsonArray).orEmpty().joinToString("") { item ->
            item.asObject()?.let { it.str("plain_text").ifEmpty { it.obj("text")?.str("content").orEmpty() } }.orEmpty()
        }

    private fun option(value: JsonElement?): NotionValue.Option? =
        value.asObject()?.let { NotionValue.Option(it.strOrNull("id"), it.strOrNull("name"), it.strOrNull("color")) }

    private fun options(value: JsonElement?): List<NotionValue.Option> = (value as? JsonArray).orEmpty().mapNotNull(::option)

    private fun date(value: JsonElement?): NotionValue.DateRange {
        val date = value.asObject() ?: return NotionValue.DateRange(null, null, null)
        return NotionValue.DateRange(date.strOrNull("start"), date.strOrNull("end"), date.strOrNull("time_zone"))
    }

    private fun user(value: JsonElement?): NotionValue.User? {
        val user = value.asObject() ?: return null
        return NotionValue.User(user.strOrNull("id") ?: return null, user.strOrNull("name"))
    }

    private fun users(value: JsonElement?): List<NotionValue.User> = (value as? JsonArray).orEmpty().mapNotNull(::user)

    private fun files(value: JsonElement?): List<NotionValue.FileRef> = (value as? JsonArray).orEmpty().mapNotNull { item ->
        val file = item.asObject() ?: return@mapNotNull null
        val external = file.obj("external")
        val url = (external ?: file.obj("file"))?.strOrNull("url") ?: return@mapNotNull null
        NotionValue.FileRef(file.str("name"), url, external = external != null)
    }

    private fun relationIds(value: JsonElement?): List<String> = (value as? JsonArray).orEmpty().mapNotNull { it.asObject()?.strOrNull("id") }

    /** 수식 결과·롤업 값처럼 `{"type": t, t: 값}` 모양을 푼다. */
    private fun nested(value: JsonElement?): NotionValue? {
        val obj = value.asObject() ?: return null
        val type = obj.str("type")
        return decodeValue(type, obj[type], obj)
    }

    private fun rollup(value: JsonElement?): NotionValue.Rollup {
        val obj = value.asObject() ?: return NotionValue.Rollup(null, null, null)
        val items = (obj["array"] as? JsonArray)?.mapNotNull { item -> item.asObject()?.let(::decode) }
        return NotionValue.Rollup(obj.strOrNull("function"), if (items == null) nested(obj) else null, items)
    }

    private fun uniqueId(value: JsonElement?): NotionValue.UniqueId {
        val obj = value.asObject()
        return NotionValue.UniqueId(obj?.strOrNull("prefix"), (obj?.get("number") as? JsonPrimitive)?.longOrNull)
    }

    fun encode(value: NotionValue): JsonObject? {
        val payload: JsonElement = when (value) {
            is NotionValue.Text -> textPayload(value.text)
            is NotionValue.Num -> value.value?.let(::JsonPrimitive) ?: JsonNull
            is NotionValue.Choice -> value.option?.let(::optionPayload) ?: JsonNull
            is NotionValue.Choices -> JsonArray(value.options.mapNotNull(::optionPayload))
            is NotionValue.DateRange -> datePayload(value)
            is NotionValue.Users -> if (value.type == "people") idList(value.users.map { it.id }) else return null
            is NotionValue.Files -> filesPayload(value) ?: return null
            is NotionValue.Check -> JsonPrimitive(value.checked)
            is NotionValue.Str -> if (value.type in WRITABLE_STRINGS) value.value?.let(::JsonPrimitive) ?: JsonNull else return null
            is NotionValue.Relation -> if (value.hasMore) return null else idList(value.ids) // 25개로 잘린 목록을 쓰면 나머지 연결이 지워진다
            is NotionValue.Formula, is NotionValue.Rollup, is NotionValue.UniqueId, is NotionValue.Raw -> return null
        }
        return buildJsonObject { put(value.type, payload) }
    }

    /** 노션은 글자 조각 하나를 2000자까지 받는다. 더 길면 여러 조각으로 나눠 보낸다(잘라 버리면 긴 메모가 줄어든다). */
    private fun textPayload(text: String): JsonArray = buildJsonArray {
        text.chunked(PropertyEncoder.MAX_TITLE).forEach { piece -> add(buildJsonObject { putJsonObject("text") { put("content", piece) } }) }
    }

    /** id가 있으면 id로, 없으면 이름으로(select·multi_select는 새 옵션 이름을 노션이 만든다). 둘 다 없으면 값이 없다. */
    private fun optionPayload(option: NotionValue.Option): JsonObject? = when {
        option.id != null -> buildJsonObject { put("id", option.id) }
        option.name != null -> buildJsonObject { put("name", option.name) }
        else -> null
    }

    private fun datePayload(date: NotionValue.DateRange): JsonElement {
        val start = date.start ?: return JsonNull
        return buildJsonObject {
            put("start", start)
            date.end?.let { put("end", it) }
            date.timeZone?.let { put("time_zone", it) }
        }
    }

    private fun idList(ids: List<String>): JsonArray = JsonArray(ids.map { id -> buildJsonObject { put("id", id) } })

    /** 노션이 올려 둔 파일(만료 주소)은 다시 보낼 수 없어, 외부 주소만 있을 때만 쓸 수 있다. */
    private fun filesPayload(files: NotionValue.Files): JsonArray? {
        if (files.files.any { !it.external }) return null
        return JsonArray(
            files.files.map { file ->
                buildJsonObject {
                    put("name", file.name)
                    putJsonObject("external") { put("url", file.url) }
                }
            },
        )
    }

    private val WRITABLE_STRINGS = setOf("url", "email", "phone_number")
}

private fun JsonElement?.asObject(): JsonObject? = this as? JsonObject

private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

private fun JsonObject.strOrNull(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.str(key: String): String = strOrNull(key).orEmpty()

private fun JsonElement?.number(): Double? = (this as? JsonPrimitive)?.doubleOrNull
