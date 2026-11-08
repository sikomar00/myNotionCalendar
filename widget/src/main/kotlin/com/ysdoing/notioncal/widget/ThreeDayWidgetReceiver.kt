package com.ysdoing.notioncal.widget

import androidx.glance.appwidget.GlanceAppWidget

/** WG-13 3일 위젯. 자정 알람·설정 정리는 월 위젯과 같다([NotionCalWidgetReceiverBase]). */
class ThreeDayWidgetReceiver : NotionCalWidgetReceiverBase() {
    override val glanceAppWidget: GlanceAppWidget = ThreeDayWidget()
}
