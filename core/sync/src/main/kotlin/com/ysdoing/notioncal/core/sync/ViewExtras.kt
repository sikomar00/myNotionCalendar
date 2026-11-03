package com.ysdoing.notioncal.core.sync

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
private val PAIRS = listOf("ing" to "page", "current" to "total", "읽은" to "전체")

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

/**
 * 읽은 비율(0~1). 계산할 수 없으면 null(막대를 그리지 않는다).
 * 1) 이름에 진행·progress·percent·%가 든 숫자나 수식 값: 글자에 %가 있으면 퍼센트, 아니면 0~1이면 비율이고 1보다 크면 퍼센트다.
 * 2) 없으면 (ing, page)처럼 현재 쪽과 전체 쪽이 짝인 숫자 속성: 현재 ÷ 전체.
 */
internal fun progressOf(properties: Map<String, JsonElement>): Float? {
    val numbers = LinkedHashMap<String, Double>()
    val percentText = HashSet<String>()
    for ((name, value) in properties) {
        val obj = value as? JsonObject ?: continue
        when (val type = obj.type()) {
            "number" -> (obj[type] as? JsonPrimitive)?.doubleOrNull?.let { numbers[name] = it }
            "formula" -> {
                val result = obj["formula"] as? JsonObject ?: continue
                when (result.type()) {
                    "number" -> (result["number"] as? JsonPrimitive)?.doubleOrNull?.let { numbers[name] = it }
                    "string" -> (result["string"] as? JsonPrimitive)?.contentOrNull?.trim()?.let { text ->
                        text.removeSuffix("%").trim().toDoubleOrNull()?.let {
                            numbers[name] = it
                            if (text.endsWith("%")) percentText += name
                        }
                    }
                }
            }
        }
    }
    numbers.entries.firstOrNull { (name, _) -> PROGRESS_NAMES.any { name.contains(it, ignoreCase = true) } }?.let { (name, n) ->
        return fraction(n, name in percentText)
    }
    for ((current, total) in PAIRS) {
        val done = numbers.entries.firstOrNull { it.key.equals(current, ignoreCase = true) || (current.length > 3 && it.key.contains(current)) }?.value
        val all = numbers.entries.firstOrNull { it.key.equals(total, ignoreCase = true) || (total.length > 3 && it.key.contains(total)) }?.value
        if (done != null && all != null && all > 0) return (done / all).toFloat().coerceIn(0f, 1f)
    }
    return null
}

private fun fraction(n: Double, percent: Boolean): Float = when {
    percent -> n / 100
    n <= 1.0 -> n
    else -> n / 100
}.toFloat().coerceIn(0f, 1f)
