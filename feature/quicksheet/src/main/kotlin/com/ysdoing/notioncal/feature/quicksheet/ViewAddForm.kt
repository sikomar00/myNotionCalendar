package com.ysdoing.notioncal.feature.quicksheet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.ysdoing.notioncal.core.data.view.InputForm
import com.ysdoing.notioncal.core.data.view.ViewColumn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement

/**
 * Phase 11g AD-02 새 항목 시트가 입력 양식·필터 채움·보기 열로 정한 것.
 * [fields]는 편집기로 보일 양식 속성(필터에서 채운 속성은 필터 구역이 따로 보이니 뺀다), [start]는 처음 채울 값(양식 기본값 위에 필터 채움을 덮는다),
 * [readable]은 한 줄 입력이 읽을 속성이다. 시작 값이 있는 속성은 사용자가 정한 값이라 해석하지 않고(글자는 제목에 남는다), 양식이 있으면 양식에 없는 속성도 읽지 않는다.
 */
internal data class SheetPlan(val fields: List<ViewColumn>, val start: Map<String, JsonElement>, val readable: List<ViewColumn>, val showDate: Boolean)

internal fun sheetPlan(columns: List<ViewColumn>, hasDate: Boolean, input: InputForm?, prefill: ViewAddPrefill): SheetPlan {
    val start = input?.defaults(columns).orEmpty() + prefill.values
    val fields = input?.visibleColumns(columns).orEmpty().filter { it.id !in prefill.values }
    val showDate = hasDate && input?.showDate != false
    val readable = if (input == null) columns.filter { it.id !in start && (showDate || it.type != "date") } else fields.filter { it.id !in start } + columns.filter { it.type == "date" && showDate }
    return SheetPlan(fields, start, readable, showDate)
}

/** 속성 편집기들. 값이 바뀌면 [onChange]로 속성 ID와 새 값(비웠으면 null)을 알린다. */
@Composable
internal fun PropEditors(columns: List<ViewColumn>, props: Map<String, JsonElement>, onChange: (String, JsonElement?) -> Unit) {
    columns.forEach { column -> key(column.id) { PropField(column, props[column.id], onChange = { onChange(column.id, it) }, onInvalid = {}) } }
}

/**
 * 입력 양식의 속성 편집기와, 위젯에서 연 시트면 '이 값을 기본값으로'(지금 값을 이 위젯·보기의 기본값으로 저장, [onSaveDefaults]가 성공 여부를 돌려 준다).
 * 양식 속성이 없으면 아무것도 그리지 않는다.
 */
@Composable
internal fun FormSection(
    fields: List<ViewColumn>,
    props: Map<String, JsonElement>,
    onChange: (String, JsonElement?) -> Unit,
    onSaveDefaults: (suspend (Map<String, JsonElement?>) -> Boolean)?,
) {
    if (fields.isEmpty()) return
    var saved by rememberSaveable { mutableStateOf<Boolean?>(null) } // null: 아직, true: 저장함, false: 저장하지 못함
    val scope = rememberCoroutineScope()
    PropEditors(fields, props, onChange)
    if (onSaveDefaults == null) return
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = { scope.launch { saved = onSaveDefaults(fields.associate { it.id to props[it.id] }) } }) {
            Text(stringResource(R.string.view_add_defaults_save))
        }
    }
    when (saved) {
        true -> Text(stringResource(R.string.view_add_defaults_saved), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        false -> Text(stringResource(R.string.view_add_defaults_failed), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        null -> Unit
    }
}
