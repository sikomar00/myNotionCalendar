package com.ysdoing.notioncal.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.glance.appwidget.updateAll

/** 월 위젯(WG-01), 보조 위젯(WG-06), 달력 + 일정 위젯(WG-09), 습관 위젯(HB-03), 통합 탭 위젯(WG-11), 가계부 위젯(LD-03)을 함께 다룬다. */
object AllWidgets {
    suspend fun updateAll(context: Context) {
        NotionCalWidget().updateAll(context)
        AgendaWidget().updateAll(context)
        SplitWidget().updateAll(context)
        HabitWidget().updateAll(context)
        TabbedWidget().updateAll(context)
        LedgerWidget().updateAll(context)
    }

    /** 홈 화면에 남은 위젯이 하나라도 있는지(자정 알람을 끌지 정할 때). */
    fun anyPlaced(context: Context): Boolean {
        val manager = AppWidgetManager.getInstance(context)
        // 검증 메모 12: 새 위젯을 빠뜨리면 그 위젯만 있을 때 자정 알람이 꺼진다.
        return listOf(
            NotionCalWidgetReceiver::class.java, AgendaWidgetReceiver::class.java, SplitWidgetReceiver::class.java,
            HabitWidgetReceiver::class.java, TabbedWidgetReceiver::class.java, LedgerWidgetReceiver::class.java,
        ).any { receiver ->
            manager.getAppWidgetIds(ComponentName(context, receiver)).isNotEmpty()
        }
    }
}
