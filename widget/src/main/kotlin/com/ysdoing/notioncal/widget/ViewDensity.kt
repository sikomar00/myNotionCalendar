package com.ysdoing.notioncal.widget

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.ysdoing.notioncal.core.data.view.ViewBoard
import com.ysdoing.notioncal.core.data.view.ViewColumn
import com.ysdoing.notioncal.core.data.view.ViewRow
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

// Phase 11a(VR-01·02, VR-BRD): 보기 위젯의 크기 단계별 밀도와 보드 열 나누기. 화면에 그리는 일 없이 계산만 한다.

/** PRD §5.2 크기 단계: 2×2, 4×2, 4×4, 화면 전체. */
internal enum class SizeStep { COMPACT, WIDE, LARGE, FULL }

/** 위젯 크기로 단계를 정한다. 폭이 좁으면(2열) 높아도 COMPACT, 폭이 넓으면 높이로 가른다. */
internal fun sizeStep(size: DpSize): SizeStep = when {
    size.width < NARROW_WIDTH -> SizeStep.COMPACT
    size.height < WIDE_MAX_HEIGHT -> SizeStep.WIDE
    size.height < LARGE_MAX_HEIGHT -> SizeStep.LARGE
    else -> SizeStep.FULL
}

private val NARROW_WIDTH = 200.dp
private val WIDE_MAX_HEIGHT = 200.dp
private val LARGE_MAX_HEIGHT = 400.dp
private val THREE_COLUMNS_MIN_WIDTH = 300.dp

/** 목록·표에서 보이는 최대 행 수(PRD 밀도표). 넘는 행은 '더 보기'로 뷰어에서 본다. */
internal fun rowCap(step: SizeStep): Int = when (step) {
    SizeStep.COMPACT -> 3
    SizeStep.WIDE -> 3
    SizeStep.LARGE -> 8
    SizeStep.FULL -> 14
}

/** 보드가 한 번에 보여 주는 열 수와 열마다 카드 수. */
internal data class BoardLayout(val columns: Int, val cardsPerColumn: Int)

/** 2×2는 보드를 그릴 폭이 없어 null(목록으로 대체). 4×2는 2열×2장, 4×4는 3열×4장, 전체는 3열×7장이고 폭이 모자라면 2열이다. */
internal fun boardLayout(step: SizeStep, width: Dp): BoardLayout? {
    val wide = if (width >= THREE_COLUMNS_MIN_WIDTH) 3 else 2
    return when (step) {
        SizeStep.COMPACT -> null
        SizeStep.WIDE -> BoardLayout(2, 2)
        SizeStep.LARGE -> BoardLayout(wide, 4)
        SizeStep.FULL -> BoardLayout(wide, 7)
    }
}

/** 보이지 않는 행 수. [exact]가 false면 노션에 행이 더 있을 수 있어 'N+'로 적는다. */
internal data class More(val remaining: Int, val exact: Boolean)

/** 다 보이면(그리고 다 받았으면) null. */
internal fun moreOf(total: Int, shown: Int, complete: Boolean): More? {
    val remaining = (total - shown).coerceAtLeast(0)
    return if (remaining == 0 && complete) null else More(remaining, exact = complete)
}

/** 보드의 열 하나. [id]가 null이면 값이 없거나 모르는 옵션인 행들의 '없음' 열이다. */
internal data class BoardGroup(val id: String?, val name: String, val color: String?, val rows: List<ViewRow>)

/**
 * 보드의 `group_by` 열(역할 [ViewColumn.ROLE_GROUP])의 옵션 순서대로 행을 나눈다. 빈 열도 남기고, 값이 없거나 지워진 옵션의 행은 끝의 '없음' 열에 모은다.
 * 그룹 열이나 옵션을 모르면 null(목록으로 대체한다).
 */
internal fun boardGroups(board: ViewBoard): List<BoardGroup>? {
    val column = board.columnInfo.firstOrNull { it.role == ViewColumn.ROLE_GROUP }?.takeIf { it.options.isNotEmpty() } ?: return null
    val known = column.options.map { it.id }.toSet()
    val byOption = board.rows.groupBy { (it.typed[column.id] as? JsonPrimitive)?.contentOrNull?.takeIf { id -> id in known } }
    val groups = column.options.map { BoardGroup(it.id, it.name, it.color, byOption[it.id].orEmpty()) }
    val unassigned = byOption[null].orEmpty()
    return if (unassigned.isEmpty()) groups else groups + BoardGroup(null, "", null, unassigned)
}
