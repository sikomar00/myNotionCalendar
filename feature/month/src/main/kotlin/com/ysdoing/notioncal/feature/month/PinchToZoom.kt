package com.ysdoing.notioncal.feature.month

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

/**
 * MV-08 두 손가락 확대. 두 번째 손가락이 닿을 때부터만 반응하고, 그 뒤로는 손을 다 뗄 때까지 이벤트를 먼저(Initial) 소비해
 * 페이저 넘기기·당겨서 새로고침·길게 눌러 끌기가 함께 움직이지 않게 한다. 한 손가락 제스처는 건드리지 않는다.
 * [onZoom]은 이전 이벤트 대비 배율(1보다 크면 벌림), [onEnd]는 확대가 있었던 제스처가 끝날 때 한 번 불린다.
 */
fun Modifier.pinchToZoom(onZoom: (Float) -> Unit, onEnd: () -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var zooming = false
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.changes.count { it.pressed } >= 2) zooming = true
            if (zooming) {
                val zoom = event.calculateZoom()
                if (zoom != 1f) onZoom(zoom)
                event.changes.forEach { it.consume() }
            }
        } while (event.changes.any { it.pressed })
        if (zooming) onEnd()
    }
}

/** 안쪽의 sp 크기(글자·sp로 정한 줄 높이)만 [scale]배 한다. dp 여백·칸 너비는 그대로다. */
@Composable
fun TextZoom(scale: Float, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val zoomed by rememberUpdatedState(Density(density.density, density.fontScale * scale))
    CompositionLocalProvider(LocalDensity provides zoomed, content = content)
}
