package com.ysdoing.notioncal.core.data.recipe

import com.ysdoing.notioncal.core.data.view.AggFn
import com.ysdoing.notioncal.core.data.view.AggPeriod
import com.ysdoing.notioncal.core.data.view.AggTile
import com.ysdoing.notioncal.core.data.view.FormField
import com.ysdoing.notioncal.core.data.view.InputForm
import com.ysdoing.notioncal.core.data.view.InputForms
import com.ysdoing.notioncal.core.data.view.LayoutItem
import com.ysdoing.notioncal.core.data.view.LayoutMode
import com.ysdoing.notioncal.core.data.view.TileKind
import com.ysdoing.notioncal.core.data.view.ViewColumn
import com.ysdoing.notioncal.core.data.view.WidgetLayout

/** 레시피(RC-02~05): 일정·할 일 · 해빗트래커 · 가계부(Phase 11h) · 갤러리형 기록(책·영화·사진, Phase 12d). 이미 있는 부품(배치·집계 타일·입력 양식)을 미리 짜 둔 틀이다. */
enum class Recipe { TASKS, HABITS, LEDGER, GALLERY }

/** 레시피가 고를 수 있는 등록한 보기 하나: 노션 보기 종류(`table`·`board`·`calendar`…)와 쓰기 정보([columns]). */
data class RecipeView(val viewId: String, val name: String, val type: String, val columns: List<ViewColumn>)

/**
 * 사용자가 고른 것. [viewId]가 주 보기이고(일정·할 일은 캘린더 쪽), [boardViewId]는 일정·할 일의 상태 보드(선택)다.
 * 해빗트래커는 [checkProps], 가계부는 [amountProp]·[categoryProp](선택)·[budget](선택, 타일의 목표)을 쓴다.
 * 날짜 속성은 고르지 않는다: 새 항목은 보기의 첫 날짜 열에 날짜를 쓰니(`ViewWriter`) 타일·하루 한 줄 판정도 그 열을 읽어야 어긋나지 않는다.
 */
data class RecipePicks(
    val viewId: String = "",
    val boardViewId: String = "",
    val checkProps: List<String> = emptyList(),
    val amountProp: String = "",
    val categoryProp: String = "",
    val budget: Double? = null,
    /** 갤러리형 기록의 별점 속성(숫자·선택, 선택 사항). 카드에 ★로 그려지고 새 항목 양식에 들어간다. */
    val ratingProp: String = "",
)

/** 레시피가 만든 노션 위젯 설정 묶음(`widget_configs.layoutJson`·`formJson`). 위젯 번호는 홈 화면에 놓인 뒤에 정해진다. */
data class RecipeConfig(val layoutJson: String, val formJson: String)

/**
 * 레시피 → 노션 위젯 구성을 만드는 순수 함수들. 보기 추천(`candidates`)·기본 선택(`defaultPicks`)·보기를 바꿀 때의 속성 다시 고르기(`withView`)·
 * 만들기(`build`)를 갖는다. 렌더러·저장소를 건드리지 않아 화면 없이 시험한다.
 */
object Recipes {
    /** 해빗트래커 타일 줄이 섹션 자리에 들어가도록 주간 달성률 타일은 체크박스 3개까지(연속 달성일 1 + 3 ≤ 타일 상한). */
    const val MAX_HABITS = 3

    /** 주간 달성률 타일의 목표(%). */
    const val WEEKLY_GOAL = 80.0

    private val OPTION_TYPES = setOf("select", "status")
    private val RATING_TYPES = setOf("number", "select")
    private val RATING_NAMES = listOf("별점", "평점", "평가", "rating", "score", "stars")

    /** 이 레시피가 쓸 수 있는 보기를 어울리는 순서로: 제목 열이 있는 보기(새 항목을 만들 수 있다)에, 레시피마다 필요한 속성이 있는 것만. */
    fun candidates(recipe: Recipe, views: List<RecipeView>): List<RecipeView> =
        views.filter { fits(recipe, it) }.sortedBy { rank(recipe, it.type) }

    fun fits(recipe: Recipe, view: RecipeView): Boolean = view.has("title") && when (recipe) {
        Recipe.TASKS -> true
        Recipe.HABITS -> view.has("date") && view.has("checkbox")
        Recipe.LEDGER -> view.has("date") && view.has("number")
        Recipe.GALLERY -> view.type == "gallery" // 표지 카드는 갤러리 보기만 그린다(날짜·별점은 선택)
    }

    /** 후보 첫 보기와 그 보기에서 유형이 맞는 첫 속성들(체크박스는 [MAX_HABITS]개까지). 일정·할 일은 후보 중 보드 보기를 상태 보드로 더한다. 후보가 없으면 빈 선택이다. */
    fun defaultPicks(recipe: Recipe, views: List<RecipeView>): RecipePicks {
        val fit = candidates(recipe, views)
        val primary = fit.firstOrNull() ?: return RecipePicks()
        val picks = withView(recipe, RecipePicks(), primary)
        if (recipe != Recipe.TASKS) return picks
        return picks.copy(boardViewId = fit.firstOrNull { it.type == "board" && it.viewId != primary.viewId }?.viewId.orEmpty())
    }

    /** 주 보기를 [view]로 바꾼 선택. 새 보기에도 있는 속성은 사용자가 고른 것을 지키고, 없거나 종류가 맞지 않으면 그 종류의 첫 속성으로 다시 고른다. */
    fun withView(recipe: Recipe, picks: RecipePicks, view: RecipeView): RecipePicks {
        val moved = picks.copy(viewId = view.viewId)
        return when (recipe) {
            Recipe.TASKS -> moved
            Recipe.HABITS -> moved.copy(
                checkProps = picks.checkProps.filter { id -> view.columns.any { it.id == id && it.type == "checkbox" } }
                    .ifEmpty { view.columns.filter { it.type == "checkbox" }.take(MAX_HABITS).map { it.id } },
            )
            Recipe.LEDGER -> moved.copy(
                amountProp = view.keep(picks.amountProp, "number"),
                categoryProp = view.keep(picks.categoryProp, OPTION_TYPES),
            )
            Recipe.GALLERY -> moved.copy(ratingProp = view.ratingOrGuess(picks.ratingProp))
        }
    }

    /** 고른 것으로 만든 위젯 구성. 필요한 보기·속성이 없거나 종류가 맞지 않으면 null이다(화면이 '홈 화면에 추가'를 막는다). */
    fun build(recipe: Recipe, picks: RecipePicks, views: List<RecipeView>): RecipeConfig? {
        val byId = views.associateBy { it.viewId }
        val view = byId[picks.viewId]?.takeIf { fits(recipe, it) } ?: return null
        return when (recipe) {
            Recipe.TASKS -> tasks(view, byId[picks.boardViewId]?.takeIf { it.viewId != view.viewId && fits(Recipe.TASKS, it) })
            Recipe.HABITS -> habits(picks, view)
            Recipe.LEDGER -> ledger(picks, view)
            Recipe.GALLERY -> gallery(picks, view)
        }
    }

    /** 캘린더 탭 + 상태 보드 탭(보드를 안 고르면 보기 하나). 새 항목은 날짜 열이 있는 보기에서 오늘로 시작한다. */
    private fun tasks(view: RecipeView, board: RecipeView?): RecipeConfig {
        val shown = listOfNotNull(view, board)
        val layout = WidgetLayout(if (board == null) LayoutMode.SINGLE else LayoutMode.TABS, shown.map { LayoutItem(it.viewId, tabName(it.type)) })
        val forms = shown.fold("") { text, v -> InputForms.put(text, v.viewId, InputForm(dateToday = v.has("date"))) }
        return RecipeConfig(WidgetLayout.encode(layout), forms)
    }

    /** 연속 달성일 + 체크박스마다 이번 주 달성률(목표 [WEEKLY_GOAL]%) 타일과 보기. 새 항목은 체크박스 칸·오늘 날짜·제목 'M월 d일'로 하루에 한 줄만 만든다. */
    private fun habits(picks: RecipePicks, view: RecipeView): RecipeConfig? {
        val date = view.firstDate() ?: return null
        val checks = picks.checkProps.mapNotNull { view.column(it, "checkbox") }.distinctBy { it.id }.take(MAX_HABITS)
        if (checks.isEmpty()) return null
        var layout = WidgetLayout(LayoutMode.SECTIONS, listOf(LayoutItem(view.viewId)))
        layout = layout.addTile(AggTile("", view.viewId, AggFn.STREAK, checks.first().id, date.id)) ?: return null
        for (check in checks) {
            layout = layout.addTile(AggTile("", view.viewId, AggFn.RATIO, check.id, date.id, AggPeriod.THIS_WEEK, WEEKLY_GOAL)) ?: return null
        }
        val form = InputForm(checks.map { FormField(it.id) }, showDate = true, dateToday = true, titleDate = true, oncePerDay = true)
        return RecipeConfig(WidgetLayout.encode(layout), InputForms.put("", view.viewId, form))
    }

    /**
     * 이번 달 합계 타일(예산이 있으면 목표로 진행 막대)과 보기, 분류를 골랐으면 이번 달 **분류별 도넛 타일**(Phase 12c RC-04)도.
     * 새 항목은 금액·분류 칸과 오늘 날짜로 시작하고 프리셋 줄이 보인다(섹션에 보기 하나).
     */
    private fun ledger(picks: RecipePicks, view: RecipeView): RecipeConfig? {
        val date = view.firstDate() ?: return null
        val amount = view.column(picks.amountProp, "number") ?: return null
        val category = view.columns.firstOrNull { it.id == picks.categoryProp && it.type in OPTION_TYPES }
        val tile = AggTile("", view.viewId, AggFn.SUM, amount.id, date.id, AggPeriod.THIS_MONTH, picks.budget, label = MONTH_SUM)
        val withSum = WidgetLayout(LayoutMode.SECTIONS, listOf(LayoutItem(view.viewId))).addTile(tile) ?: return null
        val donut = category?.let { AggTile("", view.viewId, AggFn.SUM, amount.id, date.id, AggPeriod.THIS_MONTH, label = MONTH_BY_CATEGORY, kind = TileKind.DONUT, groupProp = it.id) }
        val layout = donut?.let { withSum.addTile(it) } ?: withSum
        val form = InputForm(listOfNotNull(amount, category).map { FormField(it.id) }, dateToday = true)
        return RecipeConfig(WidgetLayout.encode(layout), InputForms.put("", view.viewId, form))
    }

    /**
     * 갤러리 보기 하나(표지 카드) + 새 항목 양식: 고른 별점 속성(있으면)과, 날짜 열이 있으면 오늘 날짜로 시작한다. 별점 속성은 배치 항목이 가져
     * 카드가 ★로 그린다(보기에서 숨겨져 있어도 된다).
     */
    private fun gallery(picks: RecipePicks, view: RecipeView): RecipeConfig {
        val rating = view.columns.firstOrNull { it.id == picks.ratingProp && it.type in RATING_TYPES }
        val layout = WidgetLayout(LayoutMode.SINGLE, listOf(LayoutItem(view.viewId, ratingProp = rating?.id.orEmpty())))
        val form = InputForm(listOfNotNull(rating).map { FormField(it.id) }, dateToday = view.has("date"))
        return RecipeConfig(WidgetLayout.encode(layout), InputForms.put("", view.viewId, form))
    }

    private const val MONTH_SUM = "이번 달 합계"
    private const val MONTH_BY_CATEGORY = "이번 달 분류별"

    /** 탭 이름: 캘린더·보드 보기는 종류 이름, 그 밖은 보기 이름을 쓰게 비운다. */
    private fun tabName(type: String): String = when (type) {
        "calendar" -> "캘린더"
        "board" -> "보드"
        else -> ""
    }

    private fun rank(recipe: Recipe, type: String): Int {
        val order = when (recipe) {
            Recipe.TASKS -> listOf("calendar", "board", "table", "list")
            Recipe.GALLERY -> listOf("gallery")
            else -> listOf("table", "list")
        }
        return order.indexOf(type).takeIf { it >= 0 } ?: order.size
    }

    private fun RecipeView.has(type: String) = columns.any { it.type == type }

    private fun RecipeView.column(id: String, type: String) = columns.firstOrNull { it.id == id && it.type == type }

    /** 새 항목의 날짜가 들어가는 열(캘린더의 날짜 속성, 없으면 보기의 첫 날짜 열). */
    private fun RecipeView.firstDate() = ViewColumn.dateColumnOf(columns)

    private fun RecipeView.keep(id: String, type: String): String = keep(id, setOf(type))

    /** 고른 별점 속성이 이 보기에도 맞으면 그대로, 아니면 이름이 별점·평점·rating 같은 숫자·선택 속성을 추천하고 없으면 별점 없음(빈 글자)이다. */
    private fun RecipeView.ratingOrGuess(id: String): String =
        columns.firstOrNull { it.id == id && it.type in RATING_TYPES }?.id
            ?: columns.firstOrNull { it.type in RATING_TYPES && RATING_NAMES.any { name -> it.name.contains(name, ignoreCase = true) } }?.id.orEmpty()

    private fun RecipeView.keep(id: String, types: Set<String>): String =
        columns.firstOrNull { it.id == id && it.type in types }?.id ?: columns.firstOrNull { it.type in types }?.id.orEmpty()
}
