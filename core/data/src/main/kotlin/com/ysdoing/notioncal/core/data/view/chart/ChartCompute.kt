package com.ysdoing.notioncal.core.data.view.chart

import com.ysdoing.notioncal.core.data.NotionColors
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
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

/** 막대·조각·선의 점 하나. [value]는 그리는 크기, [text]는 값을 읽는 글자(집계 타일과 같은 서식), [rows]는 이 점에 든 행 수다. */
data class ChartPoint(val key: String, val label: String, val value: Double, val text: String, val colorHex: String, val rows: Int)

/**
 * 차트 하나의 계산 결과. [valueLabel]은 무엇을 센 값인지('개수', '합계 금액'), [total]은 모든 행을 한 번에 집계한 값(number 차트의 값이자
 * 막대 합계 대신 쓰는 전체 값)이다. [partial]은 보기 행을 다 받지 못한(100행 상한) 부분 값이라는 뜻이다. [hasOther]면 마지막 점이 남은 것을 합친 '기타'다.
 */
data class ChartResult(
    val spec: ChartSpec,
    val points: List<ChartPoint>,
    val valueLabel: String,
    val total: Double?,
    val totalText: String,
    val partial: Boolean,
    val rowCount: Int,
    val hasOther: Boolean = false,
)

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
            val point = ChartPoint("value", label, total.number ?: 0.0, total.text, theme(spec) ?: ChartPalette.at(0), board.rows.size)
            return ChartResult(spec, listOf(point), label, total.number, total.text, partial, board.rows.size)
        }
        val column = board.columnInfo.firstOrNull { it.id == group.propId }
            ?: return ChartResult(spec, emptyList(), label, total.number, total.text, partial, board.rows.size)
        val scored = sorted(buckets(board.rows, column, group).filter { it.rows.isNotEmpty() || !spec.hideEmpty }.map { Scored(it, aggregate(board, it.rows, spec.value)) }, spec.sort, group.kind)
        val limit = maxPoints.coerceAtLeast(1)
        val merge = scored.size > limit && limit >= 2
        val shown = if (merge) scored.take(limit - 1) else scored.take(limit)
        val points = shown.mapIndexed { i, s -> point(s, colorFor(s.bucket, spec, i)) }.toMutableList()
        if (merge) {
            val rest = scored.drop(limit - 1).flatMap { it.bucket.rows }.distinctBy { it.pageId }
            val result = aggregate(board, rest, spec.value)
            points += ChartPoint("", OTHER_LABEL, result.number ?: 0.0, result.text, ChartPalette.GRAY, rest.size)
        }
        return ChartResult(spec, points, label, total.number, total.text, partial, board.rows.size, hasOther = merge)
    }

    private fun point(scored: Scored, color: String) =
        ChartPoint(scored.bucket.key, scored.bucket.label, scored.result.number ?: 0.0, scored.result.text, color, scored.bucket.rows.size)

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

    private fun buckets(rows: List<ViewRow>, column: ViewColumn, group: ChartGroup): List<Bucket> = when (group.kind) {
        GroupKind.OPTION, GroupKind.MULTI_OPTION -> if (group.byStatusGroup) statusGroupBuckets(rows, column) else optionBuckets(rows, column)
        GroupKind.CHECKBOX -> checkboxBuckets(rows, column)
        GroupKind.DATE -> dateBuckets(rows, column, group)
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

    private fun dateBuckets(rows: List<ViewRow>, column: ViewColumn, group: ChartGroup): List<Bucket> {
        val byKey = sortedMapOf<String, MutableList<ViewRow>>()
        val none = mutableListOf<ViewRow>()
        rows.forEach { row ->
            val date = row.typed[column.id].startDate()
            if (date == null) none += row else byKey.getOrPut(dateKey(date, group)) { mutableListOf() } += row
        }
        return byKey.map { (key, rs) -> Bucket(key, dateLabel(key, group), null, rs) } + noneBucket(none)
    }

    private fun dateKey(date: LocalDate, group: ChartGroup): String = when (group.dateBucket) {
        DateBucket.WEEK -> date.with(TemporalAdjusters.previousOrSame(if (group.weekStartsMonday) DayOfWeek.MONDAY else DayOfWeek.SUNDAY)).toString()
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
