package com.ysdoing.notioncal.widget

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.ysdoing.notioncal.core.data.WidgetConfig
import com.ysdoing.notioncal.core.data.db.ViewSnapshotEntity
import com.ysdoing.notioncal.core.data.view.ActionChange
import com.ysdoing.notioncal.core.data.view.ActionOp
import com.ysdoing.notioncal.core.data.view.ViewAction
import com.ysdoing.notioncal.core.data.view.ViewActions
import com.ysdoing.notioncal.core.data.view.ViewColumn
import com.ysdoing.notioncal.core.data.view.ViewJson
import com.ysdoing.notioncal.core.designsystem.RadioRow

// Phase 12c IX-07 액션 버튼 설정: 보기마다 이름 붙은 액션(속성 바꿈 1~3개)을 만들고 고친다. 규칙(정리·상한)은 [ViewActions]·[ViewAction]이 맡는다.

private val ACTION_TYPES = setOf("select", "status", "checkbox", "number", "date")

/** 액션이 바꿀 수 있는 속성: 선택·상태·체크박스·숫자·날짜. */
internal fun actionColumns(columns: List<ViewColumn>): List<ViewColumn> = columns.filter { it.type in ACTION_TYPES && it.writable }

/** 속성을 처음 고를 때의 변경: 체크박스는 체크, 선택·상태는 첫 옵션, 숫자는 0, 날짜는 하루 뒤. */
internal fun defaultChange(column: ViewColumn): ActionChange = when (column.type) {
    "checkbox" -> ActionChange(column.id, ActionOp.SET, "true")
    "select", "status" -> ActionChange(column.id, ActionOp.SET, column.options.firstOrNull()?.id.orEmpty())
    "number" -> ActionChange(column.id, ActionOp.SET, "0")
    else -> ActionChange(column.id, ActionOp.SHIFT_DAYS, days = 1)
}

/** 보기를 고르면 그 보기의 첫 속성 하나로 다시 시작한다(이름은 그대로). */
internal fun ViewAction.withView(viewId: String, columns: List<ViewColumn>): ViewAction =
    copy(viewId = viewId, changes = actionColumns(columns).firstOrNull()?.let { listOf(defaultChange(it)) }.orEmpty())

/** 아직 안 쓴 속성 하나를 변경으로 더한다. 상한이거나 남은 속성이 없으면 그대로다. */
internal fun ViewAction.withNextChange(columns: List<ViewColumn>): ViewAction {
    val free = actionColumns(columns).firstOrNull { column -> changes.none { it.propId == column.id } }
    return if (free == null || changes.size >= ViewActions.CHANGE_LIMIT) this else copy(changes = changes + defaultChange(free))
}

internal fun ViewAction.replaceChange(index: Int, change: ActionChange): ViewAction = copy(changes = changes.mapIndexed { i, old -> if (i == index) change else old })

internal fun ViewAction.withoutChange(index: Int): ViewAction = copy(changes = changes.filterIndexed { i, _ -> i != index })

/** 저장할 수 있는 액션: 정리하고도 남고(이름·변경), 숫자 값은 숫자다. 아니면 null이라 '저장'을 막는다. 새 액션의 번호는 담을 때 붙으니 비어 있어도 된다. */
internal fun ViewAction.complete(columns: List<ViewColumn>): ViewAction? {
    val numbersOk = changes.all { change -> change.op != ActionOp.SET || columns.firstOrNull { it.id == change.propId }?.type != "number" || ViewActions.parseNumber(change.value) != null }
    return copy(id = id.ifEmpty { "new" }).normalized()?.takeIf { numbersOk }?.copy(id = id)
}

private val DATE_STEPS = listOf(
    (ActionOp.TODAY to 0) to R.string.widget_action_date_today,
    (ActionOp.SHIFT_DAYS to -1) to R.string.widget_action_date_yesterday,
    (ActionOp.SHIFT_DAYS to 1) to R.string.widget_action_date_tomorrow,
    (ActionOp.SHIFT_DAYS to 2) to R.string.widget_action_date_after_tomorrow,
    (ActionOp.SHIFT_DAYS to 7) to R.string.widget_action_date_next_week,
)

/** 칩에 이름을 보이는 선택지(같은 것인지는 ID로 본다). */
private class Named(val id: String, val name: String) {
    override fun toString() = name

    override fun equals(other: Any?) = other is Named && other.id == id

    override fun hashCode() = id.hashCode()
}

/** 목록 한 줄 요약('날짜 → 내일로', '상태 → 완료'). 사라진 속성은 낱말만 쓴다. */
internal fun changeSummary(context: Context, change: ActionChange, column: ViewColumn?): String {
    val name = column?.name ?: context.getString(R.string.layout_tile_prop_default)
    val to = when (change.op) {
        ActionOp.TODAY -> context.getString(R.string.widget_action_date_today)
        ActionOp.SHIFT_DAYS -> DATE_STEPS.firstOrNull { it.first == (change.op to change.days) }?.let { context.getString(it.second) } ?: context.getString(R.string.widget_action_days, change.days)
        ActionOp.SET -> when (column?.type) {
            "checkbox" -> context.getString(if (change.value == "true") R.string.widget_action_check_on else R.string.widget_action_check_off)
            "select", "status" -> column.options.firstOrNull { it.id == change.value }?.name ?: change.value
            else -> change.value
        }
    }
    return context.getString(R.string.widget_action_summary, name, to)
}

/**
 * 액션 버튼 목록과 추가·수정·삭제. [viewIds]는 이 위젯이 담은 보기들이다(액션은 보기마다, 위젯당 최대 [ViewActions.LIMIT]개).
 * 위젯 행에는 보기의 첫 액션 하나가 보이고 행 편집 시트에는 전부 보인다.
 */
@Composable
internal fun ActionButtonSettings(viewIds: List<String>, data: ConfigData, config: WidgetConfig, onChange: (WidgetConfig) -> Unit) {
    if (viewIds.isEmpty()) return
    val context = LocalContext.current
    val actions = remember(config.actionsJson) { ViewActions.parse(config.actionsJson) }
    val views = remember(data.views, viewIds) { data.views.filter { it.viewId in viewIds } }
    val columnsOf = remember(views) { views.associate { it.viewId to ViewJson.columns(it.columnInfo) } }
    val set = { new: List<ViewAction> -> onChange(config.copy(actionsJson = ViewActions.encode(new))) }
    var draft by remember { mutableStateOf<ViewAction?>(null) }
    ConfigSection(stringResource(R.string.widget_action_title))
    ConfigNote(stringResource(R.string.widget_action_note, ViewActions.LIMIT))
    actions.forEach { action ->
        val view = views.firstOrNull { it.viewId == action.viewId }
        val columns = columnsOf[action.viewId].orEmpty()
        val summary = action.changes.joinToString(", ") { change -> changeSummary(context, change, columns.firstOrNull { it.id == change.propId }) }
        val edit = stringResource(R.string.widget_action_edit_description, action.name)
        val remove = stringResource(R.string.widget_action_delete_description, action.name)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.widget_action_row, action.name, view?.name?.ifBlank { stringResource(R.string.widget_config_view_untitled) } ?: stringResource(R.string.layout_config_gone), summary), Modifier.weight(1f))
            TextButton(onClick = { draft = action }, modifier = Modifier.semantics { contentDescription = edit }) { Text(stringResource(R.string.layout_tile_edit)) }
            TextButton(onClick = { set(ViewActions.remove(actions, action.id)) }, modifier = Modifier.semantics { contentDescription = remove }) {
                Text(stringResource(R.string.layout_config_remove), color = MaterialTheme.colorScheme.error)
            }
        }
    }
    val editing = draft
    if (editing != null) {
        ActionForm(editing, views, columnsOf, onChange = { draft = it }) { done ->
            if (done != null) (if (editing.id.isEmpty()) ViewActions.add(actions, done) else ViewActions.replace(actions, editing.id, done))?.let(set)
            draft = null
        }
        return
    }
    val canAdd = actions.size < ViewActions.LIMIT && views.any { actionColumns(columnsOf[it.viewId].orEmpty()).isNotEmpty() }
    TextButton(onClick = { views.firstOrNull { actionColumns(columnsOf[it.viewId].orEmpty()).isNotEmpty() }?.let { draft = ViewAction("", it.viewId, "", emptyList()).withView(it.viewId, columnsOf[it.viewId].orEmpty()) } }, enabled = canAdd) {
        Text(stringResource(R.string.widget_action_add))
    }
    if (actions.size >= ViewActions.LIMIT) ConfigNote(stringResource(R.string.widget_action_limit, ViewActions.LIMIT))
}

/** 추가·수정 양식: 보기 → 이름 → 변경(속성 → 값) 1~3개. [onDone]은 저장할 액션(취소면 null)을 받는다. */
@Composable
private fun ActionForm(draft: ViewAction, views: List<ViewSnapshotEntity>, columnsOf: Map<String, List<ViewColumn>>, onChange: (ViewAction) -> Unit, onDone: (ViewAction?) -> Unit) {
    val columns = columnsOf[draft.viewId].orEmpty()
    val ready = draft.complete(columns)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ConfigSection(stringResource(R.string.layout_tile_form_view))
        views.filter { actionColumns(columnsOf[it.viewId].orEmpty()).isNotEmpty() }.forEach { view ->
            RadioRow(view.name.ifBlank { stringResource(R.string.widget_config_view_untitled) }, draft.viewId == view.viewId) { onChange(draft.withView(view.viewId, columnsOf[view.viewId].orEmpty())) }
        }
        OutlinedTextField(
            value = draft.name,
            onValueChange = { onChange(draft.copy(name = it.takeCodePoints(ViewActions.NAME_MAX))) },
            label = { Text(stringResource(R.string.widget_action_form_name)) },
            supportingText = { Text(stringResource(R.string.widget_action_form_name_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        draft.changes.forEachIndexed { index, change -> ChangeEditor(index, change, draft, columns, onChange) }
        if (draft.changes.size < ViewActions.CHANGE_LIMIT) TextButton(onClick = { onChange(draft.withNextChange(columns)) }) { Text(stringResource(R.string.widget_action_add_change)) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onDone(null) }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.layout_tile_cancel)) }
            Button(onClick = { onDone(ready) }, enabled = ready != null, modifier = Modifier.weight(1f)) {
                Text(stringResource(if (draft.id.isEmpty()) R.string.layout_tile_confirm else R.string.layout_tile_save))
            }
        }
    }
}

/** 변경 한 줄: 속성 칩(다른 줄이 쓰는 속성은 뺀다) 아래에 그 속성에 맞는 값 고르기. */
@Composable
private fun ChangeEditor(index: Int, change: ActionChange, draft: ViewAction, columns: List<ViewColumn>, onChange: (ViewAction) -> Unit) {
    val column = columns.firstOrNull { it.id == change.propId }
    val taken = draft.changes.filterIndexed { i, _ -> i != index }.map { it.propId }.toSet()
    val candidates = actionColumns(columns).filter { it.id !in taken }
    val replace = { new: ActionChange -> onChange(draft.replaceChange(index, new)) }
    ConfigSection(stringResource(R.string.widget_action_form_change, index + 1))
    if (column == null) {
        ConfigNote(stringResource(R.string.layout_config_gone))
    } else {
        ChoiceChips(candidates.map { Named(it.id, it.name) to null }, Named(column.id, column.name)) { picked -> candidates.firstOrNull { it.id == picked.id }?.let { replace(defaultChange(it)) } }
        ValueChoice(change, column, replace)
    }
    if (draft.changes.size > 1) TextButton(onClick = { onChange(draft.withoutChange(index)) }) { Text(stringResource(R.string.widget_action_remove_change), color = MaterialTheme.colorScheme.error) }
}

@Composable
private fun ValueChoice(change: ActionChange, column: ViewColumn, replace: (ActionChange) -> Unit) {
    when (column.type) {
        "checkbox" -> ChoiceChips(listOf("true" to R.string.widget_action_check_on, "false" to R.string.widget_action_check_off), change.value) { replace(change.copy(value = it)) }
        "select", "status" -> ChoiceChips(column.options.map { Named(it.id, it.name) to null }, Named(change.value, "")) { replace(change.copy(value = it.id)) }
        "number" -> OutlinedTextField(
            value = change.value,
            onValueChange = { replace(change.copy(value = it.take(NUMBER_MAX_CHARS))) },
            label = { Text(stringResource(R.string.widget_action_form_number)) },
            isError = ViewActions.parseNumber(change.value) == null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        else -> ChoiceChips(DATE_STEPS.map { it.first to it.second }, change.op to change.days) { (op, days) -> replace(ActionChange(change.propId, op, days = days)) }
    }
}

private const val NUMBER_MAX_CHARS = 12
