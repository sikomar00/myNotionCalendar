package com.ysdoing.notioncal.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.time.DayOfWeek
import javax.inject.Inject
import javax.inject.Singleton

data class AppSettings(
    /** MV-01 주 시작 요일. PRD §15 기본값 일요일. */
    val weekStart: DayOfWeek = DayOfWeek.SUNDAY,
    /** MV-05 완료 항목 숨김. 기본은 취소선으로 표시(PRD §15). */
    val hideDone: Boolean = false,
)

@Singleton
class SettingsRepository @Inject constructor(
    @SettingsStore private val dataStore: DataStore<Preferences>,
) {
    val settings: Flow<AppSettings> = dataStore.data
        .map { prefs ->
            AppSettings(
                weekStart = prefs[WEEK_START]?.let { runCatching { DayOfWeek.valueOf(it) }.getOrNull() } ?: DayOfWeek.SUNDAY,
                hideDone = prefs[HIDE_DONE] ?: false,
            )
        }
        .distinctUntilChanged()

    suspend fun setWeekStart(day: DayOfWeek) {
        dataStore.edit { it[WEEK_START] = day.name }
    }

    suspend fun setHideDone(hide: Boolean) {
        dataStore.edit { it[HIDE_DONE] = hide }
    }

    private companion object {
        val WEEK_START = stringPreferencesKey("week_start")
        val HIDE_DONE = booleanPreferencesKey("hide_done")
    }
}
