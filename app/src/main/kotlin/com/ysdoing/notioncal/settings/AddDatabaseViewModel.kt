package com.ysdoing.notioncal.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ysdoing.notioncal.core.data.NotionColors
import com.ysdoing.notioncal.core.data.SourceKind
import com.ysdoing.notioncal.core.notion.NotionError
import com.ysdoing.notioncal.core.data.ledger.LedgerRules
import com.ysdoing.notioncal.core.sync.CompanionInspection
import com.ysdoing.notioncal.core.sync.CompanionResult
import com.ysdoing.notioncal.core.sync.ConnectionService
import com.ysdoing.notioncal.core.sync.LedgerChoice
import com.ysdoing.notioncal.core.sync.DatabaseInspection
import com.ysdoing.notioncal.core.sync.HabitChoice
import com.ysdoing.notioncal.core.sync.HabitInspectResult
import com.ysdoing.notioncal.core.sync.HabitInspection
import com.ysdoing.notioncal.core.sync.HabitLogChoice
import com.ysdoing.notioncal.core.sync.InspectResult
import com.ysdoing.notioncal.core.sync.SharedDatabasesResult
import com.ysdoing.notioncal.core.sync.SharedDatabase
import com.ysdoing.notioncal.core.sync.MappingChoice
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 등록 종류. 📅 일정, ✅ 습관 기록(+ 짝인 🌱 습관, HB-01), 💰 거래내역(+ 예산·카테고리 마스터, LD-01). */
enum class AddKind { CALENDAR, HABIT_LOG, LEDGER }

/**
 * [AddDatabaseUi.kindLost]의 판정: 이미 등록한 DB를 다른 종류로 다시 등록하려 한다(등록 화면에서 고를 수 없는 🌱 습관·예산·카테고리 포함).
 * 그대로 등록하면 그 DB의 종류가 바뀌고 짝(습관·예산·카테고리)을 잃는다. 새로 등록하는 DB([existing]이 null)는 막지 않는다.
 */
internal fun kindLost(kind: AddKind, existing: SourceKind?): Boolean = existing != null && existing.addKind() != kind

/** 등록 화면 종류로 바꾼 것. 🌱 습관·예산·카테고리는 짝 DB와 함께만 등록하므로 고를 종류가 없다(null). */
private fun SourceKind.addKind(): AddKind? = when (this) {
    SourceKind.CALENDAR -> AddKind.CALENDAR
    SourceKind.HABIT_LOG -> AddKind.HABIT_LOG
    SourceKind.LEDGER -> AddKind.LEDGER
    SourceKind.HABIT, SourceKind.BUDGET, SourceKind.CATEGORY -> null
}

/** 예산 그룹 고르기: 다시 연결이면 사용자가 고른 그룹(선택지에 남아 있을 때), 아니면 '전체', 없으면 첫 선택지. */
internal fun pickBudgetGroup(options: List<String>?, existing: String?): String =
    existing?.takeIf { options?.contains(it) == true }
        ?: options?.firstOrNull { it == LedgerRules.ALL_GROUP }
        ?: options?.firstOrNull() ?: LedgerRules.ALL_GROUP

/** LD-01 함께 등록할 예산·카테고리 마스터 상태. 자동으로 못 찾으면 링크를 넣는다. */
data class LedgerPart(
    val searching: Boolean = false,
    val searched: Boolean = false,
    val budget: CompanionInspection? = null,
    val category: CompanionInspection? = null,
    val budgetError: CompanionResult? = null,
    val categoryError: CompanionResult? = null,
    val budgetGroup: String = LedgerRules.ALL_GROUP,
    val spendNegative: Boolean = true,
)

/** NS-02 '노션에서 고르기' 목록 상태. [databases]가 null이면 아직 받지 않았다. */
data class SharedListUi(
    val loading: Boolean = false,
    val databases: List<SharedDatabase>? = null,
    val error: NotionError? = null,
    /** 목록이 길어 앞쪽만 받았다. */
    val truncated: Boolean = false,
)

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
    val ledgerChoice: LedgerChoice? = null,
    val ledger: LedgerPart = LedgerPart(),
    val shared: SharedListUi = SharedListUi(),
) {
    /** 이미 등록한 DB를 다른 종류로 등록하려 한다([kindLost]). 등록을 막고 이유를 보인다. */
    val kindLost: Boolean get() = kindLost(kind, inspection?.existingKind)

    /** [kindLost]의 까닭이 '그 종류로 쓸 속성이 노션에 없음'(종류 칩이 없음)인지. 아니면 사용자가 다른 종류를 골랐다. */
    val kindUnavailable: Boolean
        get() = when (inspection?.existingKind) {
            SourceKind.HABIT_LOG -> inspection.logSuggestion == null
            SourceKind.LEDGER -> inspection.ledgerSuggestion == null
            SourceKind.HABIT, SourceKind.BUDGET, SourceKind.CATEGORY -> true
            else -> false
        }

    /** 등록 버튼을 누를 수 있는지. */
    val canRegister: Boolean
        get() = !kindLost && when (kind) {
            AddKind.CALENDAR -> choice != null
            AddKind.HABIT_LOG -> logChoice != null && habit.inspection != null && habit.choice != null
            AddKind.LEDGER -> ledgerChoice != null && ledger.budget?.budget != null && ledger.category?.category != null
        }
}

/** NS-02 등록: 링크 → data source 조회 → 추천 매핑 → 수정 → 저장. HB-01 습관 기록이면 relation이 가리키는 🌱 습관도 함께. */
@HiltViewModel
class AddDatabaseViewModel @Inject constructor(private val connection: ConnectionService) : ViewModel() {
    private val _ui = MutableStateFlow(AddDatabaseUi())
    val ui: StateFlow<AddDatabaseUi> = _ui.asStateFlow()

    private var lastLink = ""
    private var habitJob: Job? = null
    private var inspectJob: Job? = null

    fun inspect(link: String, dataSourceId: String? = null) {
        lastLink = link
        _ui.update { it.copy(loading = true, error = null, invalidLink = false) }
        // 앞선 조회가 늦게 끝나 방금 고른 DB를 덮어쓰지 않게 취소한다(목록에서 연달아 누른 경우).
        inspectJob?.cancel()
        inspectJob = viewModelScope.launch {
            when (val result = connection.inspect(link, dataSourceId)) {
                is InspectResult.Ok -> {
                    val inspection = result.inspection
                    // 이미 습관 기록·거래내역으로 등록한 DB(다시 연결)는 그 종류로 연다.
                    val kind = when {
                        inspection.existingKind == SourceKind.HABIT_LOG && inspection.logSuggestion != null -> AddKind.HABIT_LOG
                        inspection.existingKind == SourceKind.LEDGER && inspection.ledgerSuggestion != null -> AddKind.LEDGER
                        else -> AddKind.CALENDAR
                    }
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
                            ledgerChoice = inspection.ledgerSuggestion,
                            // 다시 연결이면 사용자가 고른 지출 부호로 시작한다(예산 그룹은 예산을 찾은 뒤).
                            ledger = LedgerPart(spendNegative = inspection.existingSpendNegative ?: true),
                        )
                    }
                    if (kind == AddKind.HABIT_LOG) loadHabits()
                    if (kind == AddKind.LEDGER) findLedgerCompanions()
                }
                InspectResult.InvalidLink -> _ui.update { it.copy(loading = false, invalidLink = true, inspection = null) }
                is InspectResult.Failed -> _ui.update { it.copy(loading = false, error = result.error, inspection = null) }
            }
        }
    }

    fun selectDataSource(id: String) = inspect(lastLink, id)

    /** NS-02: 이 연결에 공유된 DB 목록을 받는다(읽기만). 받는 중이면 다시 부르지 않는다. */
    fun loadShared() {
        if (_ui.value.shared.loading) return
        _ui.update { it.copy(shared = it.shared.copy(loading = true, error = null)) }
        viewModelScope.launch {
            val shared = when (val result = connection.sharedDatabases()) {
                is SharedDatabasesResult.Ok -> SharedListUi(databases = result.databases, truncated = result.truncated)
                is SharedDatabasesResult.Failed -> SharedListUi(error = result.error)
            }
            _ui.update { it.copy(shared = shared) }
        }
    }

    /** 목록에서 고른 DB를 링크로 넣은 것처럼 조회한다(첫 인자는 부모 DB id여야 한다). */
    fun pick(database: SharedDatabase) = inspect(database.databaseId, database.dataSourceId)

    /** 잘못 골랐을 때 목록으로 돌아간다: 조회한 DB와 매핑을 비우고 받아 둔 목록·고른 색은 남긴다. */
    fun backToList() {
        inspectJob?.cancel()
        habitJob?.cancel()
        _ui.update { AddDatabaseUi(colorHex = it.colorHex, shared = it.shared) }
    }

    fun updateChoice(transform: (MappingChoice) -> MappingChoice) {
        _ui.update { state -> state.copy(choice = state.choice?.let(transform)) }
    }

    fun setKind(kind: AddKind) {
        _ui.update { it.copy(kind = kind) }
        if (kind == AddKind.HABIT_LOG && _ui.value.habit.inspection == null) loadHabits()
        if (kind == AddKind.LEDGER && !_ui.value.ledger.searched) findLedgerCompanions()
    }

    fun updateLedgerChoice(transform: (LedgerChoice) -> LedgerChoice) {
        _ui.update { state -> state.copy(ledgerChoice = state.ledgerChoice?.let(transform)) }
    }

    fun setBudgetGroup(group: String) = _ui.update { it.copy(ledger = it.ledger.copy(budgetGroup = group)) }

    fun setSpendNegative(negative: Boolean) = _ui.update { it.copy(ledger = it.ledger.copy(spendNegative = negative)) }

    /** LD-01: 거래내역과 같은 페이지의 예산·카테고리 마스터를 찾는다. */
    private fun findLedgerCompanions() {
        val inspection = _ui.value.inspection ?: return
        _ui.update { it.copy(ledger = it.ledger.copy(searching = true)) }
        viewModelScope.launch {
            val found = connection.findLedgerCompanions(inspection)
            _ui.update {
                it.copy(
                    ledger = it.ledger.copy(
                        searching = false,
                        searched = true,
                        budget = found.budget,
                        category = found.category,
                        budgetGroup = pickBudgetGroup(found.budget?.groupOptions, inspection.existingBudgetGroup),
                    ),
                )
            }
        }
    }

    /** 자동으로 못 찾았을 때 넣은 예산([budget]) 또는 카테고리 마스터 링크를 조회한다. */
    fun inspectCompanion(link: String, budget: Boolean) {
        viewModelScope.launch {
            val result = connection.inspectCompanion(link, budget)
            _ui.update { state ->
                val part = state.ledger
                val ok = (result as? CompanionResult.Ok)?.inspection
                state.copy(
                    ledger = if (budget) {
                        part.copy(budget = ok ?: part.budget, budgetError = result.takeIf { ok == null }, budgetGroup = ok?.let { pickBudgetGroup(it.groupOptions, state.inspection?.existingBudgetGroup) } ?: part.budgetGroup)
                    } else {
                        part.copy(category = ok ?: part.category, categoryError = result.takeIf { ok == null })
                    },
                )
            }
        }
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
                AddKind.LEDGER -> {
                    val part = state.ledger
                    val budget = part.budget ?: return@launch
                    val category = part.category ?: return@launch
                    connection.registerLedger(
                        inspection, state.ledgerChoice ?: return@launch,
                        budget, budget.budget ?: return@launch,
                        category, category.category ?: return@launch,
                        part.budgetGroup, part.spendNegative, state.colorHex,
                    )
                }
            }
            _ui.update { it.copy(registered = true) }
        }
    }
}
