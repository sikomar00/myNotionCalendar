package com.ysdoing.notioncal.feature.month

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.ysdoing.notioncal.core.data.CalendarEvent
import com.ysdoing.notioncal.core.data.NotionColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

/** 부록 A-1·A-2: 끄는 칩의 글자는 배경 색과 4.2:1 이상(강조 칸과 같은 규칙)으로 대비되고, 완료 글자 투명도는 한 상수([DONE_TEXT_ALPHA])만 쓴다. */
class DragChipColorsTest {
    private fun event(hex: String) = CalendarEvent(
        pageId = "p", dataSourceId = "ds", title = "t", startDate = LocalDate.of(2026, 11, 5), endDate = null, startTime = null, endTime = null,
        isAllDay = true, done = false, colorHex = hex, sourceColorHex = hex, url = "",
    )

    private fun contrast(a: Color, b: Color): Double {
        val (hi, lo) = maxOf(a.luminance(), b.luminance()) to minOf(a.luminance(), b.luminance())
        return (hi + 0.05) / (lo + 0.05)
    }

    @Test
    fun theDraggedChipTextContrastsWithItsOpaqueColorForEveryPickerColor() {
        NotionColors.pickerPalette.forEach { hex ->
            val (background, text) = dragChipColors(event(hex))
            assertEquals("불투명이어야 비치지 않는다: $hex", 1f, background.alpha, 0f)
            // 글자색은 EX-11 강조 칸과 같은 규칙(흰색·먹색 중 큰 쪽)이라 20색 최저는 빨강 4.26:1이다. 노랑·하늘색 위 흰 글자 1.5:1(A-2)이 사라졌는지 본다.
            assertTrue("$hex 대비 ${contrast(background, text)}", contrast(background, text) >= 4.2)
        }
    }

    @Test
    fun agendaAndSearchUseTheSharedDoneTextAlphaInsteadOfA55PercentLiteral() {
        listOf("AgendaView.kt", "SearchScreen.kt").forEach { name ->
            val source = File("src/main/kotlin/com/ysdoing/notioncal/feature/month/$name")
            assertTrue("소스를 찾지 못했습니다: $source", source.isFile)
            val text = source.readText()
            assertTrue("$name: 완료 글자를 55%로 직접 줄이는 코드가 남았다", !Regex("alpha\\(if \\(event\\.done\\)").containsMatchIn(text))
            assertTrue("$name: barTextAlpha를 쓴다", "barTextAlpha(event)" in text)
        }
    }
}
