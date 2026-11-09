package com.ysdoing.notioncal.core.data

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import java.time.Clock
import java.time.Duration
import java.time.LocalDate

/**
 * 서울 기준 오늘. 화면을 켜 둔 채 자정을 넘기면 다음 날을 낸다(자정 + 1초까지 기다림, 위젯의 자정 알람과 같은 시각).
 * 켜 둔 앱은 다시 보일 때(onResume)만 오늘을 새로 계산해, 자정을 넘기면 습관을 누르면 어제로 기록되고 오늘 표시도 그대로였다.
 * 기기가 깊이 잠들면 기다림이 늦게 끝날 수 있어 화면은 onResume에서도 계속 다시 계산한다.
 */
fun observeToday(clock: Clock): Flow<LocalDate> = flow {
    while (true) {
        val now = clock.instant()
        val today = now.atZone(APP_ZONE).toLocalDate()
        emit(today)
        val next = today.plusDays(1).atStartOfDay(APP_ZONE).plusSeconds(1).toInstant()
        delay(Duration.between(now, next).toMillis().coerceAtLeast(1))
    }
}.distinctUntilChanged() // 시계를 뒤로 돌려 기다림이 일찍 끝나면 같은 날을 다시 낸다: 구독을 다시 시작하지 않게
