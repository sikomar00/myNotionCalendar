package com.ysdoing.notioncal.core.data.view

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.abs

/** select·status의 선택지 하나. [group]은 status의 그룹 이름(할 일·진행 중·완료)이다. */
@Serializable
data class ViewChoice(val id: String, val name: String, val group: String? = null)

/** 보기에 보이는 속성 하나의 쓰기 정보. 위젯은 이것으로 어떤 탭을 어떤 값으로 바꿀 수 있는지 안다. */
@Serializable
data class ViewColumn(val id: String, val name: String, val type: String, val options: List<ViewChoice> = emptyList()) {
    /** 위젯에서 바로 바꿀 수 있는 종류(체크·상태·선택·숫자). 나머지는 읽기만 한다. */
    val writable: Boolean get() = type in WRITABLE_TYPES

    /** 표시용 글자. [value]는 [ViewJson.typed]의 값(체크 bool, 선택지 ID, 숫자)이다. 노션 읽기 표기(displayValue)와 같다. */
    fun display(value: JsonElement?): String = when (type) {
        "checkbox" -> if ((value as? JsonPrimitive)?.booleanOrNull == true) "✓" else ""
        "select", "status" -> options.firstOrNull { it.id == (value as? JsonPrimitive)?.contentOrNull }?.name.orEmpty()
        "number" -> (value as? JsonPrimitive)?.doubleOrNull?.let { n -> if (n % 1.0 == 0.0 && abs(n) < MAX_PLAIN) n.toLong().toString() else n.toString() }.orEmpty()
        else -> ""
    }

    /** [currentId] 다음 선택지(끝이면 처음). 눌러서 상태를 순환시킬 때 쓴다. 선택지가 없으면 null. */
    fun nextOption(currentId: String?): ViewChoice? {
        if (options.isEmpty()) return null
        val index = options.indexOfFirst { it.id == currentId }
        return options[(index + 1) % options.size]
    }

    companion object {
        val WRITABLE_TYPES = setOf("checkbox", "select", "status", "number")

        /** 이보다 큰 정수는 Long으로 바꾸면 값이 틀어지므로 그대로 글자로 쓴다. */
        private const val MAX_PLAIN = 1e15
    }
}

/** view_snapshots.columnInfo·view_rows.typed의 JSON 읽기·쓰기. 깨진 글자는 빈 값으로 읽는다(캐시라 다시 받으면 된다). */
object ViewJson {
    private val json = Json { ignoreUnknownKeys = true }
    private val columnList = ListSerializer(ViewColumn.serializer())
    private val typedMap = MapSerializer(String.serializer(), JsonElement.serializer())

    fun columns(text: String): List<ViewColumn> =
        if (text.isBlank()) emptyList() else runCatching { json.decodeFromString(columnList, text) }.getOrDefault(emptyList())

    fun encodeColumns(columns: List<ViewColumn>): String = if (columns.isEmpty()) "" else json.encodeToString(columnList, columns)

    fun typed(text: String): Map<String, JsonElement> =
        if (text.isBlank()) emptyMap() else runCatching { json.decodeFromString(typedMap, text) }.getOrDefault(emptyMap())

    fun encodeTyped(values: Map<String, JsonElement>): String = if (values.isEmpty()) "" else json.encodeToString(typedMap, values)

    /** JSON 원시값 한 개의 글자(쓰기 큐 value 열). 비어 있으면 null. */
    fun valueText(value: JsonElement?): String? = value?.takeIf { it !is JsonNull }?.toString()

    /** 쓰기 큐 value 열을 값으로 읽는다. 비어 있으면(null) 비우는 값 JsonNull, 깨진 글자면 null(노션에 지우는 쓰기를 보내지 않게 구분한다). */
    fun parseValue(text: String?): JsonElement? =
        if (text == null) JsonNull else runCatching { json.parseToJsonElement(text) }.getOrNull()
}
