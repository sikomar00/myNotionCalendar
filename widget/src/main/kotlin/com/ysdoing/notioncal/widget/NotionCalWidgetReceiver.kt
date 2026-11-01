package com.ysdoing.notioncal.widget

import androidx.glance.appwidget.GlanceAppWidget

/** WG-01 월간 위젯. 자정 알람·설정 정리는 [NotionCalWidgetReceiverBase]. */
class NotionCalWidgetReceiver : NotionCalWidgetReceiverBase() {
    override val glanceAppWidget: GlanceAppWidget = NotionCalWidget()
}
