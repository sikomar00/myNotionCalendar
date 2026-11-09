package com.ysdoing.notioncal.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import com.ysdoing.notioncal.R
import androidx.compose.material3.Checkbox
import androidx.compose.ui.semantics.Role
import com.ysdoing.notioncal.core.data.parseIdList
import com.ysdoing.notioncal.core.data.selectableExtraProps
import com.ysdoing.notioncal.core.data.NotionColors
import com.ysdoing.notioncal.core.data.db.DataSourceEntity
import androidx.compose.material.icons.filled.Check
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.selection.selectable
import com.ysdoing.notioncal.core.data.SchemaIssue
import com.ysdoing.notioncal.core.data.parsePropCatalog
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import com.ysdoing.notioncal.core.designsystem.toggleRow
import com.ysdoing.notioncal.core.designsystem.RadioRow

/** NS-09 매핑한 속성이 사라지거나 종류가 바뀌었다(이름만 바뀐 것은 제외). DB 줄 경고와 설정 탭의 문제 배지가 같이 쓴다. */
internal fun hasSchemaProblem(source: DataSourceEntity): Boolean = SchemaIssue.parse(source.schemaIssues).any { it.kind != SchemaIssue.RENAMED }

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
                    // 줄 전체 48dp 눌림 영역(designsystem 규칙, UX-02). 날 selectable은 라디오 높이(약 24dp)뿐이었다.
                    listOf(true to R.string.settings_color_by_option, false to R.string.settings_color_by_database).forEach { (value, label) ->
                        RadioRow(stringResource(label), selected = useOptions == value) { useOptions = value }
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
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .selectable(selected = selected, role = Role.RadioButton) { hex = color }
                                    .semantics { contentDescription = NotionColors.nameKo(color) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Box(Modifier.size(32.dp).clip(CircleShape).background(Color(NotionColors.argb(color))), contentAlignment = Alignment.Center) {
                                    if (selected) Icon(Icons.Filled.Check, contentDescription = null, tint = Color(NotionColors.onColor(color)))
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
                                .toggleRow(prop.id in chosen, Role.Checkbox) { on -> // 줄 전체 48dp(UX-02)
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
