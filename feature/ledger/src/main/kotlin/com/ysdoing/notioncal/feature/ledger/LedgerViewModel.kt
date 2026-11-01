package com.ysdoing.notioncal.feature.ledger

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ysdoing.notioncal.core.data.AppHealth
import com.ysdoing.notioncal.core.data.ledger.DuplicateGroup
import com.ysdoing.notioncal.core.data.ledger.LedgerBoard
import com.ysdoing.notioncal.core.data.ledger.LedgerRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.YearMonth
import javax.inject.Inject

/** 💰 탭(LD-03~09). Room만 읽고 쓰기는 [LedgerRepository](아웃박스)로 보낸다. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LedgerViewModel @Inject constructor(
    private val ledger: LedgerRepository,
    private val health: AppHealth,
) : ViewModel() {
    private val today = MutableStateFlow(ledger.today())
    private val month = MutableStateFlow(YearMonth.from(today.value))

    /** null이면 아직 읽는 중. */
    val board: StateFlow<LedgerBoard?> = combine(month, today) { m, t -> m to t }
        .flatMapLatest { (m, t) -> ledger.observeBoard(m, t) }
        .flowOn(Dispatchers.Default)
        .catch { error -> health.report(error) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 앱이 다시 보일 때(자정을 넘겨 열어 둔 경우) 오늘을 다시 계산한다(PRD §7-4). */
    fun onResume() {
        today.value = ledger.today()
    }

    fun shiftMonth(delta: Long) {
        month.value = month.value.plusMonths(delta)
    }

    fun resolveDuplicates(group: DuplicateGroup) = launch { ledger.resolveDuplicates(group) }

    fun keepBoth(group: DuplicateGroup) = launch { ledger.keepBoth(group) }

    fun trash(pageId: String) = launch { ledger.trash(pageId) }

    fun convertPlanned(pageId: String) = launch { ledger.convertPlannedToActual(pageId) }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}
