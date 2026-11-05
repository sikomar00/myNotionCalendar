package com.ysdoing.notioncal.core.data.view

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlin.math.abs

/**
 * 액션이 속성 하나에 하는 일. [SET]은 값을 그대로 쓴다(체크박스 'true'·'false', 선택·상태는 옵션 ID, 숫자, 글자),
 * [SHIFT_DAYS]는 날짜를 며칠 옮기고(날짜가 없으면 오늘부터), [TODAY]는 날짜를 오늘로 맞춘다. 날짜 연산은 시각·시간대를 그대로 둔다([TypedDate]).
 */
@Serializable
enum class ActionOp { SET, SHIFT_DAYS, TODAY }

@Serializable
data class ActionChange(val propId: String, val op: ActionOp = ActionOp.SET, val value: String = "", val days: Int = 0)

/**
 * Phase 12c IX-07 액션 버튼: 한 보기의 행에 속성 바꿈 몇 가지를 한 번에 한다('완료로', '내일로').
 * 눌리면 [ViewWriter.applyAction]이 변경마다 현재 값을 읽어 새 값을 만들고 위젯 즉시 동작처럼 4초 뒤 보낸다.
 */
@Serializable
data class ViewAction(val id: String, val viewId: String, val name: String, val changes: List<ActionChange>) {
    /** 읽을 때 앞뒤 공백·뜻 없는 변경을 바로잡는다. 번호·보기·이름이 비었거나 남는 변경이 없으면 null(버린다). */
    fun normalized(): ViewAction? {
        val kept = changes.mapNotNull { it.normalized() }.distinctBy { it.propId }.take(ViewActions.CHANGE_LIMIT)
        val cleanName = name.trim().take(ViewActions.NAME_MAX)
        if (id.isBlank() || viewId.isBlank() || cleanName.isEmpty() || kept.isEmpty()) return null
        return copy(id = id.trim(), viewId = viewId.trim(), name = cleanName, changes = kept)
    }
}

private fun ActionChange.normalized(): ActionChange? {
    val prop = propId.trim()
    if (prop.isEmpty()) return null
    return when (op) {
        ActionOp.SET -> value.takeIf { it.isNotBlank() }?.let { ActionChange(prop, op, value = it.trim()) }
        ActionOp.SHIFT_DAYS -> days.takeIf { it != 0 && abs(it) <= ViewActions.MAX_DAYS }?.let { ActionChange(prop, op, days = it) }
        ActionOp.TODAY -> ActionChange(prop, op)
    }
}

/** `widget_configs.actionsJson`의 읽기·쓰기와 편집. 편집 함수는 새 목록을 돌려주고 못 하는 일(상한)은 null이다. */
object ViewActions {
    const val LIMIT = 5
    const val CHANGE_LIMIT = 3
    const val NAME_MAX = 12
    const val MAX_DAYS = 365
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** 빈 글자·깨진 글자는 빈 목록이다. 깨진 액션만 버리고 번호가 겹치면 앞의 것만, [LIMIT]개까지 남긴다. */
    fun parse(text: String): List<ViewAction> {
        val array = text.takeIf { it.isNotBlank() }?.let { runCatching { json.parseToJsonElement(it) as? JsonArray }.getOrNull() } ?: return emptyList()
        return array.mapNotNull { runCatching { json.decodeFromJsonElement(ViewAction.serializer(), it) }.getOrNull()?.normalized() }
            .distinctBy { it.id }
            .take(LIMIT)
    }

    fun encode(actions: List<ViewAction>): String = if (actions.isEmpty()) "" else json.encodeToString(ListSerializer(ViewAction.serializer()), actions)

    /** [viewId] 보기의 액션만, 만든 순서대로. */
    fun forView(actions: List<ViewAction>, viewId: String): List<ViewAction> = actions.filter { it.viewId == viewId }

    /** 액션을 담는다(번호는 비어 있는 첫 a1·a2…). 정리한 결과가 비거나 [LIMIT]을 넘으면 null. */
    fun add(actions: List<ViewAction>, action: ViewAction): List<ViewAction>? {
        if (actions.size >= LIMIT) return null
        val id = generateSequence(1) { it + 1 }.map { "a$it" }.first { candidate -> actions.none { it.id == candidate } }
        return action.copy(id = id).normalized()?.let { actions + it }
    }

    /** 액션 [id]를 [action]으로 바꾼다(번호·자리는 그대로). 정리한 결과가 비면 null, 없는 번호면 그대로다. */
    fun replace(actions: List<ViewAction>, id: String, action: ViewAction): List<ViewAction>? =
        action.copy(id = id).normalized()?.let { fixed -> actions.map { if (it.id == id) fixed else it } }

    fun remove(actions: List<ViewAction>, id: String): List<ViewAction> = actions.filterNot { it.id == id }
}
