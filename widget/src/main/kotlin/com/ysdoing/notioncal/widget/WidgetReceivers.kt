package com.ysdoing.notioncal.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 위젯 리시버 공통 동작(검증 메모 12): 놓이거나 다시 그려질 때 자정 알람을 보장하고(WG-03), 지운 위젯의 설정을 지우고,
 * 마지막 위젯이 사라지면 자정 알람을 끈다.
 */
abstract class NotionCalWidgetReceiverBase : GlanceAppWidgetReceiver() {
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
        // 다른 종류의 위젯이 남아 있으면 자정 알람을 유지한다.
        if (!AllWidgets.anyPlaced(context)) DayChangeScheduler.cancel(context)
    }
}

/** HB-03 습관 위젯. */
class HabitWidgetReceiver : NotionCalWidgetReceiverBase() {
    override val glanceAppWidget: GlanceAppWidget = HabitWidget()
}

/** WG-11 통합 탭 위젯(📅 일정 · ✅ 습관). */
class TabbedWidgetReceiver : NotionCalWidgetReceiverBase() {
    override val glanceAppWidget: GlanceAppWidget = TabbedWidget()
}
