package com.ysdoing.notioncal.widget

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ysdoing.notioncal.core.data.WidgetConfig
import com.ysdoing.notioncal.core.data.db.ViewSnapshotEntity
import com.ysdoing.notioncal.core.data.view.LayoutItem
import com.ysdoing.notioncal.core.data.view.LayoutMode
import com.ysdoing.notioncal.core.data.view.ViewColumn
import com.ysdoing.notioncal.core.data.view.ViewJson
import com.ysdoing.notioncal.core.data.view.WidgetLayout
import com.ysdoing.notioncal.core.designsystem.CheckboxRow
import com.ysdoing.notioncal.core.designsystem.RadioRow

private const val TAB_NAME_MAX = 20

/** 글자 수는 UTF-16이 아니라 코드 포인트로 센다(이모지 같은 두 칸짜리 글자를 반으로 자르지 않는다). */
internal fun String.takeCodePoints(max: Int): String = if (codePointCount(0, length) <= max) this else substring(0, offsetByCodePoints(0, max))

/**
 * Phase 11d WL-01~03 노션 위젯의 배치 설정: 종류(단일·탭·섹션), 담을 보기(앱 설정의 '보기 추가'로 등록한 것), 순서, 탭 이름, 섹션 높이 비율.
 * 편집 규칙(상한·중복 거부·순서)은 [WidgetLayout]이 맡고 여기는 값을 넘기기만 한다.
 */
@Composable
internal fun LayoutSettings(views: List<ViewSnapshotEntity>, config: WidgetConfig, onChange: (WidgetConfig) -> Unit) {
    val layout = remember(config.layoutJson) { WidgetLayout.parse(config.layoutJson) }
    // 마지막으로 본 탭은 번호라서, 탭을 빼거나 옮기면 같은 보기를 가리키도록 번호를 다시 맞춘다.
    val columnsOf = remember(views) { views.associate { it.viewId to ViewJson.columns(it.columnInfo) } }
    val set = { new: WidgetLayout -> onChange(config.copy(layoutJson = WidgetLayout.encode(new), lastTab = new.tabIndexAfter(layout, config.lastTab))) }
    ConfigSection(stringResource(R.string.layout_config_mode))
    ChoiceChips(
        listOf(
            LayoutMode.SINGLE to R.string.layout_config_mode_single, LayoutMode.TABS to R.string.layout_config_mode_tabs,
            LayoutMode.SECTIONS to R.string.layout_config_mode_sections, LayoutMode.MERGED to R.string.layout_config_mode_merged,
        ),
        layout.mode,
    ) { set(layout.withMode(it)) }
    ConfigSection(stringResource(R.string.layout_config_views))
    views.forEach { view ->
        CheckboxRow(view.name.ifBlank { stringResource(R.string.widget_config_view_untitled) }, layout.items.any { it.viewId == view.viewId }) { on ->
            set(layout.toggle(view.viewId, on) ?: layout) // 상한을 넘으면 그대로 둔다
        }
    }
    if (views.isEmpty()) ConfigNote(stringResource(R.string.widget_config_view_none))
    when (layout.mode) {
        LayoutMode.TABS -> ConfigNote(stringResource(R.string.layout_config_limit_tabs, LayoutMode.TABS.limit))
        LayoutMode.SECTIONS -> {
            val tileRow = layout.tiles.isNotEmpty() // 집계 타일 줄이 섹션 한 자리를 쓴다
            ConfigNote(stringResource(R.string.layout_config_limit_sections, LayoutMode.SECTIONS.limit - if (tileRow) 1 else 0))
            if (tileRow) ConfigNote(stringResource(R.string.layout_config_limit_tile_row))
        }
        LayoutMode.MERGED -> ConfigNote(stringResource(R.string.layout_config_limit_merged, LayoutMode.MERGED.limit))
        LayoutMode.SINGLE -> Unit
    }
    if (layout.mode != LayoutMode.SINGLE && layout.items.isNotEmpty()) {
        ConfigSection(stringResource(if (layout.mode == LayoutMode.TABS) R.string.layout_config_order_tabs else R.string.layout_config_order_sections))
        layout.items.forEachIndexed { index, item ->
            // 이름 없는 보기도 노션에서 허용된다. 등록 목록에 없을 때만 '지워진 보기'다.
            val name = views.firstOrNull { it.viewId == item.viewId }?.name?.ifBlank { stringResource(R.string.widget_config_view_untitled) } ?: stringResource(R.string.layout_config_gone)
            LayoutItemEditor(name, index, layout, set)
            if (layout.mode == LayoutMode.MERGED) MergedSourceEditor(layout.items[index], columnsOf[layout.items[index].viewId].orEmpty(), layout, set)
        }
    }
    if (layout.mode == LayoutMode.SECTIONS) TileSettings(views, layout, set)
}

/** 담은 보기 하나: 이름 + 위·아래·빼기, 그 아래에 탭 이름 입력(탭) 또는 높이 비율(섹션). */
@Composable
private fun LayoutItemEditor(name: String, index: Int, layout: WidgetLayout, set: (WidgetLayout) -> Unit) {
    val item = layout.items[index]
    val viewId = item.viewId
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(name, Modifier.weight(1f), fontWeight = FontWeight.Bold)
            val up = stringResource(R.string.layout_config_up_description, name)
            val down = stringResource(R.string.layout_config_down_description, name)
            val remove = stringResource(R.string.layout_config_remove_description, name)
            TextButton(onClick = { set(layout.move(viewId, -1)) }, enabled = index > 0, modifier = Modifier.semantics { contentDescription = up }) { Text(stringResource(R.string.layout_config_up)) }
            TextButton(onClick = { set(layout.move(viewId, 1)) }, enabled = index < layout.items.lastIndex, modifier = Modifier.semantics { contentDescription = down }) { Text(stringResource(R.string.layout_config_down)) }
            TextButton(onClick = { set(layout.remove(viewId)) }, modifier = Modifier.semantics { contentDescription = remove }) {
                Text(stringResource(R.string.layout_config_remove), color = MaterialTheme.colorScheme.error)
            }
        }
        if (layout.mode == LayoutMode.TABS) {
            // 저장은 앞뒤 공백을 자르지만 입력 칸은 그대로 둔다(안 그러면 낱말 사이 띄어쓰기를 칠 수 없다).
            var text by remember(viewId) { mutableStateOf(item.label) }
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.takeCodePoints(TAB_NAME_MAX); set(layout.withLabel(viewId, text)) },
                label = { Text(stringResource(R.string.layout_config_tab_name)) },
                placeholder = { Text(name) },
                supportingText = { Text(stringResource(R.string.layout_config_tab_name_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        } else if (layout.mode == LayoutMode.SECTIONS) {
            Text(stringResource(R.string.layout_config_weight), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ChoiceChips(WidgetLayout.WEIGHT_RANGE.map { it to null }, item.weight) { set(layout.withWeight(viewId, it)) }
        }
    }
}

/**
 * 합쳐 보기의 소스 하나: 일정이 읽을 날짜 속성(날짜 속성이 둘 이상일 때만 고른다, 하나뿐이면 보기의 기본이다)과 일정 색.
 * 새 항목의 날짜는 늘 보기의 기본 날짜 속성에 들어가니, 기준을 바꿔도 날짜 칸으로 만드는 항목의 위치는 그대로다.
 */
@Composable
private fun MergedSourceEditor(item: LayoutItem, columns: List<ViewColumn>, layout: WidgetLayout, set: (WidgetLayout) -> Unit) {
    val dates = dateChoices(columns)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (dates.size > 1) {
            Text(stringResource(R.string.layout_merged_date), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            RadioRow(stringResource(R.string.layout_merged_date_default), item.dateProp.isEmpty()) { set(layout.withDateProp(item.viewId, "")) }
            dates.forEach { column -> RadioRow(column.name, item.dateProp == column.id) { set(layout.withDateProp(item.viewId, column.id)) } }
        }
        Text(stringResource(R.string.layout_merged_color), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ChoiceChips(COLOR_CHOICES, item.color) { set(layout.withColor(item.viewId, it)) }
    }
}
