package com.ysdoing.notioncal.widget

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.action.ActionCallback
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
        NotionCalWidget().update(context, glanceId)
        Log.d(GlanceWidgetRefresher.TAG, "월 이동 반영: ${SystemClock.elapsedRealtime() - started}ms")
    }

    companion object {
        val DeltaKey = ActionParameters.Key<Int>("delta")
    }
}

/** NS-06 위젯 새로고침 버튼. 동기화를 요청만 하고, 실제 호출은 :core:sync의 작업이 한다(PRD §7-5). */
class RefreshAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        WidgetDependencies.from(context).syncRequester().request(SyncTrigger.WIDGET_REFRESH)
    }
}
