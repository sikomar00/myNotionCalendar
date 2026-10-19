package com.ysdoing.notioncal.widget

import androidx.glance.appwidget.GlanceAppWidget

/** WG-09 '달력 + 일정' 위젯. 자정 알람·설정 정리는 월 위젯과 같다([NotionCalWidgetReceiverBase]). */
class SplitWidgetReceiver : NotionCalWidgetReceiverBase() {
    override val glanceAppWidget: GlanceAppWidget = SplitWidget()
}
