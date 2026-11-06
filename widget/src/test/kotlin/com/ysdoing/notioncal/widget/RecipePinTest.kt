package com.ysdoing.notioncal.widget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Phase 11h '홈 화면에 추가'의 핀 콜백. 런처가 위젯을 놓으면 이 PendingIntent로 위젯 번호(`EXTRA_APPWIDGET_ID`)를 덧붙여 알리니
 * 시스템이 extras를 채울 수 있게 바꿀 수 있어야 하고(MUTABLE), 같은 앱의 수신기만 가리켜야 한다(명시적 인텐트).
 */
@RunWith(RobolectricTestRunner::class)
class RecipePinTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun theCallbackIsAnExplicitMutableBroadcastToTheReceiver() {
        val shadow = shadowOf(RecipePin.callback(context))
        assertTrue(shadow.isBroadcastIntent)
        assertEquals(RecipePinReceiver::class.java.name, shadow.savedIntent.component!!.className)
        assertEquals(context.packageName, shadow.savedIntent.component!!.packageName)
        assertTrue(shadow.flags and PendingIntent.FLAG_MUTABLE != 0)
        assertEquals(0, shadow.flags and PendingIntent.FLAG_IMMUTABLE)
    }

    @Test
    fun aCallbackWithoutAWidgetNumberIsIgnored() {
        RecipePinReceiver().onReceive(context, Intent()) // 번호가 없으면 아무것도 하지 않고 비동기 작업도 시작하지 않는다
    }
}
