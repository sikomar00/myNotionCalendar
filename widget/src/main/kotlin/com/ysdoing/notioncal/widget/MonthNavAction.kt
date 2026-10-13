package com.ysdoing.notioncal.widget

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.action.ActionCallback

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
