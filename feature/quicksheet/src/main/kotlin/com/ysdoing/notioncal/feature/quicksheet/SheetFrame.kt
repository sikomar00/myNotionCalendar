package com.ysdoing.notioncal.feature.quicksheet

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 위젯 위에 뜨는 반투명 시트(투명 액티비티)의 틀: 바깥(scrim)을 누르면 [onClose], 아래에 윗모서리가 둥근 면이 있고 키보드가 올라오면 함께 올라온다.
 * 시트 내용을 눌러도 닫히지 않는다. 새 항목·행 편집·선택 시트가 같이 쓴다. [modifier]는 바깥 영역(scrim)에 붙는다.
 */
@Composable
internal fun BottomSheetFrame(onClose: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().imePadding().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { },
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
            tonalElevation = 6.dp,
            content = content,
        )
    }
}
