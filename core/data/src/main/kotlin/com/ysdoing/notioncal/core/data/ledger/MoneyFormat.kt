package com.ysdoing.notioncal.core.data.ledger

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs

/** LD-03 금액 표기. 원 단위 Long만 받는다. LD-06 금액 숨김이면 [HIDDEN]. */
object MoneyFormat {
    const val HIDDEN = "●●●"
    private const val MAN = 10_000L

    private fun grouped(value: Long): String = NumberFormat.getIntegerInstance(Locale.KOREA).format(value)

    /** 1만 미만은 그대로(9,800), 1만 이상은 만 단위(1.2만, 100만 이상은 정수 123만). 반올림. */
    fun short(value: Long, hidden: Boolean = false): String {
        if (hidden) return HIDDEN
        val sign = if (value < 0) "-" else ""
        val a = abs(value)
        if (a < MAN) return sign + grouped(a)
        val man = BigDecimal(a).divide(BigDecimal(MAN))
        val scale = if (man >= BigDecimal(100)) 0 else 1
        val text = man.setScale(scale, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
        return "$sign${text}만"
    }

    fun full(value: Long, hidden: Boolean = false): String = if (hidden) HIDDEN else grouped(value) + "원"
}
