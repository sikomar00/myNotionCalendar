package com.ysdoing.notioncal.core.data.view.chart

import com.ysdoing.notioncal.core.data.NotionColors
import com.ysdoing.notioncal.core.data.habit.HabitStats
import com.ysdoing.notioncal.core.data.view.AggFn
import com.ysdoing.notioncal.core.data.view.AggResult
import com.ysdoing.notioncal.core.data.view.AggTile
import com.ysdoing.notioncal.core.data.view.Aggregator
import com.ysdoing.notioncal.core.data.view.ViewBoard
import com.ysdoing.notioncal.core.data.view.ViewColumn
import com.ysdoing.notioncal.core.data.view.ViewRow
import com.ysdoing.notioncal.core.data.view.startDate
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/**
 * 막대·조각·선의 점 하나. [value]는 그리는 크기, [text]는 값을 읽는 글자(집계 타일과 같은 서식)다.
 * [missing]은 그 묶음에 집계할 값이 없다는 뜻이다(평균·최소·최대인데 숫자가 하나도 없음): 값은 0으로 두되 꺾은선은 그 점을 건너뛴다.
 */
data class ChartPoint(val label: String, val value: Double, val text: String, val colorHex: String, val missing: Boolean = false)

/**
 * 차트 하나의 계산 결과. [valueLabel]은 무엇을 센 값인지('개수', '합계 금액'), [totalText]는 모든 행을 한 번에 집계한 값(number 차트의 값)이다.
 * [partial]은 보기 행을 다 받지 못한(100행 상한) 부분 값이라는 뜻이다. 점이 한도를 넘으면 마지막 점이 남은 것을 합친 '기타'([ChartCompute.OTHER_LABEL])다.
 */
data class ChartResult(
    val spec: ChartSpec,
    val points: List<ChartPoint>,
    val valueLabel: String,
    val totalText: String,
    val partial: Boolean,
)

/**
 * [point]의 비율(%): 점들의 값 합에 대한 몫이다. 개수·합계일 때만 뜻이 있고(평균·최소·최대의 합은 몫이 아니다) 음수는 0으로 보며,
 * 합이 0이면 null이다. 도넛 낭독과 범례가 같은 값을 쓰도록 한 곳에 둔다.
 */
fun ChartResult.sharePercent(point: ChartPoint): Int? {
    if (spec.value.fn != AggFn.COUNT && spec.value.fn != AggFn.SUM) return null
    val sum = points.sumOf { it.value.coerceAtLeast(0.0) }
    return if (sum > 0) (point.value.coerceAtLeast(0.0) / sum * PERCENT).roundToInt() else null
}

private const val PERCENT = 100

/** 차트 색 순환. 노션 색 이름을 앱의 노션 팔레트 값으로 바꿔 쓴다(옵션 색과 같은 계열). */
object ChartPalette {
    private val names = listOf("blue", "orange", "green", "purple", "red", "yellow", "brown", "pink", "gray")
    const val GRAY = "#9B9A97"

    fun at(index: Int): String = NotionColors.hexFor(names[index % names.size]) ?: GRAY
}

/**
 * 보기 행(`ViewRow.typed`)을 x축 속성으로 묶어 집계한 차트 값(순수). 값은 기존 집계 타일의 [Aggregator]를 그룹별 행으로 불러 쓰니 서식·반올림이 같다.
 * 노션이 아니라 Room에 있는 행만 본다. 행은 상한(100) 안이라 더 있으면 [ChartResult.partial]이다.
 */
object ChartCompute {
    const val NONE_LABEL = "없음"
    const val OTHER_LABEL = "기타"
    private const val CHECKED_LABEL = "체크함"
    private const val UNCHECKED_LABEL = "체크 안 함"
    private val EPOCH = LocalDate.of(2000, 1, 1)

    /** 꺾은선에서 빈 날짜 묶음을 채울 때의 점 수 상한. 그보다 넓은 범위는 채우지 않는다(점이 너무 많아진다). */
    private const val MAX_FILL = 120

    /** 묶음 하나. [colorHex]가 있으면 옵션·체크 상태처럼 뜻이 있는 색이다. */
    private class Bucket(val key: String, val label: String, val colorHex: String?, val rows: List<ViewRow>, val none: Boolean = false)

    private class Scored(val bucket: Bucket, val result: AggResult)

    /** [maxPoints]보다 많은 점은 뒤쪽을 합쳐 '기타' 하나로(합친 행으로 다시 집계) 줄인다. 위젯 크기별로 다른 값을 준다. */
    fun compute(board: ViewBoard, spec: ChartSpec, maxPoints: Int = Int.MAX_VALUE): ChartResult {
        val total = aggregate(board, board.rows, spec.value)
        val label = valueLabel(board, spec.value)
        val partial = !board.rowsComplete
        val group = spec.group
        if (group == null) {
            val point = ChartPoint(label, total.number ?: 0.0, total.text, theme(spec) ?: ChartPalette.at(0), missing = total.number == null)
            return ChartResult(spec, listOf(point), label, total.text, partial)
        }
        val column = board.columnInfo.firstOrNull { it.id == group.propId } ?: return ChartResult(spec, emptyList(), label, total.text, partial)
        val fill = spec.type == ChartType.LINE && !spec.hideEmpty
        val buckets = buckets(board.rows, column, group, fill).filter { it.rows.isNotEmpty() || !spec.hideEmpty }
        val scored = sorted(buckets.map { Scored(it, aggregate(board, it.rows, spec.value)) }, spec.sort, group.kind)
        val limit = maxPoints.coerceAtLeast(1)
        val merge = scored.size > limit && limit >= 2
        val shown = if (merge) scored.take(limit - 1) else scored.take(limit)
        val points = shown.mapIndexed { i, s -> point(s, colorFor(s.bucket, spec, i)) }.toMutableList()
        if (merge) {
            val rest = scored.drop(limit - 1).flatMap { it.bucket.rows }.distinctBy { it.pageId }
            val result = aggregate(board, rest, spec.value)
            points += ChartPoint(OTHER_LABEL, result.number ?: 0.0, result.text, ChartPalette.GRAY, missing = result.number == null)
        }
        return ChartResult(spec, points, label, total.text, partial)
    }

    /** 한 가지 색으로 그리는 차트(꺾은선)의 색: `color_theme`의 색, 없거나 auto·colorful이면 팔레트 첫 색. */
    fun accent(spec: ChartSpec): String = theme(spec) ?: ChartPalette.at(0)

    private fun point(scored: Scored, color: String) =
        ChartPoint(scored.bucket.label, scored.result.number ?: 0.0, scored.result.text, color, missing = scored.result.number == null)

    private fun aggregate(board: ViewBoard, rows: List<ViewRow>, value: ChartValue): AggResult =
        Aggregator.compute(board.copy(rows = rows), AggTile("chart", board.viewId, value.fn, value.propId), EPOCH, DayOfWeek.SUNDAY)

    private fun valueLabel(board: ViewBoard, value: ChartValue): String {
        val name = board.columnInfo.firstOrNull { it.id == value.propId }?.name.orEmpty()
        return when (value.fn) {
            AggFn.SUM -> "합계 $name"
            AggFn.AVG -> "평균 $name"
            AggFn.MIN -> "최소 $name"
            AggFn.MAX -> "최대 $name"
            else -> "개수"
        }.trim()
    }

    // --- 묶음

    private fun buckets(rows: List<ViewRow>, column: ViewColumn, group: ChartGroup, fillDates: Boolean): List<Bucket> = when (group.kind) {
        GroupKind.OPTION, GroupKind.MULTI_OPTION -> if (group.byStatusGroup) statusGroupBuckets(rows, column) else optionBuckets(rows, column)
        GroupKind.CHECKBOX -> checkboxBuckets(rows, column)
        GroupKind.DATE -> dateBuckets(rows, column, group, fillDates)
        GroupKind.TEXT -> textBuckets(rows, column)
    }

    /** 옵션 id 하나(선택·상태) 또는 여럿(다중 선택). 값이 없거나 지워진 옵션이면 빈 목록. */
    private fun optionIds(value: JsonElement?, column: ViewColumn): List<String> {
        val ids = when (value) {
            is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            is JsonPrimitive -> listOfNotNull(value.contentOrNull)
            else -> emptyList()
        }
        return ids.filter { id -> column.options.any { it.id == id } }
    }

    private fun optionBuckets(rows: List<ViewRow>, column: ViewColumn): List<Bucket> {
        val byOption = HashMap<String, MutableList<ViewRow>>()
        val none = mutableListOf<ViewRow>()
        rows.forEach { row ->
            val ids = optionIds(row.typed[column.id], column)
            if (ids.isEmpty()) none += row else ids.forEach { byOption.getOrPut(it) { mutableListOf() } += row }
        }
        return column.options.map { Bucket(it.id, it.name, optionColor(it.color), byOption[it.id].orEmpty()) } + noneBucket(none)
    }

    /** 상태를 그룹(할 일·진행 중·완료)으로 묶는다. 그룹 색은 그 그룹 첫 옵션의 색이다. */
    private fun statusGroupBuckets(rows: List<ViewRow>, column: ViewColumn): List<Bucket> {
        val groups = column.options.mapNotNull { it.group }.distinct()
        val byGroup = HashMap<String, MutableList<ViewRow>>()
        val none = mutableListOf<ViewRow>()
        rows.forEach { row ->
            val option = optionIds(row.typed[column.id], column).firstOrNull()?.let { id -> column.options.first { it.id == id } }
            val name = option?.group
            if (name == null) none += row else byGroup.getOrPut(name) { mutableListOf() } += row
        }
        return groups.map { g -> Bucket(g, g, optionColor(column.options.first { it.group == g }.color), byGroup[g].orEmpty()) } + noneBucket(none)
    }

    private fun checkboxBuckets(rows: List<ViewRow>, column: ViewColumn): List<Bucket> {
        val (checked, unchecked) = rows.partition { (it.typed[column.id] as? JsonPrimitive)?.booleanOrNull == true }
        return listOf(
            Bucket("true", CHECKED_LABEL, NotionColors.hexFor("green"), checked),
            Bucket("false", UNCHECKED_LABEL, ChartPalette.GRAY, unchecked),
        )
    }

    /** [fill]이면(꺾은선) 행이 없는 중간 날짜 묶음도 빈 묶음으로 채워 x 간격이 날짜 간격을 따른다. */
    private fun dateBuckets(rows: List<ViewRow>, column: ViewColumn, group: ChartGroup, fill: Boolean): List<Bucket> {
        val byKey = sortedMapOf<String, MutableList<ViewRow>>()
        val none = mutableListOf<ViewRow>()
        rows.forEach { row ->
            val date = row.typed[column.id].startDate()
            if (date == null) none += row else byKey.getOrPut(dateKey(date, group)) { mutableListOf() } += row
        }
        val keys = if (fill) withGaps(byKey.keys.toList(), group) else byKey.keys.toList()
        return keys.map { key -> Bucket(key, dateLabel(key, group), null, byKey[key].orEmpty()) } + noneBucket(none)
    }

    private fun withGaps(keys: List<String>, group: ChartGroup): List<String> {
        val bucket = group.dateBucket
        if (keys.size < 2 || bucket == null) return keys
        val filled = ArrayList<String>()
        var key = keys.first()
        while (key <= keys.last()) { // 같은 단위의 키는 글자 순서가 날짜 순서다
            filled += key
            if (filled.size > MAX_FILL) return keys
            key = nextKey(key, bucket)
        }
        return filled
    }

    private fun nextKey(key: String, bucket: DateBucket): String = when (bucket) {
        DateBucket.DAY -> LocalDate.parse(key).plusDays(1).toString()
        DateBucket.WEEK -> LocalDate.parse(key).plusWeeks(1).toString()
        DateBucket.MONTH -> YearMonth.parse(key).plusMonths(1).toString()
        DateBucket.YEAR -> (key.toInt() + 1).toString()
    }

    private fun dateKey(date: LocalDate, group: ChartGroup): String = when (group.dateBucket) {
        DateBucket.WEEK -> HabitStats.weekStartOf(date, if (group.weekStartsMonday) DayOfWeek.MONDAY else DayOfWeek.SUNDAY).toString()
        DateBucket.MONTH -> date.toString().take(MONTH_KEY)
        DateBucket.YEAR -> date.toString().take(YEAR_KEY)
        else -> date.toString()
    }

    private fun dateLabel(key: String, group: ChartGroup): String = when (group.dateBucket) {
        DateBucket.WEEK -> LocalDate.parse(key).format(DAY_LABEL) + "~"
        DateBucket.MONTH -> key.replace('-', '.')
        DateBucket.YEAR -> key
        else -> LocalDate.parse(key).format(DAY_LABEL)
    }

    private val DAY_LABEL = DateTimeFormatter.ofPattern("M/d")
    private const val MONTH_KEY = 7
    private const val YEAR_KEY = 4

    private fun textBuckets(rows: List<ViewRow>, column: ViewColumn): List<Bucket> {
        val byText = LinkedHashMap<String, MutableList<ViewRow>>()
        val none = mutableListOf<ViewRow>()
        rows.forEach { row ->
            val text = (row.typed[column.id] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            if (text.isEmpty()) none += row else byText.getOrPut(text) { mutableListOf() } += row
        }
        return byText.map { (text, rs) -> Bucket(text, text, null, rs) } + noneBucket(none)
    }

    private fun noneBucket(rows: List<ViewRow>): List<Bucket> =
        if (rows.isEmpty()) emptyList() else listOf(Bucket("", NONE_LABEL, ChartPalette.GRAY, rows, none = true))

    // --- 순서·색

    /** x 기준 정렬은 '없음'을 늘 끝에 둔다(날짜는 날짜 순, 그 밖은 이름 순). 값 기준은 '없음'도 값대로. */
    private fun sorted(list: List<Scored>, sort: ChartSort, kind: GroupKind): List<Scored> {
        fun xKey(s: Scored) = if (kind == GroupKind.DATE) s.bucket.key else s.bucket.label
        val (none, rest) = list.partition { it.bucket.none }
        return when (sort) {
            ChartSort.MANUAL -> list
            ChartSort.X_ASC -> rest.sortedBy(::xKey) + none
            ChartSort.X_DESC -> rest.sortedByDescending(::xKey) + none
            ChartSort.Y_ASC -> list.sortedBy { it.result.number ?: 0.0 }
            ChartSort.Y_DESC -> list.sortedByDescending { it.result.number ?: 0.0 }
        }
    }

    /** 노션 기본색(default)은 색이 아니라서 옵션 색으로 쓰지 않는다. */
    private fun optionColor(name: String?): String? = name?.takeIf { it != "default" }?.let(NotionColors::hexFor)

    private fun theme(spec: ChartSpec): String? =
        spec.colorTheme?.takeIf { it != "auto" && it != "colorful" && it != "default" }?.let(NotionColors::hexFor)

    /** 옵션·체크 상태의 색이 먼저, 없으면 `color_theme` 색(도넛은 조각을 구분해야 해서 제외), 그것도 없으면 팔레트 순환. */
    private fun colorFor(bucket: Bucket, spec: ChartSpec, index: Int): String =
        bucket.colorHex ?: theme(spec)?.takeIf { spec.type != ChartType.DONUT } ?: ChartPalette.at(index)
}
