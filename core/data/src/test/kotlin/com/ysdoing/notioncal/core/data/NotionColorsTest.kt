package com.ysdoing.notioncal.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 부록 A-14: 색 견본 낭독은 '#DFAB01'·'yellow'가 아니라 한글 색 이름이다. */
class NotionColorsTest {
    @Test
    fun everyPickerColorHasAUniqueKoreanName() {
        val names = NotionColors.pickerPalette.map { NotionColors.nameKo(it) }
        assertEquals(20, names.size)
        assertEquals("이름이 겹친다: $names", names.size, names.toSet().size)
        names.forEach {
            assertFalse("hex 그대로: $it", it.startsWith("#"))
            assertTrue("한글이 아니다: $it", it.any { c -> c in '가'..'힣' })
        }
    }

    @Test
    fun theNotionKeysAndTheirHexesShareTheSameName() {
        assertEquals("노랑", NotionColors.nameKo("#DFAB01"))
        assertEquals("노랑", NotionColors.nameKo(NotionColors.hexFor("yellow")!!))
        assertEquals("빨강", NotionColors.nameKo("#e03e3e")) // 대소문자를 가리지 않는다
    }

    @Test
    fun anUnknownColorIsStillSpoken() {
        assertEquals("사용자 색", NotionColors.nameKo("#123456"))
    }

    private fun lin(v: Int): Double = (v / 255.0).let { if (it <= 0.03928) it / 12.92 else Math.pow((it + 0.055) / 1.055, 2.4) }

    private fun luminance(argb: Int) = 0.2126 * lin(argb shr 16 and 0xFF) + 0.7152 * lin(argb shr 8 and 0xFF) + 0.0722 * lin(argb and 0xFF)

    @Test
    fun theTextColorOnEveryPickerColorStaysAtTheContrastTheEmphasisDesignAccepted() {
        // EX-11 설계는 흰색·먹색 중 큰 쪽을 쓴다. 20색의 가장 낮은 값은 빨강 #E03E3E의 4.26:1(흰 글자)이고 그 아래로 내려가지 않게 고정한다.
        NotionColors.pickerPalette.forEach { hex ->
            val background = luminance(NotionColors.argb(hex))
            val text = luminance(NotionColors.onColor(hex))
            val contrast = (maxOf(background, text) + 0.05) / (minOf(background, text) + 0.05)
            assertTrue("$hex 대비 $contrast", contrast >= 4.2)
        }
    }
}
