package com.ysdoing.notioncal.widget

import androidx.glance.appwidget.GlanceAppWidget

/** WG-06 보조 위젯(오늘·다가오는 일정). 자정 알람·설정 정리는 월 위젯과 같다([NotionCalWidgetReceiverBase]). */
class AgendaWidgetReceiver : NotionCalWidgetReceiverBase() {
    override val glanceAppWidget: GlanceAppWidget = AgendaWidget()
}
