package com.ysdoing.notioncal.feature.dday

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ysdoing.notioncal.core.data.CalendarEvent
import com.ysdoing.notioncal.core.data.EventRepository
import com.ysdoing.notioncal.core.data.dday.DdayMode
import com.ysdoing.notioncal.core.data.dday.DdayRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** DD-01 디데이 화면: Room만 읽는다(고정 목록·일정 제목 검색). 고정은 앱 상태라 노션에 쓰지 않는다. */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class DdayViewModel @Inject constructor(
    private val ddays: DdayRepository,
    private val events: EventRepository,
) : ViewModel() {
    private val query = MutableStateFlow("")

    private val results = query.debounce(SEARCH_DEBOUNCE_MS).flatMapLatest { q ->
        flow<List<CalendarEvent>> { emit(events.search(q, ddays.today())) }
    }

    val state: StateFlow<DdayUiState> = combine(
        ddays.observe(ddays.today()),
        ddays.removedNotice,
        query,
        results,
        ddays.observePinnedIds(),
    ) { pins, notice, q, found, ids -> DdayUiState(pins, notice, q, found, ids) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DdayUiState())

    fun setQuery(text: String) {
        query.value = text
    }

    fun pin(pageId: String) {
        viewModelScope.launch { ddays.pin(pageId) }
    }

    fun unpin(pageId: String) {
        viewModelScope.launch { ddays.unpin(pageId) }
    }

    fun setMode(pageId: String, mode: DdayMode) {
        viewModelScope.launch { ddays.setMode(pageId, mode) }
    }

    fun dismissNotice() {
        viewModelScope.launch { ddays.dismissRemovedNotice() }
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 200L
    }
}
