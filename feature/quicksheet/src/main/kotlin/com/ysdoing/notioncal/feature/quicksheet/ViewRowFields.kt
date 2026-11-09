package com.ysdoing.notioncal.feature.quicksheet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.ysdoing.notioncal.core.data.APP_ZONE
import com.ysdoing.notioncal.core.data.view.TypedDate
import com.ysdoing.notioncal.core.data.view.ViewColumn
import com.ysdoing.notioncal.core.designsystem.CheckboxRow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import java.time.LocalDate

// Phase 11b ED-01·02: 보기 행 편집 시트의 속성별 편집기. 값은 [ViewColumn.display]가 적은 모양(체크 bool, 옵션 ID, 숫자, 글자, 옵션 ID 목록, 날짜 객체)이다.
// 글자 입력칸은 속성 이름을 `label`로 둬 낭독이 입력칸과 이어지게 한다(UX-07). 스크롤 영역은 시트 하나뿐이라 칩은 가로 스크롤 없이 줄바꿈한다(UX-09).

/**
 * 속성 하나의 편집기. [value]는 편집 중인 값이고(없으면 null), 바뀌면 [onChange]로 새 값을 알린다(값 없음은 null).
 * 숫자 칸이 숫자가 아닌 글자일 때는 [onInvalid]로 알려 저장을 막는다.
 */
@Composable
internal fun PropField(column: ViewColumn, value: JsonElement?, onChange: (JsonElement?) -> Unit, onInvalid: (Boolean) -> Unit) {
    when (column.type) {
        "title" -> TextProp(column, value, KeyboardType.Text, false, onChange)
        "rich_text" -> TextProp(column, value, KeyboardType.Text, true, onChange)
        "url" -> TextProp(column, value, KeyboardType.Uri, false, onChange)
        "email" -> TextProp(column, value, KeyboardType.Email, false, onChange)
        "phone_number" -> TextProp(column, value, KeyboardType.Phone, false, onChange)
        "number" -> NumberProp(column, value, onChange, onInvalid)
        "checkbox" -> CheckboxRow(column.name, (value as? JsonPrimitive)?.booleanOrNull == true) { onChange(JsonPrimitive(it)) }
        "select", "status" -> SingleChoiceProp(column, value, onChange)
        "multi_select" -> MultiChoiceProp(column, value, onChange)
        "date" -> DateProp(column, value, onChange)
    }
}

@Composable
private fun TextProp(column: ViewColumn, value: JsonElement?, keyboard: KeyboardType, multiline: Boolean, onChange: (JsonElement?) -> Unit) {
    var text by rememberSaveable(column.id) { mutableStateOf((value as? JsonPrimitive)?.contentOrNull.orEmpty()) }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            onChange(JsonPrimitive(it))
        },
        label = { Text(column.name) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = !multiline,
        minLines = if (multiline) 3 else 1,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard, autoCorrectEnabled = keyboard == KeyboardType.Text),
    )
}

@Composable
private fun NumberProp(column: ViewColumn, value: JsonElement?, onChange: (JsonElement?) -> Unit, onInvalid: (Boolean) -> Unit) {
    var text by rememberSaveable(column.id) { mutableStateOf((value as? JsonPrimitive)?.doubleOrNull?.let(::formatNumber).orEmpty()) }
    val invalid = parseNumber(text) is NumberInput.Invalid
    OutlinedTextField(
        value = text,
        onValueChange = { input ->
            text = input
            val parsed = parseNumber(input)
            onInvalid(parsed is NumberInput.Invalid)
            when (parsed) {
                is NumberInput.Value -> onChange(JsonPrimitive(parsed.number))
                NumberInput.Blank -> onChange(null)
                NumberInput.Invalid -> Unit // 저장은 막고, 마지막으로 읽은 값은 그대로 둔다
            }
        },
        label = { Text(column.name) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        isError = invalid,
        supportingText = if (invalid) ({ Text(stringResource(R.string.vre_number_invalid)) }) else null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
    )
}

@Composable
private fun PropLabel(name: String) {
    Text(name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** select·status: 하나만 고른다. 고른 칩을 다시 누르면 select는 비운다(status는 비울 수 없다, 노션 규칙). */
@Composable
private fun SingleChoiceProp(column: ViewColumn, value: JsonElement?, onChange: (JsonElement?) -> Unit) {
    val current = (value as? JsonPrimitive)?.contentOrNull
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        PropLabel(column.name)
        ChipFlow {
            column.options.forEach { option ->
                OptionChip(option.name, option.color, selected = option.id == current) {
                    onChange(if (option.id == current && column.type == "select") null else JsonPrimitive(option.id))
                }
            }
        }
    }
}

/** 다중 선택: 기존 옵션 칩을 켜고 끄고, 새 옵션은 이름으로 더한다(노션이 만든다). 새 옵션은 `{"name":…}`로 값에 들어간다. */
@Composable
private fun MultiChoiceProp(column: ViewColumn, value: JsonElement?, onChange: (JsonElement?) -> Unit) {
    val items = (value as? JsonArray).orEmpty()
    val ids = items.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    val names = items.mapNotNull { ((it as? JsonObject)?.get("name") as? JsonPrimitive)?.contentOrNull }
    fun emit(newIds: List<String>, newNames: List<String>) =
        onChange(JsonArray(newIds.map { JsonPrimitive(it) } + newNames.map { JsonObject(mapOf("name" to JsonPrimitive(it))) })) // 비워도 빈 목록이다(다중 선택은 null을 받지 않는다)
    var draft by rememberSaveable(column.id + NEW_SUFFIX) { mutableStateOf("") }
    val hasComma = ',' in draft // 노션은 다중 선택 옵션 이름에 쉼표를 허용하지 않는다
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        PropLabel(column.name)
        ChipFlow {
            column.options.forEach { option ->
                OptionChip(option.name, option.color, selected = option.id in ids) { emit(if (option.id in ids) ids - option.id else ids + option.id, names) }
            }
            names.forEach { name -> OptionChip(name, null, selected = true) { emit(ids, names - name) } }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                label = { Text(stringResource(R.string.vre_new_option)) },
                modifier = Modifier.weight(1f),
                singleLine = true,
                isError = hasComma,
                supportingText = if (hasComma) ({ Text(stringResource(R.string.vre_option_comma)) }) else null,
            )
            TextButton(
                onClick = {
                    val name = draft.trim()
                    val existing = column.options.firstOrNull { it.name == name }
                    when {
                        existing != null -> if (existing.id !in ids) emit(ids + existing.id, names)
                        name !in names -> emit(ids, names + name)
                    }
                    draft = ""
                },
                enabled = draft.isNotBlank() && !hasComma,
            ) { Text(stringResource(R.string.qs_add)) }
        }
    }
}

private const val NEW_SUFFIX = ":new"

/**
 * 날짜: 시작 날짜 칩, 끝 날짜 추가·지우기, 시간 켜기·끄기와 시작·끝 시각(종일 스위치 + 시간 칩, Phase 12c), 날짜 지우기.
 * 날짜만 바꿀 때 시각·오프셋·시간대는 그대로 남는다([TypedDate]).
 */
@Composable
private fun DateProp(column: ViewColumn, value: JsonElement?, onChange: (JsonElement?) -> Unit) {
    val draft = TypedDate.parse(value)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        PropLabel(column.name)
        ChipFlow {
            if (draft == null) {
                DateChip(LocalDate.now(APP_ZONE), label = stringResource(R.string.vre_date_pick)) { onChange(TypedDate(it.toString()).toJson()) }
            } else {
                DateChip(draft.startDay()) { onChange(draft.withStart(it).toJson()) }
                val end = draft.endDay()
                if (end != null) {
                    DateChip(end) { onChange(draft.withEnd(it).toJson()) }
                    TextButton(onClick = { onChange(draft.withoutEnd().toJson()) }) { Text(stringResource(R.string.vre_date_end_clear)) }
                } else {
                    DateChip(draft.startDay(), label = stringResource(R.string.vre_date_end_add)) { onChange(draft.withEnd(it).toJson()) }
                }
                TextButton(onClick = { onChange(null) }) { Text(stringResource(R.string.vre_date_clear)) }
            }
        }
        if (draft != null) {
            // 시간 일정이 하나짜리(끝 없음)면 끝 시각 칩은 시작과 같은 시각으로 보인다. 고르면 그때부터 시작~끝 범위가 된다.
            val time = draft.startTime()?.let { TimeRange(it, draft.endTime() ?: it) }
            TimeChip(time) { next -> onChange((if (next == null) draft.asAllDay() else draft.withTimes(next.start, next.end)).toJson()) }
        }
    }
}

/** ED-02 읽기 전용 속성: 잠금 아이콘 + 값 + 이유. 한 덩어리로 읽힌다. */
@Composable
internal fun LockedProp(prop: RowProp.Locked) {
    val reason = stringResource(if (prop.reason == LockReason.COMPUTED) R.string.vre_locked_computed else R.string.vre_locked_elsewhere)
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Lock, contentDescription = stringResource(R.string.vre_locked_icon), modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Column {
            PropLabel(prop.name)
            Text(prop.shown.ifBlank { stringResource(R.string.vre_empty_value) }, style = MaterialTheme.typography.bodyLarge)
            Text(reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
