package com.ysdoing.notioncal.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ysdoing.notioncal.core.data.NotionColors
import com.ysdoing.notioncal.core.data.SourceKind
import com.ysdoing.notioncal.core.notion.NotionError
import com.ysdoing.notioncal.core.sync.ConnectionService
import com.ysdoing.notioncal.core.sync.DatabaseInspection
import com.ysdoing.notioncal.core.sync.HabitChoice
import com.ysdoing.notioncal.core.sync.HabitInspectResult
import com.ysdoing.notioncal.core.sync.HabitInspection
import com.ysdoing.notioncal.core.sync.HabitLogChoice
import com.ysdoing.notioncal.core.sync.InspectResult
import com.ysdoing.notioncal.core.sync.MappingChoice
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 등록 종류. 📅 일정 또는 ✅ 습관 기록(+ 짝인 🌱 습관, HB-01). */
enum class AddKind { CALENDAR, HABIT_LOG }

/** HB-01 짝인 🌱 습관 조회 상태. */
data class HabitPart(
    val loading: Boolean = false,
    val inspection: HabitInspection? = null,
    val choice: HabitChoice? = null,
    val error: NotionError? = null,
    val noRelation: Boolean = false,
)

data class AddDatabaseUi(
    val loading: Boolean = false,
    val inspection: DatabaseInspection? = null,
    val choice: MappingChoice? = null,
    val colorHex: String = NotionColors.palette.first { it.first == "blue" }.second,
    val error: NotionError? = null,
    val invalidLink: Boolean = false,
    val registered: Boolean = false,
    val kind: AddKind = AddKind.CALENDAR,
    val logChoice: HabitLogChoice? = null,
    val habit: HabitPart = HabitPart(),
) {
    /** 등록 버튼을 누를 수 있는지. */
    val canRegister: Boolean
        get() = when (kind) {
            AddKind.CALENDAR -> choice != null
            AddKind.HABIT_LOG -> logChoice != null && habit.inspection != null && habit.choice != null
        }
}

/** NS-02 등록: 링크 → data source 조회 → 추천 매핑 → 수정 → 저장. HB-01 습관 기록이면 relation이 가리키는 🌱 습관도 함께. */
@HiltViewModel
class AddDatabaseViewModel @Inject constructor(private val connection: ConnectionService) : ViewModel() {
    private val _ui = MutableStateFlow(AddDatabaseUi())
    val ui: StateFlow<AddDatabaseUi> = _ui.asStateFlow()

    private var lastLink = ""
    private var habitJob: Job? = null

    fun inspect(link: String, dataSourceId: String? = null) {
        lastLink = link
        _ui.update { it.copy(loading = true, error = null, invalidLink = false) }
        viewModelScope.launch {
            when (val result = connection.inspect(link, dataSourceId)) {
                is InspectResult.Ok -> {
                    val inspection = result.inspection
                    // 이미 습관 기록으로 등록한 DB(다시 연결)는 습관 기록으로 연다.
                    val kind = if (inspection.existingKind == SourceKind.HABIT_LOG && inspection.logSuggestion != null) AddKind.HABIT_LOG else AddKind.CALENDAR
                    _ui.update {
                        // 이미 등록한 DB(다시 연결)면 그 색을 유지한다.
                        it.copy(
                            loading = false,
                            inspection = inspection,
                            choice = inspection.suggestion,
                            colorHex = inspection.existingColorHex ?: it.colorHex,
                            kind = kind,
                            logChoice = inspection.logSuggestion,
                            habit = HabitPart(),
                        )
                    }
                    if (kind == AddKind.HABIT_LOG) loadHabits()
                }
                InspectResult.InvalidLink -> _ui.update { it.copy(loading = false, invalidLink = true, inspection = null) }
                is InspectResult.Failed -> _ui.update { it.copy(loading = false, error = result.error, inspection = null) }
            }
        }
    }

    fun selectDataSource(id: String) = inspect(lastLink, id)

    fun updateChoice(transform: (MappingChoice) -> MappingChoice) {
        _ui.update { state -> state.copy(choice = state.choice?.let(transform)) }
    }

    fun setKind(kind: AddKind) {
        _ui.update { it.copy(kind = kind) }
        if (kind == AddKind.HABIT_LOG && _ui.value.habit.inspection == null) loadHabits()
    }

    fun updateLogChoice(transform: (HabitLogChoice) -> HabitLogChoice) {
        val before = _ui.value.logChoice
        _ui.update { state -> state.copy(logChoice = state.logChoice?.let(transform)) }
        // 습관 연결 속성을 바꾸면 가리키는 🌱 습관을 다시 읽는다.
        if (before?.linkPropId != _ui.value.logChoice?.linkPropId) loadHabits()
    }

    fun updateHabitChoice(transform: (HabitChoice) -> HabitChoice) {
        _ui.update { state -> state.copy(habit = state.habit.copy(choice = state.habit.choice?.let(transform))) }
    }

    private fun loadHabits() {
        val state = _ui.value
        val inspection = state.inspection ?: return
        val link = state.logChoice?.linkPropId ?: return
        habitJob?.cancel()
        _ui.update { it.copy(habit = HabitPart(loading = true)) }
        habitJob = viewModelScope.launch {
            val part = when (val result = connection.inspectHabits(inspection, link)) {
                is HabitInspectResult.Ok -> HabitPart(inspection = result.inspection, choice = result.inspection.suggestion)
                HabitInspectResult.NoRelationTarget -> HabitPart(noRelation = true)
                is HabitInspectResult.Failed -> HabitPart(error = result.error)
            }
            _ui.update { it.copy(habit = part) }
        }
    }

    fun setColor(hex: String) = _ui.update { it.copy(colorHex = hex) }

    fun register() {
        val state = _ui.value
        val inspection = state.inspection ?: return
        viewModelScope.launch {
            when (state.kind) {
                AddKind.CALENDAR -> connection.register(inspection, state.choice ?: return@launch, state.colorHex)
                AddKind.HABIT_LOG -> connection.registerHabit(
                    inspection,
                    state.logChoice ?: return@launch,
                    state.habit.inspection ?: return@launch,
                    state.habit.choice ?: return@launch,
                    state.colorHex,
                )
            }
            _ui.update { it.copy(registered = true) }
        }
    }
}
