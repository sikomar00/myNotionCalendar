package com.ysdoing.notioncal.core.data.view

import com.ysdoing.notioncal.core.data.habit.HabitStats
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Phase 11e AG-01 집계 함수 7종. [needsProperty]면 값을 읽을 속성이 필요하고(합계·평균·최소·최대=숫자, 비율·연속 달성일=체크박스), 개수는 속성이 없다.
 * [needsDate]인 연속 달성일은 날짜 속성도 있어야 한다(나머지는 기간을 고를 때만).
 */
enum class AggFn(val needsProperty: Boolean, val needsDate: Boolean = false, private val type: String? = null) {
    COUNT(needsProperty = false),
    SUM(needsProperty = true, type = "number"),
    AVG(needsProperty = true, type = "number"),
    MIN(needsProperty = true, type = "number"),
    MAX(needsProperty = true, type = "number"),
    RATIO(needsProperty = true, type = "checkbox"),
    STREAK(needsProperty = true, needsDate = true, type = "checkbox"),
    ;

    /** [column]이 이 함수의 속성으로 쓸 수 있는 종류인가. */
    fun accepts(column: ViewColumn): Boolean = type == null || column.type == type
}

/** Phase 12c 타일 색으로 고를 수 있는 노션 색 이름(옵션 색과 같은 계열). 빈 글자는 위젯 기본색이다. */
val TILE_COLORS = listOf("gray", "brown", "orange", "yellow", "green", "blue", "purple", "pink", "red")

/** 타일 종류: 값 하나([AGG])와, 분류 속성으로 묶은 도넛([DONUT], Phase 12c RC-04 '분류별 도넛'). */
enum class TileKind { AGG, DONUT }

/** 타일이 세는 기간. 날짜 속성([AggTile.dateProp])의 시작 날짜가 기준이다. 연속 달성일은 기간을 무시한다. */
enum class AggPeriod { ALL, TODAY, THIS_WEEK, THIS_MONTH }

/**
 * 집계 타일 하나(AG-01): 보기 + 속성 + 함수 + 기간 + 목표(AG-02). [id]는 위젯 안에서 겹치지 않는 이름(`WidgetLayout`이 붙인다),
 * [label]이 비면 위젯이 함수·속성 이름으로 만든다. 목표는 0 초과일 때만 뜻이 있다(비율은 %, 연속 달성일은 일 단위).
 */
@Serializable
data class AggTile(
    val id: String,
    val viewId: String,
    val fn: AggFn = AggFn.COUNT,
    val propId: String = "",
    val dateProp: String = "",
    val period: AggPeriod = AggPeriod.ALL,
    val goal: Double? = null,
    val label: String = "",
    /** 타일 색([TILE_COLORS], 빈 글자 = 기본): 라벨 앞 점과 진행 막대 채움에 쓴다. 글자 색은 바꾸지 않아 대비가 그대로다. */
    val color: String = "",
    /** 타일 종류. 도넛은 [groupProp](선택·상태·다중 선택 속성)으로 묶어 [fn](개수·합계)으로 센 값의 몫을 보여 준다. 옛 JSON은 값 타일이다. */
    val kind: TileKind = TileKind.AGG,
    val groupProp: String = "",
) {
    /**
     * 읽을 때 앞뒤 공백·뜻 없는 값을 바로잡는다. 필요한 속성이 없거나 번호·보기가 빈 타일은 null(버린다).
     * 도넛은 묶을 속성이 있어야 하고 함수는 개수·합계뿐이며 목표·색은 없다(조각 색은 옵션 색이다).
     */
    fun normalized(): AggTile? {
        val tileId = id.trim()
        val view = viewId.trim()
        val prop = propId.trim()
        val date = dateProp.trim()
        val group = groupProp.trim()
        val donut = kind == TileKind.DONUT
        if (tileId.isEmpty() || view.isEmpty() || (fn.needsProperty && prop.isEmpty()) || (fn.needsDate && date.isEmpty())) return null
        if (donut && (group.isEmpty() || (fn != AggFn.COUNT && fn != AggFn.SUM))) return null
        return copy(
            id = tileId, viewId = view, propId = prop, dateProp = date, label = label.trim(),
            period = if (date.isEmpty()) AggPeriod.ALL else period, // 날짜 속성이 없으면 기간은 전체
            goal = goal?.takeIf { it.isFinite() && it > 0 && !donut },
            color = color.trim().takeIf { it in TILE_COLORS && !donut }.orEmpty(),
            groupProp = if (donut) group else "",
        )
    }
}

/**
 * 타일 하나의 계산 결과. [number]가 null이면 계산할 값이 없다(속성이 사라졌거나 값이 하나도 없음, [text]는 '—').
 * [partial]은 보기 행을 다 받지 못해(`rowsComplete=false`) 부분 값이라는 뜻이고, [progress]는 목표가 있을 때 막대 비율 0~1,
 * [goalText]는 막대 옆 글자('목표 80%', '목표 12% 초과')다.
 */
data class AggResult(val number: Double?, val text: String, val partial: Boolean, val progress: Float?, val goalText: String?) {
    companion object {
        const val NONE = "—"
        fun unavailable(partial: Boolean) = AggResult(null, NONE, partial, null, null)
    }
}

/** 보기 행(`ViewRow.typed`의 원래 값)으로 집계 타일을 계산하는 순수 함수. 노션이 아니라 Room에 있는 행만 본다. */
object Aggregator {
    fun compute(board: ViewBoard, tile: AggTile, today: LocalDate, weekStart: DayOfWeek): AggResult {
        val partial = !board.rowsComplete
        val prop = board.columnInfo.firstOrNull { it.id == tile.propId }
        val date = board.columnInfo.firstOrNull { it.id == tile.dateProp }
        val broken = (tile.fn.needsProperty && (prop == null || !tile.fn.accepts(prop))) ||
            ((tile.fn.needsDate || tile.period != AggPeriod.ALL) && date?.type != "date")
        if (broken) return AggResult.unavailable(partial)
        val value = if (tile.fn == AggFn.STREAK) streak(board.rows, tile, today) else value(inPeriod(board.rows, tile, today, weekStart), tile)
        return result(value, tile, partial, prop?.numberFormat.orEmpty())
    }

    /** 타일의 기간에 드는 행(기간이 전체거나 날짜 속성이 없으면 모두). 도넛 타일도 같은 기간 규칙을 쓴다. */
    internal fun inPeriod(rows: List<ViewRow>, tile: AggTile, today: LocalDate, weekStart: DayOfWeek): List<ViewRow> {
        val range = when (tile.period) {
            AggPeriod.ALL -> return rows
            AggPeriod.TODAY -> today..today
            AggPeriod.THIS_WEEK -> HabitStats.weekStartOf(today, weekStart).let { it..it.plusDays(6) }
            AggPeriod.THIS_MONTH -> today.withDayOfMonth(1)..today.withDayOfMonth(today.lengthOfMonth())
        }
        return rows.filter { row -> row.typed[tile.dateProp].startDate()?.let { it in range } == true }
    }

    private fun value(rows: List<ViewRow>, tile: AggTile): Double? {
        if (tile.fn == AggFn.COUNT) return rows.size.toDouble()
        if (tile.fn == AggFn.RATIO) return rows.takeIf { it.isNotEmpty() }?.let { r -> r.count { it.checked(tile.propId) } * 100.0 / r.size }
        val numbers = rows.mapNotNull { (it.typed[tile.propId] as? JsonPrimitive)?.doubleOrNull }.filter { it.isFinite() }
        return when (tile.fn) {
            AggFn.SUM -> numbers.sum()
            AggFn.AVG -> numbers.takeIf { it.isNotEmpty() }?.average()
            AggFn.MIN -> numbers.minOrNull()
            else -> numbers.maxOrNull()
        }
    }

    /**
     * 연속 달성일(AG-04): 체크된 페이지가 있는 날(하루 1페이지로 본다)이 끊기지 않은 일수. 오늘이 아직 미체크여도 끊김으로 보지 않고 어제부터 센다.
     * 해빗 위젯의 연속(하루 쉼은 건너뜀, `HabitStats`)과 규칙이 달라 함수를 나눠 둔다. 기간은 무시한다.
     */
    private fun streak(rows: List<ViewRow>, tile: AggTile, today: LocalDate): Double {
        val days = rows.filter { it.checked(tile.propId) }.mapNotNull { it.typed[tile.dateProp].startDate() }.toSet()
        var day = if (today in days) today else today.minusDays(1)
        var count = 0
        while (day in days) {
            count++
            day = day.minusDays(1)
        }
        return count.toDouble()
    }

    /**
     * [format]은 값을 읽은 숫자 속성의 노션 형식이다. 합계·평균·최소·최대는 퍼센트·통화 형식이면 그 모양(`₩12,000`, `25%`)으로 쓰고(정밀도는 형식이 정한다),
     * 개수·비율·연속 일수와 쉼표·기본 형식은 아래 고정 서식이다.
     */
    private fun result(value: Double?, tile: AggTile, partial: Boolean, format: String): AggResult {
        if (value == null) return AggResult.unavailable(partial)
        // 목표 판정과 막대는 화면에 보이는(반올림한) 값으로 한다. 안 그러면 '80%' 옆에 '목표 99%'가 뜰 수 있다.
        val shown = BigDecimal(value).setScale(decimals(tile.fn), RoundingMode.HALF_UP).toDouble()
        val styled = tile.fn in NUMBER_VALUED && NumberFormats.isStyled(format)
        val text = when {
            styled -> NumberFormats.format(value, format)
            else -> plainText(shown, tile.fn)
        }
        val goal = tile.goal?.takeIf { it > 0 }
        return AggResult(value, text, partial, goal?.let { (shown / it).toFloat().coerceIn(0f, 1f) }, goal?.let { goalText(shown, it) })
    }

    private val NUMBER_VALUED = setOf(AggFn.SUM, AggFn.AVG, AggFn.MIN, AggFn.MAX)

    private fun plainText(shown: Double, fn: AggFn): String {
        return when (fn) {
            AggFn.COUNT -> format(shown, "#,##0")
            AggFn.AVG -> format(shown, "#,##0.#")
            AggFn.RATIO -> "${shown.roundToInt()}%"
            AggFn.STREAK -> "${shown.roundToInt()}일"
            else -> format(shown, "#,##0.##")
        }
    }

    /** 함수별로 화면에 보여 주는 소수 자릿수. */
    private fun decimals(fn: AggFn): Int = when (fn) {
        AggFn.COUNT, AggFn.RATIO, AggFn.STREAK -> 0
        AggFn.AVG -> 1
        else -> 2
    }

    /**
     * 모자라면 내림(99.6%를 100%로 읽히게 하지 않는다), 넘으면 올림(조금이라도 넘으면 0%가 아니라 1%).
     * 목표와 같은 값(0.1 + 0.2 = 0.30000000000000004 같은 부동소수 잡음 포함)은 넘은 것으로 보지 않는다.
     */
    private fun goalText(value: Double, goal: Double): String = when {
        value > goal + EPSILON * maxOf(1.0, abs(goal)) -> "목표 ${ceil((value / goal - 1) * 100 - EPSILON).toInt().coerceAtLeast(1)}% 초과"
        else -> "목표 ${floor(value / goal * 100 + EPSILON).toInt().coerceAtLeast(0)}%"
    }

    private fun format(value: Double, pattern: String): String {
        val format = DecimalFormat(pattern, DecimalFormatSymbols(Locale.US)).apply { roundingMode = RoundingMode.HALF_UP }
        return format.format(value).let { if (it == "-0") "0" else it }
    }

    private fun ViewRow.checked(propId: String) = (typed[propId] as? JsonPrimitive)?.booleanOrNull == true

    private const val EPSILON = 1e-9
}
