package com.ysdoing.notioncal.widget

import android.content.Context
import com.ysdoing.notioncal.core.data.DataSourceRepository
import com.ysdoing.notioncal.core.data.EventRepository
import com.ysdoing.notioncal.core.data.SettingsRepository
import com.ysdoing.notioncal.core.data.SyncStatusRepository
import com.ysdoing.notioncal.core.data.SyncRequester
import com.ysdoing.notioncal.core.data.WidgetConfigRepository
import com.ysdoing.notioncal.core.data.WidgetRefresher
import dagger.Binds
import dagger.Module
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import com.ysdoing.notioncal.core.data.HolidayRepository
import com.ysdoing.notioncal.core.data.habit.HabitRepository

/** Glance 위젯·리시버·액션은 Hilt가 직접 주입하지 않으므로 EntryPoint로 저장소를 꺼낸다. 모두 Room만 읽는다. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetDependencies {
    fun events(): EventRepository
    fun settings(): SettingsRepository
    fun widgetConfigs(): WidgetConfigRepository
    fun dataSources(): DataSourceRepository
    fun syncStatus(): SyncStatusRepository
    fun holidays(): HolidayRepository
    fun syncRequester(): SyncRequester

    /** HB-02·03 습관 보드와 체크(아웃박스로만 쓴다). */
    fun habits(): HabitRepository

    companion object {
        fun from(context: Context): WidgetDependencies =
            EntryPointAccessors.fromApplication(context.applicationContext, WidgetDependencies::class.java)
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class WidgetModule {
    @Binds
    abstract fun bindWidgetRefresher(impl: GlanceWidgetRefresher): WidgetRefresher
}
