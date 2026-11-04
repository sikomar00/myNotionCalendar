package com.ysdoing.notioncal.feature.viewer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ysdoing.notioncal.core.data.view.ViewBoard
import com.ysdoing.notioncal.core.data.view.ViewRow

/** 뷰어 화면의 상태. 등록하지 않았거나 지워진 보기는 [Missing]이다. */
sealed interface ViewerState {
    data object Loading : ViewerState
    data object Missing : ViewerState
    data class Ready(val board: ViewBoard) : ViewerState
}

/** VR-02 '더 보기' 전체 화면(`view/{viewId}`). Room 스냅샷만 읽는다. */
@Composable
fun ViewerRoute(onBack: () -> Unit, viewModel: ViewerViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    ViewerContent(state, onBack, onOpen = { runCatching { uriHandler.openUri(it) } })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerContent(state: ViewerState, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val title = (state as? ViewerState.Ready)?.board?.name?.takeIf { it.isNotBlank() } ?: stringResource(R.string.viewer_title_fallback)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.viewer_back)) }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (state) {
                ViewerState.Loading -> {
                    val loading = stringResource(R.string.viewer_loading)
                    CircularProgressIndicator(Modifier.align(Alignment.Center).semantics { contentDescription = loading })
                }
                ViewerState.Missing -> Message(stringResource(R.string.viewer_missing))
                is ViewerState.Ready -> if (state.board.rows.isEmpty()) Message(stringResource(R.string.viewer_empty)) else Rows(state.board, onOpen)
            }
        }
    }
}

@Composable
private fun Message(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun Rows(board: ViewBoard, onOpen: (String) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            val count = if (board.rowsComplete) stringResource(R.string.viewer_count, board.rows.size) else stringResource(R.string.viewer_count_incomplete, board.rows.size)
            Text(count, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(board.rows, key = { it.pageId }) { row -> RowCard(board, row, onOpen) }
    }
}

/** 행 하나: 첫 칸(제목)을 굵게, 값이 있는 나머지 열은 '열 이름 값'으로. 줄 전체가 눌림이다(48dp 이상). */
@Composable
private fun RowCard(board: ViewBoard, row: ViewRow, onOpen: (String) -> Unit) {
    val title = row.cells.firstOrNull().orEmpty()
    val description = stringResource(R.string.viewer_row_description, title)
    Surface(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = row.url.isNotBlank()) { onOpen(row.url) }.semantics { contentDescription = description },
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            board.columns.drop(1).forEachIndexed { i, name ->
                val value = row.cells.getOrNull(i + 1).orEmpty()
                if (value.isNotEmpty()) Text("$name $value", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
