package com.ysdoing.notioncal.widget

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.ysdoing.notioncal.core.data.db.ViewSnapshotEntity
import com.ysdoing.notioncal.core.data.view.AggFn
import com.ysdoing.notioncal.core.data.view.AggPeriod
import com.ysdoing.notioncal.core.data.view.AggTile
import com.ysdoing.notioncal.core.data.view.ViewColumn
import com.ysdoing.notioncal.core.data.view.ViewJson
import com.ysdoing.notioncal.core.data.view.WidgetLayout
import com.ysdoing.notioncal.core.designsystem.RadioRow

private const val TILE_LABEL_MAX = 20

/** 함수에 쓸 수 있는 속성(개수는 속성이 필요 없다). */
internal fun propertyChoices(fn: AggFn, columns: List<ViewColumn>): List<ViewColumn> = if (fn.needsProperty) columns.filter { fn.accepts(it) } else emptyList()

/** 기간·연속 달성일의 기준이 될 수 있는 날짜 속성. */
internal fun dateChoices(columns: List<ViewColumn>): List<ViewColumn> = columns.filter { it.type == "date" }

/** 타일 추가 양식에 입력 중인 값. [goalText]는 글자 그대로(쉼표 허용), 잘못된 목표는 '목표 없음'으로 본다. */
internal data class TileDraft(
    val viewId: String = "",
    val fn: AggFn = AggFn.COUNT,
    val propId: String = "",
    val dateProp: String = "",
    val period: AggPeriod = AggPeriod.ALL,
    val goalText: String = "",
    val label: String = "",
) {
    /** 목표를 적었는데 읽을 수 없거나 0 이하다(비워 두면 목표 없음이라 문제 없다). */
    val goalInvalid: Boolean get() = goalText.isNotBlank() && parseGoal(goalText)?.takeIf { it > 0 } == null

    /** 지금 값으로 만든 타일. 필요한 것(보기·속성·연속 달성일의 날짜 속성)이 없거나 목표가 잘못이면 null이라 '추가'를 막는다. 번호는 담을 때 붙는다. */
    fun toTile(): AggTile? = if (goalInvalid) null else AggTile("t", viewId, fn, propId, dateProp, period, parseGoal(goalText), label).normalized()
}

private val THOUSANDS = Regex("""\d{1,3}(,\d{3})+(\.\d+)?""")

/** 목표 글자 → 숫자. 끝의 %는 떼고, '1,500'은 천 단위 쉼표로, 그 밖의 쉼표('1,5')는 소수점으로 읽는다. 읽을 수 없으면 null. */
internal fun parseGoal(text: String): Double? {
    val t = text.trim().removeSuffix("%").trim()
    if (t.isEmpty()) return null
    return (if (THOUSANDS.matches(t)) t.replace(",", "") else t.replace(',', '.')).toDoubleOrNull()?.takeIf { it.isFinite() }
}

/** 함수를 바꾸면 고른 속성이 맞지 않을 때만 맞는 첫 속성으로 바꾸고, 날짜 속성은 비어 있을 때만 첫 날짜 속성으로 채운다. */
internal fun TileDraft.withFn(fn: AggFn, columns: List<ViewColumn>): TileDraft {
    val props = propertyChoices(fn, columns)
    // 고른 속성이 새 함수에도 맞으면 그대로 둔다(합계에서 평균으로 바꿔도 같은 열).
    return copy(fn = fn, propId = props.firstOrNull { it.id == propId }?.id ?: props.firstOrNull()?.id.orEmpty(), dateProp = dateProp.ifEmpty { dateChoices(columns).firstOrNull()?.id.orEmpty() })
}

/** 보기를 고르면 그 보기의 열로 다시 시작한다(개수부터). */
internal fun TileDraft.withView(viewId: String, columns: List<ViewColumn>): TileDraft = TileDraft(viewId = viewId).withFn(AggFn.COUNT, columns)

private val PERIOD_CHOICES = listOf(
    AggPeriod.ALL to R.string.layout_tile_period_all,
    AggPeriod.TODAY to R.string.layout_tile_period_today,
    AggPeriod.THIS_WEEK to R.string.layout_tile_period_week,
    AggPeriod.THIS_MONTH to R.string.layout_tile_period_month,
)

/**
 * Phase 11e AG-01·02 집계 타일 구역(섹션 배치에서만): 만든 타일 목록과 삭제, 타일 추가 양식. 수정은 지우고 다시 추가한다.
 * 속성 목록은 등록한 보기의 열 정보에서 오고, 규칙(함수에 맞는 속성·상한)은 [AggFn.accepts]·[WidgetLayout.addTile]이 맡는다.
 */
@Composable
internal fun TileSettings(views: List<ViewSnapshotEntity>, layout: WidgetLayout, set: (WidgetLayout) -> Unit) {
    val context = LocalContext.current
    val columnsOf = remember(views) { views.associate { it.viewId to ViewJson.columns(it.columnInfo) } }
    var draft by remember { mutableStateOf<TileDraft?>(null) }
    ConfigSection(stringResource(R.string.layout_tile_config_title))
    ConfigNote(stringResource(R.string.layout_tile_config_note, WidgetLayout.TILE_LIMIT))
    layout.tiles.forEach { tile ->
        val view = views.firstOrNull { it.viewId == tile.viewId }
        val name = tileLabel(context, tile, view?.name, columnsOf[tile.viewId].orEmpty())
        val remove = stringResource(R.string.layout_tile_delete_description, name)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.layout_tile_summary, name, view?.name?.ifBlank { stringResource(R.string.widget_config_view_untitled) } ?: stringResource(R.string.layout_config_gone)), Modifier.weight(1f))
            TextButton(onClick = { set(layout.removeTile(tile.id)) }, modifier = Modifier.semantics { contentDescription = remove }) {
                Text(stringResource(R.string.layout_config_remove), color = MaterialTheme.colorScheme.error)
            }
        }
    }
    val editing = draft
    if (editing != null) {
        TileForm(editing, views, columnsOf, onChange = { draft = it }) { tile ->
            if (tile != null) layout.addTile(tile)?.let(set)
            draft = null
        }
        return
    }
    val canAdd = layout.addTile(AggTile("t", "v")) != null
    TextButton(onClick = { views.firstOrNull()?.let { draft = TileDraft().withView(it.viewId, columnsOf[it.viewId].orEmpty()) } }, enabled = views.isNotEmpty() && canAdd) {
        Text(stringResource(R.string.layout_tile_add))
    }
    if (!canAdd) ConfigNote(stringResource(R.string.layout_tile_limit, WidgetLayout.TILE_LIMIT))
}

/** 추가 양식: 보기 → 함수 → 속성 → 날짜 속성·기간 → 목표값 → 이름. [onDone]은 만든 타일(취소면 null)을 받는다. */
@Composable
private fun TileForm(draft: TileDraft, views: List<ViewSnapshotEntity>, columnsOf: Map<String, List<ViewColumn>>, onChange: (TileDraft) -> Unit, onDone: (AggTile?) -> Unit) {
    val columns = columnsOf[draft.viewId].orEmpty()
    val dates = dateChoices(columns)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ConfigSection(stringResource(R.string.layout_tile_form_view))
        views.forEach { view ->
            RadioRow(view.name.ifBlank { stringResource(R.string.widget_config_view_untitled) }, draft.viewId == view.viewId) { onChange(draft.withView(view.viewId, columnsOf[view.viewId].orEmpty())) }
        }
        ConfigSection(stringResource(R.string.layout_tile_form_fn))
        ChoiceChips(AggFn.entries.map { it to fnName(it) }, draft.fn) { onChange(draft.withFn(it, columns)) }
        if (draft.fn.needsProperty) {
            ConfigSection(stringResource(R.string.layout_tile_form_prop))
            val props = propertyChoices(draft.fn, columns)
            if (props.isEmpty()) ConfigNote(stringResource(R.string.layout_tile_no_prop))
            props.forEach { RadioRow(it.name, draft.propId == it.id) { onChange(draft.copy(propId = it.id)) } }
        }
        if (dates.size > 1) {
            ConfigSection(stringResource(R.string.layout_tile_form_date))
            dates.forEach { RadioRow(it.name, draft.dateProp == it.id) { onChange(draft.copy(dateProp = it.id)) } }
        }
        if (draft.fn == AggFn.STREAK) {
            ConfigNote(stringResource(if (dates.isEmpty()) R.string.layout_tile_no_date else R.string.layout_tile_streak_note))
        } else if (dates.isNotEmpty()) {
            ConfigSection(stringResource(R.string.layout_tile_form_period))
            ChoiceChips(PERIOD_CHOICES, draft.period) { onChange(draft.copy(period = it)) }
        }
        OutlinedTextField(
            value = draft.goalText,
            onValueChange = { onChange(draft.copy(goalText = it.take(12))) },
            label = { Text(stringResource(R.string.layout_tile_form_goal)) },
            supportingText = { Text(stringResource(if (draft.goalInvalid) R.string.layout_tile_form_goal_invalid else R.string.layout_tile_form_goal_hint)) },
            isError = draft.goalInvalid,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = draft.label,
            onValueChange = { onChange(draft.copy(label = it.takeCodePoints(TILE_LABEL_MAX))) },
            label = { Text(stringResource(R.string.layout_tile_form_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onDone(null) }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.layout_tile_cancel)) }
            Button(onClick = { onDone(draft.toTile()) }, enabled = draft.toTile() != null, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.layout_tile_confirm)) }
        }
    }
}
