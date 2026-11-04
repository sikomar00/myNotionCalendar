package com.ysdoing.notioncal.widget

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.ysdoing.notioncal.core.data.view.ViewBoard
import com.ysdoing.notioncal.core.data.view.ViewChoice
import com.ysdoing.notioncal.core.data.view.ViewColumn
import com.ysdoing.notioncal.core.data.view.ViewRow
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Phase 11a(VR-01·02, VR-BRD): 크기 단계별 밀도, '더 보기' 표기, 보드의 열 나누기. 모두 순수 계산이다. */
class ViewDensityTest {
    @Test
    fun sizeStepsFollowTheWidgetGridFromTwoByTwoToFull() {
        assertEquals(SizeStep.COMPACT, sizeStep(DpSize(110.dp, 110.dp)))
        assertEquals(SizeStep.COMPACT, sizeStep(DpSize(180.dp, 400.dp))) // 폭이 좁으면 높아도 작은 단계
        assertEquals(SizeStep.WIDE, sizeStep(DpSize(358.dp, 152.dp)))
        assertEquals(SizeStep.LARGE, sizeStep(DpSize(358.dp, 330.dp)))
        assertEquals(SizeStep.FULL, sizeStep(DpSize(411.dp, 700.dp)))
    }

    @Test
    fun theSamsungLauncherReportsAboutOnePointFourTimesTheDrawnSize() {
        // S25 실측(2026-11-04): 삼성 런처는 크기를 약 1.4배로 보고한다. 2열×2행 234×213, 4열×5행 501×581, 행 하나 늘 때 약 123dp.
        assertEquals(SizeStep.COMPACT, sizeStep(DpSize(234.dp, 213.dp))) // 2×2
        assertEquals(SizeStep.COMPACT, sizeStep(DpSize(234.dp, 458.dp))) // 2×4: 폭이 좁으면 높아도 작은 단계
        assertEquals(SizeStep.WIDE, sizeStep(DpSize(501.dp, 213.dp))) // 4×2
        assertEquals(SizeStep.LARGE, sizeStep(DpSize(501.dp, 458.dp))) // 4×4
        assertEquals(SizeStep.FULL, sizeStep(DpSize(501.dp, 581.dp))) // 4×5 이상
    }

    @Test
    fun rowCapsAndBoardLayoutsFollowTheDensityTable() {
        assertEquals(listOf(3, 3, 8, 14), SizeStep.entries.map { rowCap(it) })
        assertNull(boardLayout(SizeStep.COMPACT, 150.dp)) // 2×2는 목록으로 대체
        assertEquals(BoardLayout(2, 2), boardLayout(SizeStep.WIDE, 358.dp))
        assertEquals(BoardLayout(3, 4), boardLayout(SizeStep.LARGE, 358.dp))
        assertEquals(BoardLayout(2, 4), boardLayout(SizeStep.LARGE, 260.dp)) // 폭이 모자라면 열을 줄인다
        assertEquals(BoardLayout(3, 7), boardLayout(SizeStep.FULL, 411.dp))
    }

    @Test
    fun theMoreLabelCountsTheRowsNotShownAndMarksAnIncompleteSet() {
        assertNull(moreOf(total = 8, shown = 8, complete = true)) // 다 보인다
        assertEquals(More(12, exact = true), moreOf(total = 20, shown = 8, complete = true)) // +12
        assertEquals(More(92, exact = false), moreOf(total = 100, shown = 8, complete = false)) // 92+ (노션에 더 있을 수 있다)
        assertEquals(More(0, exact = false), moreOf(total = 100, shown = 100, complete = false)) // 더 보기만
    }

    private val status = ViewColumn(
        "st", "Status", "status", listOf(ViewChoice("o1", "할 일", color = "gray"), ViewChoice("o2", "진행 중", color = "blue"), ViewChoice("o3", "완료", color = "green")),
        role = ViewColumn.ROLE_GROUP,
    )

    private fun row(id: String, group: String?) = ViewRow(id, "https://n/$id", listOf("행 $id"), typed = group?.let { mapOf("st" to JsonPrimitive(it)) }.orEmpty())

    private fun board(rows: List<ViewRow>, columns: List<ViewColumn> = listOf(status)) =
        ViewBoard("v1", "보드", "board", listOf("Name"), rows, 1, null, columns)

    @Test
    fun boardGroupsFollowTheOptionOrderAndKeepEmptyOnes() {
        val groups = boardGroups(board(listOf(row("a", "o2"), row("b", "o1"), row("c", "o2"))))!!
        assertEquals(listOf("o1", "o2", "o3"), groups.map { it.id })
        assertEquals(listOf(listOf("b"), listOf("a", "c"), emptyList<String>()), groups.map { g -> g.rows.map { it.pageId } })
        assertEquals(listOf("gray", "blue", "green"), groups.map { it.color })
    }

    @Test
    fun rowsWithoutAKnownGroupGoToATrailingNoneGroup() {
        val groups = boardGroups(board(listOf(row("a", "o1"), row("b", null), row("c", "지워진-옵션"))))!!
        assertEquals(listOf("o1", "o2", "o3", null), groups.map { it.id })
        assertEquals(listOf("b", "c"), groups.last().rows.map { it.pageId })
    }

    @Test
    fun withoutAGroupColumnOrItsOptionsTheViewFallsBackToAList() {
        assertNull(boardGroups(board(listOf(row("a", "o1")), columns = emptyList())))
        assertNull(boardGroups(board(listOf(row("a", "o1")), columns = listOf(status.copy(options = emptyList())))))
        assertNull(boardGroups(board(listOf(row("a", "o1")), columns = listOf(status.copy(role = "")))))
    }

    // --- Phase 11d WL-03: 탭·섹션 한 칸(영역)에 들어가는 행 수는 그 칸의 높이로 정한다.

    private val metrics = viewMetrics(1f, 1f)

    @Test
    fun aPaneTooShortForAHeaderAndTheMoreLineHasNoRows() {
        assertEquals(0, paneRowLimit(40.dp, metrics, table = false, tall = false))
        assertEquals(0, paneRowLimit(metrics.header, metrics, table = false, tall = false)) // 머리줄만으로 찼다
    }

    @Test
    fun eachExtraRowHeightAddsExactlyOneRowAndATallRowNeverFitsMore() {
        val row = metrics.listRow + ROW_GAP * 2
        val base = paneRowLimit(200.dp, metrics, table = false, tall = false)
        assertEquals(base + 1, paneRowLimit(200.dp + row, metrics, table = false, tall = false))
        assertEquals(base + 2, paneRowLimit(200.dp + row * 2, metrics, table = false, tall = false))
        assertEquals(true, paneRowLimit(200.dp, metrics, table = false, tall = true) <= base) // 눌러 바꿀 칸이 있으면 행이 높다
    }

    @Test
    fun aTableSpendsPartOfTheHeightOnItsColumnHeader() {
        val noColumnHeader = metrics.copy(tableHeader = 0.dp)
        val heights = (80..400).map { it.dp }
        // 열 머리가 차지하는 만큼 같은 높이에서 행이 같거나 적게 들어가고, 어떤 높이에서는 실제로 한 줄이 줄어든다.
        assertEquals(true, heights.all { paneRowLimit(it, metrics, table = true, tall = false) <= paneRowLimit(it, noColumnHeader, table = true, tall = false) })
        assertEquals(true, heights.any { paneRowLimit(it, metrics, table = true, tall = false) < paneRowLimit(it, noColumnHeader, table = true, tall = false) })
    }
}
