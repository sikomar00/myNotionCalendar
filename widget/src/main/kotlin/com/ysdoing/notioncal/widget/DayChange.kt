package com.ysdoing.notioncal.widget

import android.app.AlarmManager
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.glance.appwidget.updateAll
import com.ysdoing.notioncal.core.data.APP_ZONE
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * WG-03: 자정이 지나면 위젯의 오늘 칸을 옮긴다. 한 가지 브로드캐스트에만 의존하지 않도록(§7-4)
 * 서울 자정 정확 알람 + 부팅·시간·시간대 변경·앱 업데이트 브로드캐스트를 모두 받는다.
 */
object DayChangeScheduler {
    const val ACTION_MIDNIGHT = "com.ysdoing.notioncal.widget.action.MIDNIGHT"

    /** 다음 서울 자정 + 1초. 기기 시간대와 무관하다. */
    fun nextMidnight(now: Instant): Instant =
        now.atZone(APP_ZONE).toLocalDate().plusDays(1).atStartOfDay(APP_ZONE).plusSeconds(1).toInstant()

    // canScheduleExactAlarms()로 확인한 뒤에만 정확 알람을 쓴다(USE_EXACT_ALARM 선언, 캘린더 앱 용도). lint가 이 확인을 인식하지 못한다.
    @SuppressLint("MissingPermission")
    fun scheduleNext(context: Context, now: Instant = Instant.now()) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val at = nextMidnight(now).toEpochMilli()
        // RTC(깨우지 않음): 화면이 꺼져 있으면 다음에 켤 때 바로 발화한다. 보는 순간에는 맞는 날짜가 된다.
        if (alarms.canScheduleExactAlarms()) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC, at, pendingIntent(context))
        } else {
            alarms.setWindow(AlarmManager.RTC, at, 60_000L, pendingIntent(context))
        }
    }

    fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java)?.cancel(pendingIntent(context))
    }

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, DayChangeReceiver::class.java).setAction(ACTION_MIDNIGHT),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

class DayChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // 허용한 action만 처리한다(다른 앱이 보낸 위조 인텐트 무시).
        if (intent.action !in HANDLED_ACTIONS) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                NotionCalWidget().updateAll(context)
            } finally {
                DayChangeScheduler.scheduleNext(context)
                pending.finish()
            }
        }
    }

    private companion object {
        val HANDLED_ACTIONS = setOf(
            DayChangeScheduler.ACTION_MIDNIGHT,
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
        )
    }
}
