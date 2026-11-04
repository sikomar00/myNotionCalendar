package com.ysdoing.notioncal.feature.quicksheet

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.ysdoing.notioncal.core.data.view.ViewBoard
import com.ysdoing.notioncal.core.data.view.ViewRow

/** 위젯 행 편집 시트가 보여 줄 것: 아직 읽는 중 / 보기나 행이 없음(지워짐) / 행. */
sealed interface ViewRowState {
    data object Loading : ViewRowState
    data object Missing : ViewRowState
    data class Ready(val board: ViewBoard, val row: ViewRow) : ViewRowState
}

/**
 * Phase 11c ED-01: 위젯 행을 누르면 위젯 위에 뜨는 편집 시트의 내용(투명 액티비티 [ViewRowActivity]가 싣는다). 아래에서 올라오는 모양이고
 * 바깥을 누르거나 뒤로 가면 닫는다. 저장 안 한 변경이 있으면 닫기 전에 '변경을 버릴까요?'를 먼저 묻는다(ED-04). 행이 없으면 바로 닫는다.
 */
@Composable
fun ViewRowHost(state: ViewRowState, actions: ViewRowActions) {
    var dirty by remember { mutableStateOf(false) }
    var askDiscard by rememberSaveable { mutableStateOf(false) }
    val requestClose = { if (dirty) askDiscard = true else actions.onClose() }
    when (state) {
        ViewRowState.Loading -> Unit
        ViewRowState.Missing -> LaunchedEffect(Unit) { actions.onClose() }
        is ViewRowState.Ready -> {
            BackHandler(onBack = requestClose)
            val maxHeight = (LocalConfiguration.current.screenHeightDp * SHEET_MAX_FRACTION).dp
            BottomSheetFrame(onClose = requestClose, modifier = Modifier.testTag("viewRowScrim")) {
                ViewRowEditor(
                    state.board, state.row, actions,
                    modifier = Modifier.heightIn(max = maxHeight),
                    onDirtyChange = { dirty = it },
                    bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
                )
            }
        }
    }
    if (askDiscard) DiscardChangesDialog(onDiscard = { askDiscard = false; actions.onClose() }, onKeep = { askDiscard = false })
}

private const val SHEET_MAX_FRACTION = 0.85f
