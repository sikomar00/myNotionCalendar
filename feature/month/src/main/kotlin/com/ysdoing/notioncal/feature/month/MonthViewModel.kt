package com.ysdoing.notioncal.feature.month

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ysdoing.notioncal.core.data.APP_ZONE
import com.ysdoing.notioncal.core.data.AppHealth
import com.ysdoing.notioncal.core.data.AppSettings
import com.ysdoing.notioncal.core.data.CalendarEvent
import com.ysdoing.notioncal.core.data.ConflictRepository
import com.ysdoing.notioncal.core.data.DataSourceRepository
import com.ysdoing.notioncal.core.data.EventChange
import com.ysdoing.notioncal.core.data.EventRepository
import com.ysdoing.notioncal.core.data.EventWriter
import com.ysdoing.notioncal.core.data.SettingsRepository
import com.ysdoing.notioncal.core.data.SyncRequester
import com.ysdoing.notioncal.core.data.SyncStatusRepository
import com.ysdoing.notioncal.core.data.SyncTrigger
import com.ysdoing.notioncal.core.data.calendar.CalendarView
import com.ysdoing.notioncal.core.data.calendar.CalendarZoom
import com.ysdoing.notioncal.core.data.calendar.EventFilter
import com.ysdoing.notioncal.core.data.ColorOption
import com.ysdoing.notioncal.core.data.parseColorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlin.math.roundToInt
import com.ysdoing.notioncal.core.data.calendar.GRID_DAYS
import com.ysdoing.notioncal.core.data.dateValue
import com.ysdoing.notioncal.core.data.db.ConflictEntity
import com.ysdoing.notioncal.core.data.calendar.gridStart
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject

/** 상단 상태줄·배너(PF-05 최소 형태). */
data class SyncHeader(
    val syncing: Boolean = false,
    val lastSuccessAt: Long? = null,
    /** 마지막 동기화 오류 종류 이름(NotionError 클래스 이름). */
    val lastError: String? = null,
    val hasSources: Boolean = true,
    /** 아직 노션에 보내지 않은 쓰기 수(NS-04). */
    val pendingWrites: Int = 0,
    /** 확인하지 않은 충돌·반영 실패(NS-05). */
    val writeIssues: List<ConflictEntity> = emptyList(),
)

/** MV-09 필터 시트의 DB 한 줄. [options]는 색 기준 select 값(색 속성이 없으면 비어 있다). */
data class FilterSource(
    val id: String,
    val name: String,
    val colorHex: String,
    val hasColorProp: Boolean,
    val options: List<ColorOption>,
)

@HiltViewModel
class MonthViewModel @Inject constructor(
    private val eventRepository: EventRepository,
    private val settingsRepository: SettingsRepository,
    private val syncRequester: SyncRequester,
    syncStatus: SyncStatusRepository,
    dataSources: DataSourceRepository,
    private val conflicts: ConflictRepository,
    private val health: AppHealth,
    private val writer: EventWriter,
    private val clock: Clock,
) : ViewModel() {

    private val _today = MutableStateFlow(today())

    /** 앱이 다시 보일 때마다 다시 계산한다(PRD §7-4, 자정을 넘겨 열어 둔 경우). */
    val today: StateFlow<LocalDate> = _today.asStateFlow()

    val settings: StateFlow<AppSettings?> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val filter = settingsRepository.settings.map { it.filter }.distinctUntilChanged()

    /** MV-09 필터 시트에 보일 켜 둔 DB와 색 기준 값. */
    val filterSources: StateFlow<List<FilterSource>> = dataSources.observeAll()
        .map { sources ->
            sources.filter { it.enabled }.map { source ->
                FilterSource(
                    id = source.dataSourceId,
                    name = source.name,
                    colorHex = source.colorHex,
                    hasColorProp = source.colorPropId != null,
                    options = if (source.colorPropId != null) parseColorOptions(source.colorOptions) else emptyList(),
                )
            }
        }
        .flowOn(Dispatchers.Default)
        .catch { error ->
            health.report(error)
            emit(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val writeState = combine(conflicts.pendingCount, conflicts.unseen) { pending, issues -> pending to issues }

    val header: StateFlow<SyncHeader> = combine(
        syncRequester.isSyncing,
        syncStatus.latest,
        syncStatus.latestSuccess,
        dataSources.observeAll(),
        writeState,
    ) { syncing, latest, success, sources, (pending, issues) ->
        SyncHeader(
            syncing = syncing,
            lastSuccessAt = success?.at,
            lastError = latest?.takeIf { it.result != "OK" }?.error,
            hasSources = sources.isNotEmpty(),
            pendingWrites = pending,
            writeIssues = issues,
        )
    }.catch { error ->
        // DB를 열지 못하면 앱이 죽는 대신 진단 화면으로 간다(PF-10).
        health.report(error)
        emit(SyncHeader())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SyncHeader())

    private val rangeFlows = HashMap<Pair<LocalDate, LocalDate>, StateFlow<List<CalendarEvent>?>>()

    /** 그리드 42일 범위의 일정. Room만 읽으므로 오프라인·인증 오류와 무관하게 즉시 나온다(PF-02). */
    fun eventsFor(month: YearMonth, weekStart: DayOfWeek): StateFlow<List<CalendarEvent>?> {
        val from = gridStart(month, weekStart)
        return eventsBetween(from, from.plusDays(GRID_DAYS - 1))
    }

    /**
     * [from]~[to]와 겹치는 일정(MV-06 주·3일·목록 보기도 같은 길로 읽는다). 같은 범위는 흐름을 함께 쓴다.
     * MV-09 앱 필터를 여기서 한 번만 건다(모든 보기에 같이 적용, 위젯과는 무관).
     */
    fun eventsBetween(from: LocalDate, to: LocalDate): StateFlow<List<CalendarEvent>?> =
        rangeFlows.getOrPut(from to to) {
            combine(eventRepository.observeRange(from, to), filter) { events, f -> f.apply(events) }
                .flowOn(Dispatchers.Default)
                .catch { error ->
                    health.report(error)
                    emit(emptyList())
                }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
        }

    /** MV-08 확대를 마친 배율을 저장한다(재실행 후에도 유지). */
    fun saveZoom(percent: Float) {
        viewModelScope.launch { settingsRepository.setZoomPercent(CalendarZoom.clamp(percent).roundToInt()) }
    }

    /** MV-09 필터 저장. */
    fun setFilter(filter: EventFilter) {
        viewModelScope.launch { settingsRepository.setFilter(filter) }
    }

    /** MV-06 보기 바꾸기(마지막 보기를 기억한다). */
    fun setView(view: CalendarView) {
        viewModelScope.launch { settingsRepository.setCalendarView(view) }
    }

    fun onResume() {
        _today.value = today()
    }

    fun refresh() = syncRequester.request(SyncTrigger.PULL_TO_REFRESH)

    /** MV-07: 끌어 놓은 일정을 [days]일 옮긴다. 시간·기간 길이는 그대로고, 노션에는 아웃박스로 보낸다. */
    fun move(event: CalendarEvent, days: Long) {
        if (days == 0L) return
        viewModelScope.launch { writer.update(event.pageId, EventChange(date = event.dateValue().shiftedDays(days))) }
    }

    fun acknowledgeConflicts() {
        viewModelScope.launch { conflicts.markAllSeen() }
    }

    private fun today(): LocalDate = LocalDate.now(clock.withZone(APP_ZONE))
}
