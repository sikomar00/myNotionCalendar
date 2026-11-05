package com.ysdoing.notioncal.core.data.view.chart

import com.ysdoing.notioncal.core.data.view.AggFn
import com.ysdoing.notioncal.core.data.view.ViewColumn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * 노션 보기 `configuration`(원본 JSON, [com.ysdoing.notioncal.core.data.view.ViewBoard.viewConfig])에서 차트 설정을 읽는다(순수).
 * 모양은 공식 문서(Working with views › Chart configuration)를 따르되 실제 조회 응답은 실측 전이라, 모르는 키는 무시하고 핵심 키만 본다.
 * 그릴 수 있는 것: x축이 선택·다중 선택·상태·체크박스·날짜(일·주·월·년)·글자(`exact`), 집계가 개수·합계·평균·최소·최대(숫자 속성).
 * 못 그리는 것(결과 모드, 수식·롤업·생성 시각 축, 상대 날짜, 숫자 구간, 중앙값 등)은 [ChartParse.Unsupported]다. 누적(`stack_by`·`group_style`)은 읽지 않고 합계로 그린다.
 */
object ChartConfig {
    private val json = Json { ignoreUnknownKeys = true }

    private sealed interface Step<out T> {
        data class Ok<T>(val value: T) : Step<T>

        data class No(val reason: String) : Step<Nothing>
    }

    private inline fun <T> Step<T>.orFail(fail: (String) -> Nothing): T = when (this) {
        is Step.Ok -> value
        is Step.No -> fail(reason)
    }

    /** [columns]는 보기의 쓰기 정보(`ViewBoard.columnInfo`). 축·값 속성이 여기에 없으면(값이 `typed`에 없다) 못 그린다. */
    fun parse(raw: String, columns: List<ViewColumn>): ChartParse {
        val config = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return ChartParse.Unsupported("설정을 읽지 못함")
        val type = chartType(config.text("chart_type")) ?: return ChartParse.Unsupported("chart_type ${config.text("chart_type")}")
        val value = value(if (type == ChartType.NUMBER) config.obj("value") else config.obj("y_axis"), columns).orFail { return ChartParse.Unsupported(it) }
        val common = ChartSpec(
            type, null, value,
            sort = topSort(config.text("sort")),
            hideEmpty = config.flag("hide_empty_groups"),
            colorTheme = config.text("color_theme"),
            legendOff = config.text("legend_position") == "off",
        )
        if (type == ChartType.NUMBER) return ChartParse.Ok(common)
        val x = config.obj("x_axis") ?: return ChartParse.Unsupported("x축 없음(결과 모드)")
        val group = group(x, columns).orFail { return ChartParse.Unsupported(it) }
        // 위쪽 sort가 있으면 그것이, 없으면 x축의 sort가 순서를 정한다(문서가 둘의 우선순위를 적지 않아 구체적인 위쪽을 먼저 본다).
        val sort = if (config.text("sort") != null) common.sort else axisSort(x.obj("sort")?.text("type"))
        return ChartParse.Ok(common.copy(group = group, sort = sort, hideEmpty = common.hideEmpty || x.flag("hide_empty_groups")))
    }

    private fun chartType(name: String?): ChartType? = when (name) {
        "column" -> ChartType.COLUMN
        "bar" -> ChartType.BAR
        "line" -> ChartType.LINE
        "donut" -> ChartType.DONUT
        "number" -> ChartType.NUMBER
        else -> null
    }

    /** 집계가 없으면 노션 기본인 개수로 본다. 개수 밖은 숫자 속성이 보기 열에 있어야 한다. */
    private fun value(obj: JsonObject?, columns: List<ViewColumn>): Step<ChartValue> {
        val fn = when (val name = obj?.text("aggregator") ?: "count") {
            "count" -> AggFn.COUNT
            "sum" -> AggFn.SUM
            "average" -> AggFn.AVG
            "min" -> AggFn.MIN
            "max" -> AggFn.MAX
            else -> return Step.No("집계 $name")
        }
        if (!fn.needsProperty) return Step.Ok(ChartValue(fn))
        val id = obj?.text("property_id").orEmpty()
        val column = columns.firstOrNull { it.id == id }
        return if (column != null && fn.accepts(column)) Step.Ok(ChartValue(fn, id)) else Step.No("숫자 속성을 알 수 없음")
    }

    private fun group(x: JsonObject, columns: List<ViewColumn>): Step<ChartGroup> {
        val id = x.text("property_id").orEmpty()
        val column = columns.firstOrNull { it.id == id } ?: return Step.No("x축 속성을 알 수 없음")
        val groupBy = x.text("group_by")
        return when (column.type) {
            "select" -> Step.Ok(ChartGroup(id, GroupKind.OPTION))
            "multi_select" -> Step.Ok(ChartGroup(id, GroupKind.MULTI_OPTION))
            "status" -> Step.Ok(ChartGroup(id, GroupKind.OPTION, byStatusGroup = groupBy == "group"))
            "checkbox" -> Step.Ok(ChartGroup(id, GroupKind.CHECKBOX))
            "date" -> dateBucket(groupBy)?.let { Step.Ok(ChartGroup(id, GroupKind.DATE, it, weekStartsMonday = x.number("start_day_of_week") == 1)) } ?: Step.No("날짜 묶음 $groupBy")
            "title", "rich_text" -> if (groupBy == null || groupBy == "exact") Step.Ok(ChartGroup(id, GroupKind.TEXT)) else Step.No("글자 묶음 $groupBy")
            else -> Step.No("x축 속성 종류 ${column.type}")
        }
    }

    private fun dateBucket(name: String?): DateBucket? = when (name) {
        "day" -> DateBucket.DAY
        "week" -> DateBucket.WEEK
        "month" -> DateBucket.MONTH
        "year" -> DateBucket.YEAR
        else -> null
    }

    private fun topSort(name: String?): ChartSort = when (name) {
        "x_ascending" -> ChartSort.X_ASC
        "x_descending" -> ChartSort.X_DESC
        "y_ascending" -> ChartSort.Y_ASC
        "y_descending" -> ChartSort.Y_DESC
        else -> ChartSort.MANUAL
    }

    private fun axisSort(name: String?): ChartSort = when (name) {
        "ascending" -> ChartSort.X_ASC
        "descending" -> ChartSort.X_DESC
        else -> ChartSort.MANUAL
    }

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.flag(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull == true

    private fun JsonObject.number(key: String): Int? = (this[key] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
}
