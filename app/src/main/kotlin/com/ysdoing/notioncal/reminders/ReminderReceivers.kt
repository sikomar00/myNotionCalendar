package com.ysdoing.notioncal.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ysdoing.notioncal.core.data.EventWriter
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * EX-01 알람 발화와 다시 걸기. 알람이 울리면 알림을 보내고, 부팅·시간·시간대 변경·앱 업데이트 때는 알람을 다시 건다.
 * 허용한 action만 처리한다(다른 앱이 보낸 위조 인텐트 무시).
 */
@AndroidEntryPoint
class ReminderReceiver : BroadcastReceiver() {
    @Inject
    lateinit var scheduler: ReminderScheduler

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != ACTION_FIRE && action !in RESCHEDULE_ACTIONS) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                if (action == ACTION_FIRE) scheduler.fire() else scheduler.reschedule()
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_FIRE = "com.ysdoing.notioncal.action.REMINDER_FIRE"
        private val RESCHEDULE_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
        )
    }
}

/** EX-01 알림의 '완료' 버튼: 아웃박스를 거쳐 노션에 완료를 반영하고 그 알림을 지운다(화면·위젯도 바로 바뀐다). */
@AndroidEntryPoint
class ReminderActionReceiver : BroadcastReceiver() {
    @Inject
    lateinit var writer: EventWriter

    @Inject
    lateinit var notifier: ReminderNotifier

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_DONE) return
        val pageId = intent.getStringExtra(EXTRA_PAGE_ID) ?: return
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0)
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                writer.setDone(pageId, true)
                notifier.cancel(notificationId)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_DONE = "com.ysdoing.notioncal.action.REMINDER_DONE"
        const val EXTRA_PAGE_ID = "pageId"
        const val EXTRA_NOTIFICATION_ID = "notificationId"
    }
}
