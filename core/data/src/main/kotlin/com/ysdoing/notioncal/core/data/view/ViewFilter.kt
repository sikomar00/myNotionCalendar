package com.ysdoing.notioncal.core.data.view

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import java.time.LocalDate

/** 보기 필터를 한 행에 대어 본 결과. [UNKNOWN]은 폰에서 가릴 수 없다는 뜻이다('반영 대기', 전송 뒤 보기를 다시 받는다). */
enum class FilterMatch { YES, NO, UNKNOWN }

/**
 * Phase 11c 노션 보기 필터(보기 필터 + 빠른 필터를 합친 JSON, `view_snapshots.filterJson`)를 폰에서 판정한다.
 * 위젯에서 바꾼 행이 필터에서 빠지는지(IX-06)와 새 항목에 채울 값(AD-01)을 알려는 것이라 단순 유형만 가린다:
 * 체크박스·선택·상태·다중 선택·숫자·글자(제목·텍스트)·절대 날짜 비교와 `and`/`or`. 수식·롤업·관계·상대 날짜('이번 주')·모르는 속성은 [FilterMatch.UNKNOWN]이다.
 * 값은 [ViewRow.typed]와 같은 모양이다(체크 bool, 옵션 ID, 숫자, 글자, 옵션 ID 목록, 날짜 객체). 선택 조건의 값은 옵션 이름이나 ID 어느 쪽이든 맞춘다.
 */
class ViewFilter private constructor(private val root: JsonObject?) {
    /** 필터가 없으면 모든 행이 맞는다. */
    fun matches(columns: List<ViewColumn>, typed: Map<String, JsonElement>): FilterMatch = root?.let { evaluate(it, columns, typed) } ?: FilterMatch.YES

    /** 필터가 가리키는 속성 ID 전부(안쪽 `and`/`or` 포함). */
    fun propertyIds(): Set<String> = buildSet { root?.let { collectIds(it, this) } }

    /**
     * 새 항목에 미리 채울 값(AD-01): 최상위 조건이나 최상위 `and`의 같음(equals) 조건 중 체크박스·선택·상태·숫자. 속성 ID → 쓰기 값(typed 모양).
     * `or` 안의 조건과 포함·비교 조건은 채우지 않는다(어느 값이 맞는지 정할 수 없다). 선택지는 옵션 ID로 바꾸고, 없는 옵션이면 건너뛴다.
     */
    fun equalsConditions(columns: List<ViewColumn>): Map<String, JsonElement> {
        val leaves = root?.let { top -> (top["and"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: listOf(top) }.orEmpty()
        return buildMap {
            leaves.forEach { leaf ->
                val column = columns.firstOrNull { it.id == leaf.propertyId() } ?: return@forEach
                val (op, operand) = leaf.condition(column.type)?.entries?.singleOrNull() ?: return@forEach
                if (op == "equals") equalsValue(column, operand)?.let { put(column.id, it) }
            }
        }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** 빈 글자·깨진 글자·객체가 아니거나 빈 객체면 필터 없음이다. */
        fun parse(text: String): ViewFilter {
            val element = text.takeIf { it.isNotBlank() }?.let { runCatching { json.parseToJsonElement(it) }.getOrNull() } as? JsonObject
            return ViewFilter(element?.takeIf { it.isNotEmpty() })
        }
    }
}

private fun JsonObject.propertyId(): String? = (this["property"] as? JsonPrimitive)?.contentOrNull

/** 조건 본문: `{"property":…, "<유형>": {…}}`에서 유형이 [type]과 같을 때의 `{…}`. */
private fun JsonObject.condition(type: String): JsonObject? = this[type] as? JsonObject

private fun collectIds(node: JsonObject, into: MutableSet<String>) {
    (node["and"] as? JsonArray ?: node["or"] as? JsonArray)?.forEach { (it as? JsonObject)?.let { child -> collectIds(child, into) } }
    node.propertyId()?.let(into::add)
}

private fun equalsValue(column: ViewColumn, operand: JsonElement): JsonElement? = when (column.type) {
    "checkbox" -> (operand as? JsonPrimitive)?.booleanOrNull?.let { JsonPrimitive(it) }
    "number" -> (operand as? JsonPrimitive)?.doubleOrNull?.let { JsonPrimitive(it) }
    "select", "status" -> (operand as? JsonPrimitive)?.contentOrNull?.let { key -> column.options.firstOrNull { it.id == key || it.name == key } }?.let { JsonPrimitive(it.id) }
    else -> null
}

private fun evaluate(node: JsonObject, columns: List<ViewColumn>, typed: Map<String, JsonElement>): FilterMatch {
    (node["and"] as? JsonArray)?.let { parts -> return allOf(parts, columns, typed) }
    (node["or"] as? JsonArray)?.let { parts -> return anyOf(parts, columns, typed) }
    val column = columns.firstOrNull { it.id == node.propertyId() } ?: return FilterMatch.UNKNOWN // 열을 모른다(보기에 없는 속성, 타임스탬프 필터 등)
    val condition = node.condition(column.type) ?: return FilterMatch.UNKNOWN
    val (op, operand) = condition.entries.singleOrNull() ?: return FilterMatch.UNKNOWN
    return judge(column, typed[column.id], op, operand)
}

/** `and`: 하나라도 아니면 아니다(나머지가 뭐든). 아니라는 것이 없고 모르는 것이 있으면 모른다. */
private fun allOf(parts: JsonArray, columns: List<ViewColumn>, typed: Map<String, JsonElement>): FilterMatch {
    val results = parts.map { (it as? JsonObject)?.let { node -> evaluate(node, columns, typed) } ?: FilterMatch.UNKNOWN }
    return when {
        FilterMatch.NO in results -> FilterMatch.NO
        FilterMatch.UNKNOWN in results -> FilterMatch.UNKNOWN
        else -> FilterMatch.YES
    }
}

/** `or`: 하나라도 맞으면 맞다. 맞는 것이 없고 모르는 것이 있으면 모른다. 비어 있으면 거르지 않는다. */
private fun anyOf(parts: JsonArray, columns: List<ViewColumn>, typed: Map<String, JsonElement>): FilterMatch {
    if (parts.isEmpty()) return FilterMatch.YES
    val results = parts.map { (it as? JsonObject)?.let { node -> evaluate(node, columns, typed) } ?: FilterMatch.UNKNOWN }
    return when {
        FilterMatch.YES in results -> FilterMatch.YES
        FilterMatch.UNKNOWN in results -> FilterMatch.UNKNOWN
        else -> FilterMatch.NO
    }
}

private fun Boolean.match() = if (this) FilterMatch.YES else FilterMatch.NO

private fun judge(column: ViewColumn, value: JsonElement?, op: String, operand: JsonElement): FilterMatch = when (column.type) {
    "checkbox" -> judgeCheckbox((value as? JsonPrimitive)?.booleanOrNull == true, op, operand)
    "select", "status" -> judgeOptions(optionKeys(column, listOfNotNull(value)), op, operand)
    "multi_select" -> judgeOptions(optionKeys(column, (value as? JsonArray).orEmpty()), op, operand)
    "number" -> judgeNumber((value as? JsonPrimitive)?.doubleOrNull, op, operand)
    "title", "rich_text", "url", "email", "phone_number" -> judgeText((value as? JsonPrimitive)?.contentOrNull.orEmpty(), op, operand)
    "date" -> judgeDate(value, op, operand)
    else -> FilterMatch.UNKNOWN
}

private fun judgeCheckbox(on: Boolean, op: String, operand: JsonElement): FilterMatch {
    val expected = (operand as? JsonPrimitive)?.booleanOrNull ?: return FilterMatch.UNKNOWN
    return when (op) {
        "equals" -> (on == expected).match()
        "does_not_equal" -> (on != expected).match()
        else -> FilterMatch.UNKNOWN
    }
}

/** 고른 옵션의 비교 글자들: 옵션 ID와 이름(조건 값이 어느 쪽이든 맞추려고). 새 옵션(`{"name":…}`)은 이름만. */
private fun optionKeys(column: ViewColumn, items: List<JsonElement>): Set<String> = buildSet {
    items.forEach { item ->
        val id = (item as? JsonPrimitive)?.contentOrNull
        if (id != null) {
            add(id)
            column.options.firstOrNull { it.id == id }?.let { add(it.name) }
        } else {
            ((item as? JsonObject)?.get("name") as? JsonPrimitive)?.contentOrNull?.let(::add)
        }
    }
}

private fun judgeOptions(keys: Set<String>, op: String, operand: JsonElement): FilterMatch {
    val wanted = (operand as? JsonPrimitive)?.contentOrNull
    return when (op) {
        "equals", "contains" -> if (wanted == null) FilterMatch.UNKNOWN else (wanted in keys).match()
        "does_not_equal", "does_not_contain" -> if (wanted == null) FilterMatch.UNKNOWN else (wanted !in keys).match()
        "is_empty" -> keys.isEmpty().match()
        "is_not_empty" -> keys.isNotEmpty().match()
        else -> FilterMatch.UNKNOWN
    }
}

private fun judgeNumber(current: Double?, op: String, operand: JsonElement): FilterMatch {
    if (op == "is_empty") return (current == null).match()
    if (op == "is_not_empty") return (current != null).match()
    val limit = (operand as? JsonPrimitive)?.doubleOrNull ?: return FilterMatch.UNKNOWN
    if (current == null) return (op == "does_not_equal").match() // 값이 없으면 같지 않을 뿐 비교는 모두 아니다
    return when (op) {
        "equals" -> (current == limit).match()
        "does_not_equal" -> (current != limit).match()
        "greater_than" -> (current > limit).match()
        "less_than" -> (current < limit).match()
        "greater_than_or_equal_to" -> (current >= limit).match()
        "less_than_or_equal_to" -> (current <= limit).match()
        else -> FilterMatch.UNKNOWN
    }
}

private fun judgeText(current: String, op: String, operand: JsonElement): FilterMatch {
    if (op == "is_empty") return current.isEmpty().match()
    if (op == "is_not_empty") return current.isNotEmpty().match()
    val wanted = (operand as? JsonPrimitive)?.contentOrNull ?: return FilterMatch.UNKNOWN
    return when (op) {
        "equals" -> current.equals(wanted, ignoreCase = true).match()
        "does_not_equal" -> (!current.equals(wanted, ignoreCase = true)).match()
        "contains" -> current.contains(wanted, ignoreCase = true).match()
        "does_not_contain" -> (!current.contains(wanted, ignoreCase = true)).match()
        "starts_with" -> current.startsWith(wanted, ignoreCase = true).match()
        "ends_with" -> current.endsWith(wanted, ignoreCase = true).match()
        else -> FilterMatch.UNKNOWN
    }
}

/** 날짜: 시작 날짜(시간·시간대는 무시)를 조건의 날짜와 견준다. 상대 날짜('이번 주' 등)는 판정하지 않는다. */
private fun judgeDate(value: JsonElement?, op: String, operand: JsonElement): FilterMatch {
    val current = ((value as? JsonObject)?.get("start") as? JsonPrimitive)?.contentOrNull?.dateOrNull()
    if (op == "is_empty") return (current == null).match()
    if (op == "is_not_empty") return (current != null).match()
    val limit = (operand as? JsonPrimitive)?.contentOrNull?.dateOrNull() ?: return FilterMatch.UNKNOWN
    if (current == null) return FilterMatch.NO
    return when (op) {
        "equals" -> (current == limit).match()
        "before" -> current.isBefore(limit).match()
        "after" -> current.isAfter(limit).match()
        "on_or_before" -> (!current.isAfter(limit)).match()
        "on_or_after" -> (!current.isBefore(limit)).match()
        else -> FilterMatch.UNKNOWN
    }
}

internal fun String.dateOrNull(): LocalDate? = runCatching { LocalDate.parse(substringBefore('T')) }.getOrNull()

/** 날짜 속성 값(`{"start": …}`)의 시작 날짜. 날짜가 아니거나 비었으면 null이다. */
internal fun JsonElement?.startDate(): LocalDate? = ((this as? JsonObject)?.get("start") as? JsonPrimitive)?.contentOrNull?.dateOrNull()
