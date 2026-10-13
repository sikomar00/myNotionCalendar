package com.ysdoing.notioncal.core.sync

import com.ysdoing.notioncal.core.data.SecretStore
import com.ysdoing.notioncal.core.data.SyncRequester
import com.ysdoing.notioncal.core.notion.TokenProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.runBlocking

@Module
@InstallIn(SingletonComponent::class)
object SyncModule {
    /**
     * OkHttp 스레드에서 호출된다(메인 스레드 아님). 첫 호출 뒤에는 [SecretStore] 캐시에서 바로 돌려준다.
     */
    @Provides
    fun provideTokenProvider(secretStore: SecretStore): TokenProvider =
        TokenProvider { runBlocking { secretStore.token() } }

    @Provides
    fun provideSyncRequester(scheduler: SyncScheduler): SyncRequester = scheduler
}
