package com.ysdoing.notioncal.reminders

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Clock

/** EX-01: Android 12(API 31·32)는 알림 권한이 없으므로 권한 검사로 알림을 막지 않는다. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ReminderPermissionTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val notifier = ReminderNotifier(context, Clock.systemUTC())
    private val manager get() = shadowOf(context.getSystemService(NotificationManager::class.java))

    @Test
    @Config(sdk = [32])
    fun android12NeedsNoRuntimePermission() {
        assertTrue(notificationPermissionGranted(context))
        assertTrue(notifier.canNotify())
        // 시스템 설정에서 앱 알림을 끄면 보내지 않는다.
        manager.setNotificationsEnabled(false)
        assertFalse(notifier.canNotify())
    }

    @Test
    @Config(sdk = [35])
    fun android13AndLaterNeedsPermission() {
        assertFalse(notificationPermissionGranted(context))
        assertFalse(notifier.canNotify())
        shadowOf(context as Application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        assertTrue(notifier.canNotify())
    }
}
