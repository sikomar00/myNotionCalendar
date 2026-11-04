package com.ysdoing.notioncal.feature.quicksheet

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.ysdoing.notioncal.core.data.AppIntents
import com.ysdoing.notioncal.core.data.IoDispatcher
import com.ysdoing.notioncal.core.data.SettingsRepository
import com.ysdoing.notioncal.core.data.view.ViewColumn
import com.ysdoing.notioncal.core.data.view.ViewRepository
import com.ysdoing.notioncal.core.data.view.ViewWriter
import com.ysdoing.notioncal.core.designsystem.NotionCalTheme
import com.ysdoing.notioncal.core.designsystem.RadioRow
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import javax.inject.Inject

/** 선택 시트가 보여 줄 것: 읽는 중 / 보기·행·속성이 없음 / 행 제목 + 속성 + 지금 고른 옵션. */
sealed interface ViewChoiceState {
    data object Loading : ViewChoiceState
    data object Missing : ViewChoiceState
    data class Ready(val rowTitle: String, val column: ViewColumn, val currentId: String?) : ViewChoiceState
}

/**
 * Phase 11c IX-02 선택지가 4개 이상인 select를 위젯에서 눌렀을 때 뜨는 작은 선택 시트의 내용. 옵션을 하나 누르면 바로 고르고
 * (select는 '비우기'도 있다, 상태는 노션 규칙상 비울 수 없다) 닫는다.
 */
@Composable
fun ViewChoiceContent(rowTitle: String, column: ViewColumn, currentId: String?, onPick: (String?) -> Unit, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(rowTitle.ifBlank { stringResource(R.string.vre_untitled) }, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(column.name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        column.options.forEach { option -> RadioRow(option.name, option.id == currentId) { onPick(option.id) } }
        if (column.type == "select") TextButton(onClick = { onPick(null) }) { Text(stringResource(R.string.vre_clear_option)) }
        TextButton(onClick = onCancel) { Text(stringResource(R.string.qs_cancel)) }
    }
}

/** 선택 시트의 상태와 고르기. 쓰기 큐에 4초 지연으로 쌓는다(되돌릴 수 있다). 화면이 닫혀도 쌓는 일은 끝까지 한다. */
@HiltViewModel
class ViewChoiceViewModel @Inject constructor(
    private val views: ViewRepository,
    private val writer: ViewWriter,
    @IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {
    fun state(viewId: String, pageId: String, propId: String): Flow<ViewChoiceState> = views.observe(viewId).map { board ->
        val row = board?.rows?.firstOrNull { it.pageId == pageId }
        val column = board?.columnInfo?.firstOrNull { it.id == propId && it.options.isNotEmpty() }
        if (board == null || row == null || column == null) {
            ViewChoiceState.Missing
        } else {
            ViewChoiceState.Ready(row.cells.firstOrNull().orEmpty(), column, (row.typed[propId] as? JsonPrimitive)?.contentOrNull)
        }
    }.flowOn(io)

    suspend fun pick(viewId: String, pageId: String, propId: String, optionId: String?): Boolean = viewModelScope.async {
        writer.set(viewId, pageId, propId, optionId?.let(::JsonPrimitive) ?: JsonNull, ViewWriter.UNDO_WINDOW_MS)
    }.await()
}

/** Phase 11c IX-02 위젯 위에 뜨는 반투명 선택 시트. 같은 앱의 PendingIntent로만 열므로 exported=false. */
@AndroidEntryPoint
class ViewChoiceActivity : ComponentActivity() {
    @Inject
    lateinit var settings: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val viewId = intent.getStringExtra(AppIntents.EXTRA_VIEW_ID)
        val pageId = intent.getStringExtra(AppIntents.EXTRA_PAGE_ID)
        val propId = intent.getStringExtra(AppIntents.EXTRA_PROP_ID)
        if (viewId == null || pageId == null || propId == null) {
            finish()
            return
        }
        setContent {
            val dynamicColor by remember { settings.settings.map { it.dynamicColor } }.collectAsStateWithLifecycle(false)
            NotionCalTheme(dynamicColor) {
                val viewModel: ViewChoiceViewModel = hiltViewModel()
                val state by remember(viewId, pageId, propId) { viewModel.state(viewId, pageId, propId) }.collectAsStateWithLifecycle(ViewChoiceState.Loading)
                val scope = rememberCoroutineScope()
                BackHandler(onBack = ::finish)
                when (val current = state) {
                    ViewChoiceState.Loading -> Unit
                    ViewChoiceState.Missing -> LaunchedEffect(Unit) { finish() }
                    is ViewChoiceState.Ready -> Box(
                        Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = ::finish),
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        Surface(
                            modifier = Modifier.fillMaxWidth().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { },
                            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                            tonalElevation = 6.dp,
                        ) {
                            ViewChoiceContent(
                                current.rowTitle, current.column, current.currentId,
                                onPick = { id -> scope.launch { viewModel.pick(viewId, pageId, propId, id); finish() } },
                                onCancel = ::finish,
                                modifier = Modifier.navigationBarsPadding().heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.6f).dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
