package com.ysdoing.notioncal.widget

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.Flow
import com.ysdoing.notioncal.core.data.APP_ZONE
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDate

/**
 * 위젯이 그리는 '오늘'(서울). Glance 세션이 살아 있으면 관찰하는 상태가 바뀌어야 다시 그려([SystemFontScale]과 같은 까닭),
 * 그릴 때 now()를 읽기만 하면 자정 갱신(updateAll)을 받아도 어제가 남을 수 있었다. 자정 리시버와 갱신 요청이 updateAll 전에 [refresh]한다.
 */
internal object WidgetToday {
    private val state = MutableStateFlow(now())

    val flow: StateFlow<LocalDate> = state.asStateFlow()

    fun refresh() {
        state.value = now()
    }

    private fun now(): LocalDate = LocalDate.now(APP_ZONE)
}

/**
 * [key](오늘·보는 범위)가 바뀌어 새 Flow로 갈아탈 때, 첫 값이 오기 전까지 직전 값을 그린다. 자정에 살아 있는 세션이 새 날로 다시 그려질 때
 * 빈 초기값 한 프레임('일정 없음'·습관 등록 안내)이 깜빡였다. 날짜별로 그리는 위젯이라 직전 값의 다른 날 항목은 새 범위에 그려지지 않는다.
 */
@Composable
internal fun <T> rememberLatest(key: Any, initial: T, flow: () -> Flow<T>): T {
    val last = remember { mutableStateOf(initial) }
    val value by remember(key) { flow() }.collectAsState(last.value)
    SideEffect { last.value = value }
    return value
}

/**
 * 세션이 그릴 오늘. 관찰은 자정에 다시 그리게 하는 계기이고, now()와 큰 쪽을 써서 갱신이 늦어도(알람 지연) 어제를 그리지 않는다.
 */
@Composable
internal fun widgetToday(): LocalDate = maxOf(WidgetToday.flow.collectAsState().value, LocalDate.now(APP_ZONE))
