package com.ysdoing.notioncal.feature.quicksheet

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ysdoing.notioncal.core.data.APP_ZONE
import com.ysdoing.notioncal.core.data.CalendarEvent
import com.ysdoing.notioncal.core.data.DataSourceRepository
import com.ysdoing.notioncal.core.data.DateValue
import com.ysdoing.notioncal.core.data.EditableEvent
import com.ysdoing.notioncal.core.data.EditableEventRepository
import com.ysdoing.notioncal.core.data.EventChange
import com.ysdoing.notioncal.core.data.EventRepository
import com.ysdoing.notioncal.core.data.EventWriter
import com.ysdoing.notioncal.core.data.SettingsRepository
import com.ysdoing.notioncal.core.data.db.DataSourceEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import javax.inject.Inject
import com.ysdoing.notioncal.core.data.HolidayRepository

/** 시간 입력(시작·종료). 종일 일정이면 null. */
data class TimeRange(val start: LocalTime, val end: LocalTime)

/** 추가할 일정 하나(WI-02·WI-07). [endDate]는 기간 일정의 마지막 날(포함). */
data class NewEvent(
    val title: String,
    val date: LocalDate,
    val time: TimeRange?,
    val dataSourceId: String,
    val endDate: LocalDate? = null,
)

/** 날짜 + 선택적 시간을 저장용 값으로 바꾼다. 시간은 Asia/Seoul 기준(NS-07). */
fun dateValueOf(date: LocalDate, time: TimeRange?, endDate: LocalDate? = null): DateValue {
    val lastDay = endDate?.takeIf { it.isAfter(date) }
    if (time == null) return DateValue.allDay(date, lastDay)
    val start = date.atTime(time.start).atZone(APP_ZONE).toInstant()
    var end = (lastDay ?: date).atTime(time.end).atZone(APP_ZONE).toInstant()
    if (!end.isAfter(start)) end = start.plusSeconds(3600)
    return DateValue(date.toString(), startTime = start.toEpochMilli(), endTime = end.toEpochMilli())
}

/**
 * 퀵 시트·앱 날짜 시트 공용(WI-01·02·03·05, MV-04). Room만 읽고, 쓰기는 [EventWriter]로만 한다.
 * 노션 전송은 아웃박스 작업이 뒤따른다(PRD §7-1).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class QuickSheetViewModel @Inject constructor(
    private val events: EventRepository,
    private val editable: EditableEventRepository,
    dataSources: DataSourceRepository,
    settings: SettingsRepository,
    private val writer: EventWriter,
    private val holidays: HolidayRepository,
) : ViewModel() {
    private val date = MutableStateFlow<LocalDate?>(null)

    fun showDay(day: LocalDate) {
        date.value = day
    }

    /** 그날 일정(여러 날 일정 포함). */
    val dayEvents: StateFlow<List<CalendarEvent>> = date
        .flatMapLatest { d -> if (d == null) flowOf(emptyList()) else events.observeRange(d, d) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** EX-02 그날 공휴일 이름(없거나 표시를 끄면 null). */
    val dayHoliday: StateFlow<String?> = date
        .flatMapLatest { d -> if (d == null) flowOf(emptyMap()) else holidays.observe(d, d) }
        .map { it.values.firstOrNull() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 추가할 수 있는 DB(앱에서 켠 일정 DB). 습관 기록은 습관 화면·위젯에서만 만든다. */
    val sources: StateFlow<List<DataSourceEntity>> = dataSources.observeCalendarSources()
        .map { list -> list.filter { it.enabled } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 기본 DB: 설정값이 켜져 있으면 그것, 아니면 첫 번째 켠 DB. */
    val defaultSourceId: StateFlow<String?> = combine(settings.settings, sources) { s, list ->
        s.defaultDataSourceId?.takeIf { id -> list.any { it.dataSourceId == id } } ?: list.firstOrNull()?.dataSourceId
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun observeEvent(pageId: String): Flow<EditableEvent?> = editable.observe(pageId)

    /** WI-02 */
    fun add(event: NewEvent, onAdded: () -> Unit = {}) {
        if (event.title.isBlank()) return
        viewModelScope.launch {
            writer.create(event.dataSourceId, event.title, dateValueOf(event.date, event.time, event.endDate))
            onAdded()
        }
    }

    /** NS-10 반복 일정: 펼친 날짜마다 따로 만든다(아웃박스가 순서대로 보낸다). 모두 쌓은 뒤 [onAdded]. */
    fun addAll(events: List<NewEvent>, onAdded: () -> Unit = {}) {
        val valid = events.filter { it.title.isNotBlank() }
        if (valid.isEmpty()) return
        viewModelScope.launch {
            valid.forEach { event -> writer.create(event.dataSourceId, event.title, dateValueOf(event.date, event.time, event.endDate)) }
            onAdded()
        }
    }

    /** WI-03 */
    fun setDone(pageId: String, done: Boolean) {
        viewModelScope.launch { writer.setDone(pageId, done) }
    }

    /** WI-05 */
    fun save(pageId: String, change: EventChange, onSaved: () -> Unit = {}) {
        viewModelScope.launch {
            writer.update(pageId, change)
            onSaved()
        }
    }

    fun trash(pageId: String, onTrashed: () -> Unit = {}) {
        viewModelScope.launch {
            writer.trash(pageId)
            onTrashed()
        }
    }
}
