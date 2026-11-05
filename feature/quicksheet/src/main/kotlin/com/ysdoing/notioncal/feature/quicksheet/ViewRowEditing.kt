package com.ysdoing.notioncal.feature.quicksheet

import com.ysdoing.notioncal.core.data.view.ViewBoard
import com.ysdoing.notioncal.core.data.view.TypedDate
import com.ysdoing.notioncal.core.data.view.ViewColumn
import com.ysdoing.notioncal.core.data.view.ViewRow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import java.time.LocalDate

// Phase 11b ED-01·02: 보기 행 편집 시트의 순수 계산. 화면 없이 시험한다.

/** 편집 시트의 속성 한 줄. 보기의 표시 순서대로 나온다. */
internal sealed interface RowProp {
    val name: String

    /** 앱에서 고칠 수 있는 속성(편집기가 붙는다). */
    data class Editable(val column: ViewColumn) : RowProp {
        override val name: String get() = column.name
    }

    /** 읽기 전용: 잠금 아이콘 + [shown](행에서 받은 표시 글자) + [reason](ED-02). */
    data class Locked(override val name: String, val shown: String, val reason: LockReason) : RowProp
}

/** 잠긴 이유: 노션이 계산하는 값(수식·롤업·생성/편집 일시·작성자·ID) / 노션에서 고치는 값(연결·사람·파일, 쓰기 정보에 없는 형식). */
internal enum class LockReason { COMPUTED, ELSEWHERE }

private val COMPUTED_TYPES = setOf("formula", "rollup", "created_time", "created_by", "last_edited_time", "last_edited_by", "unique_id")

/** 보기의 표시 속성을 순서대로 편집/잠금으로 가른다. 쓰기 정보([ViewBoard.columnInfo])에서 이름으로 짝을 짓는다(한 DB 안에서 속성 이름은 겹치지 않는다). */
internal fun rowProps(board: ViewBoard, row: ViewRow): List<RowProp> = board.columns.mapIndexed { index, name ->
    val column = board.columnInfo.firstOrNull { it.name == name }
    if (column != null && column.writable) {
        RowProp.Editable(column)
    } else {
        RowProp.Locked(name, row.cells.getOrNull(index).orEmpty(), if (column?.type in COMPUTED_TYPES) LockReason.COMPUTED else LockReason.ELSEWHERE)
    }
}

/**
 * 바뀐 속성만 돌려준다(속성 ID → 새 값, 비우기는 [JsonNull]). 읽기 전용·모르는 열은 없는 것으로 본다.
 * 모양만 다른 값은 바뀐 것이 아니다: 빈 글자와 값 없음, 꺼짐과 값 없음, 다중 선택의 순서.
 */
internal fun changedValues(columns: List<ViewColumn>, initial: Map<String, JsonElement>, edited: Map<String, JsonElement>): Map<String, JsonElement> =
    columns.filter { it.writable && canonical(it, initial[it.id]) != canonical(it, edited[it.id]) }
        .associate { it.id to (edited[it.id] ?: JsonNull) }

private fun canonical(column: ViewColumn, value: JsonElement?): Any? = when (column.type) {
    "checkbox" -> (value as? JsonPrimitive)?.booleanOrNull == true
    "title", "rich_text", "url", "email", "phone_number" -> (value as? JsonPrimitive)?.contentOrNull.orEmpty()
    "number" -> (value as? JsonPrimitive)?.doubleOrNull
    "select", "status" -> (value as? JsonPrimitive)?.contentOrNull
    "multi_select" -> (value as? JsonArray).orEmpty().map(::optionKey).toSet()
    "date" -> TypedDate.parse(value)
    else -> value
}

/** 다중 선택 값 하나의 비교 키: 기존 옵션은 ID, 새 옵션은 이름(`{"name":…}`). */
private fun optionKey(item: JsonElement): String =
    (item as? JsonPrimitive)?.contentOrNull ?: "new:" + ((item as? JsonObject)?.get("name") as? JsonPrimitive)?.contentOrNull.orEmpty()

/** 숫자 입력칸의 글자를 읽은 결과: 값 / 비어 있음(값 지우기) / 숫자가 아님(저장 못 함). */
internal sealed interface NumberInput {
    data class Value(val number: Double) : NumberInput
    data object Blank : NumberInput
    data object Invalid : NumberInput
}

internal fun parseNumber(text: String): NumberInput {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return NumberInput.Blank
    // NaN·Infinity·넘치는 큰 수는 JSON으로 쓸 수 없어 저장하다 앱이 꺼진다: 숫자가 아닌 것으로 본다.
    return trimmed.toDoubleOrNull()?.takeIf { it.isFinite() }?.let { NumberInput.Value(it) } ?: NumberInput.Invalid
}

/** 12.0 → "12", 12.5 → "12.5" */
internal fun formatNumber(value: Double): String =
    if (value % 1.0 == 0.0 && kotlin.math.abs(value) < 1e15) value.toLong().toString() else value.toString()
