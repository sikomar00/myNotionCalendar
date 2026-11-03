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
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

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

    /** M5 표지 내려받기: 노션 클라이언트(토큰 헤더·재시도 인터셉터)와 **다른** 클라이언트. 인터셉터가 하나도 없다. */
    @Provides
    fun provideCoverDownload(): CoverDownload = CoverDownloader(
        client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .dns(PublicOnlyDns) // 이름이 사설·로컬 주소로 풀리면 연결하지 않는다(SSRF)
            .followRedirects(false) // 리다이렉트는 CoverDownloader가 한 단계씩 같은 규칙으로 따라간다
            .build(),
        scale = { CoverScaler.scale(it) },
    )

    @Provides
    fun provideSyncRequester(scheduler: SyncScheduler): SyncRequester = scheduler

    @Provides
    fun provideSyncScheduleInfo(scheduler: SyncScheduler): SyncScheduleInfo = scheduler

    @Provides
    fun provideOutboxRequester(scheduler: SyncScheduler): OutboxRequester = OutboxRequester { scheduler.requestOutbox() }
}
