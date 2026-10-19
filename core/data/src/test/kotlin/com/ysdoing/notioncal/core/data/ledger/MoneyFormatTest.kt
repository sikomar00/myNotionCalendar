package com.ysdoing.notioncal.core.data.ledger

import org.junit.Assert.assertEquals
import org.junit.Test

/** LD-03 금액 표기: 1만 미만은 그대로(9,800), 1만 이상은 만 단위 축약(1.2만, 123만). */
class MoneyFormatTest {
    @Test
    fun shortForm() {
        assertEquals("0", MoneyFormat.short(0))
        assertEquals("9,800", MoneyFormat.short(9_800))
        assertEquals("1.2만", MoneyFormat.short(12_300))
        assertEquals("123만", MoneyFormat.short(1_234_000))
        assertEquals("1만", MoneyFormat.short(10_000))
        assertEquals("1.3만", MoneyFormat.short(12_500)) // 반올림
        assertEquals("100만", MoneyFormat.short(999_999))
        assertEquals("-1.5만", MoneyFormat.short(-15_000))
    }

    @Test
    fun fullForm() {
        assertEquals("1,234,000원", MoneyFormat.full(1_234_000))
        assertEquals("0원", MoneyFormat.full(0))
    }

    /** LD-06 금액 숨김: 숫자 대신 같은 표시. */
    @Test
    fun hidden() {
        assertEquals("●●●", MoneyFormat.short(12_300, hidden = true))
        assertEquals("●●●", MoneyFormat.full(12_300, hidden = true))
    }
}
