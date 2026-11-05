package com.ysdoing.notioncal.core.data.view

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** 입력 양식의 속성 하나: 속성 ID와, 새 항목 시트가 처음에 채워 둘 기본값([ViewColumn.display]가 아닌 시트가 쓰는 값 모양, 없으면 null). */
@Serializable
data class FormField(val propId: String, val default: JsonElement? = null)

/**
 * Phase 11g AD-02 보기 하나의 입력 양식. 새 항목 시트가 [fields]의 속성만 이 순서로 보이고(제목은 늘 있다), [showDate]면 날짜 칩을 보인다.
 * 편집 함수는 새 양식을 돌려준다. 아무것도 고르지 않은 양식([isDefault])은 '양식 없음'과 같다(시트가 예전처럼 동작).
 */
@Serializable
data class InputForm(val fields: List<FormField> = emptyList(), val showDate: Boolean = true) {
    val isDefault: Boolean get() = this == InputForm()

    /** 체크하면 맨 뒤에 담고(이미 있으면 그대로), 해제하면 뺀다. */
    fun toggle(propId: String, on: Boolean): InputForm = when {
        !on -> copy(fields = fields.filterNot { it.propId == propId })
        fields.any { it.propId == propId } -> this
        else -> copy(fields = fields + FormField(propId))
    }

    /** [propId]를 [delta]칸(−1 앞, +1 뒤) 옮긴다. 끝이거나 없는 속성이면 그대로다. */
    fun move(propId: String, delta: Int): InputForm {
        val from = fields.indexOfFirst { it.propId == propId }
        val to = from + delta
        if (from < 0 || to !in fields.indices) return this
        return copy(fields = fields.toMutableList().also { it.add(to, it.removeAt(from)) })
    }

    fun withShowDate(on: Boolean): InputForm = copy(showDate = on)

    /** 시트에서 '이 값을 기본값으로'를 눌렀을 때: 양식의 모든 속성의 기본값을 [values]로 바꾼다(값이 없으면 기본값을 지운다). */
    fun withDefaults(values: Map<String, JsonElement>): InputForm = copy(fields = fields.map { it.copy(default = values[it.propId]) })

    /** 시트가 편집기로 보일 속성들: 양식 순서대로, 보기에 없어졌거나 양식에 못 쓰는 속성은 뺀다. */
    fun visibleColumns(columns: List<ViewColumn>): List<ViewColumn> = resolved(columns).map { it.first }

    /** 보이는 속성의 기본값(속성 ID → 값). 옵션이 지워져 더는 맞지 않는 값은 옵션만 걸러 내고, 남는 게 없으면 뺀다. */
    fun defaults(columns: List<ViewColumn>): Map<String, JsonElement> =
        resolved(columns).mapNotNull { (column, field) -> field.default?.let { fitted(column, it) }?.let { column.id to it } }.toMap()

    private fun resolved(columns: List<ViewColumn>): List<Pair<ViewColumn, FormField>> {
        val byId = editable(columns).associateBy { it.id }
        return fields.mapNotNull { field -> byId[field.propId]?.let { it to field } }
    }

    companion object {
        /** 양식에 담을 수 있는 속성: 앱에서 쓸 수 있는 종류에서 제목(늘 있다)과 날짜(날짜 칩)를 뺀 것. */
        fun editable(columns: List<ViewColumn>): List<ViewColumn> = columns.filter { it.writable && it.type != "title" && it.type != "date" }

        private fun fitted(column: ViewColumn, value: JsonElement): JsonElement? {
            val ids = column.options.map { it.id }.toSet()
            return when (column.type) {
                "select", "status" -> value.takeIf { (it as? JsonPrimitive)?.contentOrNull in ids }
                "multi_select" -> (value as? JsonArray)?.filter { (it as? JsonPrimitive)?.contentOrNull in ids }?.takeIf { it.isNotEmpty() }?.let(::JsonArray)
                else -> value
            }
        }
    }
}

/** 위젯의 `widget_configs.formJson`: 보기 ID → [InputForm]인 JSON 글자 하나. 깨진 글자·깨진 양식은 양식 없음으로 읽고 다른 양식은 지키지 않는다. */
object InputForms {
    private val json = Json { ignoreUnknownKeys = true }
    private val map = MapSerializer(String.serializer(), InputForm.serializer())

    fun parse(text: String): Map<String, InputForm> {
        val root = text.takeIf { it.isNotBlank() }?.let { runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull() } ?: return emptyMap()
        return root.mapNotNull { (viewId, element) ->
            val form = runCatching { json.decodeFromJsonElement(InputForm.serializer(), element) }.getOrNull() ?: return@mapNotNull null
            viewId.trim().takeIf { it.isNotEmpty() }?.let { it to form.normalized() }
        }.toMap()
    }

    fun get(text: String, viewId: String): InputForm? = parse(text)[viewId]

    /** [form]을 [viewId]의 양식으로 넣은 글자. 아무것도 고르지 않은 양식은 항목을 지운다. 양식이 하나도 없으면 빈 글자다. */
    fun put(text: String, viewId: String, form: InputForm): String {
        val forms = parse(text).let { if (form.isDefault) it - viewId else it + (viewId to form) }
        return if (forms.isEmpty()) "" else json.encodeToString(map, forms)
    }

    /** [viewId]의 양식이 있을 때만 그 기본값을 [values]로 바꾼 글자. 양식이 없으면 null이다. */
    fun withDefaults(text: String, viewId: String, values: Map<String, JsonElement>): String? =
        get(text, viewId)?.let { put(text, viewId, it.withDefaults(values)) }

    private fun InputForm.normalized(): InputForm =
        copy(fields = fields.map { it.copy(propId = it.propId.trim()) }.filter { it.propId.isNotEmpty() }.distinctBy { it.propId })
}
