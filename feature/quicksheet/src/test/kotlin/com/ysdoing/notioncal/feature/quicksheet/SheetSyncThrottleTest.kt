package com.ysdoing.notioncal.feature.quicksheet

import com.ysdoing.notioncal.core.data.SyncRequester
import com.ysdoing.notioncal.core.data.SyncTrigger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Test

/** 시트를 열 때 동기화를 요청하되, 연달아 열면 줄인다. */
class SheetSyncThrottleTest {
    private val requests = mutableListOf<SyncTrigger>()
    private val sync = object : SyncRequester {
        override fun request(trigger: SyncTrigger, dataSourceId: String?) {
            requests += trigger
        }

        override val isSyncing: Flow<Boolean> = flowOf(false)
    }

    @Test
    fun firstOpenRequestsAndQuickReopensDoNot() {
        val throttle = SheetSyncThrottle(minGapMs = 60_000)
        throttle.request(sync, now = 1_000)
        throttle.request(sync, now = 30_000)
        throttle.request(sync, now = 60_999)
        assertEquals(listOf(SyncTrigger.APP_OPEN), requests)
    }

    @Test
    fun requestsAgainAfterTheGap() {
        val throttle = SheetSyncThrottle(minGapMs = 60_000)
        throttle.request(sync, now = 1_000)
        throttle.request(sync, now = 61_000)
        assertEquals(listOf(SyncTrigger.APP_OPEN, SyncTrigger.APP_OPEN), requests)
    }
}
