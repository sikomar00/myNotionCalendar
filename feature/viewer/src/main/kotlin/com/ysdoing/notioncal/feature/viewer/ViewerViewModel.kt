package com.ysdoing.notioncal.feature.viewer

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ysdoing.notioncal.core.data.view.ViewRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** 뷰어가 보여 줄 보기(경로 인자 `viewId`)를 Room에서 읽는다. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ViewerViewModel @Inject constructor(savedState: SavedStateHandle, views: ViewRepository) : ViewModel() {
    val state: StateFlow<ViewerState> = savedState.getStateFlow(VIEW_ID, "")
        .flatMapLatest { views.observe(it) }
        .map { board -> if (board == null) ViewerState.Missing else ViewerState.Ready(board) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ViewerState.Loading)

    companion object {
        const val VIEW_ID = "viewId"
    }
}
