package com.ysdoing.notioncal.widget

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import com.ysdoing.notioncal.core.data.SyncTrigger

/** WI-04: 위젯마다 보고 있는 달을 바꾼다. delta = -1(이전), +1(다음), 0(오늘). */
class MonthNavAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val started = SystemClock.elapsedRealtime()
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(glanceId)
        val configs = WidgetDependencies.from(context).widgetConfigs()
        when (val delta = parameters[DeltaKey] ?: 0) {
            0 -> configs.resetMonth(appWidgetId)
            else -> configs.shiftMonth(appWidgetId, delta)
        }
        // 검증 메모 5: 누른 위젯의 종류로 다시 그린다(통합 위젯의 📅 탭에서 눌러도 탭이 사라지지 않게).
        WidgetKinds.update(context, glanceId)
        Log.d(GlanceWidgetRefresher.TAG, "월 이동 반영: ${SystemClock.elapsedRealtime() - started}ms")
    }

    companion object {
        val DeltaKey = ActionParameters.Key<Int>("delta")
    }
}

/** 타임라인(VR-TML)의 이전·다음 창·오늘. 캘린더의 달 이동([MonthNavAction])과 이동량을 따로 저장해 한 위젯에 둘이 있어도 서로 움직이지 않는다. 매개변수는 같은 [MonthNavAction.DeltaKey]다. */
class TimelineNavAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(glanceId)
        val configs = WidgetDependencies.from(context).widgetConfigs()
        when (val delta = parameters[MonthNavAction.DeltaKey] ?: 0) {
            0 -> configs.resetTimeline(appWidgetId)
            else -> configs.shiftTimeline(appWidgetId, delta)
        }
        WidgetKinds.update(context, glanceId)
    }
}

internal fun timelineNavAction(delta: Int) = actionRunCallback<TimelineNavAction>(actionParametersOf(MonthNavAction.DeltaKey to delta))

/** NS-06 위젯 새로고침 버튼. 동기화를 요청만 하고, 실제 호출은 :core:sync의 작업이 한다(PRD §7-5). */
class RefreshAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        WidgetDependencies.from(context).syncRequester().request(SyncTrigger.WIDGET_REFRESH)
    }
}
