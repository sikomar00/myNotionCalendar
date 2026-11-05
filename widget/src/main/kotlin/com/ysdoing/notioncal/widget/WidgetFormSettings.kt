package com.ysdoing.notioncal.widget

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import com.ysdoing.notioncal.core.data.WidgetConfig
import com.ysdoing.notioncal.core.data.db.ViewSnapshotEntity
import com.ysdoing.notioncal.core.data.view.InputForm
import com.ysdoing.notioncal.core.data.view.InputForms
import com.ysdoing.notioncal.core.data.view.formColumns
import com.ysdoing.notioncal.core.data.view.ViewColumn
import com.ysdoing.notioncal.core.designsystem.CheckboxRow
import com.ysdoing.notioncal.core.designsystem.SwitchRow

/** 입력 양식 편집 줄 하나: 양식에 담을 수 있는 속성 [column]과 담았는지 [checked]. */
internal data class FormRow(val column: ViewColumn, val checked: Boolean)

/** 담은 속성을 양식 순서대로 먼저, 나머지 담을 수 있는 속성을 보기 순서대로. 보기에서 사라진 속성은 양식에 남아 있어도 보이지 않는다. */
internal fun formRows(form: InputForm, columns: List<ViewColumn>): List<FormRow> {
    val checked = form.visibleColumns(columns)
    val rest = InputForm.editable(columns).filter { column -> checked.none { it.id == column.id } }
    return checked.map { FormRow(it, true) } + rest.map { FormRow(it, false) }
}

/**
 * Phase 11g AD-02 입력 양식 설정: 보기마다 새 항목 시트에 보일 속성(체크)과 순서(위·아래), 날짜 칩 여부. 기본값은 시트에서 정한다.
 * 편집 규칙은 [InputForm]이 맡고 여기는 값을 넘기기만 한다.
 */
@Composable
internal fun InputFormSettings(viewIds: List<String>, data: ConfigData, config: WidgetConfig, onChange: (WidgetConfig) -> Unit) {
    if (viewIds.isEmpty()) return
    val forms = remember(config.formJson) { InputForms.parse(config.formJson) }
    ConfigSection(stringResource(R.string.widget_config_form))
    ConfigNote(stringResource(R.string.widget_config_form_note))
    viewIds.forEach { viewId -> ViewFormEditor(viewId, data.views.firstOrNull { it.viewId == viewId }, forms[viewId], config, onChange) }
}

@Composable
private fun ViewFormEditor(viewId: String, view: ViewSnapshotEntity?, stored: InputForm?, config: WidgetConfig, onChange: (WidgetConfig) -> Unit) {
    val name = view?.name?.ifBlank { stringResource(R.string.widget_config_view_untitled) } ?: stringResource(R.string.layout_config_gone)
    Text(name, fontWeight = FontWeight.Bold)
    // 속성은 보기 행을 읽지 않고 등록한 보기의 쓰기 정보에서 얻는다(안 보이는 도우미 열은 빠진다).
    val columns = remember(view?.columnInfo, view?.columns) { view?.formColumns().orEmpty() }
    val hasDate = columns.any { it.type == "date" }
    if (InputForm.editable(columns).isEmpty() && !hasDate) {
        ConfigNote(stringResource(if (columns.isEmpty()) R.string.widget_config_form_unavailable else R.string.widget_config_form_none))
        return
    }
    val form = remember(stored, columns) { stored?.pruned(columns) ?: InputForm() }
    val set = { new: InputForm -> onChange(config.copy(formJson = InputForms.put(config.formJson, viewId, new))) }
    val defaults = remember(form, columns) { form.defaults(columns) }
    val rows = remember(form, columns) { formRows(form, columns) }
    val checkedCount = rows.count { it.checked }
    rows.forEachIndexed { index, row -> FormRowEditor(row, index, checkedCount, defaults[row.column.id]?.let(row.column::display), form, set) }
    if (hasDate) SwitchRow(stringResource(R.string.widget_config_form_date), form.showDate) { set(form.withShowDate(it)) }
}

/** 속성 하나: 체크, 담았으면 기본값 요약과 위·아래. [index]는 [formRows]에서의 자리(담은 것들이 앞이라 담은 줄에서는 양식 안의 자리와 같다). */
@Composable
private fun FormRowEditor(row: FormRow, index: Int, checkedCount: Int, default: String?, form: InputForm, set: (InputForm) -> Unit) {
    val name = row.column.name
    val label = if (default.isNullOrEmpty()) name else stringResource(R.string.widget_config_form_row_default, name, default)
    Row(verticalAlignment = Alignment.CenterVertically) {
        CheckboxRow(label, row.checked, Modifier.weight(1f)) { set(form.toggle(row.column.id, it)) }
        if (row.checked) {
            val up = stringResource(R.string.layout_config_up_description, name)
            val down = stringResource(R.string.layout_config_down_description, name)
            TextButton(onClick = { set(form.move(row.column.id, -1)) }, enabled = index > 0, modifier = Modifier.semantics { contentDescription = up }) { Text(stringResource(R.string.layout_config_up)) }
            TextButton(onClick = { set(form.move(row.column.id, 1)) }, enabled = index < checkedCount - 1, modifier = Modifier.semantics { contentDescription = down }) { Text(stringResource(R.string.layout_config_down)) }
        }
    }
}
