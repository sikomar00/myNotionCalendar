package com.ysdoing.notioncal.widget

import android.content.Context
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
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.semantics.semantics
import androidx.glance.semantics.testTag
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle

// Phase 11c IX-05: 위젯의 즉시 동작 뒤 약 4초 동안 '되돌리기' 줄을 보인다. 그 동안은 쓰기를 노션에 보내지 않고, 눌러서 되돌리면 보내지 않는다.

private val UNDO_HEIGHT = 36.dp

/** '방금 바꾼 것 되돌리기' 줄. 지연 중인 쓰기가 있을 때만 그려지고(4초가 지나 보내면 다음 갱신에서 사라진다), 줄 전체가 눌림이다. */
@androidx.compose.runtime.Composable
internal fun ViewUndoLine(viewId: String, palette: WidgetPalette, fontSp: Float) {
    val context = LocalContext.current
    Box(
        GlanceModifier.fillMaxWidth().height(UNDO_HEIGHT)
            .clickable(actionRunCallback<UndoAction>(actionParametersOf(ViewCellAction.ViewKey to viewId)))
            .semantics { testTag = "viewUndo" },
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(context.getString(R.string.view_widget_undo), style = TextStyle(color = palette.accent, fontSize = fontSp.sp, fontWeight = FontWeight.Bold), maxLines = 1)
    }
}

/** '되돌리기' 누르기: 그 보기에서 지연 중인 쓰기를 모두 지우고 행을 처음 값으로 되돌린 뒤 다시 그린다. 노션은 부르지 않는다. */
class UndoAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val viewId = parameters[ViewCellAction.ViewKey] ?: return
        WidgetDependencies.from(context).viewWriter().undoPending(viewId)
        WidgetKinds.update(context, glanceId)
    }
}
