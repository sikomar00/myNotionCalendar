package com.ysdoing.notioncal.widget

import com.ysdoing.notioncal.core.data.recipe.Recipe
import com.ysdoing.notioncal.core.data.recipe.RecipePicks
import com.ysdoing.notioncal.core.data.recipe.RecipeView
import com.ysdoing.notioncal.core.data.recipe.Recipes
import com.ysdoing.notioncal.core.data.view.ViewBoard
import com.ysdoing.notioncal.core.data.view.ViewColumn
import com.ysdoing.notioncal.core.data.view.ViewRow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * Phase 11h 레시피 미리보기의 상태: 레시피가 만든 구성이 설정 화면과 같은 방식으로 그려질 상태가 되고, 집계 타일은 보기의 실제 행으로 계산된다.
 * (그림 자체는 설정 화면 미리보기와 같은 `LayoutWidgetContent`라 따로 시험하지 않는다.)
 */
class RecipePreviewStateTest {
    private val columns = listOf(ViewColumn("t", "이름", "title"), ViewColumn("d", "날짜", "date"), ViewColumn("n", "금액", "number"))
    private val recipeView = RecipeView("led", "가계부", "table", columns)
    private val now = LocalDate.of(2026, 11, 5).atStartOfDay(ZoneId.of("Asia/Seoul")).plusHours(12).toInstant().toEpochMilli()

    private fun day(iso: String): JsonElement = JsonObject(mapOf("start" to JsonPrimitive(iso)))

    private fun row(id: String, date: String, amount: Double) = ViewRow(id, "", listOf(id), typed = mapOf("d" to day(date), "n" to JsonPrimitive(amount)))

    private val board = ViewBoard(
        "led", "가계부", "table", listOf("이름", "날짜", "금액"),
        listOf(row("a", "2026-11-02", 12000.0), row("b", "2026-11-03", 8000.0), row("c", "2026-10-30", 5000.0)), now, null, columns,
    )

    @Test
    fun theLedgerRecipeGivesAMonthlySumTileFromTheViewsRealRows() {
        val picks = RecipePicks(viewId = "led", dateProp = "d", amountProp = "n", budget = 100_000.0)
        val config = Recipes.build(Recipe.LEDGER, picks, listOf(recipeView))!!
        val state = WidgetPreviews.layoutPreview(
            com.ysdoing.notioncal.core.data.WidgetConfig(0, layoutJson = config.layoutJson, formJson = config.formJson),
            names = mapOf("led" to "가계부"), now = now, boards = mapOf("led" to board),
        )
        val cell = state.tileCells().single()
        assertEquals(20_000.0, cell.result.number!!, 0.0) // 11월 두 건만(10월 건은 이번 달이 아니다)
        assertEquals(0.2f, cell.result.progress!!, 0.0001f) // 예산 10만 원 대비
        assertEquals(listOf("led"), state.layout.items.map { it.viewId })
        assertTrue(state.boards.containsKey("led")) // 칸도 같은 행을 쓴다
    }

    @Test
    fun aViewWithoutRealRowsStillGetsAPaneFromTheSampleRows() {
        val picks = RecipePicks(viewId = "led", dateProp = "d", amountProp = "n")
        val config = Recipes.build(Recipe.LEDGER, picks, listOf(recipeView))!!
        val state = WidgetPreviews.layoutPreview(
            com.ysdoing.notioncal.core.data.WidgetConfig(0, layoutJson = config.layoutJson), names = mapOf("led" to "가계부"), now = now,
        )
        assertEquals("가계부", state.boards["led"]!!.name) // 아직 못 받은 보기도 이름이 있는 예시 칸으로
        assertEquals(null, state.tileCells().single().result.number) // 타일은 값이 있어야 뜻이 있어 비워 둔다
    }
}
