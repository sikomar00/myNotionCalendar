package com.ysdoing.notioncal.feature.quicksheet

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ysdoing.notioncal.core.data.APP_ZONE
import com.ysdoing.notioncal.core.data.weather.WeatherDay
import com.ysdoing.notioncal.core.designsystem.WeatherAttribution
import com.ysdoing.notioncal.core.designsystem.WeatherBadge
import java.time.format.DateTimeFormatter

private val fetchedFormat = DateTimeFormatter.ofPattern("M/d HH:mm")

/**
 * WX-03 날짜 시트 위쪽: 하루 요약('☀ 21°/12°') + 3시간 간격 시간별 줄(받아 둔 48시간 안), WX-05 출처.
 * 받아온 시각을 늘 보이고, 오래된 예보(12시간 넘음)는 흐리게 한다(WX-01).
 */
@Composable
internal fun DayWeatherStrip(weather: DayWeather) {
    Column(Modifier.fillMaxWidth().testTag("dayWeather"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            weather.day?.let { WeatherBadge(it, weather.stale, iconSize = 18.dp, fontSize = 13.sp) }
            // WX-01: 받아온 시각을 늘 보인다(비행기 모드에서 마지막 예보가 언제 것인지).
            if (weather.fetchedAt != null) {
                Text(
                    stringResource(R.string.qs_weather_fetched, weather.fetchedAt.atZone(APP_ZONE).format(fetchedFormat)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (weather.hours.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                weather.hours.forEach { hour ->
                    val label = stringResource(R.string.qs_weather_hour, hour.hour)
                    val description = listOfNotNull(label, hour.sky?.label, hour.temp?.let { "${it}도" }).joinToString(", ")
                    Column(
                        Modifier.widthIn(min = 36.dp).alpha(if (weather.stale) 0.5f else 1f).clearAndSetSemantics { contentDescription = description },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        WeatherBadge(WeatherDay(java.time.LocalDate.MIN, hour.sky, hour.temp, null, hour.pop), stale = false, vertical = true, iconSize = 16.dp, fontSize = 11.sp)
                    }
                }
            }
        }
        WeatherAttribution()
    }
}
