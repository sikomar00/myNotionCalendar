package com.ysdoing.notioncal

import com.ysdoing.notioncal.core.sync.CompareReport
import com.ysdoing.notioncal.core.sync.Mismatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** §12 대조: '노션과 비교' 결과 문구. */
class CompareDescriptionTest {
    private val from = LocalDate.of(2026, 8, 1)
    private val to = LocalDate.of(2026, 12, 31)

    @Test
    fun matchShowsCountsAndZero() {
        val text = describeComparison(CompareReport(from, to, 93, 93, emptyList()), syncError = null)
        assertEquals("범위 2026-08-01 ~ 2026-12-31\n노션 93건, 앱 93건\n\n불일치 0건 ✓", text)
    }

    @Test
    fun mismatchesAreSummarizedAndListedUpToFive() {
        val day = LocalDate.of(2026, 10, 14)
        val mismatches = List(6) { Mismatch(Mismatch.Kind.REMOTE_ONLY, day, "일정$it") } +
            Mismatch(Mismatch.Kind.DIFFERENT, day, "회의", listOf("title", "done"))
        val text = describeComparison(CompareReport(from, to, 10, 4, mismatches), syncError = null)
        assertTrue(text, text.contains("불일치 7건: 노션에만 6, 앱에만 0, 내용 다름 1"))
        assertTrue(text, text.contains("2026-10-14 일정0 — 노션에만"))
        assertTrue(text, text.endsWith("… 외 2건"))
    }
}
