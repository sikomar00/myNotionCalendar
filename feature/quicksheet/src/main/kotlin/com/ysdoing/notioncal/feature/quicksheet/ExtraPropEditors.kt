package com.ysdoing.notioncal.feature.quicksheet

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.ysdoing.notioncal.core.data.ExtraProp
import com.ysdoing.notioncal.core.data.NotionColors
import com.ysdoing.notioncal.core.data.OutboxJson
import com.ysdoing.notioncal.core.data.PropValue
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/** 편집 중인 추가 속성 값(속성 ID → 값). 프로세스가 죽었다 살아나도 남게 JSON으로 저장한다(PF-06). */
internal val PropEditsSaver = Saver<Map<String, PropValue>, String>(
    save = { OutboxJson.encodeToString(MapSerializer(String.serializer(), PropValue.serializer()), it) },
    restore = { OutboxJson.decodeFromString(MapSerializer(String.serializer(), PropValue.serializer()), it) },
)

/** EX-07: 속성 하나의 편집기. 타입은 노션 API 이름(select·status·multi_select·number·url). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ExtraPropEditor(prop: ExtraProp, value: PropValue, onChange: (PropValue) -> Unit, key: String) {
    Column(Modifier.fillMaxWidth()) {
        if (prop.type != "number" && prop.type != "url") {
            Text(prop.name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        when (prop.type) {
            "select", "status" -> FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val selected = value.optionIds?.firstOrNull()
                prop.options.forEach { option ->
                    OptionChip(option.name, option.color, selected = option.id == selected) {
                        // status는 비울 수 없다(노션 규칙). select는 다시 누르면 비운다.
                        val next = if (option.id == selected && prop.type == "select") emptyList() else listOf(option.id)
                        onChange(PropValue(optionIds = next))
                    }
                }
            }
            "multi_select" -> FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val selected = value.optionIds.orEmpty()
                prop.options.forEach { option ->
                    OptionChip(option.name, option.color, selected = option.id in selected) {
                        onChange(PropValue(optionIds = if (option.id in selected) selected - option.id else selected + option.id))
                    }
                }
            }
            "number" -> {
                var text by rememberSaveable(key) { mutableStateOf(value.number?.let(::formatNumber).orEmpty()) }
                OutlinedTextField(
                    value = text,
                    onValueChange = { input ->
                        text = input
                        onChange(PropValue(number = input.trim().toDoubleOrNull()))
                    },
                    label = { Text(prop.name) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = text.isNotBlank() && text.trim().toDoubleOrNull() == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
            }
            "url" -> {
                var text by rememberSaveable(key) { mutableStateOf(value.text.orEmpty()) }
                val context = LocalContext.current
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { input ->
                            text = input
                            onChange(PropValue(text = input.trim().ifEmpty { null }))
                        },
                        label = { Text(prop.name) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                    )
                    if (text.startsWith("http://") || text.startsWith("https://")) {
                        TextButton(onClick = {
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, text.trim().toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        }) { Text(stringResource(R.string.qs_open_link)) }
                    }
                }
            }
        }
    }
}

/** 노션 옵션 색 점이 붙은 선택 칩. [color]는 노션 색 이름이고 모르면 회색이다. 일정 시트와 보기 행 시트가 같이 쓴다. */
@Composable
internal fun OptionChip(name: String, color: String?, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(name) },
        leadingIcon = {
            Box(Modifier.size(8.dp).clip(CircleShape).background(Color(NotionColors.argb(NotionColors.hexFor(color) ?: "#787774"))))
        },
    )
}
