package com.ysdoing.notioncal.core.data.view.chart

import com.ysdoing.notioncal.core.data.view.AggFn

// Phase 12b VR-CHT: 노션 차트 보기 설정(`chart_type`·`x_axis`·`y_axis`…)에서 앱이 그릴 수 있는 부분만 담은 값. 읽기는 [ChartConfig], 계산은 [ChartCompute].

/** 노션 `chart_type`. column은 세로 막대, bar는 가로 막대, number는 값 하나다. */
enum class ChartType { COLUMN, BAR, LINE, DONUT, NUMBER }

/** x축을 묶는 속성의 종류. */
enum class GroupKind { OPTION, MULTI_OPTION, CHECKBOX, DATE, TEXT }

/** 날짜로 묶을 때의 단위(`group_by`). */
enum class DateBucket { DAY, WEEK, MONTH, YEAR }

/** 막대·조각의 순서: 노션이 정한 순서(옵션 순서·날짜 순), x 기준, 값 기준. */
enum class ChartSort { MANUAL, X_ASC, X_DESC, Y_ASC, Y_DESC }

/** x축 묶음. [byStatusGroup]은 상태를 옵션이 아니라 그룹(할 일·진행 중·완료)으로 묶고, [weekStartsMonday]는 주 단위 묶음이 월요일에 시작한다(기본 일요일). */
data class ChartGroup(
    val propId: String,
    val kind: GroupKind,
    val dateBucket: DateBucket? = null,
    val byStatusGroup: Boolean = false,
    val weekStartsMonday: Boolean = false,
)

/** y축(또는 number 차트의 `value`) 집계: 개수, 또는 숫자 속성 [propId]의 합계·평균·최소·최대. 기존 집계 타일의 [AggFn]을 그대로 쓴다. */
data class ChartValue(val fn: AggFn, val propId: String = "")

/**
 * 그릴 수 있는 차트 하나. [group]은 number 차트면 null이다. [colorTheme]은 노션 `color_theme`(색 이름, `auto`·`colorful`·null은 팔레트 순환),
 * [legendOff]는 `legend_position = off`다.
 */
data class ChartSpec(
    val type: ChartType,
    val group: ChartGroup?,
    val value: ChartValue,
    val sort: ChartSort = ChartSort.MANUAL,
    val hideEmpty: Boolean = false,
    val colorTheme: String? = null,
    val legendOff: Boolean = false,
)

/** 설정을 읽은 결과. 못 그리는 설정은 [Unsupported]이고 위젯은 표·목록으로 대신 그린다. [reason]은 시험·진단용이다. */
sealed interface ChartParse {
    data class Ok(val spec: ChartSpec) : ChartParse

    data class Unsupported(val reason: String) : ChartParse
}
