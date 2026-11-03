package com.ysdoing.notioncal.core.sync

import com.ysdoing.notioncal.core.data.view.ViewColumn
import com.ysdoing.notioncal.core.notion.dto.DataSourceDto
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

// M5 책 위젯이 쓰는, 보기에 '보이는 열'과 무관한 값(표지 주소·읽은 비율). 보이는 열의 글자(cells)에는 이 값이 없을 수 있어
// 행의 속성 JSON(보기에서 숨긴 속성도 들어 있다)에서 직접 읽는다.

private val COVER_NAMES = listOf("cover", "표지", "image", "이미지", "thumbnail")
private val PROGRESS_NAMES = listOf("진행", "progress", "percent", "%")

private fun JsonElement?.type(): String = ((this as? JsonObject)?.get("type") as? JsonPrimitive)?.contentOrNull.orEmpty()

/**
 * 파일 속성에서 표지 주소를 고른다. 이름에 cover·표지·image 같은 말이 든 파일 속성을 먼저 보고, 없으면 파일이 든 첫 파일 속성이다.
 * 노션이 올려 준 주소(file)와 외부 주소(external)를 모두 읽는다. https가 아니면 버린다(받지 않는다).
 */
internal fun coverUrlOf(properties: Map<String, JsonElement>): String {
    val candidates = properties.mapNotNull { (name, value) ->
        if (value.type() != "files") return@mapNotNull null
        firstFileUrl((value as JsonObject)["files"]).takeIf { it.isNotEmpty() }?.let { name to it }
    }
    val chosen = candidates.firstOrNull { (name, _) -> COVER_NAMES.any { name.contains(it, ignoreCase = true) } } ?: candidates.firstOrNull()
    return chosen?.second.orEmpty()
}

private fun firstFileUrl(files: JsonElement?): String {
    val first = (files as? JsonArray)?.firstOrNull() as? JsonObject ?: return ""
    val holder = (first[first.type()] as? JsonObject) ?: return ""
    return (holder["url"] as? JsonPrimitive)?.contentOrNull.orEmpty().takeIf { it.startsWith("https://") }.orEmpty()
}

/** 이름이 [names] 중 하나와 같거나(대소문자 무시) [partial]에 든 말을 포함하면 짝의 한쪽으로 본다. */
private class NameRule(val names: List<String>, val partial: List<String> = emptyList()) {
    fun matches(name: String) = names.any { it.equals(name, ignoreCase = true) } || partial.any { name.contains(it, ignoreCase = true) }
}

private val CURRENT_PAGE = NameRule(listOf("ing", "read", "current"), partial = listOf("current", "읽은"))
private val TOTAL_PAGES = NameRule(listOf("page", "pages", "total"), partial = listOf("total", "전체"))

/** 숫자 값과, %가 붙은 글자 수식으로 나온 이름들. */
private class Numbers(val values: Map<String, Double>, val percentText: Set<String>)

/**
 * 읽은 비율(0~1). 계산할 수 없으면 null(막대를 그리지 않는다).
 * 1) 이름에 진행·progress·percent·%가 든 숫자나 수식 값: 글자에 %가 있으면 퍼센트, 아니면 0~1이면 비율이고 1보다 크면 퍼센트다.
 * 2) 없으면 현재 쪽과 전체 쪽이 짝인 숫자 속성(`ing`·`page`, `Current Page`·`Total Pages`처럼 대소문자 무관): 현재 ÷ 전체.
 */
internal fun progressOf(properties: Map<String, JsonElement>): Float? {
    val numbers = collectNumbers(properties)
    return namedProgress(numbers) ?: pairProgress(numbers.values)
}

private fun collectNumbers(properties: Map<String, JsonElement>): Numbers {
    val values = LinkedHashMap<String, Double>()
    val percentText = HashSet<String>()
    for ((name, value) in properties) {
        val obj = value as? JsonObject ?: continue
        when (obj.type()) {
            "number" -> primitiveNumber(obj["number"])?.let { values[name] = it }
            "formula" -> formulaNumber(obj["formula"] as? JsonObject)?.let { (n, isPercent) ->
                values[name] = n
                if (isPercent) percentText += name
            }
        }
    }
    return Numbers(values, percentText)
}

private fun primitiveNumber(element: JsonElement?): Double? = (element as? JsonPrimitive)?.doubleOrNull

/** 수식 결과가 숫자면 그 값, 글자면 `40%`·`40`을 읽는다. (값, 퍼센트 글자인지). */
private fun formulaNumber(result: JsonObject?): Pair<Double, Boolean>? = when (result?.type()) {
    "number" -> primitiveNumber(result["number"])?.let { it to false }
    "string" -> (result["string"] as? JsonPrimitive)?.contentOrNull?.trim()?.let { text ->
        text.removeSuffix("%").trim().toDoubleOrNull()?.let { it to text.endsWith("%") }
    }
    else -> null
}

private fun namedProgress(numbers: Numbers): Float? =
    numbers.values.entries.firstOrNull { (name, _) -> PROGRESS_NAMES.any { name.contains(it, ignoreCase = true) } }
        ?.let { (name, n) -> fraction(n, name in numbers.percentText) }

private fun pairProgress(values: Map<String, Double>): Float? {
    val done = values.entries.firstOrNull { CURRENT_PAGE.matches(it.key) && !TOTAL_PAGES.matches(it.key) }?.value
    val total = values.entries.firstOrNull { TOTAL_PAGES.matches(it.key) && !CURRENT_PAGE.matches(it.key) }?.value
    return if (done != null && total != null && total > 0) (done / total).toFloat().coerceIn(0f, 1f) else null
}

private fun fraction(n: Double, percent: Boolean): Float = when {
    percent -> n / 100
    n <= 1.0 -> n
    else -> n / 100
}.toFloat().coerceIn(0f, 1f)

/**
 * M5b 읽은 쪽·전체 쪽 숫자 속성에 [ViewColumn.role]을 달아 쓰기 정보에 넣는다. [progressOf]의 `ing`·`page` 짝과 같은 이름 규칙이고,
 * 보기에서 숨긴 속성이라도 스키마에 있으면 들어온다(위젯 행은 그 값을 `view_rows.typed`에 함께 둔다). 짝이 없으면 그대로다.
 */
internal fun withReadingRoles(columns: List<ViewColumn>, schema: DataSourceDto): List<ViewColumn> {
    val numbers = schema.properties.values.filter { it.type == "number" }
    val read = numbers.firstOrNull { CURRENT_PAGE.matches(it.name) && !TOTAL_PAGES.matches(it.name) } ?: return columns
    val total = numbers.firstOrNull { TOTAL_PAGES.matches(it.name) && !CURRENT_PAGE.matches(it.name) }
    val roles = listOfNotNull(read to ViewColumn.ROLE_PAGES_READ, total?.let { it to ViewColumn.ROLE_PAGES_TOTAL })
    val tagged = columns.map { column -> roles.firstOrNull { (property, _) -> property.id == column.id }?.let { column.copy(role = it.second) } ?: column }
    val missing = roles.filter { (property, _) -> tagged.none { it.id == property.id } }
    return tagged + missing.map { (property, role) -> ViewColumn(property.id, property.name, "number", role = role) }
}
