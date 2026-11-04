package com.ysdoing.notioncal.feature.quicksheet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ysdoing.notioncal.core.data.view.ViewBoard
import com.ysdoing.notioncal.core.data.view.ViewJson
import com.ysdoing.notioncal.core.data.view.ViewRow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement

/** 편집 중인 값(속성 ID → 값)을 프로세스가 죽었다 살아나도 남게 JSON 글자로 저장한다(PF-06). 값이 작아 복원 때 풀어도 부담이 없다. */
private val EditsSaver = Saver<Map<String, JsonElement>, String>(save = { ViewJson.encodeTyped(it) }, restore = { ViewJson.typed(it) })

/**
 * Phase 11b ED-01~05 보기 행 편집 시트의 내용. 보기의 표시 속성을 순서대로 유형별 편집기(읽기 전용은 잠금)로 보이고,
 * 저장은 바뀐 속성만 [onSave]로 넘긴다(성공이면 [onClose]). 휴지통은 확인한 뒤 [onTrash], 열기는 [onOpen]에 행 주소를 준다.
 * 노션 쓰기는 모르고 호출한 쪽이 쓰기 큐로 보낸다. 저장 안 한 변경이 생기고 사라지는 것은 [onDirtyChange]로 알린다(닫기 확인용, ED-04).
 * 바뀐 것은 시트를 연 때의 값과 견준다: 연 뒤 동기화로 행이 바뀌어도 건드리지 않은 칸은 바뀐 것이 아니다.
 */
@Composable
fun ViewRowEditor(
    board: ViewBoard,
    row: ViewRow,
    onSave: suspend (Map<String, JsonElement>) -> Boolean,
    onTrash: suspend () -> Unit,
    onOpen: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    onDirtyChange: (Boolean) -> Unit = {},
    bottomInset: Dp = 0.dp,
) {
    val props = rowProps(board, row)
    val columns = props.filterIsInstance<RowProp.Editable>().map { it.column }
    val initial = remember(row.pageId) { row.typed }
    var edits by rememberSaveable(row.pageId, stateSaver = EditsSaver) { mutableStateOf(row.typed) }
    var invalid by rememberSaveable(row.pageId) { mutableStateOf(emptyList<String>()) }
    val dirty = changedValues(columns, initial, edits).isNotEmpty()
    LaunchedEffect(dirty) { onDirtyChange(dirty) }

    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp + bottomInset),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(rowTitle(board, row).ifBlank { stringResource(R.string.vre_untitled) }, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
        props.forEach { prop ->
            when (prop) {
                is RowProp.Editable -> key(prop.column.id) {
                    val id = prop.column.id
                    PropField(
                        prop.column, edits[id],
                        onChange = { value -> edits = if (value == null) edits - id else edits + (id to value) },
                        onInvalid = { bad -> invalid = if (bad) (invalid - id) + id else invalid - id },
                    )
                }
                is RowProp.Locked -> LockedProp(prop)
            }
        }
        EditorActions(row.url, dirty && invalid.isEmpty(), { onSave(changedValues(columns, initial, edits)) }, onTrash, onOpen, onClose)
    }
}

/**
 * ED-03·UX-08: 저장은 폭 전체 주 버튼, 열기·휴지통은 그 아래 줄(줄바꿈)에 떨어뜨리고 휴지통은 error 색이다.
 * 저장은 [save]가 true일 때만 닫고, 아니면 시트를 열어 둔 채 알린다. 휴지통은 확인 대화상자를 거친다.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditorActions(
    url: String,
    canSave: Boolean,
    save: suspend () -> Boolean,
    onTrash: suspend () -> Unit,
    onOpen: (String) -> Unit,
    onClose: () -> Unit,
) {
    var saving by remember { mutableStateOf(false) } // 다시 만들어진 뒤 '저장 중'으로 남지 않게 저장하지 않는다
    var failed by rememberSaveable { mutableStateOf(false) }
    var confirmTrash by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    if (failed) Text(stringResource(R.string.vre_save_failed), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
    Button(
        onClick = {
            scope.launch {
                saving = true
                failed = false
                val saved = save()
                saving = false
                if (saved) onClose() else failed = true
            }
        },
        enabled = canSave && !saving,
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(R.string.qs_save)) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (url.isNotBlank()) TextButton(onClick = { onOpen(url) }) { Text(stringResource(R.string.qs_open_in_notion)) }
        TextButton(onClick = { confirmTrash = true }) { Text(stringResource(R.string.vre_trash), color = MaterialTheme.colorScheme.error) }
    }
    if (confirmTrash) {
        AlertDialog(
            onDismissRequest = { confirmTrash = false },
            title = { Text(stringResource(R.string.qs_delete_confirm_title)) },
            text = { Text(stringResource(R.string.qs_delete_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmTrash = false
                    scope.launch {
                        onTrash()
                        onClose()
                    }
                }) { Text(stringResource(R.string.vre_trash_confirm), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmTrash = false }) { Text(stringResource(R.string.qs_cancel)) } },
        )
    }
}

/** 시트 머리에 쓸 행 제목: 제목 속성의 표시 글자(제목 속성이 보기에 없으면 첫 칸). */
private fun rowTitle(board: ViewBoard, row: ViewRow): String {
    val index = board.columns.indexOfFirst { name -> board.columnInfo.firstOrNull { it.name == name }?.type == "title" }.takeIf { it >= 0 } ?: 0
    return row.cells.getOrNull(index).orEmpty()
}
