package com.ysdoing.notioncal.core.data.view

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.floor

/**
 * Phase 12d RC-05 별점 글자: 숫자 속성(0~[MAX])을 `★★★★☆`로, 선택 속성은 옵션 이름 그대로 보인다(비트맵 없이 글자로 그린다).
 * 숫자는 반올림해 0~[MAX]로 자르고, 값이 없으면(비어 있으면) null이라 카드가 줄을 그리지 않는다.
 */
object RatingText {
    const val MAX = 5

    /** 별 글자. 0점이면 빈 별만, 범위 밖은 끝으로 자른다. */
    fun stars(value: Double): String = rounded(value).let { "★".repeat(it) + "☆".repeat(MAX - it) }

    /** 칸 값 → 카드에 보일 글자. 숫자는 별, 선택은 옵션 이름이고 값이 없거나 맞지 않는 열이면 null. */
    fun text(column: ViewColumn, value: JsonElement?): String? = when (column.type) {
        "number" -> number(value)?.let(::stars)
        "select" -> optionName(column, value)
        else -> null
    }

    /** 낭독 글자: '별점 4점 (5점 만점)', 선택은 '별점 좋음'. [text]가 null이면 null. */
    fun spoken(column: ViewColumn, value: JsonElement?): String? = when (column.type) {
        "number" -> number(value)?.let { "별점 ${rounded(it)}점 (${MAX}점 만점)" }
        "select" -> optionName(column, value)?.let { "별점 $it" }
        else -> null
    }

    private fun rounded(value: Double): Int = if (value.isFinite()) floor(value.coerceIn(0.0, MAX.toDouble()) + 0.5).toInt() else 0

    private fun number(value: JsonElement?): Double? = (value as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it.isFinite() }

    private fun optionName(column: ViewColumn, value: JsonElement?): String? {
        val id = (value as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: return null
        return column.options.firstOrNull { it.id == id }?.name?.takeIf { it.isNotBlank() }
    }
}
