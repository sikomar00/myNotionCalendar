package com.ysdoing.notioncal.core.data.view

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * Phase 12c 날짜 속성 값의 편집·계산 규칙. 종일·시간·기간 어느 모양이든 날짜만 바꾸면 시각·오프셋·시간대가 그대로 남고,
 * 시각을 켜고 끄고, 며칠씩 옮긴다(액션 버튼 '내일로').
 */
class TypedDateTest {
    private val seoul = "+09:00"

    // --- 날짜만 바꾸기(11g까지의 규칙)

    @Test
    fun datesKeepTheirTimeWhenOnlyTheDayChanges() {
        val date = TypedDate("2026-11-04T09:00:00.000$seoul", "2026-11-04T10:00:00.000$seoul", "Asia/Seoul")
        val moved = date.withStart(LocalDate.of(2026, 11, 6))
        assertEquals("2026-11-06T09:00:00.000$seoul", moved.start)
        assertEquals("2026-11-06T10:00:00.000$seoul", moved.end) // 끝이 시작보다 앞서면 끌어 올린다
        assertEquals("Asia/Seoul", moved.timeZone)
        assertEquals("2026-11-06", TypedDate("2026-11-04").withStart(LocalDate.of(2026, 11, 6)).start)
    }

    @Test
    fun aTimedEndNeverFallsBeforeTheStartOnTheSameDay() {
        val range = TypedDate("2026-11-04T15:00:00.000$seoul", "2026-11-05T10:00:00.000$seoul")
        assertEquals("2026-11-04T15:00:00.000$seoul", range.withEnd(LocalDate.of(2026, 11, 4)).end) // 끝 시간(10:00)이 시작(15:00)보다 앞서면 시작과 같게
        assertEquals("2026-11-05T15:00:00.000$seoul", range.withStart(LocalDate.of(2026, 11, 5)).end)
        assertEquals("2026-11-04T18:00:00.000$seoul", TypedDate("2026-11-04T15:00:00.000$seoul", "2026-11-04T18:00:00.000$seoul").withEnd(LocalDate.of(2026, 11, 4)).end)
    }

    @Test
    fun anEndBeforeTheStartIsRaisedToTheStartAndEndCanBeCleared() {
        val date = TypedDate("2026-11-10")
        assertEquals("2026-11-10", date.withEnd(LocalDate.of(2026, 11, 8)).end)
        assertEquals("2026-11-12", date.withEnd(LocalDate.of(2026, 11, 12)).end)
        assertNull(date.withEnd(LocalDate.of(2026, 11, 12)).withoutEnd().end)
    }

    @Test
    fun datesRoundTripThroughJson() {
        val json: JsonObject = TypedDate("2026-11-10", "2026-11-12").toJson()
        assertEquals("""{"start":"2026-11-10","end":"2026-11-12"}""", json.toString())
        assertEquals(TypedDate("2026-11-10", "2026-11-12"), TypedDate.parse(json))
        assertEquals(TypedDate("2026-11-10T09:00:00", null, "America/New_York"), TypedDate.parse(TypedDate("2026-11-10T09:00:00", null, "America/New_York").toJson()))
        assertNull(TypedDate.parse(JsonNull))
        assertNull(TypedDate.parse(null))
        assertNull(TypedDate.parse(JsonObject(emptyMap())))
    }

    @Test
    fun theDayOfAStartWithATimeIsItsDatePartAndTimesAreReadFromTheText() {
        assertEquals(LocalDate.of(2026, 11, 4), TypedDate("2026-11-04T09:00:00.000$seoul").startDay())
        assertEquals(LocalDate.of(2026, 11, 4), TypedDate("2026-11-04").startDay())
        val timed = TypedDate("2026-11-04T09:30:00.000$seoul", "2026-11-04T11:00:00.000Z")
        assertEquals(listOf(true, LocalTime.of(9, 30), LocalTime.of(11, 0)), listOf(timed.isTimed, timed.startTime(), timed.endTime()))
        assertEquals(listOf(false, null, null), TypedDate("2026-11-04", "2026-11-06").let { listOf(it.isTimed, it.startTime(), it.endTime()) })
        assertEquals(LocalTime.of(9, 0), TypedDate("2026-11-04T09:00:00").startTime()) // 오프셋 없는 글자(시간대는 따로)
    }

    // --- 시각 켜고 끄기(12c)

    @Test
    fun turningTimeOnAnAllDayDateMakesItTimedInSeoulWithAnEndOnlyWhenTheTimesDiffer() {
        assertEquals(TypedDate("2026-11-04T09:00:00.000$seoul"), TypedDate("2026-11-04").withTimes(LocalTime.of(9, 0), LocalTime.of(9, 0))) // 한 시각: 끝 없음
        assertEquals(TypedDate("2026-11-04T09:00:00.000$seoul", "2026-11-04T10:00:00.000$seoul"), TypedDate("2026-11-04").withTimes(LocalTime.of(9, 0), LocalTime.of(10, 0)))
        // 기간이던 날짜는 날짜를 지키고 시각만 붙는다
        assertEquals(
            TypedDate("2026-11-04T09:00:00.000$seoul", "2026-11-06T18:00:00.000$seoul"),
            TypedDate("2026-11-04", "2026-11-06").withTimes(LocalTime.of(9, 0), LocalTime.of(18, 0)),
        )
    }

    @Test
    fun changingTheTimesOfATimedDateKeepsItsOffsetOrItsMissingOffsetAndTimeZone() {
        val utc = TypedDate("2026-11-04T09:00:00.000Z", "2026-11-04T10:00:00.000Z").withTimes(LocalTime.of(13, 15), LocalTime.of(14, 0))
        assertEquals(TypedDate("2026-11-04T13:15:00.000Z", "2026-11-04T14:00:00.000Z"), utc)
        val zoned = TypedDate("2026-11-04T09:00:00", null, "America/New_York").withTimes(LocalTime.of(10, 0), LocalTime.of(10, 0))
        assertEquals(TypedDate("2026-11-04T10:00:00.000", null, "America/New_York"), zoned) // 오프셋 없이 시간대로 읽는 글자는 그대로
        val minus = TypedDate("2026-11-04T09:00:00.000-05:00").withTimes(LocalTime.of(8, 0), LocalTime.of(9, 0))
        assertEquals(TypedDate("2026-11-04T08:00:00.000-05:00", "2026-11-04T09:00:00.000-05:00"), minus)
    }

    @Test
    fun anEndTimeBeforeTheStartOnTheSameDayIsRaisedToTheStart() {
        val t = TypedDate("2026-11-04").withTimes(LocalTime.of(15, 0), LocalTime.of(10, 0))
        assertEquals(TypedDate("2026-11-04T15:00:00.000$seoul", "2026-11-04T15:00:00.000$seoul"), t)
        // 끝 날짜가 다음 날이면 끝 시각이 앞서도 된다
        val next = TypedDate("2026-11-04", "2026-11-05").withTimes(LocalTime.of(22, 0), LocalTime.of(6, 0))
        assertEquals("2026-11-05T06:00:00.000$seoul", next.end)
    }

    @Test
    fun turningTimeOffKeepsTheDatesDropsTheZoneAndAnEndOnTheSameDay() {
        assertEquals(TypedDate("2026-11-04"), TypedDate("2026-11-04T09:00:00.000$seoul", "2026-11-04T10:00:00.000$seoul", "Asia/Seoul").asAllDay())
        assertEquals(TypedDate("2026-11-04", "2026-11-06"), TypedDate("2026-11-04T09:00:00.000$seoul", "2026-11-06T18:00:00.000$seoul").asAllDay())
        assertEquals(TypedDate("2026-11-04", "2026-11-06"), TypedDate("2026-11-04", "2026-11-06").asAllDay()) // 이미 종일이면 그대로
        assertFalse(TypedDate("2026-11-04T09:00:00.000$seoul").asAllDay().isTimed)
    }

    // --- 며칠씩 옮기기(IX-07 '내일로')

    @Test
    fun shiftingMovesAllDayTimedAndRangeValuesKeepingTheirTimes() {
        assertEquals(TypedDate("2026-11-05"), TypedDate("2026-11-04").shiftedDays(1))
        assertEquals(TypedDate("2026-11-05T09:00:00.000$seoul"), TypedDate("2026-11-04T09:00:00.000$seoul").shiftedDays(1))
        assertEquals(
            TypedDate("2026-11-05T09:00:00.000$seoul", "2026-11-05T10:30:00.000$seoul", "Asia/Seoul"),
            TypedDate("2026-11-04T09:00:00.000$seoul", "2026-11-04T10:30:00.000$seoul", "Asia/Seoul").shiftedDays(1),
        )
        assertEquals(TypedDate("2026-11-05", "2026-11-07"), TypedDate("2026-11-04", "2026-11-06").shiftedDays(1)) // 기간은 길이를 지킨다
        assertEquals(TypedDate("2026-11-06T22:00:00.000$seoul", "2026-11-08T06:00:00.000$seoul"), TypedDate("2026-11-04T22:00:00.000$seoul", "2026-11-06T06:00:00.000$seoul").shiftedDays(2))
    }

    @Test
    fun shiftingCrossesMonthYearAndLeapDayBoundariesAndGoesBackwards() {
        assertEquals("2026-12-01", TypedDate("2026-11-30").shiftedDays(1).start)
        assertEquals("2027-01-01", TypedDate("2026-12-31").shiftedDays(1).start)
        assertEquals("2028-02-29", TypedDate("2028-02-28").shiftedDays(1).start) // 윤년
        assertEquals("2027-03-01", TypedDate("2027-02-28").shiftedDays(1).start)
        assertEquals("2026-10-31", TypedDate("2026-11-01").shiftedDays(-1).start)
        assertEquals("2026-11-04", TypedDate("2026-11-04").shiftedDays(0).start)
        assertEquals("2026-11-11", TypedDate("2026-11-04T23:30:00.000$seoul").shiftedDays(7).start.substringBefore('T')) // 자정 직전 시각도 날짜만 옮겨 시각이 밀리지 않는다
        assertEquals("23:30", TypedDate("2026-11-04T23:30:00.000$seoul").shiftedDays(7).startTime().toString())
    }

    @Test
    fun todayIsTheAppZonesDateAsAnAllDayValue() {
        val today = TypedDate.today()
        assertFalse(today.isTimed)
        assertNull(today.end)
        assertTrue(today.startDay() == java.time.LocalDate.now(com.ysdoing.notioncal.core.data.APP_ZONE))
    }
}
