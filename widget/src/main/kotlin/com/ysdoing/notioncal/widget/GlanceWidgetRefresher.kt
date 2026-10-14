package com.ysdoing.notioncal.widget

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.glance.appwidget.updateAll
import com.ysdoing.notioncal.core.data.WidgetRefresher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 마지막 요청 뒤 [windowMs] 동안 새 요청이 없으면 [action]을 한 번 실행한다.
 * 동기화 한 번에 여러 번 들어오는 갱신 요청을 하나로 묶는다(PRD §7-3, 300ms).
 */
class Debouncer(
    private val scope: CoroutineScope,
    private val windowMs: Long,
    private val action: suspend () -> Unit,
) {
    private var pending: Job? = null

    @Synchronized
    fun request() {
        pending?.cancel()
        pending = scope.launch {
            delay(windowMs)
            action()
        }
    }
}

/** WG-05: 데이터가 바뀌면 모든 위젯을 갱신한다. 요청~갱신 완료 시간을 로그로 남겨 §3 지표(0.5초)를 확인한다. */
@Singleton
class GlanceWidgetRefresher @Inject constructor(
    @ApplicationContext private val context: Context,
) : WidgetRefresher {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var firstRequestAt = 0L

    private val debouncer = Debouncer(scope, DEBOUNCE_MS) {
        val requestedAt = firstRequestAt
        firstRequestAt = 0L
        // 살아 있는 위젯 세션은 관찰 상태가 바뀌어야 다시 그린다. 기기 글꼴 크기도 그 상태에 넣는다(S25-06).
        SystemFontScale.refresh(context)
        runCatching { NotionCalWidget().updateAll(context) }
            .onFailure { Log.w(TAG, "위젯 갱신 실패", it) }
        val config = context.resources.configuration
        Log.d(
            TAG,
            "위젯 갱신 완료: 첫 요청부터 ${SystemClock.elapsedRealtime() - requestedAt}ms (글꼴 ${config.fontScale}, 밀도 ${config.densityDpi})",
        )
    }

    override fun requestUpdate() {
        if (firstRequestAt == 0L) firstRequestAt = SystemClock.elapsedRealtime()
        debouncer.request()
    }

    companion object {
        const val TAG = "NotionCalWidget"
        const val DEBOUNCE_MS = 300L
    }
}
