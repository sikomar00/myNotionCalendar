package com.ysdoing.notioncal.widget

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.ysdoing.notioncal.core.data.WidgetConfig
import com.ysdoing.notioncal.core.data.WidgetTheme
import com.ysdoing.notioncal.core.data.db.DataSourceEntity
import com.ysdoing.notioncal.core.designsystem.CheckboxRow
import kotlin.math.roundToInt

// 위젯 설정 화면의 공통 조각. WidgetConfigActivity.kt가 500줄을 넘지 않게 나눴다.

/**
 * '초기화': 모양 설정(테마·투명도·글자 크기·줄 수·줄 간격·날씨·완료 숨김·금액 숨김·표시할 DB)만 기본값으로 되돌린다.
 * 위젯이 가리키는 대상(보기·디데이)과 보고 있는 달·탭·보드 위치, 사용자가 만든 프리셋·배치는 모양이 아니라 고른 것이라 남긴다.
 */
internal fun WidgetConfig.resetLook(): WidgetConfig = WidgetConfig(
    appWidgetId = appWidgetId, monthOffset = monthOffset, selectedTab = selectedTab, ddayPageId = ddayPageId, viewId = viewId,
    viewPage = viewPage, presetsJson = presetsJson, // 보드 위치와 사용자가 만든 프리셋은 모양이 아니다
    layoutJson = layoutJson, lastTab = lastTab, // 배치(보기 목록)와 보고 있는 탭도 모양이 아니다
)

/**
 * 미리보기를 화면 폭에 맞춘 배율. 높이는 줄이지 않고 [maxHeight]에서 자른다(위젯 위쪽만 보임):
 * 세로로 긴 위젯(4×7)을 높이에 맞추면 글자가 읽히지 않을 만큼 작아지기 때문이다.
 */
internal fun previewScale(size: DpSize, availableWidth: Dp): Float = minOf(1f, availableWidth / size.width)

internal fun previewHeight(size: DpSize, scale: Float, maxHeight: Dp): Dp = minOf(size.height * scale, maxHeight)

@Composable
internal fun ConfigSection(title: String) {
    // 구역 제목은 먹색 굵은 글자(화면 틀에 강조색을 쓰지 않음, DESIGN.md).
    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
}

@Composable
internal fun ConfigNote(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun SourcePicker(sources: List<DataSourceEntity>, selected: Set<String>, onChange: (Set<String>) -> Unit) {
    CheckboxRow(stringResource(R.string.widget_config_sources_all), selected.isEmpty()) { if (it) onChange(emptySet()) }
    sources.forEach { source ->
        CheckboxRow(source.name, source.dataSourceId in selected) { on -> onChange(if (on) selected + source.dataSourceId else selected - source.dataSourceId) }
    }
}

/** 칩 한 줄. 화면 폭을 넘으면 다음 줄로 넘어간다(줄 수 0~6 칩이 좁은 화면에서 잘리지 않게). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun <T> ChoiceChips(options: List<Pair<T, Int?>>, selected: T, onSelect: (T) -> Unit) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (value, label) ->
            FilterChip(
                selected = value == selected,
                onClick = { onSelect(value) },
                label = { Text(label?.let { stringResource(it) } ?: value.toString()) },
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
    }
}

/** 모든 위젯이 쓰는 모양 설정: 테마, 배경 불투명도, 글자 크기. */
@Composable
internal fun AppearanceSettings(config: WidgetConfig, onChange: (WidgetConfig) -> Unit) {
    ConfigSection(stringResource(R.string.widget_config_theme))
    ChoiceChips(
        listOf(
            WidgetTheme.SYSTEM to R.string.widget_config_theme_system,
            WidgetTheme.LIGHT to R.string.widget_config_theme_light,
            WidgetTheme.DARK to R.string.widget_config_theme_dark,
            WidgetTheme.MATERIAL_YOU to R.string.widget_config_theme_material_you,
        ),
        config.theme,
    ) { onChange(config.copy(theme = it)) }

    val opacityName = stringResource(R.string.widget_config_opacity_name)
    ConfigSection(stringResource(R.string.widget_config_opacity, config.opacity))
    Slider(
        modifier = Modifier.semantics { contentDescription = opacityName }, // 이름 없는 슬라이더는 낭독이 '슬라이더'뿐이다(부록 A-8). 값은 슬라이더가 따로 읽는다
        value = config.opacity.toFloat(),
        onValueChange = { onChange(config.copy(opacity = (it / 10).roundToInt() * 10)) },
        valueRange = 0f..100f,
        steps = 9,
    )

    val fontName = stringResource(R.string.widget_config_font_name)
    ConfigSection(stringResource(R.string.widget_config_font, config.fontScale))
    Slider(
        modifier = Modifier.semantics { contentDescription = fontName },
        value = config.fontScale.toFloat(),
        onValueChange = { onChange(config.copy(fontScale = (it / 10).roundToInt() * 10)) },
        valueRange = WidgetConfig.FONT_SCALE_RANGE.first.toFloat()..WidgetConfig.FONT_SCALE_RANGE.last.toFloat(),
        steps = (WidgetConfig.FONT_SCALE_RANGE.last - WidgetConfig.FONT_SCALE_RANGE.first) / 10 - 1,
    )
}
