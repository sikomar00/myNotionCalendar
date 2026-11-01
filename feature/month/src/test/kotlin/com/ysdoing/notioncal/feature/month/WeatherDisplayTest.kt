package com.ysdoing.notioncal.feature.month

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import com.ysdoing.notioncal.core.data.weather.Sky
import com.ysdoing.notioncal.core.data.weather.WeatherDay
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/**
 * WX-03 앱 날씨: 주 보기·목록 보기 날짜 머리에 아이콘 + 최고·최저, 보기를 바꿔도 같은 날 같은 값(같은 낭독),
 * 월 보기는 오늘 칸에만(설정을 켜면 예보가 있는 모든 칸), 예보 없는 날은 비운다. 오래된 예보는 낭독에 알린다.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h760dp-xxhdpi")
class WeatherDisplayTest {
    @get:Rule
    val compose = createComposeRule()

    private val today = LocalDate.of(2026, 10, 14)
    private val weather = MonthWeather(
        days = mapOf(
            today to WeatherDay(today, Sky.CLEAR, 21, 12, 10),
            today.plusDays(1) to WeatherDay(today.plusDays(1), Sky.RAIN, 17, 11, 80),
        ),
    )

    @Test
    fun weekHeadersShowIconAndTemps() {
        compose.setContent {
            MaterialTheme {
                Surface {
                    TimeGrid(
                        start = LocalDate.of(2026, 10, 12), days = 7, today = today, nowMinute = null, events = emptyList(),
                        hourHeight = 48.dp, onDayClick = {}, onEventClick = {}, weather = weather,
                    )
                }
            }
        }
        compose.onNodeWithContentDescription("맑음, 최고 21도 최저 12도").assertExists()
        compose.onNodeWithContentDescription("비, 최고 17도 최저 11도").assertExists()
    }

    @Test
    fun agendaHeadersShowSameValues() {
        compose.setContent {
            MaterialTheme {
                AgendaView(
                    initialFocus = today, jump = null, today = today, events = { _, _ -> MutableStateFlow(emptyList()) },
                    onDayClick = {}, onEventClick = {}, onFocusChange = {}, weather = weather,
                )
            }
        }
        compose.onNodeWithContentDescription("맑음, 최고 21도 최저 12도").assertExists()
    }

    @Test
    fun monthShowsTodayOnlyUnlessAllCells() {
        var state by mutableStateOf(MonthGridState(YearMonth.of(2026, 10), DayOfWeek.SUNDAY, today, emptyList(), weather = weather))
        compose.setContent { MaterialTheme { MonthGrid(state, onDayClick = {}) } }
        compose.onAllNodesWithContentDescription("맑음, 최고 21도 최저 12도").assertCountEquals(1)
        compose.onAllNodesWithContentDescription("비, 최고 17도 최저 11도").assertCountEquals(0)

        state = state.copy(weather = weather.copy(allMonthCells = true, stale = true))
        compose.onAllNodesWithContentDescription("비, 최고 17도 최저 11도, 오래된 예보").assertCountEquals(1)
    }
}
