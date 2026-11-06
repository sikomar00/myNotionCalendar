package com.ysdoing.notioncal.core.data.view

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.abs

/**
 * Phase 12c AG-03 노션 숫자 속성의 `number.format`을 글자로 바꾸는 순수 규칙. 형식은 **표시만** 바꾸고 저장된 값은 그대로다(노션 문서: 0.25 + percent = 25%).
 * 형식이 없거나(`""`) `number`·모르는 글자면 예전과 같은 기본 글자(정수는 소수점 없이)다. `number_with_commas`는 쉼표, `percent`는 100배 + %,
 * 통화는 기호 + 쉼표이고 원·엔처럼 소수를 쓰지 않는 통화는 소수 없이, 나머지는 소수 둘이다.
 */
object NumberFormats {
    private class Currency(val symbol: String, val decimals: Int)

    private val CURRENCIES = mapOf(
        "dollar" to Currency("$", 2), "canadian_dollar" to Currency("CA$", 2), "singapore_dollar" to Currency("S$", 2), "hong_kong_dollar" to Currency("HK$", 2),
        "new_zealand_dollar" to Currency("NZ$", 2), "new_taiwan_dollar" to Currency("NT$", 2), "euro" to Currency("€", 2), "pound" to Currency("£", 2),
        "yen" to Currency("¥", 0), "yuan" to Currency("CN¥", 2), "won" to Currency("₩", 0), "rupee" to Currency("₹", 2), "ruble" to Currency("₽", 2),
        "real" to Currency("R$", 2), "lira" to Currency("₺", 2), "rupiah" to Currency("Rp", 0), "franc" to Currency("CHF ", 2), "krona" to Currency("kr ", 2),
        "norwegian_krone" to Currency("kr ", 2), "danish_krone" to Currency("kr ", 2), "mexican_peso" to Currency("MX$", 2), "rand" to Currency("R ", 2),
        "zloty" to Currency("zł ", 2), "baht" to Currency("฿", 2), "forint" to Currency("Ft ", 0), "koruna" to Currency("Kč ", 2), "shekel" to Currency("₪", 2),
        "chilean_peso" to Currency("CLP$", 0), "philippine_peso" to Currency("₱", 2), "dirham" to Currency("AED ", 2), "colombian_peso" to Currency("COL$", 0),
        "riyal" to Currency("SAR ", 2), "ringgit" to Currency("RM", 2), "leu" to Currency("lei ", 2), "argentine_peso" to Currency("ARS$", 2),
        "uruguayan_peso" to Currency("UYU$", 2), "peruvian_sol" to Currency("S/ ", 2),
    )

    private const val PERCENT_DECIMALS = 2
    private const val MAX_COMMA_DECIMALS = 10
    private const val MAX_PLAIN = 1e15

    /** 집계 글자가 노션 형식을 따를 형식인가(퍼센트·통화). 쉼표 형식과 기본은 집계 타일이 이미 쉼표로 쓴다. */
    fun isStyled(format: String): Boolean = format == "percent" || format in CURRENCIES

    /** [value]를 [format]대로. 유한하지 않은 값은 빈 글자. */
    fun format(value: Double, format: String): String {
        if (!value.isFinite()) return ""
        val currency = CURRENCIES[format]
        return when {
            format == "percent" -> signed(value, BigDecimal(value.toString()).movePointRight(2), PERCENT_DECIMALS, trim = true, suffix = "%")
            format == "number_with_commas" -> signed(value, BigDecimal(value.toString()), MAX_COMMA_DECIMALS, trim = true)
            currency != null -> signed(value, BigDecimal(value.toString()), currency.decimals, trim = false, prefix = currency.symbol)
            else -> plain(value)
        }
    }

    /** 형식이 없을 때의 기본: 정수(1e15 미만)는 소수점 없이, 나머지는 있는 그대로. */
    private fun plain(n: Double): String = if (n % 1.0 == 0.0 && abs(n) < MAX_PLAIN) n.toLong().toString() else n.toString()

    /** 부호는 기호 앞에 붙고(-$1,234.50), 반올림해 0이면 붙지 않는다. [trim]이면 끝의 0을 지운다(소수를 쓴 만큼만 보인다). */
    private fun signed(value: Double, scaled: BigDecimal, decimals: Int, trim: Boolean, prefix: String = "", suffix: String = ""): String {
        val rounded = scaled.abs().setScale(decimals, RoundingMode.HALF_UP)
        val body = grouped(if (trim) rounded.stripTrailingZeros().let { if (it.scale() < 0) it.setScale(0) else it } else rounded)
        return (if (value < 0 && rounded.signum() != 0) "-" else "") + prefix + body + suffix
    }

    private fun grouped(n: BigDecimal): String {
        val pattern = "#,##0" + if (n.scale() > 0) "." + "0".repeat(n.scale()) else ""
        return DecimalFormat(pattern, DecimalFormatSymbols(Locale.US)).apply { roundingMode = RoundingMode.HALF_UP }.format(n)
    }
}
