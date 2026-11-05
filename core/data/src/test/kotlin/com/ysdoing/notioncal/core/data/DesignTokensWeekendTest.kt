package com.ysdoing.notioncal.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/** 부록 A-15: 요일색은 토큰 한 곳에 있다. 값은 옛 복사본(달력·위젯·습관)과 같고, 작은 글자도 읽히는 4.5:1 이상이다. */
class DesignTokensWeekendTest {
    private fun lin(v: Long): Double = (v / 255.0).let { if (it <= 0.03928) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }

    private fun luminance(argb: Long) = 0.2126 * lin(argb shr 16 and 0xFF) + 0.7152 * lin(argb shr 8 and 0xFF) + 0.0722 * lin(argb and 0xFF)

    private fun contrast(a: Long, b: Long): Double {
        val (hi, lo) = luminance(a).let { x -> luminance(b).let { y -> maxOf(x, y) to minOf(x, y) } }
        return (hi + 0.05) / (lo + 0.05)
    }

    @Test
    fun weekendColorsKeepTheValuesTheOldCopiesHad() {
        assertEquals(listOf(0xFFC62828, 0xFF1565C0), listOf(DesignTokens.Light.SUNDAY, DesignTokens.Light.SATURDAY))
        assertEquals(listOf(0xFFFF6B6B, 0xFF5B9BF8), listOf(DesignTokens.Dark.SUNDAY, DesignTokens.Dark.SATURDAY))
    }

    @Test
    fun weekendColorsAreReadableOnThePaperInBothThemes() {
        listOf(DesignTokens.Light.SUNDAY, DesignTokens.Light.SATURDAY).forEach { assertTrue("라이트 $it", contrast(it, DesignTokens.Light.PAPER) >= 4.5) }
        listOf(DesignTokens.Dark.SUNDAY, DesignTokens.Dark.SATURDAY).forEach { assertTrue("다크 $it", contrast(it, DesignTokens.Dark.PAPER) >= 4.5) }
    }
}
