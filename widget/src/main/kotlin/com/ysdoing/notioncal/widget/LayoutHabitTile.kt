package com.ysdoing.notioncal.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.semantics.testTag
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import com.ysdoing.notioncal.core.data.view.HabitCell
import com.ysdoing.notioncal.core.data.view.HabitGrid
import com.ysdoing.notioncal.core.data.view.HabitGridResult
import com.ysdoing.notioncal.core.data.view.HabitLine
import com.ysdoing.notioncal.core.data.view.ViewWriter
import java.time.LocalDate
import java.time.format.TextStyle as JavaTextStyle
import java.util.Locale

// Phase 12e RC-03: 해빗 7일 격자 타일. 습관(체크박스)마다 오늘까지 최근 7일 칸이고, 칸을 누르면 그날 행의 체크를 토글한다.
// 그날 페이지가 없으면 첫 체크가 그날 페이지('M월 d일')를 만든다([ViewWriter.checkHabit]). 오늘 칸이 '오늘 줄 체크'다.

private val HABIT_ROW_MIN = 28.dp
private val HABIT_NAME_MAX_WIDTH = 96.dp
private val HABIT_COUNT_WIDTH = 32.dp
private val HABIT_ICON = 20.dp

/** 그릴 해빗 격자 [cell]과 습관 줄 수·높이. 값이 없으면(보기를 못 읽음) 안내 한 줄만 있는 낮은 카드(줄 0)다. */
internal data class HabitPlan(val cell: TileCell, val lines: Int, val height: Dp)

private fun habitRowHeight(m: ViewMetrics): Dp = maxOf(m.tableRow, HABIT_ROW_MIN)

/** 카드 높이: 위아래 안쪽 여백 + 요일 머리 + 습관 줄들. */
internal fun habitHeight(m: ViewMetrics, lines: Int): Dp = AGG_TILE_PADDING * 2 + m.tableHeader + habitRowHeight(m) * lines

/** 2×2이거나 습관 한 줄도 안 들어가면 null(격자는 건너뛴다). 들어가는 만큼의 습관 줄만 그린다. */
internal fun planHabits(cell: TileCell, m: ViewMetrics, size: DpSize, room: Dp): HabitPlan? {
    if (sizeStep(size) == SizeStep.COMPACT) return null
    val total = cell.habits?.lines?.size ?: return HabitPlan(cell, 0, m.tile).takeIf { room >= m.tile }
    val fit = ((room - AGG_TILE_PADDING * 2 - m.tableHeader) / habitRowHeight(m)).toInt()
    val lines = minOf(total, fit)
    return if (lines >= 1) HabitPlan(cell, lines, habitHeight(m, lines)) else null
}

/** 격자를 그릴 때 줄·칸이 함께 쓰는 값: 누를 보기·날짜 속성, 오늘, 이름·칸 폭, 글자 모양. */
private class HabitContext(val viewId: String, val dateProp: String, val today: LocalDate, val nameWidth: Dp, val cellWidth: Dp, val look: LayoutLook)

/** 격자를 못 그릴 때의 안내: 보기를 못 읽으면(없음·못 그림) 보기 상태 글자, 아니면 습관 속성이 없어진 것이다. */
private fun habitNote(context: Context, cell: TileCell): String {
    val board = cell.board ?: return context.getString(R.string.layout_tile_missing)
    val status = viewStatusText(context, board, cell.nowMillis)?.takeIf { board.notFound || board.unsupported }
    return status ?: context.getString(R.string.layout_habit_unavailable)
}

@Composable
internal fun HabitGridTile(plan: HabitPlan, look: LayoutLook, width: Dp) {
    val context = LocalContext.current
    val m = look.metrics
    val palette = look.palette
    val grid = plan.cell.habits
    val label = tileLabel(context, plan.cell.tile, plan.cell.board)
    val base = GlanceModifier.fillMaxWidth().height(plan.height).tile(palette).padding(AGG_TILE_PADDING)
    if (grid == null) {
        val note = habitNote(context, plan.cell)
        Column(base.semantics { testTag = "layoutHabitNote"; contentDescription = "$label, $note" }) {
            Text(label, style = TextStyle(color = palette.subtle, fontSize = (m.subSp * m.spFactor).sp), maxLines = 1)
            Text(note, style = TextStyle(color = palette.subtle, fontSize = (m.subSp * m.spFactor).sp), maxLines = 1)
        }
        return
    }
    val inner = width - AGG_TILE_PADDING * 2
    val nameWidth = minOf(HABIT_NAME_MAX_WIDTH, inner * NAME_SHARE)
    val cellWidth = (inner - nameWidth - HABIT_COUNT_WIDTH) / HabitGrid.DAYS
    val ctx = HabitContext(plan.cell.board?.viewId.orEmpty(), plan.cell.tile.dateProp, grid.days.last(), nameWidth, cellWidth, look)
    Column(base.semantics { testTag = "layoutHabitGrid" }) {
        HabitDayHeader(grid, ctx)
        grid.lines.take(plan.lines).forEach { HabitRow(it, ctx) }
    }
}

private const val NAME_SHARE = 0.3f

/**
 * 요일 글자 7칸(오늘은 굵게·강조색). 이름·개수 칸은 비워 둔다(자식 9개, Glance 한도 10 안). 행을 일부만 받았으면(100행 상한) 이름 칸 자리에 그렇다고 쓴다:
 * 못 받은 날은 비어 보이고 눌러서 같은 날 페이지가 하나 더 생길 수 있어서다.
 */
@Composable
private fun HabitDayHeader(grid: HabitGridResult, ctx: HabitContext) {
    val m = ctx.look.metrics
    val sp = (m.subSp * m.spFactor).sp
    Row(GlanceModifier.fillMaxWidth().height(m.tableHeader), verticalAlignment = Alignment.CenterVertically) {
        if (grid.partial) {
            Text(LocalContext.current.getString(R.string.layout_tile_partial), modifier = GlanceModifier.width(ctx.nameWidth), style = TextStyle(color = ctx.look.palette.subtle, fontSize = sp), maxLines = 1)
        } else {
            Spacer(GlanceModifier.width(ctx.nameWidth))
        }
        grid.days.forEach { day ->
            val isToday = day == ctx.today
            Text(
                day.dayOfWeek.getDisplayName(JavaTextStyle.NARROW, Locale.KOREAN),
                modifier = GlanceModifier.width(ctx.cellWidth),
                style = TextStyle(
                    color = if (isToday) ctx.look.palette.accent else ctx.look.palette.subtle, fontSize = sp,
                    fontWeight = if (isToday) FontWeight.Bold else null, textAlign = TextAlign.Center,
                ),
                maxLines = 1,
            )
        }
        Spacer(GlanceModifier.width(HABIT_COUNT_WIDTH))
    }
}

/** 습관 한 줄: 이름 · 7일 칸 · 'n/7'. 칸은 모양(채운 원 / 빈 원)과 낭독('함'/'안 함')으로도 구분한다(A11Y-03). */
@Composable
private fun HabitRow(line: HabitLine, ctx: HabitContext) {
    val m = ctx.look.metrics
    Row(GlanceModifier.fillMaxWidth().height(habitRowHeight(m)).semantics { testTag = "layoutHabitRow" }, verticalAlignment = Alignment.CenterVertically) {
        Text(line.name, modifier = GlanceModifier.width(ctx.nameWidth).padding(end = 4.dp), style = TextStyle(color = ctx.look.palette.text, fontSize = (m.titleSp * m.spFactor).sp), maxLines = 1)
        line.cells.forEach { HabitDayCell(line, it, ctx) }
        Text("${line.count}/${HabitGrid.DAYS}", modifier = GlanceModifier.width(HABIT_COUNT_WIDTH), style = TextStyle(color = ctx.look.palette.subtle, fontSize = (m.subSp * m.spFactor).sp, textAlign = TextAlign.End), maxLines = 1)
    }
}

@Composable
private fun HabitDayCell(line: HabitLine, cell: HabitCell, ctx: HabitContext) {
    val context = LocalContext.current
    val day = cell.date
    val isToday = day == ctx.today
    val dayText = if (isToday) context.getString(R.string.widget_today) else context.getString(R.string.habit_widget_date, day.monthValue, day.dayOfMonth, day.dayOfWeek.getDisplayName(JavaTextStyle.SHORT, Locale.KOREAN))
    val description = listOf(line.name, dayText, context.getString(if (cell.done) R.string.habit_widget_done else R.string.habit_widget_not_done)).joinToString(", ")
    val action = actionRunCallback<HabitCheckAction>(
        actionParametersOf(
            ViewCellAction.ViewKey to ctx.viewId, ViewCellAction.PropKey to line.propId,
            HabitCheckAction.DayKey to day.toEpochDay(), HabitCheckAction.DateKey to ctx.dateProp,
        ),
    )
    Box(
        GlanceModifier.width(ctx.cellWidth).height(habitRowHeight(ctx.look.metrics)).clickable(action).semantics { contentDescription = description; testTag = if (isToday) "layoutHabitToday" else "layoutHabitCell" },
        contentAlignment = Alignment.Center,
    ) {
        when {
            cell.done -> WidgetIcon(R.drawable.ic_widget_check_circle, ctx.look.palette.green, HABIT_ICON)
            isToday -> WidgetIcon(R.drawable.ic_widget_circle, ctx.look.palette.accent, HABIT_ICON)
            else -> WidgetIcon(R.drawable.ic_widget_circle, ctx.look.palette.subtle, HABIT_ICON)
        }
    }
}

/**
 * 해빗 격자 칸 누르기(Phase 12e RC-03): 그날 행이 있으면 체크를 뒤집고, 없으면 첫 체크로 그날 페이지를 만든다(같은 날 큐에 있으면 거기에 합친다).
 * 날짜는 타일이 고른 날짜 속성([DateKey])으로 찾고 쓴다. 위젯 즉시 동작이라 4초 뒤 보내고 그 안에 '되돌리기'를 누를 수 있다.
 * 로컬만 고치고 노션은 부르지 않는다. 누른 위젯은 바로 다시 그린다.
 */
class HabitCheckAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val viewId = parameters[ViewCellAction.ViewKey] ?: return
        val propId = parameters[ViewCellAction.PropKey] ?: return
        val day = parameters[DayKey] ?: return
        WidgetDependencies.from(context).viewWriter().checkHabit(viewId, propId, LocalDate.ofEpochDay(day), parameters[DateKey].orEmpty(), ViewWriter.UNDO_WINDOW_MS)
        WidgetKinds.update(context, glanceId)
    }

    companion object {
        val DayKey = ActionParameters.Key<Long>("habitDay")
        val DateKey = ActionParameters.Key<String>("habitDateProp")
    }
}
