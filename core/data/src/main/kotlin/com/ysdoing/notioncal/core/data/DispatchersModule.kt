package com.ysdoing.notioncal.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import com.ysdoing.notioncal.core.data.db.DataSourceDao
import com.ysdoing.notioncal.core.data.db.EventDao
import com.ysdoing.notioncal.core.data.db.HabitDao
import com.ysdoing.notioncal.core.data.db.LedgerDao
import com.ysdoing.notioncal.core.data.db.HolidayDao
import com.ysdoing.notioncal.core.data.db.NotionCalDatabase
import com.ysdoing.notioncal.core.data.db.RecoveringOpenHelperFactory
import com.ysdoing.notioncal.core.data.db.SyncLogDao
import com.ysdoing.notioncal.core.data.db.WidgetConfigDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import java.time.Clock
import javax.inject.Qualifier
import javax.inject.Singleton

/** DB·네트워크·JSON 파싱은 이 디스패처에서만 돌린다(PF-03). 테스트에서는 교체한다. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class SettingsStore

@Module
@InstallIn(SingletonComponent::class)
object DispatchersModule {
    @Provides
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    /** 시각은 이 Clock으로만 읽어 테스트에서 고정한다. */
    @Provides
    fun provideClock(): Clock = Clock.systemUTC()
}

@Module
@InstallIn(SingletonComponent::class)
object DataModule {
    /**
     * Room은 첫 쿼리 때 백그라운드에서 연다. 여기서는 빌더만 만든다(PF-01).
     * 마이그레이션이 실패하면 캐시만 비우고 DB 등록·위젯 설정·미전송 쓰기를 살려 다시 연다(PF-09).
     */
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context, health: AppHealth): NotionCalDatabase =
        Room.databaseBuilder(context, NotionCalDatabase::class.java, NotionCalDatabase.NAME)
            .openHelperFactory(
                RecoveringOpenHelperFactory(
                    beforeOpen = health::checkFault, // 디버그 고장 주입(PF-10)
                    forceRecover = health::consumeDatabaseReset, // 진단 화면의 'DB 다시 만들기'
                ),
            )
            .build()

    @Provides
    fun provideEventDao(db: NotionCalDatabase): EventDao = db.eventDao()

    @Provides
    fun provideDataSourceDao(db: NotionCalDatabase): DataSourceDao = db.dataSourceDao()

    @Provides
    fun provideSyncLogDao(db: NotionCalDatabase): SyncLogDao = db.syncLogDao()

    @Provides
    fun provideWidgetConfigDao(db: NotionCalDatabase): WidgetConfigDao = db.widgetConfigDao()

    @Provides
    fun provideHolidayDao(db: NotionCalDatabase): HolidayDao = db.holidayDao()

    @Provides
    fun provideHabitDao(db: NotionCalDatabase): HabitDao = db.habitDao()

    @Provides
    fun provideLedgerDao(db: NotionCalDatabase): LedgerDao = db.ledgerDao()

    @Provides
    @Singleton
    @SettingsStore
    fun provideSettingsStore(
        @ApplicationContext context: Context,
        @IoDispatcher io: CoroutineDispatcher,
    ): DataStore<Preferences> = PreferenceDataStoreFactory.create(
        scope = CoroutineScope(io + SupervisorJob()),
        produceFile = { context.preferencesDataStoreFile("settings") },
    )

    @Provides
    @Singleton
    fun provideSecretStore(
        @ApplicationContext context: Context,
        @IoDispatcher io: CoroutineDispatcher,
    ): SecretStore = SecretStore(
        dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(io + SupervisorJob()),
            produceFile = { context.preferencesDataStoreFile(SecretStore.FILE_NAME) },
        ),
        aeadProvider = { withContext(io) { KeystoreAead.get(context) } },
    )
}
