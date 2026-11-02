package com.ysdoing.notioncal.settings

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ysdoing.notioncal.core.data.db.ViewSnapshotEntity
import com.ysdoing.notioncal.core.data.view.ViewRepository
import com.ysdoing.notioncal.core.notion.NotionError
import com.ysdoing.notioncal.core.sync.ViewListResult
import com.ysdoing.notioncal.core.sync.ViewOption
import com.ysdoing.notioncal.core.sync.ViewRegistration
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

/** VW-04 '보기 추가' 화면 상태. [busyId]는 등록 중인 보기. */
data class AddViewUi(val loading: Boolean = false, val result: ViewListResult? = null, val busyId: String? = null)

@HiltViewModel
class AddViewViewModel @Inject constructor(
    private val registration: ViewRegistration,
    private val repository: ViewRepository,
) : ViewModel() {
    private val _ui = MutableStateFlow(AddViewUi())
    val ui: StateFlow<AddViewUi> = _ui.asStateFlow()

    val registered = repository.observeRegistered()

    private companion object {
        const val TAG = "NotionCalViews"
    }

    fun load(link: String) {
        _ui.update { it.copy(loading = true, result = null) }
        viewModelScope.launch {
            val result = try {
                registration.list(link)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 예상 밖 실패도 화면에 오류로 남긴다(조용히 사라지지 않게). 링크·제목은 로그에 쓰지 않는다.
                Log.w(TAG, "보기 목록 실패: ${e.javaClass.simpleName}")
                ViewListResult.Failed(NotionError.Server(null))
            }
            _ui.update { it.copy(loading = false, result = result) }
        }
    }

    fun register(dataSourceId: String, view: ViewOption) {
        _ui.update { it.copy(busyId = view.viewId) }
        viewModelScope.launch {
            val done = try {
                registration.register(dataSourceId, view)
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "보기 등록 실패: ${e.javaClass.simpleName}")
                false
            }
            _ui.update { state ->
                val ok = state.result as? ViewListResult.Ok
                state.copy(busyId = null, result = ok?.copy(views = ok.views.map { v -> if (v.viewId == view.viewId) v.copy(registered = done) else v }) ?: state.result)
            }
        }
    }

    fun remove(view: ViewSnapshotEntity) {
        viewModelScope.launch {
            repository.remove(view.viewId)
            _ui.update { state ->
                val ok = state.result as? ViewListResult.Ok
                state.copy(result = ok?.copy(views = ok.views.map { v -> if (v.viewId == view.viewId) v.copy(registered = false) else v }) ?: state.result)
            }
        }
    }
}
