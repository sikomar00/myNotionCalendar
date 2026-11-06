package com.ysdoing.notioncal.core.data.view

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Phase 12d RC-05 별점 글자: 숫자는 ★☆ 다섯 칸으로(반올림·범위 밖은 자름), 선택은 옵션 이름, 비면 없음. */
class RatingTextTest {
    private val number = ViewColumn("r", "별점", "number")
    private val select = ViewColumn("s", "평가", "select", listOf(ViewChoice("o1", "좋음"), ViewChoice("o2", "")))

    @Test
    fun numbersRoundToWholeStarsAndClampToZeroThroughFive() {
        assertEquals("★★★★☆", RatingText.stars(4.0))
        assertEquals("★★★★★", RatingText.stars(4.5)) // 반올림
        assertEquals("★★★★☆", RatingText.stars(4.4))
        assertEquals("☆☆☆☆☆", RatingText.stars(0.0))
        assertEquals("★★★★★", RatingText.stars(9.0)) // 범위 밖은 끝으로
        assertEquals("☆☆☆☆☆", RatingText.stars(-3.0))
        assertEquals("☆☆☆☆☆", RatingText.stars(Double.NaN))
    }

    @Test
    fun aNumberColumnShowsStarsAndSpeaksTheScore() {
        assertEquals("★★★★☆", RatingText.text(number, JsonPrimitive(4.0)))
        assertEquals("별점 4점 (5점 만점)", RatingText.spoken(number, JsonPrimitive(4.0)))
        assertEquals("별점 5점 (5점 만점)", RatingText.spoken(number, JsonPrimitive(7)))
    }

    @Test
    fun aSelectColumnShowsTheOptionNameAndAnEmptyNameIsNothing() {
        assertEquals("좋음", RatingText.text(select, JsonPrimitive("o1")))
        assertEquals("별점 좋음", RatingText.spoken(select, JsonPrimitive("o1")))
        assertNull(RatingText.text(select, JsonPrimitive("o2"))) // 이름이 빈 옵션
        assertNull(RatingText.text(select, JsonPrimitive("gone"))) // 지워진 옵션
    }

    @Test
    fun emptyValuesAndOtherColumnTypesGiveNoLine() {
        assertNull(RatingText.text(number, null))
        assertNull(RatingText.text(number, JsonNull))
        assertNull(RatingText.text(number, JsonPrimitive("4"))) // 글자는 숫자가 아니다
        assertNull(RatingText.text(ViewColumn("t", "메모", "rich_text"), JsonPrimitive("x")))
        assertNull(RatingText.spoken(number, null))
    }
}
