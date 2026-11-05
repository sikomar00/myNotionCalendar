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
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Phase 11g AD-02 새 항목 시트가 입력 양식·필터 채움·보기 열로 정한 것.
 * [fields]는 편집기로 보일 양식 속성(필터에서 채운 속성은 필터 구역이 따로 보이니 뺀다), [start]는 처음 채울 값(양식 기본값 위에 필터 채움을 덮는다),
 * [readable]은 한 줄 입력이 읽을 속성이다. 시작 값이 있는 속성은 사용자가 정한 값이라 해석하지 않고(글자는 제목에 남는다), 양식이 있으면 양식에 없는 속성도 읽지 않는다.
 */
internal data class SheetPlan(
    val fields: List<ViewColumn>,
    val start: Map<String, JsonElement>,
    val readable: List<ViewColumn>,
    val showDate: Boolean,
    /** Phase 11h 레시피 플래그: 날짜를 오늘로 시작·제목이 비면 오늘 날짜·하루 한 줄([InputForm]의 같은 이름 필드). */
    val dateToday: Boolean,
    val titleDate: Boolean,
    val oncePerDay: Boolean,
)

internal fun sheetPlan(columns: List<ViewColumn>, hasDate: Boolean, input: InputForm?, prefill: ViewAddPrefill): SheetPlan {
    val start = input?.defaults(columns).orEmpty() + prefill.values
    val fields = input?.visibleColumns(columns).orEmpty().filter { it.id !in prefill.values }
    val showDate = hasDate && input?.showDate != false
    // 속성을 하나도 안 고르고 날짜 칩도 숨기지 않은 양식(레시피가 날짜만 오늘로 시작하려는 것)은 한 줄 입력의 해석을 막지 않는다.
    val restricts = input != null && (input.fields.isNotEmpty() || !input.showDate)
    val readable = if (!restricts) columns.filter { it.id !in start && (showDate || it.type != "date") } else fields.filter { it.id !in start } + columns.filter { it.type == "date" && showDate }
    return SheetPlan(fields, start, readable, showDate, dateToday = showDate && input?.dateToday == true, titleDate = input?.titleDate == true, oncePerDay = input?.oncePerDay == true)
}

/**
 * 새 항목으로 만들어질 것과 막힘(순수 규칙, [resolveAdd]). [date]는 만들 날짜, [autoToday]는 고르지 않았지만 양식의 기본값으로 오늘이 쓰이는가,
 * [title]은 만들 제목, [titleHint]는 제목이 비었을 때 쓰일 날짜 글자(제목 날짜 양식일 때만, 칸 이름에 보인다), [dayTaken]은 하루 한 줄 양식에서 그 날짜에 이미 항목이 있는가.
 */
internal data class AddResolution(val date: LocalDate?, val autoToday: Boolean, val title: String, val titleHint: String?, val dayTaken: Boolean)

/**
 * 날짜 칩(AUTO: 아직 안 골랐다 / EMPTY: 날짜 없음을 골랐다 / 오늘·내일·직접 고른 날)과 글에서 읽은 날짜([quickDate])·양식 기본값으로 날짜를 정한다:
 * 고른 칩 > 글에서 읽은 날짜 > 양식의 오늘. 제목이 비면(`titleDate`) 만들 날짜(없으면 오늘)의 'M월 d일'로 채운다. 프리셋을 만들 때는 둘 다 쓰지 않는다.
 */
internal fun resolveAdd(
    choice: DateChoice,
    pickedIso: String?,
    quickDate: LocalDate?,
    quickTitle: String,
    plan: SheetPlan,
    today: LocalDate,
    takenDates: Set<LocalDate>,
    presetOnly: Boolean,
): AddResolution {
    val date = when (choice) {
        DateChoice.AUTO -> quickDate ?: today.takeIf { plan.dateToday }
        DateChoice.EMPTY -> null
        DateChoice.TODAY -> today
        DateChoice.TOMORROW -> today.plusDays(1)
        DateChoice.PICK -> pickedIso?.let(LocalDate::parse)
    }
    val hint = if (plan.titleDate && !presetOnly) DateTimeFormatter.ofPattern("M월 d일").format(date ?: today) else null
    return AddResolution(
        date = date,
        autoToday = choice == DateChoice.AUTO && quickDate == null && plan.dateToday,
        title = quickTitle.ifBlank { hint.orEmpty() },
        titleHint = hint,
        dayTaken = plan.oncePerDay && !presetOnly && date != null && date in takenDates,
    )
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
