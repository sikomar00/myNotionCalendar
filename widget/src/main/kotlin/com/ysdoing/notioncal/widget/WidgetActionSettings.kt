package com.ysdoing.notioncal.widget

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.ysdoing.notioncal.core.data.WidgetConfig
import com.ysdoing.notioncal.core.data.view.ViewPresets

private val NUMBER_STEPS = listOf(1, 5, 10, 50, 100)

/**
 * Phase 11g ST-07 '동작' 탭(보기·노션 위젯): 숫자 ± 단계(IX-03), 프리셋 목록과 만들기(AD-05), 입력 양식(AD-02).
 * [onCreatePreset]은 새 항목 시트를 '프리셋 만들기'로 여는 일을 화면에 맡긴다(보기 ID를 넘긴다).
 */
@Composable
internal fun ActionSettings(kind: ConfigKind, data: ConfigData, config: WidgetConfig, onChange: (WidgetConfig) -> Unit, onCreatePreset: (String) -> Unit) {
    // IX-03: 숫자 열의 − + 가 한 번에 바꾸는 값. 정수 단계만 고른다(소수 단계는 시트에서 직접 고친다).
    ConfigSection(stringResource(R.string.widget_config_number_step))
    ChoiceChips(NUMBER_STEPS.map { it to null }, config.numberStep.toInt().takeIf { it.toDouble() == config.numberStep } ?: -1) {
        onChange(config.copy(numberStep = it.toDouble()))
    }
    // 프리셋 줄은 보기 하나일 때만 그려지니(탭·섹션은 보기마다 값이 달라 뜻이 안 맞는다) 목록도 그때만 둔다.
    if (presetsApply(kind, config)) PresetList(config, presetViewId(kind, config), onChange, onCreatePreset)
    InputFormSettings(formViewIds(kind, config), data, config, onChange)
}

/** AD-05: 저장해 둔 프리셋 목록과 삭제, 그리고 '프리셋 만들기'([viewId]가 있을 때). 프리셋은 새 항목 시트의 '프리셋으로 저장'으로도 쌓인다. */
@Composable
private fun PresetList(config: WidgetConfig, viewId: String?, onChange: (WidgetConfig) -> Unit, onCreatePreset: (String) -> Unit) {
    val presets = ViewPresets.parse(config.presetsJson)
    ConfigSection(stringResource(R.string.widget_config_presets))
    if (presets.isEmpty()) ConfigNote(stringResource(R.string.widget_config_presets_none))
    presets.forEach { preset ->
        val delete = stringResource(R.string.widget_config_preset_delete, preset.name)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(preset.name, Modifier.weight(1f))
            TextButton(onClick = { onChange(config.copy(presetsJson = ViewPresets.remove(config.presetsJson, preset.name))) }, modifier = Modifier.semantics { contentDescription = delete }) {
                Text(stringResource(R.string.widget_config_preset_delete_label), color = MaterialTheme.colorScheme.error)
            }
        }
    }
    if (viewId != null) OutlinedButton(onClick = { onCreatePreset(viewId) }) { Text(stringResource(R.string.widget_config_preset_create)) }
}
