package com.ysdoing.notioncal.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ysdoing.notioncal.core.data.AppSettings
import com.ysdoing.notioncal.core.data.DataSourceRepository
import com.ysdoing.notioncal.core.data.SettingsRepository
import com.ysdoing.notioncal.core.data.db.DataSourceEntity
import com.ysdoing.notioncal.core.sync.ConnectionResult
import com.ysdoing.notioncal.core.sync.ConnectionService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import javax.inject.Inject
import com.ysdoing.notioncal.core.sync.HolidayScheduler

sealed interface ConnectionUi {
    data object Idle : ConnectionUi
    data object Testing : ConnectionUi
    data class Done(val result: ConnectionResult) : ConnectionUi
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val connection: ConnectionService,
    private val dataSources: DataSourceRepository,
    private val settingsRepository: SettingsRepository,
    private val holidayScheduler: HolidayScheduler,
) : ViewModel() {
    private val _tokenSaved = MutableStateFlow<Boolean?>(null)
    val tokenSaved: StateFlow<Boolean?> = _tokenSaved.asStateFlow()

    private val _connection = MutableStateFlow<ConnectionUi>(ConnectionUi.Idle)
    val connectionState: StateFlow<ConnectionUi> = _connection.asStateFlow()

    val sources: StateFlow<List<DataSourceEntity>> = dataSources.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    init {
        viewModelScope.launch { _tokenSaved.value = connection.hasToken() }
    }

    fun saveToken(token: String) {
        if (token.isBlank()) return
        viewModelScope.launch {
            _connection.value = ConnectionUi.Testing
            val result = connection.saveAndTest(token)
            _tokenSaved.value = true
            _connection.value = ConnectionUi.Done(result)
        }
    }

    fun test() {
        viewModelScope.launch {
            _connection.value = ConnectionUi.Testing
            _connection.value = ConnectionUi.Done(connection.test())
        }
    }

    fun setEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch { dataSources.setEnabled(id, enabled) }
    }

    /** EX-07: 편집 화면에 보여 줄 속성 */
    /** EX-03 DB 색·색 기준. */
    fun setColorStyle(id: String, colorHex: String, useOptionColors: Boolean) {
        viewModelScope.launch { dataSources.setColorStyle(id, colorHex, useOptionColors) }
    }

    fun setExtraProps(id: String, propIds: List<String>) {
        viewModelScope.launch { dataSources.setExtraProps(id, propIds) }
    }

    fun delete(id: String) {
        viewModelScope.launch { dataSources.delete(id) }
    }

    fun setWeekStart(day: DayOfWeek) {
        viewModelScope.launch { settingsRepository.setWeekStart(day) }
    }

    fun setHideDone(hide: Boolean) {
        viewModelScope.launch { settingsRepository.setHideDone(hide) }
    }

    /** EX-02 공휴일 표시. 켤 때는 권한을 받은 뒤에 부르고, 바로 폰의 공휴일 캘린더를 읽는다. */
    fun setShowHolidays(show: Boolean) {
        viewModelScope.launch {
            settingsRepository.setShowHolidays(show)
            if (show) holidayScheduler.requestNow()
        }
    }

    /** S25-10 Material You 색 켜기·끄기. */
    fun setDynamicColor(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setDynamicColor(enabled) }
    }

    /** NS-06 주기 동기화 간격. 바뀌면 PeriodicSync가 작업을 다시 예약한다. */
    fun setSyncInterval(minutes: Int) {
        viewModelScope.launch { settingsRepository.setSyncInterval(minutes) }
    }

    /** WI-02 퀵 추가 기본 DB. */
    fun setDefaultDataSource(id: String) {
        viewModelScope.launch { settingsRepository.setDefaultDataSource(id) }
    }
}
