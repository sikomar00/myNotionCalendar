package com.ysdoing.notioncal.feature.quicksheet

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.ysdoing.notioncal.core.data.AppIntents
import com.ysdoing.notioncal.core.data.IoDispatcher
import com.ysdoing.notioncal.core.data.SettingsRepository
import com.ysdoing.notioncal.core.data.SyncRequester
import com.ysdoing.notioncal.core.data.view.ViewRepository
import com.ysdoing.notioncal.core.data.view.ViewWriter
import com.ysdoing.notioncal.core.designsystem.NotionCalTheme
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonElement
import javax.inject.Inject

/**
 * 위젯 행 편집 시트의 상태. 보기 행 전체를 메인 스레드에서 다루지 않게 저장소 흐름을 줄이는 일은 IO에서 한다.
 * 저장·휴지통은 쓰기 큐에 쌓을 뿐이고 노션에는 동기화 쪽이 보낸다. 시트를 열 때 노션과 맞춘다([SheetSyncThrottle]이 연달아 열기를 줄인다).
 */
@HiltViewModel
class ViewRowViewModel @Inject constructor(
    private val views: ViewRepository,
    private val writer: ViewWriter,
    sync: SyncRequester,
    @IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {
    init {
        SheetSyncThrottle.Shared.request(sync)
    }

    fun state(viewId: String, pageId: String): Flow<ViewRowState> =
        views.observe(viewId)
            .map { board -> board?.rows?.firstOrNull { it.pageId == pageId }?.let { ViewRowState.Ready(board, it) } ?: ViewRowState.Missing }
            .flowOn(io)

    /** 바꾼 속성마다 쓰기를 쌓는다. 하나라도 못 쌓으면 false. 화면이 닫혀도 쌓는 일은 끝까지 한다. */
    suspend fun save(viewId: String, pageId: String, changes: Map<String, JsonElement>): Boolean = viewModelScope.async {
        changes.map { (propId, value) -> writer.set(viewId, pageId, propId, value) }.all { it }
    }.await()

    suspend fun trash(viewId: String, pageId: String) {
        viewModelScope.async { writer.trash(viewId, pageId) }.await()
    }
}

/** Phase 11c ED-01 위젯 위에 뜨는 반투명 행 편집 시트. 위젯 행을 누르면 열린다(같은 앱의 PendingIntent로만 열므로 exported=false). */
@AndroidEntryPoint
class ViewRowActivity : ComponentActivity() {
    @Inject
    lateinit var settings: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val viewId = intent.getStringExtra(AppIntents.EXTRA_VIEW_ID)
        val pageId = intent.getStringExtra(AppIntents.EXTRA_PAGE_ID)
        if (viewId == null || pageId == null) {
            finish()
            return
        }
        setContent {
            val dynamicColor by remember { settings.settings.map { it.dynamicColor } }.collectAsStateWithLifecycle(false)
            NotionCalTheme(dynamicColor) {
                val viewModel: ViewRowViewModel = hiltViewModel()
                val state by remember(viewId, pageId) { viewModel.state(viewId, pageId) }.collectAsStateWithLifecycle(ViewRowState.Loading)
                ViewRowHost(
                    state,
                    ViewRowActions(
                        onSave = { viewModel.save(viewId, pageId, it) },
                        onTrash = { viewModel.trash(viewId, pageId) },
                        onOpen = ::openInNotion,
                        onClose = ::finish,
                    ),
                )
            }
        }
    }

    private fun openInNotion(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { Toast.makeText(this, R.string.vre_open_failed, Toast.LENGTH_SHORT).show() }
    }
}
