package com.ysdoing.notioncal.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class NotionCalWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NotionCalWidget()

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        // 위젯이 놓이거나 다시 그려질 때 자정 알람을 보장한다(WG-03).
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
        DayChangeScheduler.cancel(context)
    }
}
