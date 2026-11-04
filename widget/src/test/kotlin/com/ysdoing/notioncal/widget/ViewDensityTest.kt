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
}
