package com.ysdoing.notioncal.feature.month

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.ysdoing.notioncal.feature.quicksheet.QuickSheetContent
import com.ysdoing.notioncal.feature.quicksheet.QuickSheetStart
import androidx.compose.ui.Modifier

/**
 * MV-04 날짜 시트: 그날 목록에서 체크·편집·추가. 위젯 퀵 시트와 같은 [QuickSheetContent]를 쓴다.
 * 앱 안에서는 날짜를 훑어보는 경우가 많아 키보드를 자동으로 띄우지 않는다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DaySheet(start: QuickSheetStart, onDismiss: () -> Unit) {
    // 실기기에서 마지막 일정이 화면 아래(내비게이션 바 밑)에 걸렸다.
    // 1) 시트를 연 직후에는 그날 일정이 아직 로드되지 않아, 시트가 짧은 높이로 자리를 잡고 내용이 길어져도 올라오지
    //    않았다. → 높이를 화면의 85%로 고정해 처음부터 그 높이로 열고, 넘치는 일정은 시트 안에서 스크롤한다.
    // 2) 시트 창은 내비게이션 바 높이를 첫 프레임에 0으로 받는다. → 메인 창에서 읽은 값을 스크롤 내용 맨 아래 여백으로 준다.
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        contentWindowInsets = { WindowInsets(0) },
    ) {
        QuickSheetContent(
            start = start,
            viewModel = hiltViewModel(key = "day-sheet"),
            onFinished = onDismiss,
            autoFocus = false,
            bottomInset = navBottom,
            modifier = Modifier.fillMaxHeight(SHEET_HEIGHT),
        )
    }
}

private const val SHEET_HEIGHT = 0.85f
