package com.ysdoing.notioncal.feature.habit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ysdoing.notioncal.core.data.AppHealth
import com.ysdoing.notioncal.core.data.habit.HabitBoard
import com.ysdoing.notioncal.core.data.habit.HabitRepository
import com.ysdoing.notioncal.core.data.habit.ToggleResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

/** HB-02·HB-04 습관 화면. Room만 읽고 체크는 [HabitRepository](아웃박스)로 보낸다. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HabitViewModel @Inject constructor(
    private val habits: HabitRepository,
    private val health: AppHealth,
) : ViewModel() {
    private val today = MutableStateFlow(habits.today())

    /** null이면 아직 읽는 중. */
    val board: StateFlow<HabitBoard?> = today
        .flatMapLatest { habits.observeBoard(it) }
        .flowOn(Dispatchers.Default)
        .catch { error -> health.report(error) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _blocked = MutableStateFlow(false)

    /** 연결 속성이 고장 나 체크를 막았다(안내 한 번). */
    val blocked: StateFlow<Boolean> = _blocked.asStateFlow()

    /** 앱이 다시 보일 때(자정을 넘겨 열어 둔 경우) 오늘을 다시 계산한다(PRD §7-4). */
    fun onResume() {
        today.value = habits.today()
    }

    fun toggle(habitId: String, date: LocalDate) {
        viewModelScope.launch {
            if (habits.toggle(habitId, date) == ToggleResult.BLOCKED) _blocked.value = true
        }
    }

    fun dismissBlocked() {
        _blocked.value = false
    }
}
