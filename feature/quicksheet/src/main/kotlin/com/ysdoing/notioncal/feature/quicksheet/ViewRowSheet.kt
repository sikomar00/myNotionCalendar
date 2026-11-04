package com.ysdoing.notioncal.feature.quicksheet

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.ysdoing.notioncal.core.data.view.ViewBoard
import com.ysdoing.notioncal.core.data.view.ViewRow

/**
 * 보기 행 편집 시트(ED-01). [ViewRowEditor]를 아래에서 올라오는 시트에 담는다. 닫기는 [ViewRowActions.onClose]다.
 * ED-04: 저장 안 한 변경이 있으면 아래로 밀기·바깥 누르기·뒤로 가기 어느 쪽으로 닫아도 '변경을 버릴까요?'를 먼저 묻는다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewRowSheet(board: ViewBoard, row: ViewRow, actions: ViewRowActions) {
    val dirty = remember { mutableStateOf(false) }
    var askDiscard by rememberSaveable { mutableStateOf(false) }
    // 시트 창은 내비게이션 바 높이를 첫 프레임에 0으로 받는다. 메인 창에서 읽은 값을 스크롤 내용 맨 아래 여백으로 준다(DaySheet와 같은 이유).
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { target ->
            // 아래로 밀어 닫으려는 순간 변경이 있으면 시트는 그대로 두고 확인을 띄운다.
            val blocked = target == SheetValue.Hidden && dirty.value
            if (blocked) askDiscard = true
            !blocked
        },
    )
    ModalBottomSheet(
        onDismissRequest = { if (dirty.value) askDiscard = true else actions.onClose() },
        sheetState = sheetState,
        contentWindowInsets = { WindowInsets(0) },
    ) {
        ViewRowEditor(board, row, actions, onDirtyChange = { dirty.value = it }, bottomInset = navBottom)
    }
    if (askDiscard) DiscardChangesDialog(onDiscard = { askDiscard = false; actions.onClose() }, onKeep = { askDiscard = false })
}

/** ED-04 '변경을 버릴까요?'. 버리기는 error 색이다(UX-08). */
@Composable
internal fun DiscardChangesDialog(onDiscard: () -> Unit, onKeep: () -> Unit) {
    AlertDialog(
        onDismissRequest = onKeep,
        title = { Text(stringResource(R.string.vre_discard_title)) },
        text = { Text(stringResource(R.string.vre_discard_body)) },
        confirmButton = { TextButton(onClick = onDiscard) { Text(stringResource(R.string.vre_discard), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onKeep) { Text(stringResource(R.string.vre_keep_editing)) } },
    )
}
