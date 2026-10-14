package com.ysdoing.notioncal.widget

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * S25-06: 기기 글꼴 크기. 위젯 줄 높이는 이 값으로 계산한다(WidgetMetrics).
 *
 * Glance 세션이 살아 있으면 갱신 요청을 받아도 관찰 중인 상태(설정·일정)가 바뀌지 않는 한 다시 그리지 않고,
 * 세션 컨텍스트는 시작 때의 설정을 계속 들고 있다(실기기 확인). 그래서 앱 컨텍스트에서 읽은 값을 Flow로 알린다.
 */
internal object SystemFontScale {
    private val state = MutableStateFlow<Float?>(null)

    val flow: StateFlow<Float?> = state.asStateFlow()

    /** 지금 값(처음 부르면 앱 컨텍스트에서 읽는다). */
    fun current(context: Context): Float = state.value ?: read(context).also { state.value = it }

    /** 위젯 갱신 직전에 부른다. 값이 바뀌었으면 살아 있는 세션이 다시 그린다. */
    fun refresh(context: Context) {
        state.value = read(context)
    }

    private fun read(context: Context): Float = context.applicationContext.resources.configuration.fontScale
}
