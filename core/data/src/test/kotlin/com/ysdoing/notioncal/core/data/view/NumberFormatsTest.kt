package com.ysdoing.notioncal.core.data.view

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 12c AG-03 노션 숫자 형식. 형식은 표시만 바꾸고(0.25 + percent = 25%) 저장값은 그대로다. */
class NumberFormatsTest {
    private fun f(value: Double, format: String) = NumberFormats.format(value, format)

    @Test
    fun noFormatPlainAndUnknownFormatsKeepTheOldPlainText() {
        for (format in listOf("", "number", "bitcoin")) {
            assertEquals("12", f(12.0, format))
            assertEquals("1.5", f(1.5, format))
            assertEquals("-7", f(-7.0, format))
            assertEquals("1234567", f(1_234_567.0, format)) // 쉼표 없음: 예전 표시 그대로
        }
    }

    @Test
    fun commasAddThousandsSeparatorsAndKeepTheDecimalsThatWereEntered() {
        assertEquals("1,234,567", f(1_234_567.0, "number_with_commas"))
        assertEquals("1,234.5", f(1234.5, "number_with_commas"))
        assertEquals("-9,876.25", f(-9876.25, "number_with_commas"))
        assertEquals("0", f(0.0, "number_with_commas"))
        assertEquals("0.001", f(0.001, "number_with_commas"))
    }

    @Test
    fun percentIsTheFractionTimesOneHundredWithUpToTwoDecimals() {
        assertEquals("25%", f(0.25, "percent"))
        assertEquals("25.5%", f(0.255, "percent"))
        assertEquals("100%", f(1.0, "percent"))
        assertEquals("0%", f(0.0, "percent"))
        assertEquals("-50%", f(-0.5, "percent"))
        assertEquals("12.35%", f(0.12345, "percent")) // 12.345 → 반올림
        assertEquals("1,250%", f(12.5, "percent"))
    }

    @Test
    fun twoDecimalCurrenciesShowTheSymbolCommasAndTwoDecimals() {
        assertEquals("$1,234.50", f(1234.5, "dollar"))
        assertEquals("$0.00", f(0.0, "dollar"))
        assertEquals("-$3.00", f(-3.0, "dollar"))
        assertEquals("€1,000.00", f(1000.0, "euro"))
        assertEquals("£12.35", f(12.345, "pound")) // 반올림
        assertEquals("₹5.00", f(5.0, "rupee"))
        assertEquals("CN¥99.90", f(99.9, "yuan"))
        assertEquals("฿1,200.00", f(1200.0, "baht"))
        assertEquals("CHF 12.00", f(12.0, "franc"))
        assertEquals("CA$7.50", f(7.5, "canadian_dollar"))
    }

    @Test
    fun zeroDecimalCurrenciesShowNoDecimalsAndRound() {
        assertEquals("₩12,000", f(12000.0, "won"))
        assertEquals("-₩500", f(-500.0, "won"))
        assertEquals("¥1,235", f(1234.6, "yen"))
        assertEquals("Ft 1,000", f(1000.0, "forint"))
        assertEquals("Rp15,000", f(15000.0, "rupiah"))
    }

    @Test
    fun aValueThatRoundsToZeroHasNoMinusSign() {
        assertEquals("$0.00", f(-0.004, "dollar"))
        assertEquals("₩0", f(-0.4, "won"))
        assertEquals("0%", f(-0.00001, "percent"))
    }

    @Test
    fun hugeValuesStayExactAndNonFiniteValuesAreEmpty() {
        assertEquals("$1,234,567,890,123.45", f(1234567890123.45, "dollar"))
        assertEquals("", f(Double.NaN, "dollar"))
        assertEquals("", f(Double.POSITIVE_INFINITY, "percent"))
    }

    @Test
    fun onlyPercentAndCurrenciesAreStyledForAggregation() {
        for (format in listOf("percent", "dollar", "won", "euro", "yen", "peruvian_sol")) assertTrue(format, NumberFormats.isStyled(format))
        for (format in listOf("", "number", "number_with_commas", "bitcoin")) assertFalse(format, NumberFormats.isStyled(format))
    }

    // --- ViewColumn

    @Test
    fun aNumberColumnDisplaysByItsFormatAndAnOldColumnWithoutOneIsUnchanged() {
        val won = ViewColumn("a", "금액", "number", numberFormat = "won")
        assertEquals("₩12,000", won.display(JsonPrimitive(12000.0)))
        assertEquals("", won.display(null))
        assertEquals("12000", ViewColumn("a", "금액", "number").display(JsonPrimitive(12000.0)))
        assertEquals("1.5", ViewColumn("a", "금액", "number").display(JsonPrimitive(1.5)))
        assertEquals("25%", ViewColumn("a", "달성", "number", numberFormat = "percent").display(JsonPrimitive(0.25)))
    }

    @Test
    fun theFormatIsStoredInColumnInfoJsonOnlyWhenThereIsOne() {
        val json = ViewJson.encodeColumns(listOf(ViewColumn("a", "금액", "number", numberFormat = "won"), ViewColumn("b", "이름", "title")))
        assertEquals(listOf("won", ""), ViewJson.columns(json).map { it.numberFormat })
        assertEquals(1, Regex("numberFormat").findAll(json).count()) // 기본값은 쓰지 않아 옛 JSON과 같은 모양
        // 옛 스냅샷(필드 없음)도 읽힌다
        assertEquals("", ViewJson.columns("""[{"id":"a","name":"금액","type":"number"}]""").single().numberFormat)
    }
}
