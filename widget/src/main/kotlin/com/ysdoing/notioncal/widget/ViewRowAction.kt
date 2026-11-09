package com.ysdoing.notioncal.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
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
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.semantics.testTag
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.ysdoing.notioncal.core.data.view.ViewAction
import com.ysdoing.notioncal.core.data.view.ViewActions
import com.ysdoing.notioncal.core.data.view.ViewRow

// Phase 12c IX-07: 위젯 행 옆 액션 버튼. 보기의 첫 액션 하나를 행마다 칩으로 두고(2×2보다 넓을 때), 나머지는 행 편집 시트의 버튼 줄에 있다.

private val ACTION_MIN_WIDTH = 48.dp
private val ACTION_MAX_WIDTH = 96.dp
private val ACTION_PADDING = 16.dp
private val ACTION_PILL_MIN_HEIGHT = 24.dp
private val ACTION_ROW_MARGIN = 8.dp

/** 행 옆 액션 칩의 폭: 이름 글자 수만큼(최소 눌림 폭 48dp, 최대 96dp). 표 머리가 같은 폭을 비워 열이 어긋나지 않게 한다. */
internal fun rowActionWidth(action: ViewAction, look: ViewLook): Dp = (look.pillSp.dp * action.name.length + ACTION_PADDING).coerceIn(ACTION_MIN_WIDTH, ACTION_MAX_WIDTH)

/**
 * 행 오른쪽 끝의 액션 칩: 눌리면 그 행에 보기의 첫 액션을 적용한다. 이름은 글자 수만큼 폭을 잡고(최소 눌림 폭 48dp), 액션이 없거나 좁은 크기면 아무것도 그리지 않는다.
 */
@Composable
internal fun RowActionChip(look: ViewLook, viewId: String, row: ViewRow, rowHeight: Dp) {
    val action = look.rowAction ?: return
    val context = LocalContext.current
    val width = rowActionWidth(action, look)
    val run = actionRunCallback<ViewActionRunAction>(
        actionParametersOf(
            ViewCellAction.ViewKey to viewId, ViewCellAction.PageKey to row.pageId,
            ViewActionRunAction.ActionKey to action.id, ViewActionRunAction.WidgetKey to look.actionWidgetId,
        ),
    )
    Box(
        GlanceModifier.width(width).height((rowHeight - ACTION_ROW_MARGIN).coerceAtLeast(ACTION_PILL_MIN_HEIGHT)).padding(horizontal = 2.dp)
            .background(look.palette.track).cornerRadius(ACTION_PILL_MIN_HEIGHT / 2).clickable(run)
            .semantics {
                contentDescription = context.getString(R.string.view_row_action_description, action.name)
                testTag = "viewRowAction"
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(action.name, style = TextStyle(color = look.palette.accent, fontSize = look.pillSp.sp, fontWeight = FontWeight.Medium), maxLines = 1)
    }
}

/**
 * 액션 버튼 누름: 위젯 설정에서 액션을 읽어 그 행에 적용한다. 로컬 행을 먼저 고치고 쓰기 큐에 쌓을 뿐 노션은 부르지 않는다(보내기는 동기화 쪽).
 * 위젯의 즉시 동작처럼 4초 뒤 보내져 그 사이 '되돌리기'를 누를 수 있다. 설정에서 액션이 지워졌으면 아무것도 하지 않는다.
 */
class ViewActionRunAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val viewId = parameters[ViewCellAction.ViewKey] ?: return
        val pageId = parameters[ViewCellAction.PageKey] ?: return
        val actionId = parameters[ActionKey] ?: return
        val widgetId = parameters[WidgetKey] ?: return
        val deps = WidgetDependencies.from(context)
        val action = ViewActions.parse(deps.widgetConfigs().get(widgetId).actionsJson).firstOrNull { it.id == actionId && it.viewId == viewId } ?: return
        deps.viewWriter().applyAction(viewId, pageId, action)
        WidgetKinds.update(context, glanceId)
    }

    companion object {
        val ActionKey = ActionParameters.Key<String>("actionId")
        val WidgetKey = ActionParameters.Key<Int>("actionWidgetId")
    }
}
