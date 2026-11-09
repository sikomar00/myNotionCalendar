package com.ysdoing.notioncal.feature.quicksheet

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/** 날짜 시트는 ViewModel을 다시 쓰므로, 다른 날을 열면 직전 날의 값이 아니라 빈 값으로 시작한다. */
class ForDayTest {
    private val day3 = LocalDate.of(2026, 11, 3)
    private val day10 = LocalDate.of(2026, 11, 10)

    @Test
    fun aValueOfAnotherDayIsNotShown() {
        val previous = ForDay(day3, listOf("3일 일정"))
        assertEquals(emptyList<String>(), previous.on(day10, emptyList()))
        assertEquals(listOf("3일 일정"), previous.on(day3, emptyList()))
    }
}
