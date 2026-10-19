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
import com.ysdoing.notioncal.core.data.weather.Region
import com.ysdoing.notioncal.core.data.weather.RegionCatalog
import com.ysdoing.notioncal.core.data.weather.WeatherLocationMode
import com.ysdoing.notioncal.core.data.weather.WeatherPlace
import com.ysdoing.notioncal.core.data.weather.WeatherRepository
import com.ysdoing.notioncal.core.data.weather.WeatherStatus
import com.ysdoing.notioncal.core.sync.WeatherScheduler
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import com.ysdoing.notioncal.core.sync.HolidayScheduler
import java.time.Clock
import com.ysdoing.notioncal.core.data.reminders.ReminderRules
import com.ysdoing.notioncal.reminders.ReminderScheduler

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
    private val reminderScheduler: ReminderScheduler,
    private val clock: Clock,
    private val weather: WeatherRepository,
    private val regions: RegionCatalog,
    private val weatherScheduler: WeatherScheduler,
) : ViewModel() {
    /** S25-11 정확한 알람을 쓸 수 있는지. 설정 화면이 다시 보일 때 [refreshExactAlarm]로 갱신한다. */
    private val _exactAlarm = MutableStateFlow(reminderScheduler.exactAllowed())
    val exactAlarm: StateFlow<Boolean> = _exactAlarm.asStateFlow()

    fun refreshExactAlarm() {
        _exactAlarm.value = reminderScheduler.exactAllowed()
    }

    /** EX-01 알림 켜기·끄기(켤 때는 권한을 받은 뒤 부른다). */
    fun setRemindersEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setRemindersEnabled(enabled, clock.millis())
            reminderScheduler.reschedule()
        }
    }

    /** EX-01 알림 규칙(전체 공통). */
    fun setReminderRules(rules: ReminderRules) {
        viewModelScope.launch {
            settingsRepository.setReminderRules(rules)
            reminderScheduler.reschedule()
        }
    }

    private val _tokenSaved = MutableStateFlow<Boolean?>(null)
    val tokenSaved: StateFlow<Boolean?> = _tokenSaved.asStateFlow()

    private val _connection = MutableStateFlow<ConnectionUi>(ConnectionUi.Idle)
    val connectionState: StateFlow<ConnectionUi> = _connection.asStateFlow()

    val sources: StateFlow<List<DataSourceEntity>> = dataSources.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** WX-01 받아온 시각(설정 화면 표시). */
    val weatherStatus: StateFlow<WeatherStatus> = weather.observeStatus()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WeatherStatus(null, false, null))

    fun setWeatherEnabled(on: Boolean) {
        viewModelScope.launch {
            settingsRepository.setWeatherEnabled(on)
            if (on) weatherScheduler.requestNow()
        }
    }

    fun setWeatherMode(mode: WeatherLocationMode) {
        viewModelScope.launch { settingsRepository.setWeatherLocationMode(mode) }
    }

    suspend fun searchRegions(query: String): List<Region> = regions.search(query)

    /** WX-02 지역을 바꾸면 이전 지역 예보를 지우고 바로 받는다. */
    fun pickRegion(region: Region) {
        viewModelScope.launch {
            settingsRepository.setWeatherLocationMode(WeatherLocationMode.REGION)
            changePlace(WeatherPlace(region.name, region.lat, region.lon))
        }
    }

    /** WX-02 대략 위치: 가장 가까운 시·군·구 이름을 붙여 소수 둘째 자리로 저장한다. */
    fun useLocation(lat: Double, lon: Double) {
        viewModelScope.launch {
            val near = regions.all().minByOrNull { (it.lat - lat) * (it.lat - lat) + (it.lon - lon) * (it.lon - lon) }
            changePlace(WeatherPlace(near?.name?.let { "$it 근처" } ?: "현재 위치", lat, lon))
        }
    }

    private suspend fun changePlace(place: WeatherPlace) {
        if (settingsRepository.settings.first().weatherPlace?.rounded() != place.rounded()) weather.clear()
        settingsRepository.setWeatherPlace(place)
        weatherScheduler.requestNow()
    }

    fun setWeatherMonthCells(show: Boolean) {
        viewModelScope.launch { settingsRepository.setWeatherMonthCellIcons(show) }
    }

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

    /** EX-11 강조할 일정(속성·값). */
    fun setEmphasis(id: String, propId: String?, optionIds: List<String>) {
        viewModelScope.launch { dataSources.setEmphasis(id, propId, optionIds) }
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
