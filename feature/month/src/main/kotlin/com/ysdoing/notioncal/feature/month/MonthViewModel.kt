package com.ysdoing.notioncal.feature.month

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ysdoing.notioncal.core.data.APP_ZONE
import com.ysdoing.notioncal.core.data.AppHealth
import com.ysdoing.notioncal.core.data.AppSettings
import com.ysdoing.notioncal.core.data.CalendarEvent
import com.ysdoing.notioncal.core.data.ConflictRepository
import com.ysdoing.notioncal.core.data.DataSourceRepository
import com.ysdoing.notioncal.core.data.EventRepository
import com.ysdoing.notioncal.core.data.SettingsRepository
import com.ysdoing.notioncal.core.data.SyncRequester
import com.ysdoing.notioncal.core.data.SyncStatusRepository
import com.ysdoing.notioncal.core.data.SyncTrigger
import com.ysdoing.notioncal.core.data.calendar.GRID_DAYS
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

@HiltViewModel
class MonthViewModel @Inject constructor(
    private val eventRepository: EventRepository,
    settingsRepository: SettingsRepository,
    private val syncRequester: SyncRequester,
    syncStatus: SyncStatusRepository,
    dataSources: DataSourceRepository,
    private val conflicts: ConflictRepository,
    private val health: AppHealth,
    private val clock: Clock,
) : ViewModel() {

    private val _today = MutableStateFlow(today())

    /** 앱이 다시 보일 때마다 다시 계산한다(PRD §7-4, 자정을 넘겨 열어 둔 경우). */
    val today: StateFlow<LocalDate> = _today.asStateFlow()

    val settings: StateFlow<AppSettings?> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

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

    private val monthFlows = HashMap<Pair<YearMonth, DayOfWeek>, StateFlow<List<CalendarEvent>?>>()

    /** 그리드 42일 범위의 일정. Room만 읽으므로 오프라인·인증 오류와 무관하게 즉시 나온다(PF-02). */
    fun eventsFor(month: YearMonth, weekStart: DayOfWeek): StateFlow<List<CalendarEvent>?> =
        monthFlows.getOrPut(month to weekStart) {
            val from = gridStart(month, weekStart)
            eventRepository.observeRange(from, from.plusDays(GRID_DAYS - 1))
                .catch { error ->
                    health.report(error)
                    emit(emptyList())
                }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
        }

    fun onResume() {
        _today.value = today()
    }

    fun refresh() = syncRequester.request(SyncTrigger.PULL_TO_REFRESH)

    fun acknowledgeConflicts() {
        viewModelScope.launch { conflicts.markAllSeen() }
    }

    private fun today(): LocalDate = LocalDate.now(clock.withZone(APP_ZONE))
}
