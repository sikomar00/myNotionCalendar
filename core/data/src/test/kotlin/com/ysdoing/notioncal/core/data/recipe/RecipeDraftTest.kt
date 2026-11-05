package com.ysdoing.notioncal.core.data.recipe

import com.ysdoing.notioncal.core.data.view.ViewChoice
import com.ysdoing.notioncal.core.data.view.ViewColumn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Phase 11h 레시피 화면의 선택 상태(순수): 레시피를 바꾸면 기본 선택으로 다시 시작하고, 보기·속성·예산을 고쳐도 항상 맞는 구성이거나 null이다. */
class RecipeDraftTest {
    private fun col(id: String, type: String, vararg options: String) = ViewColumn(id, "열 $id", type, options.map { ViewChoice("o-$it", it) })
    private val title = col("t", "title")
    private val calendar = RecipeView("cal", "캘린더", "calendar", listOf(title, col("d", "date")))
    private val board = RecipeView("brd", "보드", "board", listOf(title, col("s", "status", "할 일")))
    private val habits = RecipeView("hab", "습관", "table", listOf(title, col("d", "date"), col("e", "date"), col("c1", "checkbox"), col("c2", "checkbox"), col("c3", "checkbox"), col("c4", "checkbox")))
    private val habits2 = RecipeView("hab2", "습관 둘", "table", listOf(title, col("d2", "date"), col("x", "checkbox")))
    private val ledger = RecipeView("led", "가계부", "table", listOf(title, col("d", "date"), col("n", "number"), col("m", "number"), col("k", "select", "식비"), col("s", "status", "완료")))
    private val views = listOf(calendar, board, habits, habits2, ledger)

    @Test
    fun startingARecipeTakesItsDefaultPicks() {
        val draft = RecipeDraft().start(Recipe.LEDGER, views)
        assertEquals(Recipe.LEDGER, draft.recipe)
        assertEquals(RecipePicks(viewId = "led", amountProp = "n", categoryProp = "k"), draft.picks)
        assertEquals("", draft.budgetText)
    }

    @Test
    fun switchingRecipeDropsTheOldPicksAndTheBudget() {
        val ledger = RecipeDraft().start(Recipe.LEDGER, views).withBudget("500,000")
        val tasks = ledger.start(Recipe.TASKS, views)
        assertEquals(RecipePicks(viewId = "cal", boardViewId = "brd"), tasks.picks)
        assertEquals("", tasks.budgetText)
    }

    @Test
    fun selectingAViewRederivesItsPropertiesButOnlyForRegisteredViews() {
        val draft = RecipeDraft().start(Recipe.HABITS, views)
        val moved = draft.selectView("hab2", views)
        assertEquals(RecipePicks(viewId = "hab2", checkProps = listOf("x")), moved.picks)
        assertEquals(draft, draft.selectView("gone", views)) // 등록에 없는 보기는 무시한다
    }

    @Test
    fun toggleCheckAddsAndRemovesAndStopsAtThree() {
        var draft = RecipeDraft().start(Recipe.HABITS, views)
        assertEquals(listOf("c1", "c2", "c3"), draft.picks.checkProps)
        draft = draft.toggleCheck("c4") // 이미 셋이라 더하지 않는다
        assertEquals(listOf("c1", "c2", "c3"), draft.picks.checkProps)
        draft = draft.toggleCheck("c2")
        assertEquals(listOf("c1", "c3"), draft.picks.checkProps)
        draft = draft.toggleCheck("c4")
        assertEquals(listOf("c1", "c3", "c4"), draft.picks.checkProps)
    }

    @Test
    fun choosingWhatIsAlreadyChosenChangesNothing() {
        val draft = RecipeDraft().start(Recipe.LEDGER, views).selectCategory("").withBudget("300000")
        assertEquals(draft, draft.switchTo(Recipe.LEDGER, views)) // 같은 레시피 칩을 또 눌러도 고른 것·예산이 그대로다
        assertEquals(draft, draft.selectView("led", views)) // 같은 보기를 또 눌러도 '분류 없음' 같은 고른 값이 첫 속성으로 돌아가지 않는다
        assertEquals(Recipe.HABITS, draft.switchTo(Recipe.HABITS, views).recipe) // 다른 레시피면 기본 선택으로 다시 시작
        assertEquals(Recipe.LEDGER, RecipeDraft().switchTo(Recipe.LEDGER, views).recipe) // 아직 시작 전이면 시작한다
    }

    @Test
    fun propertySettersAndTheBoardChangeOnlyTheirOwnPick() {
        val draft = RecipeDraft().start(Recipe.LEDGER, views)
        assertEquals("m", draft.selectAmount("m").picks.amountProp)
        assertEquals("s", draft.selectCategory("s").picks.categoryProp)
        assertEquals("", draft.selectCategory("").picks.categoryProp) // 분류는 비울 수 있다
        val tasks = RecipeDraft().start(Recipe.TASKS, views)
        assertEquals("", tasks.selectBoard("").picks.boardViewId) // '보드 없음'
        assertEquals("brd", tasks.selectBoard("").selectBoard("brd").picks.boardViewId)
    }

    @Test
    fun theBudgetTextKeepsWhatWasTypedButOnlyAPositiveNumberBecomesTheGoal() {
        val draft = RecipeDraft().start(Recipe.LEDGER, views)
        assertEquals(500000.0, draft.withBudget("500,000").picks.budget)
        assertEquals("500,000", draft.withBudget("500,000").budgetText)
        assertEquals(1.5, draft.withBudget(" 1.5 ").picks.budget)
        listOf("", "  ", "abc", "-5", "0", "NaN", "1e999").forEach { assertNull(it, draft.withBudget(it).picks.budget) }
        assertEquals("abc", draft.withBudget("abc").budgetText) // 입력 칸은 그대로 둔다
    }

    @Test
    fun configIsOnlyThereWhenThePicksFormAValidRecipe() {
        val ok = RecipeDraft().start(Recipe.LEDGER, views)
        assertNotNull(ok.config(views))
        assertNull(ok.selectAmount("").config(views)) // 금액을 비우면 만들 수 없다
        assertNull(RecipeDraft().config(views)) // 아직 시작하지 않았다
        assertNull(RecipeDraft().start(Recipe.HABITS, listOf(calendar)).config(listOf(calendar))) // 맞는 보기가 없다
    }

    @Test
    fun theBudgetEndsUpAsTheTilesGoal() {
        val config = RecipeDraft().start(Recipe.LEDGER, views).withBudget("300000").config(views)!!
        assertEquals(true, config.layoutJson.contains("300000"))
    }

    @Test
    fun repairingDropsPicksOfAViewThatWasRemovedAndFallsBackToDefaults() {
        val draft = RecipeDraft().start(Recipe.LEDGER, views)
        assertEquals(draft, draft.repaired(views)) // 그대로면 바뀌지 않는다
        val without = views.filter { it.viewId != "led" }
        assertEquals(RecipePicks(), draft.repaired(without).picks) // 가계부에 맞는 보기가 하나도 없다
        val budgeted = draft.withBudget("300000").repaired(without + RecipeView("led3", "셋", "table", listOf(title, col("d", "date"), col("q", "number"))))
        assertEquals(300000.0, budgeted.picks.budget) // 입력 칸에 글자가 남아 있으니 목표도 함께 남는다
        val other = views + RecipeView("led2", "가계부 둘", "table", listOf(title, col("d", "date"), col("q", "number")))
        assertEquals("led2", draft.repaired(other.filter { it.viewId != "led" }).picks.viewId)
        assertEquals("cal", RecipeDraft().repaired(views).picks.viewId) // 시작 전(고른 보기가 없음)이면 기본 선택으로 채운다
    }
}
