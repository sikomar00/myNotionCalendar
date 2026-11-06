package com.ysdoing.notioncal.core.data.view

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 위젯에서 눌러 바꿀 수 있는 열의 조건과 표시 규칙. */
class ViewColumnTest {
    private val two = listOf(ViewChoice("a", "하나"), ViewChoice("b", "둘"))

    @Test
    fun checkboxIsAlwaysTappable() {
        assertTrue(ViewColumn("c", "완료", "checkbox").tappable)
    }

    @Test
    fun statusAndSelectNeedAtLeastTwoOptionsToBeTappable() {
        assertTrue(ViewColumn("s", "상태", "status", two).tappable)
        assertTrue(ViewColumn("s", "분류", "select", two).tappable)
        assertFalse(ViewColumn("s", "상태", "status", two.take(1)).tappable) // 눌러도 같은 값
        assertFalse(ViewColumn("s", "분류", "select").tappable) // 선택지가 없다
    }

    @Test
    fun numbersTitlesAndDatesAreNotTapped() {
        listOf("number", "title", "date").forEach { assertFalse(it, ViewColumn("x", "열", it, two).tappable) }
    }

    @Test
    fun nextOptionWrapsAndStartsFromTheFirstWhenThereIsNoCurrent() {
        val column = ViewColumn("s", "상태", "status", two)
        assertEquals("b", column.nextOption("a")?.id)
        assertEquals("a", column.nextOption("b")?.id)
        assertEquals("a", column.nextOption(null)?.id)
        assertNull(ViewColumn("s", "상태", "status").nextOption("a"))
    }

    @Test
    fun hugeWholeNumbersStayCorrectInsteadOfOverflowingLong() {
        val number = ViewColumn("n", "쪽수", "number")
        assertEquals("12", number.display(JsonPrimitive(12.0)))
        assertEquals("1.0E19", number.display(JsonPrimitive(1e19))) // Long.MAX_VALUE(9223372036854775807)로 잘못 보이지 않는다
    }
}
