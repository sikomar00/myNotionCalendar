package com.ysdoing.notioncal.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import com.ysdoing.notioncal.core.data.calendar.CalendarView
import com.ysdoing.notioncal.core.data.calendar.CalendarZoom
import com.ysdoing.notioncal.core.data.calendar.EventFilter
import com.ysdoing.notioncal.core.data.reminders.ReminderRules
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
    /** MV-08 두 손가락 확대 배율(%), 70~150. */
    val zoomPercent: Int = CalendarZoom.DEFAULT,
    /** MV-09 앱에서만 숨길 DB. */
    val hiddenSourceIds: Set<String> = emptySet(),
    /** MV-09 앱에서만 숨길 색 기준 값("dsId/optionId"). */
    val hiddenOptionKeys: Set<String> = emptySet(),
    /** S25-10 Material You(배경화면 색) 사용. 기본 켬. */
    val dynamicColor: Boolean = true,
    /** EX-02 공휴일 표시(폰의 공휴일 캘린더, READ_CALENDAR). 권한을 받으면 켠다. */
    val showHolidays: Boolean = false,
    /** EX-02 공휴일을 마지막으로 읽어 온 시각(epoch ms). 앱 열기 때 하루 한 번 다시 읽는다. */
    val holidaysRefreshedAt: Long = 0,
    /** EX-01 알림 켬(알림 권한을 받으면 켠다). */
    val remindersEnabled: Boolean = false,
    /** EX-01 알림 규칙(전체 공통). */
    val reminderRules: ReminderRules = ReminderRules(),
    /** EX-01 마지막으로 처리한 알림 시각(epoch ms). 이 시각 이후 알림만 보낸다. */
    val remindersHandledUntil: Long = 0,
) {
    val filter: EventFilter get() = EventFilter(hiddenSourceIds, hiddenOptionKeys, hideDone)

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
                zoomPercent = (prefs[ZOOM_PERCENT] ?: CalendarZoom.DEFAULT).coerceIn(CalendarZoom.MIN, CalendarZoom.MAX),
                hiddenSourceIds = prefs[HIDDEN_SOURCES].orEmpty(),
                dynamicColor = prefs[DYNAMIC_COLOR] ?: true,
                showHolidays = prefs[SHOW_HOLIDAYS] ?: false,
                holidaysRefreshedAt = prefs[HOLIDAYS_REFRESHED_AT] ?: 0,
                remindersEnabled = prefs[REMINDERS_ENABLED] ?: false,
                reminderRules = ReminderRules(
                    timedMinutes = prefs[TIMED_REMINDERS]?.let { saved ->
                        ReminderRules.normalize(saved.split(',').mapNotNull { it.trim().toIntOrNull() })
                    } ?: listOf(ReminderRules.DEFAULT_TIMED_MINUTES),
                    allDayMinuteOfDay = (prefs[ALL_DAY_REMINDER_MINUTE] ?: ReminderRules.DEFAULT_ALL_DAY_MINUTE).coerceIn(0, 24 * 60 - 1),
                    allDayDayBefore = prefs[ALL_DAY_REMINDER_DAY_BEFORE] ?: false,
                ),
                remindersHandledUntil = prefs[REMINDERS_HANDLED_UNTIL] ?: 0,
                hiddenOptionKeys = prefs[HIDDEN_OPTIONS].orEmpty(),
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

    suspend fun setZoomPercent(percent: Int) {
        dataStore.edit { it[ZOOM_PERCENT] = percent.coerceIn(CalendarZoom.MIN, CalendarZoom.MAX) }
    }

    /** MV-09 필터를 한 번에 저장한다. 완료 숨김은 설정 화면(MV-05)과 같은 값이다. */
    suspend fun setFilter(filter: EventFilter) {
        dataStore.edit {
            it[HIDDEN_SOURCES] = filter.hiddenSourceIds
            it[HIDDEN_OPTIONS] = filter.hiddenOptionKeys
            it[HIDE_DONE] = filter.hideDone
        }
    }

    suspend fun setDynamicColor(enabled: Boolean) {
        dataStore.edit { it[DYNAMIC_COLOR] = enabled }
    }

    suspend fun setShowHolidays(show: Boolean) {
        dataStore.edit { it[SHOW_HOLIDAYS] = show }
        widgetRefresher.requestUpdate()
    }

    suspend fun setHolidaysRefreshedAt(at: Long) {
        dataStore.edit { it[HOLIDAYS_REFRESHED_AT] = at }
    }

    /** EX-01 알림 켜기·끄기. 켤 때 [now]부터 처리한 것으로 둬서 지난 알림을 몰아 보내지 않는다. */
    suspend fun setRemindersEnabled(enabled: Boolean, now: Long) {
        dataStore.edit {
            if (enabled && it[REMINDERS_ENABLED] != true) it[REMINDERS_HANDLED_UNTIL] = now
            it[REMINDERS_ENABLED] = enabled
        }
    }

    suspend fun setReminderRules(rules: ReminderRules) {
        dataStore.edit {
            it[TIMED_REMINDERS] = ReminderRules.normalize(rules.timedMinutes).joinToString(",")
            it[ALL_DAY_REMINDER_MINUTE] = rules.allDayMinuteOfDay.coerceIn(0, 24 * 60 - 1)
            it[ALL_DAY_REMINDER_DAY_BEFORE] = rules.allDayDayBefore
        }
    }

    suspend fun setRemindersHandledUntil(at: Long) {
        dataStore.edit { it[REMINDERS_HANDLED_UNTIL] = at }
    }

    private companion object {
        val WEEK_START = stringPreferencesKey("week_start")
        val HIDE_DONE = booleanPreferencesKey("hide_done")
        val SYNC_INTERVAL = intPreferencesKey("sync_interval_minutes")
        val DEFAULT_SOURCE = stringPreferencesKey("default_data_source")
        val BATTERY_GUIDE_DISMISSED = booleanPreferencesKey("battery_guide_dismissed")
        val CALENDAR_VIEW = stringPreferencesKey("calendar_view")
        val ZOOM_PERCENT = intPreferencesKey("zoom_percent")
        val HIDDEN_SOURCES = stringSetPreferencesKey("hidden_source_ids")
        val HIDDEN_OPTIONS = stringSetPreferencesKey("hidden_option_keys")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val SHOW_HOLIDAYS = booleanPreferencesKey("show_holidays")
        val HOLIDAYS_REFRESHED_AT = longPreferencesKey("holidays_refreshed_at")
        val REMINDERS_ENABLED = booleanPreferencesKey("reminders_enabled")
        val TIMED_REMINDERS = stringPreferencesKey("timed_reminder_minutes")
        val ALL_DAY_REMINDER_MINUTE = intPreferencesKey("all_day_reminder_minute")
        val ALL_DAY_REMINDER_DAY_BEFORE = booleanPreferencesKey("all_day_reminder_day_before")
        val REMINDERS_HANDLED_UNTIL = longPreferencesKey("reminders_handled_until")
    }
}
