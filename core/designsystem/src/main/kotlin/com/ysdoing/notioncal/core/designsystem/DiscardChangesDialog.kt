package com.ysdoing.notioncal.core.designsystem

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource

/** ED-04 '변경을 버릴까요?'. 저장하지 않은 변경이 있는 시트를 닫을 때 묻는다. 버리기는 error 색이다(UX-08). */
@Composable
fun DiscardChangesDialog(onDiscard: () -> Unit, onKeep: () -> Unit) {
    AlertDialog(
        onDismissRequest = onKeep,
        title = { Text(stringResource(R.string.discard_title)) },
        text = { Text(stringResource(R.string.discard_body)) },
        confirmButton = { TextButton(onClick = onDiscard) { Text(stringResource(R.string.discard_confirm), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onKeep) { Text(stringResource(R.string.discard_keep_editing)) } },
    )
}

/**
 * ED-04 닫기 규칙 하나: 저장하지 않은 내용([dirty])이 있으면 닫기 전에 '변경을 버릴까요?'를 묻는다. 일정·가계부·보기 행 시트가 같이 쓴다.
 * 닫으려는 쪽(바깥 누르기·뒤로 가기·밀어 닫기)은 [mayClose]가 true일 때만 바로 닫는다.
 */
@Stable
class DiscardGuard internal constructor(private val asking: MutableState<Boolean>) {
    var dirty by mutableStateOf(false)

    /** 고친 것이 없으면 true(바로 닫아도 된다). 있으면 확인을 띄우고 false다. */
    fun mayClose(): Boolean {
        if (dirty) asking.value = true
        return !dirty
    }
}

/** [DiscardGuard]와 그 확인 대화상자. 확인에서 '버리기'를 고르면 [onClose]를 부른다. */
@Composable
fun rememberDiscardGuard(onClose: () -> Unit): DiscardGuard {
    val asking = rememberSaveable { mutableStateOf(false) }
    val guard = remember { DiscardGuard(asking) }
    if (asking.value) DiscardChangesDialog(onDiscard = { asking.value = false; onClose() }, onKeep = { asking.value = false })
    return guard
}

/** 아래로 밀어 닫으려는 순간 고친 내용이 있으면 시트를 그대로 두고 [guard]의 확인을 띄우는 시트 상태. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun rememberGuardedSheetState(guard: DiscardGuard): SheetState =
    rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { target -> target != SheetValue.Hidden || guard.mayClose() })

/** 폼이 고친 내용이 생기고 사라지는 것을 위로 알린다. 폼이 화면에서 빠지면(목록으로 돌아감) 고친 내용도 없는 것으로 알린다. */
@Composable
fun ReportDirty(dirty: Boolean, onDirtyChange: (Boolean) -> Unit) {
    LaunchedEffect(dirty) { onDirtyChange(dirty) }
    DisposableEffect(Unit) { onDispose { onDirtyChange(false) } }
}
