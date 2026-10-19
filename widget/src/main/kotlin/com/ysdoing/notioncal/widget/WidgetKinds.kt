package com.ysdoing.notioncal.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager

/**
 * 위젯 id → 그 위젯의 종류(검증 메모 5). 월 이동·습관 체크·설정 저장 같은 액션은 누른 위젯을 제 종류로 다시 그려야 한다
 * (월 위젯을 하드코딩하면 통합 위젯이 탭 없는 월 위젯으로 그려진다).
 */
internal object WidgetKinds {
    fun forProvider(className: String?): GlanceAppWidget = when (className) {
        AgendaWidgetReceiver::class.java.name -> AgendaWidget()
        SplitWidgetReceiver::class.java.name -> SplitWidget()
        HabitWidgetReceiver::class.java.name -> HabitWidget()
        TabbedWidgetReceiver::class.java.name -> TabbedWidget()
        LedgerWidgetReceiver::class.java.name -> LedgerWidget()
        else -> NotionCalWidget()
    }

    suspend fun update(context: Context, glanceId: GlanceId) {
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(glanceId)
        val provider = AppWidgetManager.getInstance(context).getAppWidgetInfo(appWidgetId)?.provider?.className
        forProvider(provider).update(context, glanceId)
    }
}
