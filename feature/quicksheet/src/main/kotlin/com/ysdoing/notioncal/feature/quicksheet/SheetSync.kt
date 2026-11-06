package com.ysdoing.notioncal.feature.quicksheet

import android.os.SystemClock
import com.ysdoing.notioncal.core.data.SyncRequester
import com.ysdoing.notioncal.core.data.SyncTrigger

/**
 * 시트를 열 때의 동기화 요청(사용자 결정 2026-11-03: 앱·시트를 열 때 + 위젯 새로고침). 위젯에서 시트를 연달아 열어도
 * [minGapMs] 안에는 다시 요청하지 않는다(노션 호출·배터리를 아끼고, 시트가 열리는 길을 무겁게 만들지 않는다).
 */
internal class SheetSyncThrottle(private val minGapMs: Long = 60_000L) {
    private var lastAt: Long? = null

    fun request(sync: SyncRequester, now: Long = SystemClock.elapsedRealtime()) {
        val last = lastAt
        if (last != null && now - last < minGapMs) return
        lastAt = now
        sync.request(SyncTrigger.APP_OPEN)
    }

    companion object {
        /** 앱 전체가 함께 쓴다(시트 종류와 상관없이 한 번). */
        val Shared = SheetSyncThrottle()
    }
}
