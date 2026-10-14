package com.ysdoing.notioncal.reminders

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.ysdoing.notioncal.R
import com.ysdoing.notioncal.core.data.APP_ZONE
import com.ysdoing.notioncal.core.data.AppIntents
import com.ysdoing.notioncal.core.data.reminders.Reminder
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Clock
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 알림 권한(POST_NOTIFICATIONS)은 Android 13부터 있다. 그 전 기기(minSdk 31·32)는 권한 없이 게시할 수 있으므로
 * 검사하면 늘 '거부'로 나와 알림이 막힌다.
 */
fun notificationPermissionGranted(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

/**
 * EX-01 알림 게시. 누르면 그 일정 편집 시트(퀵 시트), '완료'는 아웃박스를 거쳐 노션에 반영한다([ReminderActionReceiver]).
 * 일정 제목은 기기 알림에만 쓰고 로그에는 남기지 않는다(SEC-02).
 */
@Singleton
class ReminderNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val clock: Clock,
) {
    fun canNotify(): Boolean =
        notificationPermissionGranted(context) && NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun show(reminder: Reminder) {
        if (!canNotify()) return
        ensureChannel()
        val id = notificationId(reminder)
        val edit = Intent(AppIntents.ACTION_QUICK_SHEET)
            .setPackage(context.packageName)
            // PendingIntent가 일정마다 구분되도록 데이터 URI에 페이지 ID를 넣는다.
            .setData("notioncal://edit/${reminder.startDate}/${reminder.pageId}".toUri())
            .putExtra(AppIntents.EXTRA_MODE, AppIntents.MODE_EDIT)
            .putExtra(AppIntents.EXTRA_DATE, reminder.startDate.toString())
            .putExtra(AppIntents.EXTRA_PAGE_ID, reminder.pageId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val done = Intent(context, ReminderActionReceiver::class.java)
            .setAction(ReminderActionReceiver.ACTION_DONE)
            .setData("notioncal://done/${reminder.pageId}".toUri())
            .putExtra(ReminderActionReceiver.EXTRA_PAGE_ID, reminder.pageId)
            .putExtra(ReminderActionReceiver.EXTRA_NOTIFICATION_ID, id)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_event)
            .setContentTitle(reminder.title.ifBlank { context.getString(R.string.reminder_untitled) })
            .setContentText(describe(reminder))
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setWhen(reminder.fireAt.toEpochMilli())
            .setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(context, id, edit, flags))
            .addAction(0, context.getString(R.string.reminder_done), PendingIntent.getBroadcast(context, id, done, flags))
            .build()
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (_: SecurityException) {
            // 권한을 막 거둔 경우. 다음 앱 열기 때 알림이 꺼진다.
        }
    }

    fun cancel(id: Int) = NotificationManagerCompat.from(context).cancel(id)

    /** "오늘 10:30 · 10분 후" / "종일 · 내일" */
    internal fun describe(reminder: Reminder): String {
        val today = clock.instant().atZone(APP_ZONE).toLocalDate()
        val start = reminder.startTime
        return if (start == null) {
            context.getString(R.string.reminder_all_day, dayLabel(reminder.startDate, today))
        } else {
            val time = start.atZone(APP_ZONE)
            context.getString(
                R.string.reminder_timed,
                dayLabel(time.toLocalDate(), today),
                time.format(timeFormat),
                lead(reminder.minutesBefore ?: 0),
            )
        }
    }

    private fun dayLabel(date: LocalDate, today: LocalDate): String = when (date) {
        today -> context.getString(R.string.reminder_today)
        today.plusDays(1) -> context.getString(R.string.reminder_tomorrow)
        else -> context.getString(R.string.reminder_date, date.monthValue, date.dayOfMonth)
    }

    private fun lead(minutes: Int): String = when {
        minutes == 0 -> context.getString(R.string.reminder_now)
        minutes % (24 * 60) == 0 -> context.getString(R.string.reminder_in_days, minutes / (24 * 60))
        minutes % 60 == 0 -> context.getString(R.string.reminder_in_hours, minutes / 60)
        minutes > 60 -> context.getString(R.string.reminder_in_hours_minutes, minutes / 60, minutes % 60)
        else -> context.getString(R.string.reminder_in_minutes, minutes)
    }

    private fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.reminder_channel), NotificationManager.IMPORTANCE_HIGH),
        )
    }

    companion object {
        const val CHANNEL_ID = "event_reminders"
        private val timeFormat = DateTimeFormatter.ofPattern("H:mm")

        fun notificationId(reminder: Reminder): Int = reminder.key.hashCode()
    }
}
