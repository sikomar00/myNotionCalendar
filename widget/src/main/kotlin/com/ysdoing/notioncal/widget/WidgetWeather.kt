package com.ysdoing.notioncal.widget

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.layout.Alignment
import androidx.glance.layout.Row
import androidx.glance.layout.padding
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.semantics.testTag
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.ysdoing.notioncal.core.data.weather.WeatherDay
import com.ysdoing.notioncal.core.data.weather.WeatherRepository
import com.ysdoing.notioncal.core.designsystem.iconRes
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import java.time.LocalDate

/** WX-04 위젯 날씨: 오늘부터 받아 둔 예보와 12시간이 넘었는지(흐리게). 날씨를 끄면 비어 있다. Room만 읽는다. */
data class WidgetWeather(val days: Map<LocalDate, WeatherDay> = emptyMap(), val stale: Boolean = false) {
    companion object {
        fun observe(repo: WeatherRepository, today: LocalDate): Flow<WidgetWeather> =
            combine(repo.observeDays(today, today.plusDays(9)), repo.observeStatus()) { days, status -> WidgetWeather(days, status.stale) }
                .distinctUntilChanged()
    }
}

/**
 * 위젯 머리·날짜 머리의 날씨 한 덩이(아이콘 + '21°/12°'). 앱과 같은 벡터 아이콘을 리소스로 그린다(비트맵 아님, PF-07).
 * 오래된 예보는 더 흐린 글자색으로 그리고 낭독에 알린다(Glance에는 투명도 모디파이어가 없다).
 */
@Composable
internal fun WeatherChip(day: WeatherDay, stale: Boolean, palette: WidgetPalette, fontSp: Float, showTemps: Boolean = true, iconSize: Dp = 14.dp) {
    val color = if (stale) palette.outside else palette.subtle
    val description = day.description + if (stale) ", 오래된 예보" else ""
    Row(
        GlanceModifier.padding(start = 6.dp).semantics {
            contentDescription = description
            testTag = "weather"
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        day.sky?.let { WidgetIcon(it.iconRes(), color, iconSize) }
        if (showTemps && day.temps.isNotEmpty()) {
            Text(day.temps, modifier = GlanceModifier.padding(start = 2.dp), style = TextStyle(color = color, fontSize = fontSp.sp), maxLines = 1)
        }
    }
}
