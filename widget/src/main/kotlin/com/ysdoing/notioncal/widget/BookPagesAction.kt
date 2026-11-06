package com.ysdoing.notioncal.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback

/**
 * M5b 책 위젯 −/+: 읽은 쪽을 [DeltaKey]쪽 바꾼다. 로컬 행(읽은 비율 포함)을 먼저 고치고 보기 전용 쓰기 큐(view_writes)에 쌓을 뿐
 * 노션은 부르지 않는다(보내기는 동기화 쪽 처리기가 한다). 누른 위젯은 바로 다시 그린다.
 */
class BookPagesAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val viewId = parameters[ViewCellAction.ViewKey] ?: return
        val pageId = parameters[ViewCellAction.PageKey] ?: return
        val delta = parameters[DeltaKey] ?: return
        WidgetDependencies.from(context).viewWriter().addPages(viewId, pageId, delta)
        WidgetKinds.update(context, glanceId)
    }

    companion object {
        val DeltaKey = ActionParameters.Key<Int>("delta")
    }
}
