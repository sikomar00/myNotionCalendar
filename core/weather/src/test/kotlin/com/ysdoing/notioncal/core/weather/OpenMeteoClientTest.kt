package com.ysdoing.notioncal.core.weather

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException

/** WX-01·02 Open-Meteo 받기: 요청(좌표 둘째 자리·10일·48시간·서울 시간대), 응답 읽기(null 칸), 실패·빈 값은 IOException. */
class OpenMeteoClientTest {
    private val server = MockWebServer()

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.close()

    private fun client() = OpenMeteoClient(server.url("/"), socketTag = null)

    @Test
    fun requestsRoundedCoordinatesAndParses() = runTest {
        server.enqueue(
            MockResponse.Builder().body(
                """{"latitude":37.57,"longitude":126.98,
                "daily":{"time":["2026-10-19","2026-10-20"],"weather_code":[3,61],"temperature_2m_max":[17.3,19.7],
                  "temperature_2m_min":[9.1,null],"precipitation_probability_max":[4,20]},
                "hourly":{"time":["2026-10-19T00:00","2026-10-19T01:00"],"weather_code":[0,1],"temperature_2m":[10.2,9.8],
                  "precipitation_probability":[0,null]}}""",
            ).build(),
        )
        val forecast = client().fetch(37.5665, 126.97801)
        val request = server.takeRequest()
        val url = request.url
        assertEquals("/v1/forecast", url.encodedPath)
        assertEquals("37.57", url.queryParameter("latitude"))
        assertEquals("126.98", url.queryParameter("longitude"))
        assertEquals("Asia/Seoul", url.queryParameter("timezone"))
        assertEquals("10", url.queryParameter("forecast_days"))
        assertEquals("48", url.queryParameter("forecast_hours"))
        assertNull(url.queryParameter("models")) // 기본 모델
        assertTrue(url.queryParameter("daily")!!.contains("precipitation_probability_max"))

        assertEquals(DailyForecast("2026-10-20", 61, 19.7, null, 20), forecast.days[1])
        assertEquals(HourlyForecast("2026-10-19T01:00", 1, 9.8, null), forecast.hours[1])
    }

    @Test
    fun allNullDaysAreFailure() = runTest {
        // 기상청 모델이 멈췄을 때처럼 날짜만 오고 값이 모두 null이면 캐시를 덮지 않도록 실패로 본다.
        server.enqueue(MockResponse.Builder().body("""{"daily":{"time":["2026-10-19"],"weather_code":[null],"temperature_2m_max":[null]}}""").build())
        try {
            client().fetch(37.57, 126.98)
            fail("빈 예보는 실패여야 한다")
        } catch (_: IOException) {
        }
    }

    @Test
    fun httpErrorIsFailure() = runTest {
        server.enqueue(MockResponse.Builder().code(500).body("{}").build())
        try {
            client().fetch(37.57, 126.98)
            fail("500은 실패여야 한다")
        } catch (_: IOException) {
        }
    }
}
