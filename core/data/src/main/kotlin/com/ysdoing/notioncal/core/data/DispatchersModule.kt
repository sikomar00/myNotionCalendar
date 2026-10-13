package com.ysdoing.notioncal.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import com.ysdoing.notioncal.core.data.db.DataSourceDao
import com.ysdoing.notioncal.core.data.db.EventDao
import com.ysdoing.notioncal.core.data.db.NotionCalDatabase
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
    /** Room은 첫 쿼리 때 백그라운드에서 연다. 여기서는 빌더만 만든다(PF-01). */
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): NotionCalDatabase =
        Room.databaseBuilder(context, NotionCalDatabase::class.java, NotionCalDatabase.NAME).build()

    @Provides
    fun provideEventDao(db: NotionCalDatabase): EventDao = db.eventDao()

    @Provides
    fun provideDataSourceDao(db: NotionCalDatabase): DataSourceDao = db.dataSourceDao()

    @Provides
    fun provideSyncLogDao(db: NotionCalDatabase): SyncLogDao = db.syncLogDao()

    @Provides
    fun provideWidgetConfigDao(db: NotionCalDatabase): WidgetConfigDao = db.widgetConfigDao()

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
