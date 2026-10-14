package com.ysdoing.notioncal.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import com.ysdoing.notioncal.core.data.calendar.CalendarView
import java.time.DayOfWeek
import javax.inject.Inject
import javax.inject.Singleton

data class AppSettings(
    /** MV-01 주 시작 요일. PRD §15 기본값 일요일. */
    val weekStart: DayOfWeek = DayOfWeek.SUNDAY,
    /** MV-05 완료 항목 숨김. 기본은 취소선으로 표시(PRD §15). */
    val hideDone: Boolean = false,
    /** NS-06 주기 동기화 간격(15·30·60분). PRD §15 기본 15분. */
    val syncIntervalMinutes: Int = 15,
    /** WI-02 퀵 추가 기본 DB. null이면 첫 번째로 켠 DB. */
    val defaultDataSourceId: String? = null,
    /** S25-03 첫 실행 절전 안내 카드를 닫았는지. */
    val batteryGuideDismissed: Boolean = false,
    /** MV-06 마지막으로 본 보기. */
    val calendarView: CalendarView = CalendarView.MONTH,
) {
    companion object {
        val SYNC_INTERVALS = listOf(15, 30, 60)
    }
}

@Singleton
class SettingsRepository @Inject constructor(
    @SettingsStore private val dataStore: DataStore<Preferences>,
    private val widgetRefresher: WidgetRefresher,
) {
    val settings: Flow<AppSettings> = dataStore.data
        .map { prefs ->
            AppSettings(
                weekStart = prefs[WEEK_START]?.let { runCatching { DayOfWeek.valueOf(it) }.getOrNull() } ?: DayOfWeek.SUNDAY,
                hideDone = prefs[HIDE_DONE] ?: false,
                syncIntervalMinutes = prefs[SYNC_INTERVAL]?.takeIf { it in AppSettings.SYNC_INTERVALS } ?: 15,
                defaultDataSourceId = prefs[DEFAULT_SOURCE],
                batteryGuideDismissed = prefs[BATTERY_GUIDE_DISMISSED] ?: false,
                calendarView = prefs[CALENDAR_VIEW]?.let { runCatching { CalendarView.valueOf(it) }.getOrNull() } ?: CalendarView.MONTH,
            )
        }
        .distinctUntilChanged()

    suspend fun setWeekStart(day: DayOfWeek) {
        dataStore.edit { it[WEEK_START] = day.name }
        widgetRefresher.requestUpdate()
    }

    suspend fun setHideDone(hide: Boolean) {
        dataStore.edit { it[HIDE_DONE] = hide }
        widgetRefresher.requestUpdate()
    }

    suspend fun setSyncInterval(minutes: Int) {
        dataStore.edit { it[SYNC_INTERVAL] = minutes }
    }

    suspend fun setDefaultDataSource(id: String?) {
        dataStore.edit { if (id == null) it.remove(DEFAULT_SOURCE) else it[DEFAULT_SOURCE] = id }
    }

    suspend fun dismissBatteryGuide() {
        dataStore.edit { it[BATTERY_GUIDE_DISMISSED] = true }
    }

    suspend fun setCalendarView(view: CalendarView) {
        dataStore.edit { it[CALENDAR_VIEW] = view.name }
    }

    private companion object {
        val WEEK_START = stringPreferencesKey("week_start")
        val HIDE_DONE = booleanPreferencesKey("hide_done")
        val SYNC_INTERVAL = intPreferencesKey("sync_interval_minutes")
        val DEFAULT_SOURCE = stringPreferencesKey("default_data_source")
        val BATTERY_GUIDE_DISMISSED = booleanPreferencesKey("battery_guide_dismissed")
        val CALENDAR_VIEW = stringPreferencesKey("calendar_view")
    }
}
