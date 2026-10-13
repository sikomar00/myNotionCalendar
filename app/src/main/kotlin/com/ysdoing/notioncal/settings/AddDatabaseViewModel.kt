package com.ysdoing.notioncal.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ysdoing.notioncal.core.data.NotionColors
import com.ysdoing.notioncal.core.notion.NotionError
import com.ysdoing.notioncal.core.sync.ConnectionService
import com.ysdoing.notioncal.core.sync.DatabaseInspection
import com.ysdoing.notioncal.core.sync.InspectResult
import com.ysdoing.notioncal.core.sync.MappingChoice
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AddDatabaseUi(
    val loading: Boolean = false,
    val inspection: DatabaseInspection? = null,
    val choice: MappingChoice? = null,
    val colorHex: String = NotionColors.palette.first { it.first == "blue" }.second,
    val error: NotionError? = null,
    val invalidLink: Boolean = false,
    val registered: Boolean = false,
)

/** NS-02 등록: 링크 → data source 조회 → 추천 매핑 → 수정 → 저장. */
@HiltViewModel
class AddDatabaseViewModel @Inject constructor(private val connection: ConnectionService) : ViewModel() {
    private val _ui = MutableStateFlow(AddDatabaseUi())
    val ui: StateFlow<AddDatabaseUi> = _ui.asStateFlow()

    private var lastLink = ""

    fun inspect(link: String, dataSourceId: String? = null) {
        lastLink = link
        _ui.update { it.copy(loading = true, error = null, invalidLink = false) }
        viewModelScope.launch {
            when (val result = connection.inspect(link, dataSourceId)) {
                is InspectResult.Ok -> _ui.update {
                    it.copy(loading = false, inspection = result.inspection, choice = result.inspection.suggestion)
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

    fun setColor(hex: String) = _ui.update { it.copy(colorHex = hex) }

    fun register() {
        val state = _ui.value
        val inspection = state.inspection ?: return
        val choice = state.choice ?: return
        viewModelScope.launch {
            connection.register(inspection, choice, state.colorHex)
            _ui.update { it.copy(registered = true) }
        }
    }
}
