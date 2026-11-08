package com.ysdoing.notioncal.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.flow.first

/**
 * 월 위젯(WG-01), 보조 위젯(WG-06), 달력 + 일정 위젯(WG-09), 3일 위젯(WG-13), 습관 위젯(HB-03), 통합 탭 위젯(WG-11), 가계부 위젯(LD-03),
 * 디데이 위젯(DD-03~05), 노션 위젯(WL-01)을 함께 다룬다. 자정 갱신(WG-03)도 여기를 거쳐 디데이 숫자가 바뀐다.
 */
object AllWidgets {
    suspend fun updateAll(context: Context) {
        NotionCalWidget().updateAll(context)
        AgendaWidget().updateAll(context)
        SplitWidget().updateAll(context)
        ThreeDayWidget().updateAll(context)
        HabitWidget().updateAll(context)
        TabbedWidget().updateAll(context)
        LedgerWidget().updateAll(context)
        DdayWidget().updateAll(context)
        DdayListWidget().updateAll(context)
        ViewWidget().updateAll(context)
        LayoutWidget().updateAll(context)
        BookWidget().updateAll(context)
    }

    /** 홈 화면에 남은 위젯이 하나라도 있는지(자정 알람을 끌지 정할 때). */
    fun anyPlaced(context: Context): Boolean {
        val manager = AppWidgetManager.getInstance(context)
        // 검증 메모 12: 새 위젯을 빠뜨리면 그 위젯만 있을 때 자정 알람이 꺼진다.
        return RECEIVERS.any { receiver -> manager.getAppWidgetIds(ComponentName(context, receiver)).isNotEmpty() }
    }

    /** 홈 화면에 놓인 위젯 전부(위젯 ID 순). 설정 화면의 '위젯' 목록이 쓴다. Room만 읽지만 보기 이름을 읽으니 메인 스레드 밖에서 부른다. */
    suspend fun placed(context: Context): List<PlacedWidget> {
        val manager = AppWidgetManager.getInstance(context)
        val placedIds = RECEIVERS.associateWith { manager.getAppWidgetIds(ComponentName(context, it)) }.filterValues { it.isNotEmpty() }
        if (placedIds.isEmpty()) return emptyList() // 놓인 위젯이 없으면 이름·DB를 읽지 않는다
        val deps = WidgetDependencies.from(context)
        val viewNames = deps.views().observeRegistered().first().associate { it.viewId to it.name }
        val packages = context.packageManager
        val widgets = placedIds.flatMap { (receiver, ids) ->
            val label = packages.getReceiverInfo(ComponentName(context, receiver), 0).loadLabel(packages).toString()
            ids.map { id -> PlacedWidget(id, label, widgetSummary(context, receiver.name, deps.widgetConfigs().get(id), viewNames)) }
        }
        return numberDuplicates(widgets.sortedBy { it.appWidgetId })
    }

    /** 그 위젯의 설정 화면을 여는 인텐트(길게 눌러 다시 설정할 때와 같은 화면). */
    fun configIntent(context: Context, appWidgetId: Int): Intent = viewConfigIntent(context, appWidgetId)

    /** 위젯 리시버 전부(매니페스트와 같아야 한다, WidgetRegistryTest). 자정 알람·설정 목록·위젯 고르기 미리보기가 함께 쓴다. */
    internal val RECEIVERS = listOf(
        NotionCalWidgetReceiver::class.java, AgendaWidgetReceiver::class.java, SplitWidgetReceiver::class.java, ThreeDayWidgetReceiver::class.java,
        HabitWidgetReceiver::class.java, TabbedWidgetReceiver::class.java, LedgerWidgetReceiver::class.java,
        DdaySmallWidgetReceiver::class.java, DdayWideWidgetReceiver::class.java, DdayListWidgetReceiver::class.java,
        ViewWidgetReceiver::class.java, LayoutWidgetReceiver::class.java, BookWidgetReceiver::class.java,
    )
}
