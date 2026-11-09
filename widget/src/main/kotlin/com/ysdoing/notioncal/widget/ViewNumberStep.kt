package com.ysdoing.notioncal.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.action.Action
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.height
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.semantics.testTag
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import com.ysdoing.notioncal.core.data.view.ViewColumn
import com.ysdoing.notioncal.core.data.view.ViewRow
import com.ysdoing.notioncal.core.data.view.ViewWriter

// Phase 11c IX-03: 숫자 열의 − + 단계 버튼. 한 번에 바뀌는 값은 위젯 설정(WidgetConfig.numberStep)이고, 연타는 4초 지연 안에서 한 번의 전송으로 합쳐진다.

private val STEP_BUTTON_WIDTH = 28.dp

/** 숫자 칸: `− 값 +`. 값을 누르면 행(편집 시트), −·+는 단계값만큼 바꾼다(쓰기 큐에만 쌓고 노션은 부르지 않는다). */
@Composable
internal fun NumberStepCell(viewId: String, row: ViewRow, column: ViewColumn, raw: String, look: ViewLook, modifier: GlanceModifier, tapHeight: Dp) {
    val context = LocalContext.current
    val title = row.cells.firstOrNull().orEmpty()
    val step = look.numberStep
    Row(modifier.height(tapHeight), verticalAlignment = Alignment.CenterVertically) {
        StepButton("−", context.getString(R.string.view_widget_number_minus, title, column.name, raw.ifEmpty { "0" }, formatStep(step)), "numberMinus", stepAction(viewId, row, column, -step), look)
        Text(raw.ifEmpty { "–" }, modifier = GlanceModifier.defaultWeight(), style = look.cell.copy(textAlign = TextAlign.Center), maxLines = 1)
        StepButton("+", context.getString(R.string.view_widget_number_plus, title, column.name, raw.ifEmpty { "0" }, formatStep(step)), "numberPlus", stepAction(viewId, row, column, step), look)
    }
}

@Composable
private fun StepButton(glyph: String, description: String, tag: String, action: Action, look: ViewLook) {
    Box(
        GlanceModifier.width(STEP_BUTTON_WIDTH).fillMaxHeight().clickable(action).semantics {
            contentDescription = description
            testTag = tag
        },
        contentAlignment = Alignment.Center,
    ) { Text(glyph, style = TextStyle(color = look.palette.accent, fontSize = (look.m.titleSp * look.m.spFactor * 1.3f).sp, fontWeight = FontWeight.Bold)) }
}

private fun stepAction(viewId: String, row: ViewRow, column: ViewColumn, delta: Double): Action = actionRunCallback<NumberStepAction>(
    actionParametersOf(ViewCellAction.ViewKey to viewId, ViewCellAction.PageKey to row.pageId, ViewCellAction.PropKey to column.id, NumberStepAction.DeltaKey to delta),
)

/** 단계값의 낭독 글자: 정수면 소수점 없이('5'), 아니면 그대로('0.5'). */
internal fun formatStep(step: Double): String = if (step % 1.0 == 0.0) step.toLong().toString() else step.toString()

/** − + 누르기: 그 숫자 속성에 [DeltaKey]만큼 더해 4초 지연으로 쌓고(연타는 한 번의 전송, 되돌리기 가능) 위젯을 다시 그린다. */
class NumberStepAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val viewId = parameters[ViewCellAction.ViewKey] ?: return
        val pageId = parameters[ViewCellAction.PageKey] ?: return
        val propId = parameters[ViewCellAction.PropKey] ?: return
        val delta = parameters[DeltaKey] ?: return
        WidgetDependencies.from(context).viewWriter().add(viewId, pageId, propId, delta, ViewWriter.UNDO_WINDOW_MS)
        WidgetKinds.update(context, glanceId)
    }

    companion object {
        val DeltaKey = ActionParameters.Key<Double>("numberDelta")
    }
}
