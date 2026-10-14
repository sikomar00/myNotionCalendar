package com.ysdoing.notioncal.core.sync

import com.ysdoing.notioncal.core.data.DebugFlags
import com.ysdoing.notioncal.core.data.OutboxRequester
import com.ysdoing.notioncal.core.data.SecretStore
import com.ysdoing.notioncal.core.data.SyncRequester
import com.ysdoing.notioncal.core.data.SyncScheduleInfo
import com.ysdoing.notioncal.core.notion.TokenProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

@Module
@InstallIn(SingletonComponent::class)
object SyncModule {
    /**
     * OkHttp 스레드에서 호출된다(메인 스레드 아님). 첫 호출 뒤에는 [SecretStore] 캐시에서 바로 돌려준다.
     * 디버그 메뉴의 '인증 실패 흉내'가 켜져 있으면 가짜 토큰을 보내 실제 401을 받는다(PF-05 검증용).
     */
    @Provides
    fun provideTokenProvider(secretStore: SecretStore, debugFlags: DebugFlags): TokenProvider =
        TokenProvider {
            runBlocking {
                if (debugFlags.fakeAuthFailure.first()) DebugFlags.FAKE_TOKEN else secretStore.token()
            }
        }

    @Provides
    fun provideSyncRequester(scheduler: SyncScheduler): SyncRequester = scheduler

    @Provides
    fun provideSyncScheduleInfo(scheduler: SyncScheduler): SyncScheduleInfo = scheduler

    @Provides
    fun provideOutboxRequester(scheduler: SyncScheduler): OutboxRequester = OutboxRequester { scheduler.requestOutbox() }
}
