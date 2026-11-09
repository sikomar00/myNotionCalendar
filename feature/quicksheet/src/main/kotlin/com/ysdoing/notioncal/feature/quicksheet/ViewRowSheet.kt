package com.ysdoing.notioncal.feature.quicksheet

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.Composable
import com.ysdoing.notioncal.core.designsystem.rememberDiscardGuard
import com.ysdoing.notioncal.core.designsystem.rememberGuardedSheetState
import com.ysdoing.notioncal.core.data.view.ViewBoard
import com.ysdoing.notioncal.core.data.view.ViewRow

/**
 * 보기 행 편집 시트(ED-01). [ViewRowEditor]를 아래에서 올라오는 시트에 담는다. 닫기는 [ViewRowActions.onClose]다.
 * ED-04: 저장 안 한 변경이 있으면 아래로 밀기·바깥 누르기·뒤로 가기 어느 쪽으로 닫아도 '변경을 버릴까요?'를 먼저 묻는다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewRowSheet(board: ViewBoard, row: ViewRow, actions: ViewRowActions) {
    val guard = rememberDiscardGuard(onClose = actions.onClose)
    // 시트 창은 내비게이션 바 높이를 첫 프레임에 0으로 받는다. 메인 창에서 읽은 값을 스크롤 내용 맨 아래 여백으로 준다(DaySheet와 같은 이유).
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    ModalBottomSheet(
        onDismissRequest = { if (guard.mayClose()) actions.onClose() },
        sheetState = rememberGuardedSheetState(guard),
        contentWindowInsets = { WindowInsets(0) },
    ) {
        ViewRowEditor(board, row, actions, onDirtyChange = { guard.dirty = it }, bottomInset = navBottom)
    }
}
