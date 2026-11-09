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
// Phase 12c: 보낸 뒤 2분까지는 같은 줄이 '보낸 변경 되돌리기'로 바뀌어 반대 쓰기를 보낸다(충돌이면 노션 값을 남긴다).

internal val UNDO_HEIGHT = 36.dp

/**
 * '방금 바꾼 것 되돌리기' 줄. 지연 중인 쓰기가 있으면 그 문구이고, 이미 보냈으면([sent], 보낸 뒤 2분까지) '보낸 변경 되돌리기'다. 줄 전체가 눌림이다.
 */
@androidx.compose.runtime.Composable
internal fun ViewUndoLine(viewId: String, palette: WidgetPalette, fontSp: Float, sent: Boolean = false) {
    val context = LocalContext.current
    Box(
        GlanceModifier.fillMaxWidth().height(UNDO_HEIGHT)
            .clickable(actionRunCallback<UndoAction>(actionParametersOf(ViewCellAction.ViewKey to viewId)))
            .semantics { testTag = "viewUndo" },
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(context.getString(if (sent) R.string.view_widget_undo_sent else R.string.view_widget_undo), style = TextStyle(color = palette.accent, fontSize = fontSp.sp, fontWeight = FontWeight.Bold), maxLines = 1)
    }
}

/**
 * '되돌리기' 누르기: 그 보기에서 지연 중인 쓰기를 모두 지우고 행을 처음 값으로 되돌린 뒤 다시 그린다(노션은 부르지 않는다).
 * 지연 중인 것이 없으면 가장 최근에 보낸 변경 묶음의 반대 쓰기를 쌓는다([ViewWriter.undo]).
 */
class UndoAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val viewId = parameters[ViewCellAction.ViewKey] ?: return
        WidgetDependencies.from(context).viewWriter().undo(viewId)
        WidgetKinds.update(context, glanceId)
    }
}
