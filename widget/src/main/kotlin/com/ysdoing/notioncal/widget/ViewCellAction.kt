package com.ysdoing.notioncal.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback

/**
 * VW-08 보기 위젯 칸 탭: 체크박스는 뒤집고 상태·선택은 다음 값으로 바꾼다. 로컬 행을 먼저 고치고 쓰기 큐에 쌓을 뿐
 * 노션은 부르지 않는다(보내기는 동기화 쪽 처리기가 한다). 누른 위젯은 바로 다시 그린다.
 */
class ViewCellAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val viewId = parameters[ViewKey] ?: return
        val pageId = parameters[PageKey] ?: return
        val propId = parameters[PropKey] ?: return
        val writer = WidgetDependencies.from(context).viewWriter()
        when (parameters[TypeKey]) {
            "checkbox" -> writer.toggle(viewId, pageId, propId)
            "select", "status" -> writer.cycle(viewId, pageId, propId)
        }
        WidgetKinds.update(context, glanceId)
    }

    companion object {
        val ViewKey = ActionParameters.Key<String>("viewId")
        val PageKey = ActionParameters.Key<String>("pageId")
        val PropKey = ActionParameters.Key<String>("propId")
        val TypeKey = ActionParameters.Key<String>("propType")
    }
}
