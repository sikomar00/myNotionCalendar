package com.ysdoing.notioncal.feature.quicksheet

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import com.ysdoing.notioncal.core.data.weather.Sky
import com.ysdoing.notioncal.core.data.weather.WeatherDay
import com.ysdoing.notioncal.core.data.weather.WeatherHour
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.LocalDate

/** WX-03 날짜 시트 날씨: 하루 요약, 3시간 간격 줄(낭독 '15시, 구름 조금, 17도'), 오래되면 받은 시각, WX-05 출처. */
@RunWith(RobolectricTestRunner::class)
class DayWeatherStripTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun summaryHoursStaleTimeAndCredit() {
        val day = LocalDate.of(2026, 10, 19)
        val weather = DayWeather(
            day = WeatherDay(day, Sky.CLEAR, 21, 12, 10),
            hours = listOf(
                WeatherHour(Instant.parse("2026-10-19T03:00:00Z"), 12, Sky.CLEAR, 20, 0),
                WeatherHour(Instant.parse("2026-10-19T06:00:00Z"), 15, Sky.PARTLY_CLOUDY, 17, 5),
            ),
            stale = true,
            fetchedAt = Instant.parse("2026-10-18T13:00:00Z"),
        )
        compose.setContent { MaterialTheme { DayWeatherStrip(weather) } }
        compose.onNodeWithContentDescription("맑음, 최고 21도 최저 12도, 오래된 예보").assertExists()
        compose.onNodeWithContentDescription("15시, 구름 조금, 17도").assertExists()
        compose.onNodeWithText("10/18 22:00 받은 예보").assertExists()
        compose.onNodeWithText("Weather data by Open-Meteo.com · CC BY 4.0").assertExists()
    }
}
