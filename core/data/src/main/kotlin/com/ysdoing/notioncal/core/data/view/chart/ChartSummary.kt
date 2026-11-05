package com.ysdoing.notioncal.core.data.view.chart

/**
 * A11Y-04 차트를 스크린 리더가 읽는 한 문장(순수). 색에 기대지 않고 이름과 값을 함께 읽는다: '개수: 식비 12, 교통 6 외 2개',
 * 도넛은 비율('개수 비율: 식비 42%, 교통 21%'), number는 '합계 금액 1,234'. 행을 일부만 받았으면 그 사실도 붙인다.
 */
object ChartSummary {
    const val EMPTY = "표시할 데이터가 없습니다"
    private const val MAX_NAMED = 5

    fun sentence(result: ChartResult): String {
        val spec = result.spec
        if (spec.type == ChartType.NUMBER) return "${result.valueLabel} ${result.totalText}" + partialNote(result)
        if (result.points.isEmpty()) return EMPTY
        // 도넛은 비율로 읽는다. 비율이 뜻이 없는 집계(평균 등)나 값이 모두 0이면 범례처럼 값으로 읽는다.
        val donut = spec.type == ChartType.DONUT && result.sharePercent(result.points.first()) != null
        val named = result.points.take(MAX_NAMED).joinToString(", ") { p ->
            if (donut) "${p.label} ${result.sharePercent(p)}%" else "${p.label} ${p.text}"
        }
        val more = (result.points.size - MAX_NAMED).takeIf { it > 0 }?.let { " 외 ${it}개" }.orEmpty()
        val head = if (donut) "${result.valueLabel} 비율" else result.valueLabel
        return "$head: $named$more" + partialNote(result)
    }

    private fun partialNote(result: ChartResult) = if (result.partial) " (일부 행만 반영)" else ""

}
