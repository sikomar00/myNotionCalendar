package com.ysdoing.notioncal.feature.viewer

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ysdoing.notioncal.core.data.view.ViewRepository
import com.ysdoing.notioncal.core.data.view.ViewWriter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.json.JsonElement
import javax.inject.Inject

/** 뷰어가 보여 줄 보기(경로 인자 `viewId`)를 Room에서 읽는다. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ViewerViewModel @Inject constructor(
    private val savedState: SavedStateHandle,
    views: ViewRepository,
    private val writer: ViewWriter,
) : ViewModel() {
    val state: StateFlow<ViewerState> = savedState.getStateFlow(VIEW_ID, "")
        .flatMapLatest { views.observe(it) }
        .map { board -> if (board == null) ViewerState.Missing else ViewerState.Ready(board) }
        .flowOn(Dispatchers.Default) // 행 값 JSON 풀이가 메인 스레드에서 돌지 않게
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ViewerState.Loading)

    /**
     * 시트에서 바꾼 속성을 쓰기 큐에 쌓는다(속성마다 한 건, 노션 전송은 처리기가 한다). 하나라도 못 쌓으면 false다
     * (보기나 행이 지워졌거나 값이 속성 종류에 맞지 않음). 못 쌓은 것만 빼고 나머지는 이미 쌓였다.
     */
    suspend fun save(pageId: String, changes: Map<String, JsonElement>): Boolean {
        val viewId = savedState.get<String>(VIEW_ID) ?: return false
        return changes.map { (propId, value) -> writer.set(viewId, pageId, propId, value) }.all { it }
    }

    /** 행을 휴지통으로 보낸다(ED-05). 행은 로컬에서 바로 사라지고 노션 전송은 처리기가 한다. */
    suspend fun trash(pageId: String) {
        val viewId = savedState.get<String>(VIEW_ID) ?: return
        writer.trash(viewId, pageId)
    }

    companion object {
        const val VIEW_ID = "viewId"
    }
}
