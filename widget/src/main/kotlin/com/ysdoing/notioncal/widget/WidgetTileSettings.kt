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
import com.ysdoing.notioncal.core.data.view.TileKind
import com.ysdoing.notioncal.core.data.view.ViewColumn
import com.ysdoing.notioncal.core.data.view.ViewJson
import com.ysdoing.notioncal.core.data.view.WidgetLayout
import com.ysdoing.notioncal.core.designsystem.RadioRow

private const val TILE_LABEL_MAX = 20

/** 함수에 쓸 수 있는 속성(개수는 속성이 필요 없다). */
internal fun propertyChoices(fn: AggFn, columns: List<ViewColumn>): List<ViewColumn> = if (fn.needsProperty) columns.filter { fn.accepts(it) } else emptyList()

/** 도넛 타일이 묶을 수 있는 분류 속성(선택·상태·다중 선택). */
internal fun groupChoices(columns: List<ViewColumn>): List<ViewColumn> = columns.filter { it.type in GROUP_TYPES }

private val GROUP_TYPES = setOf("select", "status", "multi_select")

/** 기간·연속 달성일의 기준이 될 수 있는 날짜 속성. */
internal fun dateChoices(columns: List<ViewColumn>): List<ViewColumn> = columns.filter { it.type == "date" }

/** 타일 양식에 입력 중인 값. [goalText]는 글자 그대로(쉼표 허용), 잘못된 목표는 '목표 없음'으로 본다. [editingId]가 있으면 그 타일을 고치는 중이다(없으면 새 타일). */
internal data class TileDraft(
    val viewId: String = "",
    val fn: AggFn = AggFn.COUNT,
    val propId: String = "",
    val dateProp: String = "",
    val period: AggPeriod = AggPeriod.ALL,
    val goalText: String = "",
    val label: String = "",
    val color: String = "",
    val editingId: String = "",
    val kind: TileKind = TileKind.AGG,
    val groupProp: String = "",
) {
    /** 목표를 적었는데 읽을 수 없거나 0 이하다(비워 두면 목표 없음이라 문제 없다). */
    val goalInvalid: Boolean get() = goalText.isNotBlank() && parseGoal(goalText)?.takeIf { it > 0 } == null

    /** 지금 값으로 만든 타일. 필요한 것(보기·속성·연속 달성일의 날짜 속성)이 없거나 목표가 잘못이면 null이라 '추가'를 막는다. 번호는 담을 때 붙는다. */
    fun toTile(): AggTile? = if (goalInvalid) null else AggTile(editingId.ifEmpty { "t" }, viewId, fn, propId, dateProp, period, parseGoal(goalText), label, color, kind, groupProp).normalized()

    companion object {
        /** 만들어 둔 [tile]을 고치는 양식으로. 목표는 글자로 되돌린다(정수면 소수점 없이). */
        fun from(tile: AggTile): TileDraft = TileDraft(
            tile.viewId, tile.fn, tile.propId, tile.dateProp, tile.period,
            goalText = tile.goal?.let { if (it % 1.0 == 0.0) it.toLong().toString() else it.toString() }.orEmpty(), label = tile.label, color = tile.color, editingId = tile.id,
            kind = tile.kind, groupProp = tile.groupProp,
        )
    }
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

/**
 * 종류를 바꾼다. 도넛은 함수가 개수·합계뿐이고 목표·색이 없으며 분류 속성이 있어야 한다(고른 것이 맞으면 두고, 아니면 첫 분류 속성). 값 타일로 돌아가면 분류 속성을 비운다.
 */
internal fun TileDraft.withKind(kind: TileKind, columns: List<ViewColumn>): TileDraft {
    if (kind == TileKind.AGG) return copy(kind = kind, groupProp = "")
    val groups = groupChoices(columns)
    val group = groups.firstOrNull { it.id == groupProp }?.id ?: groups.firstOrNull()?.id.orEmpty()
    val donutFn = if (fn == AggFn.SUM) AggFn.SUM else AggFn.COUNT
    return copy(kind = kind, goalText = "", color = "", groupProp = group).withFn(donutFn, columns)
}

/** 보기를 고르면 그 보기의 열로 다시 시작한다(개수부터). 고른 종류는 그대로 둔다. */
internal fun TileDraft.withView(viewId: String, columns: List<ViewColumn>): TileDraft =
    TileDraft(viewId = viewId).withFn(AggFn.COUNT, columns).let { if (kind == TileKind.DONUT) it.withKind(kind, columns) else it }

private val COLOR_CHOICES = listOf(
    "" to R.string.layout_tile_color_default, "gray" to R.string.layout_tile_color_gray, "brown" to R.string.layout_tile_color_brown, "orange" to R.string.layout_tile_color_orange,
    "yellow" to R.string.layout_tile_color_yellow, "green" to R.string.layout_tile_color_green, "blue" to R.string.layout_tile_color_blue, "purple" to R.string.layout_tile_color_purple,
    "pink" to R.string.layout_tile_color_pink, "red" to R.string.layout_tile_color_red,
)

private val KIND_CHOICES = listOf(TileKind.AGG to R.string.layout_tile_kind_value, TileKind.DONUT to R.string.layout_tile_kind_donut)

private val PERIOD_CHOICES = listOf(
    AggPeriod.ALL to R.string.layout_tile_period_all,
    AggPeriod.TODAY to R.string.layout_tile_period_today,
    AggPeriod.THIS_WEEK to R.string.layout_tile_period_week,
    AggPeriod.THIS_MONTH to R.string.layout_tile_period_month,
)

/**
 * Phase 11e AG-01·02 집계 타일 구역(섹션 배치에서만): 만든 타일 목록과 수정·삭제, 타일 추가 양식(수정도 같은 양식, Phase 12c).
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
        val edit = stringResource(R.string.layout_tile_edit_description, name)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.layout_tile_summary, name, view?.name?.ifBlank { stringResource(R.string.widget_config_view_untitled) } ?: stringResource(R.string.layout_config_gone)), Modifier.weight(1f))
            TextButton(onClick = { draft = TileDraft.from(tile) }, modifier = Modifier.semantics { contentDescription = edit }) { Text(stringResource(R.string.layout_tile_edit)) }
            TextButton(onClick = { set(layout.removeTile(tile.id)) }, modifier = Modifier.semantics { contentDescription = remove }) {
                Text(stringResource(R.string.layout_config_remove), color = MaterialTheme.colorScheme.error)
            }
        }
    }
    val editing = draft
    if (editing != null) {
        val donutTaken = layout.tiles.any { it.kind == TileKind.DONUT && it.id != editing.editingId }
        TileForm(editing, views, columnsOf, donutTaken, onChange = { draft = it }) { tile ->
            if (tile != null) (if (editing.editingId.isEmpty()) layout.addTile(tile) else layout.replaceTile(editing.editingId, tile)).let { it?.let(set) }
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

/**
 * 추가·수정 양식: 보기 → 종류 → (도넛이면 분류 속성) → 함수 → 속성 → 날짜 속성·기간 → (값이면 목표값) → 이름 → (값이면 색).
 * 도넛 타일은 위젯에 하나까지라 [donutTaken]이면 도넛 종류를 고르지 못한다. [onDone]은 만든 타일(취소면 null)을 받는다.
 */
@Composable
private fun TileForm(draft: TileDraft, views: List<ViewSnapshotEntity>, columnsOf: Map<String, List<ViewColumn>>, donutTaken: Boolean, onChange: (TileDraft) -> Unit, onDone: (AggTile?) -> Unit) {
    val columns = columnsOf[draft.viewId].orEmpty()
    val dates = dateChoices(columns)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ConfigSection(stringResource(R.string.layout_tile_form_view))
        views.forEach { view ->
            RadioRow(view.name.ifBlank { stringResource(R.string.widget_config_view_untitled) }, draft.viewId == view.viewId) { onChange(draft.withView(view.viewId, columnsOf[view.viewId].orEmpty())) }
        }
        val donut = draft.kind == TileKind.DONUT
        ConfigSection(stringResource(R.string.layout_tile_form_kind))
        ChoiceChips(KIND_CHOICES.filter { !donutTaken || it.first == TileKind.AGG || donut }, draft.kind) { onChange(draft.withKind(it, columns)) }
        if (donutTaken && !donut) ConfigNote(stringResource(R.string.layout_tile_donut_limit))
        if (donut) {
            ConfigSection(stringResource(R.string.layout_tile_form_group))
            val groups = groupChoices(columns)
            if (groups.isEmpty()) ConfigNote(stringResource(R.string.layout_tile_no_group))
            groups.forEach { RadioRow(it.name, draft.groupProp == it.id) { onChange(draft.copy(groupProp = it.id)) } }
        }
        ConfigSection(stringResource(R.string.layout_tile_form_fn))
        ChoiceChips(AggFn.entries.filter { !donut || it == AggFn.COUNT || it == AggFn.SUM }.map { it to fnName(it) }, draft.fn) { onChange(draft.withFn(it, columns)) }
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
        if (!donut) {
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
        }
        OutlinedTextField(
            value = draft.label,
            onValueChange = { onChange(draft.copy(label = it.takeCodePoints(TILE_LABEL_MAX))) },
            label = { Text(stringResource(R.string.layout_tile_form_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (!donut) {
            ConfigSection(stringResource(R.string.layout_tile_form_color))
            ChoiceChips(COLOR_CHOICES, draft.color) { onChange(draft.copy(color = it)) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onDone(null) }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.layout_tile_cancel)) }
            Button(onClick = { onDone(draft.toTile()) }, enabled = draft.toTile() != null, modifier = Modifier.weight(1f)) {
                Text(stringResource(if (draft.editingId.isEmpty()) R.string.layout_tile_confirm else R.string.layout_tile_save))
            }
        }
    }
}
