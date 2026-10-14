package com.ysdoing.notioncal.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** WG-06 보조 위젯(오늘·다가오는 일정). 자정 알람·설정 정리는 월 위젯과 같은 방식이다. */
class AgendaWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = AgendaWidget()

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        DayChangeScheduler.scheduleNext(context)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                WidgetDependencies.from(context).widgetConfigs().delete(appWidgetIds.toList())
            } finally {
                pending.finish()
            }
        }
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        if (!AllWidgets.anyPlaced(context)) DayChangeScheduler.cancel(context)
    }
}
