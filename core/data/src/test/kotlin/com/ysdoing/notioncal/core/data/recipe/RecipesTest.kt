package com.ysdoing.notioncal.core.data.recipe

import com.ysdoing.notioncal.core.data.view.AggFn
import com.ysdoing.notioncal.core.data.view.AggPeriod
import com.ysdoing.notioncal.core.data.view.FormField
import com.ysdoing.notioncal.core.data.view.InputForm
import com.ysdoing.notioncal.core.data.view.InputForms
import com.ysdoing.notioncal.core.data.view.LayoutMode
import com.ysdoing.notioncal.core.data.view.TileKind
import com.ysdoing.notioncal.core.data.view.ViewChoice
import com.ysdoing.notioncal.core.data.view.ViewColumn
import com.ysdoing.notioncal.core.data.view.WidgetLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 11h RC-01~04 레시피: 등록한 보기와 고른 속성으로 노션 위젯의 배치·입력 양식 묶음을 만드는 순수 함수.
 * 화면 없이 후보 추천, 기본 선택, 만든 배치(보기·탭 이름·타일)와 양식을 본다.
 */
class RecipesTest {
    private val title = ViewColumn("t", "이름", "title")
    private val date = ViewColumn("d", "날짜", "date")
    private val due = ViewColumn("e", "마감", "date")
    private val amount = ViewColumn("n", "금액", "number")
    private val count = ViewColumn("q", "수량", "number")
    private val kind = ViewColumn("k", "분류", "select", listOf(ViewChoice("o1", "식비")))
    private val status = ViewColumn("s", "상태", "status", listOf(ViewChoice("a", "할 일")))
    private val run = ViewColumn("c1", "운동", "checkbox")
    private val read = ViewColumn("c2", "독서", "checkbox")
    private val water = ViewColumn("c3", "물", "checkbox")
    private val sleep = ViewColumn("c4", "수면", "checkbox")
    private val formula = ViewColumn("f", "합계", "formula")

    private fun view(id: String, type: String, vararg columns: ViewColumn, name: String = "보기 $id") = RecipeView(id, name, type, columns.toList())

    private val calendar = view("cal", "calendar", title, date, status)
    private val board = view("brd", "board", title, status)
    private val table = view("tbl", "table", title, date)
    private val habitTable = view("hab", "table", title, date, run, read, water, sleep)
    private val ledger = view("led", "table", title, date, amount, kind)
    private val rating = ViewColumn("r", "별점", "number")
    private val genre = ViewColumn("g", "장르", "select", listOf(ViewChoice("o1", "소설")))
    private val shelf = view("shelf", "gallery", title, date, rating, genre, name = "책장")
    private val tableOfBooks = view("books", "table", title, rating)
    private val readOnly = view("ro", "table", formula) // 제목 열이 없어 새 항목을 만들 수 없는 보기

    // --- 후보와 기본 선택

    @Test
    fun tasksOffersEveryViewThatCanCreateAnItemCalendarFirstThenBoard() {
        val views = listOf(table, board, readOnly, calendar, view("gal", "gallery", title))
        assertEquals(listOf("cal", "brd", "tbl", "gal"), Recipes.candidates(Recipe.TASKS, views).map { it.viewId })
    }

    @Test
    fun habitsNeedsADatePropertyAndACheckbox() {
        assertEquals(listOf("hab"), Recipes.candidates(Recipe.HABITS, listOf(table, calendar, ledger, habitTable)).map { it.viewId })
        assertEquals(emptyList<String>(), Recipes.candidates(Recipe.HABITS, listOf(view("x", "table", title, run)))) // 날짜가 없다
        assertEquals(emptyList<String>(), Recipes.candidates(Recipe.HABITS, listOf(view("y", "table", title, date)))) // 체크박스가 없다
    }

    @Test
    fun ledgerNeedsANumberAndADatePropertyAndPrefersTables() {
        val list = view("lst", "list", title, date, amount)
        assertEquals(listOf("led", "lst"), Recipes.candidates(Recipe.LEDGER, listOf(list, table, board, ledger)).map { it.viewId })
        assertEquals(emptyList<String>(), Recipes.candidates(Recipe.LEDGER, listOf(view("x", "table", title, amount)))) // 날짜가 없다
    }

    @Test
    fun aViewWithoutAUsableTitleIsNeverACandidate() {
        Recipe.values().forEach { assertFalse(it.name, Recipes.candidates(it, listOf(readOnly)).isNotEmpty()) }
    }

    @Test
    fun defaultPicksTakeTheFirstFittingViewAndPropertiesOfEachType() {
        val tasks = Recipes.defaultPicks(Recipe.TASKS, listOf(table, board, calendar))
        assertEquals(RecipePicks(viewId = "cal", boardViewId = "brd"), tasks) // 새 항목의 날짜는 시트가 보기의 날짜 열에 넣는다

        val habits = Recipes.defaultPicks(Recipe.HABITS, listOf(table, habitTable))
        assertEquals(RecipePicks(viewId = "hab", checkProps = listOf("c1", "c2", "c3")), habits) // 체크박스는 최대 3개

        val money = Recipes.defaultPicks(Recipe.LEDGER, listOf(ledger))
        assertEquals(RecipePicks(viewId = "led", amountProp = "n", categoryProp = "k"), money)
    }

    @Test
    fun tasksDefaultsWithOnlyOneKindOfViewLeaveTheBoardEmpty() {
        assertEquals(RecipePicks(viewId = "brd"), Recipes.defaultPicks(Recipe.TASKS, listOf(board)))
        assertEquals(RecipePicks(viewId = "tbl"), Recipes.defaultPicks(Recipe.TASKS, listOf(table)))
    }

    @Test
    fun noCandidateGivesEmptyPicks() {
        Recipe.values().forEach { assertEquals(it.name, RecipePicks(), Recipes.defaultPicks(it, emptyList())) }
    }

    @Test
    fun changingTheViewRederivesItsPropertiesAndKeepsWhatStillFits() {
        val picks = RecipePicks(viewId = "led", amountProp = "n", categoryProp = "k")
        val other = view("led2", "table", title, due, count, status)
        assertEquals(RecipePicks(viewId = "led2", amountProp = "q", categoryProp = "s"), Recipes.withView(Recipe.LEDGER, picks, other))
        // 같은 속성 ID가 새 보기에도 있으면 사용자가 고른 것을 지킨다
        val same = view("led3", "table", title, date, amount, count, kind)
        assertEquals(picks.copy(viewId = "led3"), Recipes.withView(Recipe.LEDGER, picks, same))
    }

    // --- 만들기

    private fun layoutOf(config: RecipeConfig) = WidgetLayout.parse(config.layoutJson)

    @Test
    fun tasksBuildsCalendarAndBoardTabsWithTodayAsTheDefaultDate() {
        val config = Recipes.build(Recipe.TASKS, RecipePicks(viewId = "cal", boardViewId = "brd"), listOf(calendar, board))!!
        val layout = layoutOf(config)
        assertEquals(LayoutMode.TABS, layout.mode)
        assertEquals(listOf("cal" to "캘린더", "brd" to "보드"), layout.items.map { it.viewId to it.label })
        assertEquals(emptyList<Any>(), layout.tiles)
        assertEquals(InputForm(dateToday = true), InputForms.get(config.formJson, "cal")) // 날짜 열이 있는 보기만
        assertNull(InputForms.get(config.formJson, "brd")) // 보드 보기에는 날짜 열이 없다
    }

    @Test
    fun tasksWithOneViewIsASingleLayoutAndDoesNotLabelAPlainTable() {
        val config = Recipes.build(Recipe.TASKS, RecipePicks(viewId = "tbl"), listOf(table))!!
        val layout = layoutOf(config)
        assertEquals(LayoutMode.SINGLE, layout.mode)
        assertEquals(listOf("tbl" to ""), layout.items.map { it.viewId to it.label })
        assertEquals(true, layout.presetsApply)
    }

    @Test
    fun tasksIgnoresABoardThatIsTheSameViewOrNotRegistered() {
        assertEquals(1, layoutOf(Recipes.build(Recipe.TASKS, RecipePicks(viewId = "cal", boardViewId = "cal"), listOf(calendar))!!).items.size)
        assertEquals(1, layoutOf(Recipes.build(Recipe.TASKS, RecipePicks(viewId = "cal", boardViewId = "nope"), listOf(calendar))!!).items.size)
    }

    @Test
    fun habitsBuildsStreakAndWeeklyRatioTilesAndAOncePerDayTodayForm() {
        val config = Recipes.build(Recipe.HABITS, RecipePicks(viewId = "hab", checkProps = listOf("c1", "c2")), listOf(habitTable))!!
        val layout = layoutOf(config)
        assertEquals(LayoutMode.SECTIONS, layout.mode)
        assertEquals(listOf("hab"), layout.items.map { it.viewId })
        assertEquals(
            listOf(
                Triple(AggFn.STREAK, "c1", AggPeriod.ALL),
                Triple(AggFn.RATIO, "c1", AggPeriod.THIS_WEEK),
                Triple(AggFn.RATIO, "c2", AggPeriod.THIS_WEEK),
            ),
            layout.tiles.map { Triple(it.fn, it.propId, it.period) },
        )
        assertTrue(layout.tiles.all { it.viewId == "hab" && it.dateProp == "d" })
        assertEquals(listOf(null, 80.0, 80.0), layout.tiles.map { it.goal }) // 연속 달성일에는 목표를 두지 않는다
        assertEquals(
            InputForm(listOf(FormField("c1"), FormField("c2")), showDate = true, dateToday = true, titleDate = true, oncePerDay = true),
            InputForms.get(config.formJson, "hab"),
        )
        assertTrue(layout.presetsApply)
    }

    @Test
    fun habitsKeepsAtMostThreeCheckboxesSoTheTileStripFits() {
        val picks = RecipePicks(viewId = "hab", checkProps = listOf("c1", "c2", "c3", "c4"))
        val layout = layoutOf(Recipes.build(Recipe.HABITS, picks, listOf(habitTable))!!)
        assertEquals(listOf("c1", "c2", "c3"), layout.tiles.filter { it.fn == AggFn.RATIO }.map { it.propId })
        assertTrue(layout.tiles.size <= WidgetLayout.TILE_LIMIT)
    }

    @Test
    fun ledgerBuildsAMonthlySumTileWithTheBudgetAsGoalAndAnAmountCategoryForm() {
        val picks = RecipePicks(viewId = "led", amountProp = "n", categoryProp = "k", budget = 500000.0)
        val config = Recipes.build(Recipe.LEDGER, picks, listOf(ledger))!!
        val layout = layoutOf(config)
        assertEquals(LayoutMode.SECTIONS, layout.mode)
        val tile = layout.tiles.first()
        assertEquals(listOf(AggFn.SUM, "n", "d", AggPeriod.THIS_MONTH, 500000.0), listOf(tile.fn, tile.propId, tile.dateProp, tile.period, tile.goal))
        assertEquals(InputForm(listOf(FormField("n"), FormField("k")), dateToday = true), InputForms.get(config.formJson, "led"))
        assertTrue(layout.presetsApply) // 섹션에 보기 하나라 프리셋 줄이 보인다
    }

    @Test
    fun ledgerWithACategoryAlsoBuildsAThisMonthDonutTileByThatCategory() {
        val picks = RecipePicks(viewId = "led", amountProp = "n", categoryProp = "k")
        val tiles = layoutOf(Recipes.build(Recipe.LEDGER, picks, listOf(ledger))!!).tiles
        assertEquals(2, tiles.size)
        val donut = tiles.last()
        assertEquals(listOf(TileKind.DONUT, "k", AggFn.SUM, "n", "d", AggPeriod.THIS_MONTH), listOf(donut.kind, donut.groupProp, donut.fn, donut.propId, donut.dateProp, donut.period))
        assertEquals(TileKind.AGG, tiles.first().kind)
    }

    @Test
    fun ledgerWithoutACategoryHasNoDonutTile() {
        val tiles = layoutOf(Recipes.build(Recipe.LEDGER, RecipePicks(viewId = "led", amountProp = "n"), listOf(ledger))!!).tiles
        assertEquals(listOf(TileKind.AGG), tiles.map { it.kind })
    }

    // --- Phase 12d RC-05 갤러리형 기록

    @Test
    fun theGalleryRecipeOffersOnlyGalleryViewsWithATitle() {
        assertEquals(listOf("shelf"), Recipes.candidates(Recipe.GALLERY, listOf(tableOfBooks, calendar, shelf, ledger)).map { it.viewId })
        assertEquals(emptyList<String>(), Recipes.candidates(Recipe.GALLERY, listOf(view("g2", "gallery", date)))) // 제목 열이 없다
    }

    @Test
    fun theGalleryRecipeGuessesARatingByNameAndOtherwiseLeavesItEmpty() {
        assertEquals("r", Recipes.defaultPicks(Recipe.GALLERY, listOf(shelf)).ratingProp) // 별점
        val noName = view("g3", "gallery", title, ViewColumn("q", "쪽수", "number"))
        assertEquals("", Recipes.defaultPicks(Recipe.GALLERY, listOf(noName)).ratingProp)
        // 고른 속성이 새 보기에도 있으면 지키고, 없으면 새 보기에서 다시 추천한다
        val kept = Recipes.withView(Recipe.GALLERY, RecipePicks(viewId = "shelf", ratingProp = "g"), shelf)
        assertEquals("g", kept.ratingProp)
        assertEquals("r", Recipes.withView(Recipe.GALLERY, RecipePicks(viewId = "x", ratingProp = "gone"), shelf).ratingProp)
    }

    @Test
    fun theGalleryRecipeBuildsASingleGalleryItemCarryingTheRatingAndAFormWithTheRatingAndTodaysDate() {
        val config = Recipes.build(Recipe.GALLERY, RecipePicks(viewId = "shelf", ratingProp = "r"), listOf(shelf))!!
        val layout = layoutOf(config)
        assertEquals(LayoutMode.SINGLE, layout.mode)
        assertEquals(listOf("shelf" to "r"), layout.items.map { it.viewId to it.ratingProp })
        assertEquals(InputForm(listOf(FormField("r")), dateToday = true), InputForms.get(config.formJson, "shelf"))
    }

    @Test
    fun aGalleryWithoutARatingOrADateBuildsAPlainForm() {
        val noDate = view("g4", "gallery", title)
        val config = Recipes.build(Recipe.GALLERY, RecipePicks(viewId = "g4"), listOf(noDate))!!
        assertEquals("", layoutOf(config).items.single().ratingProp)
        assertNull(InputForms.get(config.formJson, "g4")) // 기본 양식은 따로 저장하지 않는다
        // 사라지거나 맞지 않는 별점 속성은 버린다
        val ghost = Recipes.build(Recipe.GALLERY, RecipePicks(viewId = "shelf", ratingProp = "zzz"), listOf(shelf))!!
        assertEquals("", layoutOf(ghost).items.single().ratingProp)
        assertNull(Recipes.build(Recipe.GALLERY, RecipePicks(viewId = "books", ratingProp = "r"), listOf(tableOfBooks))) // 갤러리가 아니다
    }

    @Test
    fun ledgerWithoutABudgetOrCategoryStillBuilds() {
        val picks = RecipePicks(viewId = "led", amountProp = "n", budget = 0.0)
        val config = Recipes.build(Recipe.LEDGER, picks, listOf(ledger))!!
        assertNull(layoutOf(config).tiles.single().goal) // 0 이하는 목표가 아니다
        assertEquals(listOf(FormField("n")), InputForms.get(config.formJson, "led")!!.fields)
    }

    @Test
    fun missingRequiredPicksOrViewsGiveNoConfig() {
        assertNull(Recipes.build(Recipe.TASKS, RecipePicks(), listOf(calendar)))
        assertNull(Recipes.build(Recipe.TASKS, RecipePicks(viewId = "gone"), listOf(calendar)))
        assertNull(Recipes.build(Recipe.HABITS, RecipePicks(viewId = "hab"), listOf(habitTable))) // 체크박스 없음
        assertNull(Recipes.build(Recipe.LEDGER, RecipePicks(viewId = "led"), listOf(ledger))) // 금액 없음
    }

    @Test
    fun picksThatNoLongerMatchTheViewsColumnsGiveNoConfig() {
        // 속성 ID가 보기에 없거나 종류가 안 맞으면(숫자 열이 아닌 것을 금액으로) 만들지 않는다
        assertNull(Recipes.build(Recipe.LEDGER, RecipePicks(viewId = "led", amountProp = "k"), listOf(ledger)))
        assertNull(Recipes.build(Recipe.HABITS, RecipePicks(viewId = "hab", checkProps = listOf("zzz")), listOf(habitTable)))
    }

    @Test
    fun theTilesAndTheNewItemUseTheViewsFirstDateColumn() {
        // 새 항목은 보기의 첫 날짜 열에 날짜를 쓰니(ViewWriter), 타일과 하루 한 줄 판정도 같은 열을 읽어야 어긋나지 않는다
        val twoDates = view("two", "table", title, due, date, amount)
        val tile = layoutOf(Recipes.build(Recipe.LEDGER, RecipePicks(viewId = "two", amountProp = "n"), listOf(twoDates))!!).tiles.single()
        assertEquals("e", tile.dateProp)
    }

    @Test
    fun aCalendarsHiddenDatePropertyIsTheTilesDateEvenWhenAnotherDateColumnComesFirst() {
        val hidden = ViewColumn("hd", "예정일", "date", role = ViewColumn.ROLE_DATE)
        val twoDates = view("two", "table", title, date, amount, hidden)
        val tile = layoutOf(Recipes.build(Recipe.LEDGER, RecipePicks(viewId = "two", amountProp = "n"), listOf(twoDates))!!).tiles.single()
        assertEquals("hd", tile.dateProp)
    }

    @Test
    fun theBudgetBecomesTheTilesGoal() {
        assertEquals(1000.0, layoutOf(Recipes.build(Recipe.LEDGER, RecipePicks(viewId = "led", amountProp = "n", budget = 1000.0), listOf(ledger))!!).tiles.single().goal)
    }

    @Test
    fun theBuiltLayoutRoundTripsThroughTheWidgetLayoutParser() {
        listOf(
            Recipe.TASKS to RecipePicks(viewId = "cal", boardViewId = "brd"),
            Recipe.HABITS to RecipePicks(viewId = "hab", checkProps = listOf("c1", "c2", "c3")),
            Recipe.LEDGER to RecipePicks(viewId = "led", amountProp = "n", categoryProp = "k", budget = 1000.0),
        ).forEach { (recipe, picks) ->
            val config = Recipes.build(recipe, picks, listOf(calendar, board, habitTable, ledger))!!
            val layout = layoutOf(config)
            assertEquals(recipe.name, WidgetLayout.encode(layout), config.layoutJson) // 읽어도 깎이는 것이 없다(타일이 정규화에서 버려지지 않는다)
        }
    }
}
