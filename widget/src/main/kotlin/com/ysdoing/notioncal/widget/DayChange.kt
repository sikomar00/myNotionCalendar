package com.ysdoing.notioncal.widget

import android.app.AlarmManager
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.ysdoing.notioncal.core.data.APP_ZONE
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate

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

    /**
     * 앱이 시작할 때: 강제 중지하면 자정 알람이 지워져 다시 걸 길이 없었다(넘긴 달이 다음 날에도 남음). 놓인 위젯이 있으면
     * 날짜가 바뀌었는지 보고 되돌린 뒤 다시 그리고, 알람을 다시 건다. 메인 스레드 밖에서 부른다.
     * shortcut: 새 날 첫 동작이 위젯 ›라 프로세스가 함께 뜨면 이 되돌리기가 그 이동보다 늦게 끝나 이동이 사라질 수 있고(한 번 더 누르면 된다),
     * 자정 알람으로 뜬 경우 리시버와 함께 다시 그리기가 두 번 돌 수 있다. 날마다 한 번뿐이라 두었다.
     */
    suspend fun catchUp(context: Context) {
        if (!AllWidgets.anyPlaced(context)) return
        if (resetOffsetsIfNewDay(context)) AllWidgets.updateAll(context)
        scheduleNext(context)
    }

    /**
     * 서울 날짜가 바뀌었으면(자정 알람, 꺼진 채 자정을 넘긴 부팅, 시계를 손으로 넘김) 다른 달로 넘겨 둔 위젯을 오늘로 되돌린다. 되돌렸으면 true.
     * 망 시각 보정처럼 날짜가 그대로면 그대로 둔다. 처음(기록 없음)에도 되돌린다(넘긴 위젯이 없으면 바뀌는 행이 없다).
     * 날짜는 되돌리기에 성공한 뒤에만 적어 실패하면 다음에 다시 한다. 실패는 기록만 하고 false(오늘 칸 다시 그리기는 막지 않는다).
     * shortcut: 자정 바로 그때 위젯 설정 저장이 겹치면 그 저장이 옛 이동값을 다시 쓸 수 있다(위젯 버튼은 한 열만 바꿔 겹치지 않는다).
     */
    internal suspend fun resetOffsetsIfNewDay(context: Context): Boolean = runCatching {
        val deps = WidgetDependencies.from(context)
        val today = LocalDate.now(APP_ZONE)
        if (deps.settings().widgetOffsetsDay() == today) return@runCatching false
        deps.widgetConfigs().resetAllOffsets()
        deps.settings().setWidgetOffsetsDay(today)
        true
    }.onFailure { Log.w(GlanceWidgetRefresher.TAG, "날짜 변경 이동값 초기화 실패", it) }.getOrDefault(false)

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
                // 초기화에 실패해도 오늘 칸은 옮겨야 하므로 다시 그리기는 계속한다.
                DayChangeScheduler.resetOffsetsIfNewDay(context)
                AllWidgets.updateAll(context)
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
