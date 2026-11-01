package com.ysdoing.notioncal.feature.month

import androidx.compose.runtime.Immutable
import com.ysdoing.notioncal.core.data.weather.WeatherDay
import java.time.LocalDate

/**
 * WX-03 화면 날씨: 오늘부터 받아 둔 날의 예보, 12시간 넘었는지(흐리게), 월 보기에서 모든 칸에 아이콘을 둘지.
 * 날씨를 끄면 [days]가 비어 아무것도 그리지 않는다.
 */
@Immutable
data class MonthWeather(
    val days: Map<LocalDate, WeatherDay> = emptyMap(),
    val stale: Boolean = false,
    val allMonthCells: Boolean = false,
) {
    val enabled: Boolean get() = days.isNotEmpty()
}
