package com.ysdoing.notioncal.core.data.recipe

/**
 * Phase 11h 레시피 화면이 들고 있는 선택 상태(순수). 편집 함수는 새 상태를 돌려준다. [budgetText]는 가계부 예산 입력 칸의 글자이고,
 * 목표로 쓰이는 값은 [RecipePicks.budget]이다(양수만).
 */
data class RecipeDraft(val recipe: Recipe = Recipe.TASKS, val picks: RecipePicks = RecipePicks(), val budgetText: String = "") {
    /** [recipe]로 다시 시작: 그 레시피의 기본 선택(예산 칸은 비운다). */
    fun start(recipe: Recipe, views: List<RecipeView>): RecipeDraft = RecipeDraft(recipe, Recipes.defaultPicks(recipe, views))

    /** 주 보기를 바꾼다. 등록하지 않은 보기는 무시한다. */
    fun selectView(viewId: String, views: List<RecipeView>): RecipeDraft {
        val view = views.firstOrNull { it.viewId == viewId } ?: return this
        return copy(picks = Recipes.withView(recipe, picks, view))
    }

    /** 일정·할 일의 상태 보드 보기(빈 글자면 보드 없음). */
    fun selectBoard(viewId: String): RecipeDraft = copy(picks = picks.copy(boardViewId = viewId))

    fun selectDate(propId: String): RecipeDraft = copy(picks = picks.copy(dateProp = propId))

    fun selectAmount(propId: String): RecipeDraft = copy(picks = picks.copy(amountProp = propId))

    /** 가계부 분류(빈 글자면 분류 없음). */
    fun selectCategory(propId: String): RecipeDraft = copy(picks = picks.copy(categoryProp = propId))

    /** 해빗트래커 체크박스를 고르거나 푼다. 이미 [Recipes.MAX_HABITS]개면 더하지 않는다. */
    fun toggleCheck(propId: String): RecipeDraft {
        val now = picks.checkProps
        val next = if (propId in now) now - propId else if (now.size < Recipes.MAX_HABITS) now + propId else now
        return copy(picks = picks.copy(checkProps = next))
    }

    /** 예산 칸의 글자를 그대로 두고, 쉼표를 뺀 양수만 목표로 읽는다(그 밖에는 목표 없음). */
    fun withBudget(text: String): RecipeDraft {
        val value = text.replace(",", "").trim().toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 }
        return copy(picks = picks.copy(budget = value), budgetText = text)
    }

    /** 지금 선택으로 만든 위젯 구성. 맞는 구성이 아니면 null이다(화면이 '홈 화면에 추가'를 막는다). */
    fun config(views: List<RecipeView>): RecipeConfig? = Recipes.build(recipe, picks, views)

    /** 등록한 보기가 바뀐 뒤(처음 불러왔거나 보기를 지웠을 때): 고른 주 보기가 아직 맞으면 그대로, 아니면 기본 선택으로 다시 채운다. */
    fun repaired(views: List<RecipeView>): RecipeDraft {
        val keeps = picks.viewId.isNotEmpty() && views.any { it.viewId == picks.viewId && Recipes.fits(recipe, it) }
        return if (keeps) this else copy(picks = Recipes.defaultPicks(recipe, views))
    }
}
