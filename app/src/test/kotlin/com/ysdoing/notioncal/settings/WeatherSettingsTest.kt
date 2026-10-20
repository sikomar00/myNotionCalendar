package com.ysdoing.notioncal.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.ysdoing.notioncal.core.data.AppSettings
import com.ysdoing.notioncal.core.data.weather.Region
import com.ysdoing.notioncal.core.data.weather.WeatherPlace
import com.ysdoing.notioncal.core.data.weather.WeatherStatus
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

/** WX-02·05 설정 → 날씨: 켜면 위치 없을 때 지역 고르기(권한 없이), 검색해 고르기, 받아온 시각·출처 표시. */
@RunWith(RobolectricTestRunner::class)
class WeatherSettingsTest {
    @get:Rule
    val compose = createComposeRule()

    private val regions = listOf(Region("서울 강남구", 37.5, 127.06), Region("서울 강동구", 37.55, 127.15))

    @Test
    fun enablingAsksForRegionWithoutPermission() {
        val enabled = mutableListOf<Boolean>()
        val picked = mutableListOf<String>()
        var settings by mutableStateOf(AppSettings())
        compose.setContent {
            MaterialTheme {
                WeatherSection(
                    settings,
                    WeatherStatus(null, false, null),
                    WeatherSettingsActions(
                        onEnable = { enabled += it; settings = settings.copy(weatherEnabled = it) },
                        onSearch = { q -> regions.filter { r -> q.isNotBlank() && r.name.contains(q) } },
                        onPickRegion = { picked += it.name },
                    ),
                )
            }
        }
        compose.onNodeWithTag("weatherSwitch").performClick()
        assertEquals(listOf(true), enabled)
        compose.onNodeWithTag("regionSearch").performTextInput("강남")
        compose.waitForIdle()
        compose.onNodeWithText("서울 강남구").performClick()
        assertEquals(listOf("서울 강남구"), picked)
    }

    @Test
    fun showsPlaceFetchedTimeAndCredit() {
        val settings = AppSettings(weatherEnabled = true, weatherPlace = WeatherPlace("서울 종로구", 37.59, 126.98))
        compose.setContent {
            MaterialTheme { WeatherSection(settings, WeatherStatus(Instant.parse("2026-10-19T03:00:00Z"), false, "서울 종로구"), WeatherSettingsActions()) }
        }
        compose.onNodeWithText("예보 위치: 서울 종로구").assertExists()
        compose.onNodeWithText("10/19 12:00에 받은 예보").assertExists()
        compose.onNodeWithText("Weather data by Open-Meteo.com (CC BY 4.0)").assertExists()
    }
}
