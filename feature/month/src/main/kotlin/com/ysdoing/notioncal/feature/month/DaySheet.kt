package com.ysdoing.notioncal.feature.month

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.Composable
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.ysdoing.notioncal.feature.quicksheet.QuickSheetContent
import com.ysdoing.notioncal.feature.quicksheet.QuickSheetStart
import java.time.LocalDate

/**
 * MV-04 날짜 시트: 그날 목록에서 체크·편집·추가. 위젯 퀵 시트와 같은 [QuickSheetContent]를 쓴다.
 * 앱 안에서는 날짜를 훑어보는 경우가 많아 키보드를 자동으로 띄우지 않는다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DaySheet(date: LocalDate, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        QuickSheetContent(
            start = QuickSheetStart.Day(date),
            viewModel = hiltViewModel(key = "day-sheet"),
            onFinished = onDismiss,
            autoFocus = false,
        )
    }
}
