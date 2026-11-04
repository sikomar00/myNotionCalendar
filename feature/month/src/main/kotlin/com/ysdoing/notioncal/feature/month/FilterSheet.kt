package com.ysdoing.notioncal.feature.month

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ysdoing.notioncal.core.data.NotionColors
import com.ysdoing.notioncal.core.data.calendar.EventFilter
import com.ysdoing.notioncal.core.designsystem.SwitchRow
import com.ysdoing.notioncal.core.designsystem.toggleRow

/** 필터 아이콘(세 줄 깔때기). material-icons-core에 없어 같은 모양을 직접 그린다. */
internal val FilterIcon: ImageVector = materialIcon(name = "Filled.FilterList") {
    materialPath {
        moveTo(10f, 18f)
        horizontalLineToRelative(4f)
        verticalLineToRelative(-2f)
        horizontalLineToRelative(-4f)
        close()
        moveTo(3f, 6f)
        verticalLineToRelative(2f)
        horizontalLineToRelative(18f)
        verticalLineTo(6f)
        close()
        moveTo(6f, 13f)
        horizontalLineToRelative(12f)
        verticalLineToRelative(-2f)
        horizontalLineTo(6f)
        close()
    }
}

/**
 * MV-09 필터 시트. 바꾸는 즉시 [onChange]로 저장하고, 모든 보기에 바로 반영된다.
 * DB 스위치를 끄면 그 DB의 값 칩은 감춘다(DB 전체가 숨겨져 의미가 없으므로).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilterSheet(
    filter: EventFilter,
    sources: List<FilterSource>,
    onChange: (EventFilter) -> Unit,
    onDismiss: () -> Unit,
) {
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        contentWindowInsets = { WindowInsets(0) },
    ) {
        FilterContent(filter, sources, onChange, Modifier.padding(bottom = 16.dp + navBottom))
    }
}

@Composable
internal fun FilterContent(
    filter: EventFilter,
    sources: List<FilterSource>,
    onChange: (EventFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.filter_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = { onChange(EventFilter()) }, enabled = filter.isActive) { Text(stringResource(R.string.filter_reset)) }
        }
        if (sources.isEmpty()) {
            Text(stringResource(R.string.filter_no_sources), style = MaterialTheme.typography.bodyMedium)
        }
        sources.forEach { source ->
            val shown = source.id !in filter.hiddenSourceIds
            Row(
                Modifier.fillMaxWidth().toggleRow(shown) { on -> // 이름을 눌러도 켜지고 줄이 48dp 이상(A-5), 이름이 스위치의 이름이다
                    onChange(filter.copy(hiddenSourceIds = if (on) filter.hiddenSourceIds - source.id else filter.hiddenSourceIds + source.id))
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Dot(Color(NotionColors.argb(source.colorHex)))
                Spacer(Modifier.width(10.dp))
                Text(source.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Switch(checked = shown, onCheckedChange = null)
            }
            if (shown && source.hasColorProp) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(start = 20.dp)) {
                    source.options.forEach { option ->
                        OptionChip(
                            label = option.name,
                            color = Color(NotionColors.argb(NotionColors.hexFor(option.color) ?: source.colorHex)),
                            key = EventFilter.optionKey(source.id, option.id),
                            filter = filter,
                            onChange = onChange,
                        )
                    }
                    OptionChip(
                        label = stringResource(R.string.filter_no_value),
                        color = Color(NotionColors.argb(source.colorHex)),
                        key = EventFilter.optionKey(source.id, null),
                        filter = filter,
                        onChange = onChange,
                    )
                }
            }
            HorizontalDivider()
        }
        SwitchRow(stringResource(R.string.filter_hide_done), filter.hideDone) { onChange(filter.copy(hideDone = it)) }
        Text(
            stringResource(R.string.filter_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun OptionChip(label: String, color: Color, key: String, filter: EventFilter, onChange: (EventFilter) -> Unit) {
    val shown = key !in filter.hiddenOptionKeys
    FilterChip(
        selected = shown,
        onClick = {
            onChange(filter.copy(hiddenOptionKeys = if (shown) filter.hiddenOptionKeys + key else filter.hiddenOptionKeys - key))
        },
        label = { Text(label) },
        leadingIcon = { Dot(color) },
    )
}

@Composable
private fun Dot(color: Color) {
    Box(Modifier.size(10.dp).clip(CircleShape).background(color))
}
