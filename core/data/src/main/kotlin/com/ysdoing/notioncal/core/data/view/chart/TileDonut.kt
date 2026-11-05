package com.ysdoing.notioncal.core.data.view.chart

import com.ysdoing.notioncal.core.data.view.AggPeriod
import com.ysdoing.notioncal.core.data.view.AggTile
import com.ysdoing.notioncal.core.data.view.Aggregator
import com.ysdoing.notioncal.core.data.view.TileKind
import com.ysdoing.notioncal.core.data.view.ViewBoard
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * Phase 12c RC-04 분류별 도넛 타일의 값(순수). 타일의 기간에 드는 행을 [AggTile.groupProp] 속성으로 묶어 [AggTile.fn](개수·합계)으로 센 값을
 * 노션 차트 보기와 같은 [ChartCompute]로 계산한다. 묶을 속성이 사라졌거나 종류가 맞지 않거나 도넛 타일이 아니면 null이다.
 * 값이 0인 분류는 조각이 아니라 범례 노이즈라 숨기고, 점이 [maxPoints]를 넘으면 남은 것을 '기타'로 합친다.
 */
object TileDonut {
    fun compute(board: ViewBoard, tile: AggTile, today: LocalDate, weekStart: DayOfWeek, maxPoints: Int = DEFAULT_MAX_POINTS): ChartResult? {
        if (tile.kind != TileKind.DONUT) return null
        val column = board.columnInfo.firstOrNull { it.id == tile.groupProp } ?: return null
        val kind = when (column.type) {
            "select", "status" -> GroupKind.OPTION
            "multi_select" -> GroupKind.MULTI_OPTION
            else -> return null
        }
        val rows = if (tile.period == AggPeriod.ALL) board.rows else Aggregator.inPeriod(board.rows, tile, today, weekStart)
        val spec = ChartSpec(ChartType.DONUT, ChartGroup(column.id, kind), ChartValue(tile.fn, tile.propId), hideEmpty = true)
        return ChartCompute.compute(board.copy(rows = rows), spec, maxPoints)
    }

    private const val DEFAULT_MAX_POINTS = 5
}
