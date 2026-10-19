package com.ysdoing.notioncal.core.designsystem

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ysdoing.notioncal.core.data.weather.Sky
import com.ysdoing.notioncal.core.data.weather.WeatherDay

/** WX-05 하늘 8종 아이콘(Material Symbols, Apache 2.0). 앱·위젯이 같은 그림을 쓴다. */
@DrawableRes
fun Sky.iconRes(): Int = when (this) {
    Sky.CLEAR -> R.drawable.ic_weather_clear
    Sky.PARTLY_CLOUDY -> R.drawable.ic_weather_partly_cloudy
    Sky.CLOUDY -> R.drawable.ic_weather_cloudy
    Sky.RAIN -> R.drawable.ic_weather_rain
    Sky.SHOWERS -> R.drawable.ic_weather_showers
    Sky.SNOW -> R.drawable.ic_weather_snow
    Sky.THUNDER -> R.drawable.ic_weather_thunder
    Sky.FOG -> R.drawable.ic_weather_fog
}

/** 날씨 출처(WX-05). 날씨가 보이는 앱 화면·위젯 설정 화면에 둔다. */
object WeatherCredit {
    const val TEXT = "Weather data by Open-Meteo.com"
    const val SITE = "https://open-meteo.com/"
    const val LICENSE = "CC BY 4.0"
    const val LICENSE_URL = "https://creativecommons.org/licenses/by/4.0/"
}

/** 오래된 예보(12시간 넘음, WX-01)는 흐리게. */
private const val STALE_ALPHA = 0.5f

/**
 * WX-03 날씨 한 덩이: 아이콘 + '21°/12°'. 아이콘·글자는 흑연색(색은 일정에만, DESIGN.md 원칙 1).
 * 낭독은 '맑음, 최고 21도 최저 12도'(오래됐으면 ', 오래된 예보'). [vertical]이면 아이콘 아래 기온(좁은 날짜 머리).
 */
@Composable
fun WeatherBadge(
    day: WeatherDay,
    stale: Boolean,
    modifier: Modifier = Modifier,
    showTemps: Boolean = true,
    vertical: Boolean = false,
    iconSize: Dp = 14.dp,
    fontSize: TextUnit = 11.sp,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    val description = day.description + if (stale) ", 오래된 예보" else ""
    val content: @Composable () -> Unit = {
        day.sky?.let { Icon(painterResource(it.iconRes()), contentDescription = null, tint = color, modifier = Modifier.size(iconSize)) }
        if (showTemps && day.temps.isNotEmpty()) Text(day.temps, fontSize = fontSize, lineHeight = fontSize, color = color, maxLines = 1)
    }
    val m = modifier.alpha(if (stale) STALE_ALPHA else 1f).clearAndSetSemantics { contentDescription = description }
    if (vertical) {
        Column(m, horizontalAlignment = Alignment.CenterHorizontally) { content() }
    } else {
        Row(m, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) { content() }
    }
}

/** WX-05 출처 줄: 'Weather data by Open-Meteo.com · CC BY 4.0'(두 링크). */
@Composable
fun WeatherAttribution(modifier: Modifier = Modifier) {
    val link = TextLinkStyles(SpanStyle(color = MaterialTheme.colorScheme.primary))
    val text = buildAnnotatedString {
        withLink(LinkAnnotation.Url(WeatherCredit.SITE, link)) { append(WeatherCredit.TEXT) }
        append(" · ")
        withLink(LinkAnnotation.Url(WeatherCredit.LICENSE_URL, link)) { append(WeatherCredit.LICENSE) }
    }
    Text(text, modifier = modifier, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
