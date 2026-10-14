package com.ysdoing.notioncal.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** SEC-02: 토큰 3종과 일정 제목이 밖으로 나가는 글에서 사라지는지. 픽스처 토큰은 형식만 흉내 낸 가짜 값이다. */
class RedactorTest {
    private val fakeNtn = "ntn_" + "A1b2C3d4E5f6G7h8"
    private val fakeSecret = "secret_" + "Zz9Yy8Xx7Ww6"

    @Test
    fun tokensAreRemoved() {
        val text = "토큰 $fakeNtn, 옛 토큰 $fakeSecret, 헤더 Authorization: Bearer $fakeNtn"
        val scrubbed = Redactor.scrubTokens(text)
        assertFalse(scrubbed.contains(fakeNtn))
        assertFalse(scrubbed.contains(fakeSecret))
        assertFalse(scrubbed.contains("A1b2C3d4"))
        assertTrue(scrubbed.contains(Redactor.TOKEN_MASK))
    }

    @Test
    fun ordinaryWordsStay() {
        assertEquals("secret_ 단독, ntn 글자", Redactor.scrubTokens("secret_ 단독, ntn 글자"))
    }

    @Test
    fun reportHidesResponseBodies() {
        val message = """Unexpected JSON token at offset 120: Expected string literal but 'null' literal was found at path: ${'$'}.results[0].properties.Name.title[0].plain_text
JSON input: {"results":[{"properties":{"Name":{"title":[{"plain_text":"병원 예약"}]}}}]}"""
        val scrubbed = Redactor.scrubReport(message)
        assertFalse(scrubbed.contains("병원 예약"))
        assertTrue(scrubbed.contains("Unexpected JSON token")) // 오류 종류는 남는다
    }

    @Test
    fun reportHidesQuotedTitles() {
        val scrubbed = Redactor.scrubReport("""Failed to save "치과 검진" with $fakeNtn""")
        assertFalse(scrubbed.contains("치과 검진"))
        assertFalse(scrubbed.contains(fakeNtn))
    }

    @Test
    fun titleMaskIsStableAndOpaque() {
        val a = Redactor.maskTitle("영어 스터디")
        assertEquals(a, Redactor.maskTitle("영어 스터디"))
        assertNotEquals(a, Redactor.maskTitle("회의"))
        assertFalse(a.contains("영어"))
    }
}
