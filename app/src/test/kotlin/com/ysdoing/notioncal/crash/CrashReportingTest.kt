package com.ysdoing.notioncal.crash

import io.sentry.Breadcrumb
import io.sentry.SentryEvent
import io.sentry.protocol.Message
import io.sentry.protocol.SentryException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** SEC-02: 크래시 리포트에 토큰·일정 제목이 남지 않는다. 토큰은 형식만 흉내 낸 가짜 값이다. */
class CrashReportingTest {
    private val fakeToken = "ntn_" + "Zx9Cv8Bn7Mm6Ll5K"

    @Test
    fun exceptionMessageAndBodyAreScrubbed() {
        val event = SentryEvent().apply {
            exceptions = listOf(
                SentryException().apply {
                    type = "JsonDecodingException"
                    value = """Unexpected JSON token at offset 9
JSON input: {"plain_text":"병원 예약"}"""
                },
                SentryException().apply {
                    type = "IllegalStateException"
                    value = "Authorization: Bearer $fakeToken"
                },
            )
            message = Message().apply { formatted = "저장 실패 \"치과 검진\"" }
        }
        val text = CrashReporting.scrub(event).let { e ->
            e.exceptions!!.joinToString { it.value.orEmpty() } + e.message!!.formatted
        }
        assertFalse(text.contains("병원 예약"))
        assertFalse(text.contains("치과 검진"))
        assertFalse(text.contains(fakeToken))
        assertTrue(text.contains("Unexpected JSON token")) // 오류 종류는 남는다
        assertEquals("JsonDecodingException", event.exceptions!![0].type)
    }

    @Test
    fun httpBreadcrumbsAreDropped() {
        assertNull(CrashReporting.scrub(Breadcrumb.http("https://api.notion.com/v1/pages/abc", "PATCH")))
    }

    @Test
    fun otherBreadcrumbsKeepOnlyScrubbedText() {
        val crumb = Breadcrumb("화면 \"영어 스터디\" 열림").apply { setData("title", "\"영어 스터디\"") }
        val scrubbed = CrashReporting.scrub(crumb)!!
        assertFalse(scrubbed.message!!.contains("영어 스터디"))
        assertFalse((scrubbed.getData("title") as String).contains("영어 스터디"))
    }
}
