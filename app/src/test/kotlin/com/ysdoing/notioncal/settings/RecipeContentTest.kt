package com.ysdoing.notioncal.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.ysdoing.notioncal.core.data.recipe.Recipe
import com.ysdoing.notioncal.core.data.recipe.RecipeDraft
import com.ysdoing.notioncal.core.data.recipe.RecipeView
import com.ysdoing.notioncal.core.data.view.ViewChoice
import com.ysdoing.notioncal.core.data.view.ViewColumn
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Phase 11h RC-01 레시피 화면: 레시피 고르기 → 보기 추천·기본 선택 → 속성 고르기 → 미리보기 → '홈 화면에 추가'.
 * 화면 상태([RecipeUi])를 직접 넣어 어떤 줄이 보이고 누르면 무엇을 알리는지 본다(미리보기는 자리만 본다).
 */
@RunWith(RobolectricTestRunner::class)
class RecipeContentTest {
    @get:Rule
    val compose = createComposeRule()

    private fun col(id: String, name: String, type: String, vararg options: String) = ViewColumn(id, name, type, options.map { ViewChoice("o-$it", it) })
    private val title = col("t", "이름", "title")
    private val calendar = RecipeView("cal", "일정", "calendar", listOf(title, col("d", "날짜", "date")))
    private val board = RecipeView("brd", "할 일 보드", "board", listOf(title, col("s", "상태", "status", "할 일")))
    private val habits = RecipeView("hab", "습관", "table", listOf(title, col("d", "날짜", "date"), col("c1", "운동", "checkbox"), col("c2", "독서", "checkbox")))
    private val ledger = RecipeView("led", "가계부", "table", listOf(title, col("d", "날짜", "date"), col("n", "금액", "number"), col("m", "수량", "number"), col("k", "분류", "select", "식비")))
    private val gallery = RecipeView("gal", "책장", "gallery", listOf(title, col("r", "별점", "number"), col("g", "장르", "select", "소설"), col("d", "읽은 날", "date")))
    private val views = listOf(calendar, board, habits, ledger, gallery)

    private val calls = mutableListOf<String>()
    private val actions = RecipeActions(
        onRecipe = { calls += "recipe:${it.name}" }, onView = { calls += "view:$it" }, onBoard = { calls += "board:$it" },
        onAmount = { calls += "amount:$it" }, onCategory = { calls += "category:$it" }, onRating = { calls += "rating:$it" }, onCheck = { calls += "check:$it" }, onBudget = { calls += "budget:$it" },
        onAddView = { calls += "addView" }, onPin = { calls += "pin" },
    )

    private fun show(recipe: Recipe, registered: List<RecipeView>? = views, pin: PinState = PinState.IDLE, draftEdit: (RecipeDraft) -> RecipeDraft = { it }) {
        val draft = registered?.let { draftEdit(RecipeDraft().start(recipe, it)) } ?: RecipeDraft(recipe)
        val ui = RecipeUi(registered, draft, registered?.let { draft.config(it) }, pin)
        // 실제 화면처럼 세로로 스크롤되게 쌓는다(아래 줄은 화면 밖이라 스크롤해야 누를 수 있다)
        compose.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) { RecipeContent(ui, actions) { Text("미리보기 자리") } } } }
    }

    @Test
    fun whileTheViewsLoadOnlyALoadingLineShows() {
        show(Recipe.TASKS, registered = null)
        compose.onNodeWithText("불러오는 중", substring = true).assertExists()
        compose.onNodeWithText("홈 화면에 추가").assertDoesNotExist()
    }

    @Test
    fun fourRecipesAreOfferedAndPressingOneReportsIt() {
        show(Recipe.TASKS)
        compose.onNodeWithText("일정·할 일").assertIsSelected()
        compose.onNodeWithText("해빗트래커").performScrollTo().performClick()
        compose.onNodeWithText("가계부").performScrollTo().performClick()
        compose.onNodeWithText("책·영화 기록").performScrollTo().performClick()
        assertEquals(listOf("recipe:HABITS", "recipe:LEDGER", "recipe:GALLERY"), calls)
    }

    @Test
    fun theGalleryRecipePreselectsTheGuessedRatingAndReportsAChangeOrNone() {
        show(Recipe.GALLERY)
        compose.onNodeWithText("책장 (갤러리)").assertIsSelected()
        compose.onNodeWithText("별점").assertIsSelected() // 이름이 별점인 숫자 속성을 추천한다
        compose.onNodeWithText("장르").performScrollTo().performClick()
        compose.onNodeWithText("별점 없음").performScrollTo().performClick()
        compose.onNodeWithText("★★★★☆", substring = true).assertExists() // 안내 글에 보이는 모양
        assertEquals(listOf("rating:g", "rating:"), calls)
    }

    @Test
    fun withoutAGalleryViewTheGalleryRecipeSaysWhatItNeeds() {
        show(Recipe.GALLERY, registered = listOf(calendar, ledger))
        compose.onNodeWithText("갤러리 보기(제목 열 포함)가 필요합니다", substring = true).assertExists()
        compose.onNodeWithText("노션 보기 추가").performScrollTo().assertExists()
    }

    @Test
    fun tasksOffersTheViewsAndABoardPickWithNoneFirst() {
        show(Recipe.TASKS)
        compose.onNodeWithText("일정 (캘린더)").assertIsSelected() // 캘린더 보기가 먼저 추천돼 선택돼 있다
        compose.onNodeWithText("할 일 보드 (보드)").performScrollTo().performClick()
        compose.onNodeWithText("상태 보드", substring = true).assertExists()
        compose.onNodeWithText("보드 없음").performScrollTo().performClick()
        assertEquals("view:brd", calls.first())
        assertEquals("board:", calls.last())
    }

    @Test
    fun habitsShowsDateRadiosAndCheckboxRowsThatToggle() {
        show(Recipe.HABITS)
        compose.onNodeWithText("습관 (표)").assertIsSelected()
        compose.onNodeWithText("운동").performScrollTo().performClick()
        compose.onNodeWithText("독서").performScrollTo().performClick()
        assertEquals(listOf("check:c1", "check:c2"), calls)
        compose.onNodeWithText("최대 3개", substring = true).assertExists()
    }

    @Test
    fun ledgerShowsItsDefaultsAndReportsChangesAndTheBudget() {
        show(Recipe.LEDGER)
        compose.onNodeWithText("가계부 (표)").assertIsSelected()
        compose.onNodeWithText("금액").assertIsSelected() // 첫 숫자 속성이 기본이다
        compose.onNodeWithText("수량").performScrollTo().performClick()
        compose.onNodeWithText("분류").assertIsSelected()
        compose.onNodeWithText("분류 없음").performScrollTo().performClick()
        compose.onNodeWithText("한 달 예산", substring = true).performScrollTo().performTextInput("500000")
        assertEquals(listOf("amount:m", "category:", "budget:500000"), calls)
    }

    @Test
    fun theDateColumnIsNotAChoiceButItsNameIsShownForHabitsAndLedger() {
        show(Recipe.LEDGER)
        compose.onNodeWithText("새 항목의 날짜는", substring = true).assertExists()
        compose.onNodeWithText("‘날짜’", substring = true).assertExists() // 첫 날짜 열의 이름
    }

    @Test
    fun withoutAMatchingViewTheScreenSaysWhatIsNeededAndOffersToAddOne() {
        show(Recipe.LEDGER, registered = listOf(calendar, board))
        compose.onNodeWithText("맞는 노션 보기가 없습니다", substring = true).assertExists()
        compose.onNodeWithText("노션 보기 추가").performScrollTo().performClick()
        assertEquals(listOf("addView"), calls)
        compose.onNodeWithText("미리보기 자리").assertDoesNotExist()
        compose.onNodeWithText("홈 화면에 추가").assertIsNotEnabled()
    }

    @Test
    fun theAddButtonIsEnabledOnlyForAValidRecipeAndShowsThePreview() {
        show(Recipe.LEDGER)
        compose.onNodeWithText("미리보기 자리").assertExists()
        compose.onNodeWithText("홈 화면에 추가").assertIsEnabled()
        compose.onNodeWithText("홈 화면에 추가").performScrollTo().performClick()
        assertEquals(listOf("pin"), calls)
    }

    @Test
    fun anIncompleteChoiceBlocksTheButton() {
        show(Recipe.LEDGER) { it.selectAmount("") }
        compose.onNodeWithText("홈 화면에 추가").assertIsNotEnabled()
        compose.onNodeWithText("미리보기 자리").assertDoesNotExist()
    }

    @Test
    fun theButtonWaitsWhileThePinRequestIsOutAndThenSaysWhatToDo() {
        show(Recipe.LEDGER, pin = PinState.BUSY)
        compose.onNodeWithText("홈 화면에 추가").assertIsNotEnabled()
    }

    @Test
    fun aRequestedPinTellsToPickAPlaceOnTheHomeScreen() {
        show(Recipe.LEDGER, pin = PinState.REQUESTED)
        compose.onNodeWithText("홈 화면에서 위치를 골라", substring = true).assertExists()
    }

    @Test
    fun anUnsupportedLauncherTellsToLongPressAndKeepsTheRecipeForFiveMinutes() {
        show(Recipe.LEDGER, pin = PinState.UNSUPPORTED)
        compose.onNodeWithText("길게 눌러", substring = true).assertExists()
        compose.onNodeWithText("5분", substring = true).assertExists()
    }
}
