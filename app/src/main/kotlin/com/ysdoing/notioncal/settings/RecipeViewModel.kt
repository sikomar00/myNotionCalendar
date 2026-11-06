package com.ysdoing.notioncal.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ysdoing.notioncal.core.data.IoDispatcher
import com.ysdoing.notioncal.core.data.SettingsRepository
import com.ysdoing.notioncal.core.data.recipe.Recipe
import com.ysdoing.notioncal.core.data.recipe.RecipeClaims
import com.ysdoing.notioncal.core.data.recipe.RecipeDraft
import com.ysdoing.notioncal.core.data.recipe.RecipeView
import com.ysdoing.notioncal.core.data.view.ViewBoard
import com.ysdoing.notioncal.core.data.view.ViewRepository
import com.ysdoing.notioncal.core.data.view.WidgetLayout
import com.ysdoing.notioncal.core.data.view.formColumns
import com.ysdoing.notioncal.widget.RecipePin
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import javax.inject.Inject

/** 레시피 미리보기가 그리는 데 쓰는 값: 보기 이름, 구성이 읽는 보기의 실제 행, 집계 타일의 주 시작 요일. */
internal data class RecipePreviewData(val names: Map<String, String> = emptyMap(), val boards: Map<String, ViewBoard?> = emptyMap(), val weekStart: DayOfWeek = DayOfWeek.SUNDAY)

/**
 * Phase 11h 레시피 화면의 상태. 선택은 순수한 [RecipeDraft]가 들고 여기서는 등록한 보기를 읽어 채우고(처음·보기를 지웠을 때 기본 선택으로 다시 채움),
 * 홈 화면에 놓기(핀)를 요청한다. 보기 행 읽기는 IO에서 한다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class RecipeViewModel @Inject constructor(
    private val views: ViewRepository,
    private val claims: RecipeClaims,
    settings: SettingsRepository,
    @ApplicationContext private val context: Context,
    @IoDispatcher io: CoroutineDispatcher,
) : ViewModel() {
    private val registered: StateFlow<List<RecipeView>?> = views.observeRegistered()
        .map { list -> list.map { RecipeView(it.viewId, it.name, it.viewType, it.formColumns()) } }
        .flowOn(io)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private val draft = MutableStateFlow(RecipeDraft())
    private val pinState = MutableStateFlow(PinState.IDLE)

    // 구성을 만들 때 글자를 읽고 쓰니(JSON) 메인 스레드 밖에서 한다.
    internal val ui: StateFlow<RecipeUi> = combine(registered, draft, pinState) { list, current, pin -> RecipeUi(list, current, list?.let { current.config(it) }, pin) }
        .flowOn(io)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), RecipeUi(null, RecipeDraft(), null, PinState.IDLE))

    /** 구성이 읽는 보기(칸과 타일)의 실제 행. 구성이 바뀔 때만 다시 구독한다. */
    private val boards: Flow<Map<String, ViewBoard?>> = ui.map { it.config?.let { config -> WidgetLayout.parse(config.layoutJson).boardIds() }.orEmpty() }
        .distinctUntilChanged()
        .flatMapLatest { ids -> if (ids.isEmpty()) flowOf(emptyMap()) else combine(ids.map { id -> views.observe(id).map { id to it } }) { it.toMap() } }

    internal val preview: StateFlow<RecipePreviewData> = combine(boards, registered.filterNotNull(), settings.settings.map { it.weekStart }) { rows, list, weekStart ->
        RecipePreviewData(list.associate { it.viewId to it.name }, rows, weekStart)
    }.flowOn(io).stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), RecipePreviewData())

    init {
        viewModelScope.launch { registered.filterNotNull().collect { list -> draft.update { it.repaired(list) } } }
    }

    fun selectRecipe(recipe: Recipe) = edit { it.switchTo(recipe, registered.value.orEmpty()) }

    fun selectView(viewId: String) = edit { it.selectView(viewId, registered.value.orEmpty()) }

    fun selectBoard(viewId: String) = edit { it.selectBoard(viewId) }

    fun selectAmount(propId: String) = edit { it.selectAmount(propId) }

    fun selectCategory(propId: String) = edit { it.selectCategory(propId) }

    fun selectRating(propId: String) = edit { it.selectRating(propId) }

    fun toggleCheck(propId: String) = edit { it.toggleCheck(propId) }

    fun setBudget(text: String) = edit { it.withBudget(text) }

    /** 홈 화면에 추가: 구성을 보관하고 런처에 핀을 요청한다. 이미 요청 중이거나 맞는 구성이 아니면 아무것도 하지 않는다. */
    fun pin() {
        val config = ui.value.config ?: return
        if (pinState.value == PinState.BUSY) return
        pinState.value = PinState.BUSY
        viewModelScope.launch {
            pinState.value = when (RecipePin.request(context, claims, config)) {
                RecipePin.Result.REQUESTED -> PinState.REQUESTED
                RecipePin.Result.UNSUPPORTED -> PinState.UNSUPPORTED
            }
        }
    }

    /** 선택을 고치면 앞선 요청 결과 안내는 지운다. */
    private fun edit(change: (RecipeDraft) -> RecipeDraft) {
        pinState.value = PinState.IDLE
        draft.update(change)
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
