package com.ysdoing.notioncal.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 새 위젯을 매니페스트에만 더하고 목록 하나를 빠뜨리면 컴파일은 되지만 조용히 틀어진다(자정 알람이 꺼지거나, 설정이 월 위젯 설정으로
 * 열리거나, 액션 뒤 월 위젯으로 다시 그려진다). 매니페스트의 위젯 리시버 전부를 코드의 목록과 맞춰 본다.
 */
@RunWith(RobolectricTestRunner::class)
class WidgetRegistryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private val manifestReceivers: Set<String> = context.packageManager
        .queryBroadcastReceivers(Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE).setPackage(context.packageName), 0)
        .map { it.activityInfo.name }
        .toSet()

    @Test
    fun everyManifestWidgetIsInTheSharedReceiverList() {
        assertEquals(manifestReceivers, AllWidgets.RECEIVERS.map { it.name }.toSet())
    }

    @Test
    fun onlyTheMonthWidgetsFallBackToMonthDrawingAndSettings() {
        val month = NotionCalWidgetReceiver::class.java.name
        val tabbed = TabbedWidgetReceiver::class.java.name // 📅 탭이 월 위젯 내용이라 설정은 MONTH
        for (receiver in manifestReceivers - month) {
            assertFalse(receiver, WidgetKinds.forProvider(receiver) is NotionCalWidget)
        }
        for (receiver in manifestReceivers - month - tabbed) {
            assertNotEquals(receiver, ConfigKind.MONTH, configKindFor(receiver))
        }
    }
}
