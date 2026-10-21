package com.ysdoing.notioncal.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ysdoing.notioncal.R
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.ui.semantics.Role
import com.ysdoing.notioncal.core.data.parseIdList
import com.ysdoing.notioncal.core.data.selectableExtraProps
import com.ysdoing.notioncal.core.data.SourceKind
import com.ysdoing.notioncal.core.data.sourceKind
import com.ysdoing.notioncal.core.data.NotionColors
import com.ysdoing.notioncal.core.data.db.DataSourceEntity
import androidx.compose.material.icons.filled.Check
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.filled.Warning
import com.ysdoing.notioncal.core.data.SchemaIssue
import com.ysdoing.notioncal.core.data.parsePropCatalog
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi

@Composable
internal fun SourceRow(
    source: DataSourceEntity,
    onToggle: (Boolean) -> Unit,
    onChooseProps: () -> Unit,
    onChooseColor: () -> Unit,
    onDelete: () -> Unit,
) {
    // HB-01: 습관 DB(✅ 기록·🌱 습관)는 달력에 나오지 않아 색·보기 속성 설정이 없다. 종류만 밝힌다.
    val calendar = source.sourceKind == SourceKind.CALENDAR
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        if (calendar) {
            // EX-03: 색 점을 누르면 DB 색·색 기준을 고른다(터치 영역 48dp).
            val colorLabel = stringResource(R.string.settings_color_button, source.name)
            IconButton(onClick = onChooseColor, modifier = Modifier.semantics { contentDescription = colorLabel }) {
                Box(Modifier.size(18.dp).clip(CircleShape).background(Color(NotionColors.argb(source.colorHex))))
            }
        }
        Column(Modifier.weight(1f).padding(start = if (calendar) 0.dp else 12.dp)) {
            Text(source.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val kindLabel = when (source.sourceKind) {
                SourceKind.HABIT_LOG -> R.string.settings_kind_habit_log
                SourceKind.HABIT -> R.string.settings_kind_habit
                SourceKind.LEDGER -> R.string.settings_kind_ledger
                SourceKind.BUDGET -> R.string.settings_kind_budget
                SourceKind.CATEGORY -> R.string.settings_kind_category
                else -> null
            }
            kindLabel?.let {
                Text(stringResource(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        // NS-09: 매핑한 속성이 사라지거나 종류가 바뀌었으면 경고(자세한 안내는 월 화면 배너).
        if (SchemaIssue.parse(source.schemaIssues).any { it.kind != SchemaIssue.RENAMED }) {
            Icon(
                Icons.Filled.Warning,
                contentDescription = stringResource(R.string.settings_schema_warning),
                tint = MaterialTheme.colorScheme.error,
            )
        }
        if (calendar) {
            // EX-03·EX-11: 색 점만으로는 찾기 어려워 글자 버튼으로도 연다(디자인 리뷰 2026-10-16).
            TextButton(onClick = onChooseColor, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text(stringResource(R.string.settings_color_emphasis))
            }
            TextButton(onClick = onChooseProps, contentPadding = PaddingValues(horizontal = 8.dp)) { Text(stringResource(R.string.settings_extra_props)) }
        }
        Switch(checked = source.enabled, onCheckedChange = onToggle)
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.settings_delete_database, source.name))
        }
    }
}

/**
 * EX-03: DB 색(20색 격자)과 일정 색 기준을 고른다. 색 속성(select)이 있는 DB만 '속성 값 색'을 고를 수 있다.
 * 노션 쪽 옵션 색은 바꾸지 않는다(앱 표시 색만).
 * EX-11: 강조할 일정(속성 하나와 그 값들, 예: 'Pri.'의 '1st')도 여기서 고른다.
 */
@Composable
internal fun ColorDialog(
    source: DataSourceEntity,
    onSave: (String, Boolean, Pair<String?, List<String>>) -> Unit,
    onDismiss: () -> Unit,
) {
    var hex by rememberSaveable(source.dataSourceId) { mutableStateOf(source.colorHex) }
    var useOptions by rememberSaveable(source.dataSourceId) { mutableStateOf(source.useOptionColors) }
    var emphasisProp by rememberSaveable(source.dataSourceId) { mutableStateOf(source.emphasisPropId) }
    var emphasisOptions by rememberSaveable(source.dataSourceId) { mutableStateOf(parseIdList(source.emphasisOptionIds)) }
    val hasColorProp = source.colorPropId != null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_color_title, source.name)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (hasColorProp) {
                    listOf(true to R.string.settings_color_by_option, false to R.string.settings_color_by_database).forEach { (value, label) ->
                        Row(
                            Modifier.fillMaxWidth().selectable(selected = useOptions == value, role = Role.RadioButton) { useOptions = value },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = useOptions == value, onClick = null)
                            Text(stringResource(label), Modifier.padding(start = 8.dp))
                        }
                    }
                }
                Text(
                    stringResource(if (hasColorProp && useOptions) R.string.settings_color_fallback else R.string.settings_color_database),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                NotionColors.pickerPalette.chunked(5).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        row.forEach { color ->
                            val selected = color.equals(hex, ignoreCase = true)
                            Box(
                                Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .selectable(selected = selected, role = Role.RadioButton) { hex = color }
                                    .semantics { contentDescription = color },
                                contentAlignment = Alignment.Center,
                            ) {
                                Box(Modifier.size(32.dp).clip(CircleShape).background(Color(NotionColors.argb(color))), contentAlignment = Alignment.Center) {
                                    if (selected) Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White)
                                }
                            }
                        }
                    }
                }
                EmphasisPicker(
                    source = source,
                    propId = emphasisProp,
                    optionIds = emphasisOptions,
                    onProp = {
                        emphasisProp = it
                        emphasisOptions = emptyList()
                    },
                    onOptions = { emphasisOptions = it },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(hex, useOptions, emphasisProp to emphasisOptions) }) { Text(stringResource(R.string.settings_color_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) } },
    )
}

/**
 * EX-11 강조할 일정: 선택형 속성(select·multi_select·status) 하나와 강조할 값(여러 개)을 고른다.
 * 목록은 동기화 때 받아 둔 스키마(Room)에서 읽는다. 고른 값이면 앱·위젯에서 일정 색 칸·굵은 글씨로 보인다.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EmphasisPicker(
    source: DataSourceEntity,
    propId: String?,
    optionIds: List<String>,
    onProp: (String?) -> Unit,
    onOptions: (List<String>) -> Unit,
) {
    val props = parsePropCatalog(source.propCatalog).filter { it.type in EMPHASIS_TYPES && it.options.isNotEmpty() }
    HorizontalDivider()
    Text(stringResource(R.string.settings_emphasis_title), style = MaterialTheme.typography.titleSmall)
    Text(
        stringResource(if (props.isEmpty()) R.string.settings_emphasis_empty else R.string.settings_emphasis_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (props.isEmpty()) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(selected = propId == null, onClick = { onProp(null) }, label = { Text(stringResource(R.string.settings_emphasis_off)) })
        props.forEach { prop ->
            FilterChip(selected = prop.id == propId, onClick = { onProp(prop.id) }, label = { Text(prop.name, maxLines = 1) })
        }
    }
    val prop = props.firstOrNull { it.id == propId } ?: return
    Text(stringResource(R.string.settings_emphasis_values, prop.name), style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        prop.options.forEach { option ->
            val on = option.id in optionIds
            FilterChip(
                selected = on,
                onClick = { onOptions(if (on) optionIds - option.id else optionIds + option.id) },
                label = { Text(option.name, maxLines = 1) },
                leadingIcon = {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(Color(NotionColors.argb(NotionColors.hexFor(option.color) ?: source.colorHex))))
                },
            )
        }
    }
}

private val EMPHASIS_TYPES = setOf("select", "multi_select", "status")

/** EX-07: 편집 화면에 보여 줄 속성 고르기. 목록은 동기화 때 받아 둔 스키마(Room)에서 읽는다. */
@Composable
internal fun ExtraPropsDialog(source: DataSourceEntity, onSave: (List<String>) -> Unit, onDismiss: () -> Unit) {
    val candidates = source.selectableExtraProps()
    var chosen by rememberSaveable(source.dataSourceId) { mutableStateOf(parseIdList(source.extraPropIds)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_extra_props_title, source.name)) },
        text = {
            Column {
                when {
                    source.propCatalog == null -> Text(stringResource(R.string.settings_extra_props_pending))
                    candidates.isEmpty() -> Text(stringResource(R.string.settings_extra_props_empty))
                    else -> candidates.forEach { prop ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .toggleable(value = prop.id in chosen, role = Role.Checkbox) { on ->
                                    chosen = if (on) chosen + prop.id else chosen - prop.id
                                },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = prop.id in chosen, onCheckedChange = null)
                            Text(prop.name, Modifier.weight(1f))
                            Text(
                                extraTypeLabel(prop.type),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            // 목록 순서대로 저장한다(편집 화면도 이 순서).
            TextButton(onClick = { onSave(candidates.map { it.id }.filter { it in chosen }) }) {
                Text(stringResource(R.string.settings_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) } },
    )
}

@Composable
private fun extraTypeLabel(type: String): String = stringResource(
    when (type) {
        "select" -> R.string.settings_type_select
        "multi_select" -> R.string.settings_type_multi_select
        "status" -> R.string.settings_type_status
        "number" -> R.string.settings_type_number
        else -> R.string.settings_type_url
    },
)
