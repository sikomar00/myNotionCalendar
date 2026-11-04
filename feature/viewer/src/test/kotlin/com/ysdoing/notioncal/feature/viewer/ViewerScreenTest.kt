package com.ysdoing.notioncal.feature.viewer

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.ysdoing.notioncal.core.data.view.ViewBoard
import com.ysdoing.notioncal.core.data.view.ViewRow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** VR-02 '더 보기' 전체 화면: 보기의 행을 열 이름과 함께 보이고, 행을 누르면 노션 페이지를 연다(편집 시트는 11b). */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w384dp-h832dp-xxhdpi")
class ViewerScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private fun board(rows: List<ViewRow>, complete: Boolean = true) =
        ViewBoard("v1", "할 일", "table", listOf("이름", "분류"), rows, 1, null, rowsComplete = complete)

    private val rows = listOf(
        ViewRow("p1", "https://notion.so/p1", listOf("장보기", "집")),
        ViewRow("p2", "https://notion.so/p2", listOf("운동", "")),
    )

    @Test
    fun readyShowsTheNameTheRowsAndTheirColumnsAndOpensARow() {
        val opened = mutableListOf<String>()
        compose.setContent { MaterialTheme { ViewerContent(ViewerState.Ready(board(rows)), onBack = {}, onOpen = { opened += it }) } }
        compose.onNodeWithText("할 일").assertExists() // 보기 이름
        compose.onNodeWithText("2행").assertExists()
        compose.onNodeWithText("장보기").assertExists()
        compose.onNodeWithText("분류 집").assertExists() // 값이 있는 열만 '열 이름 값'으로
        compose.onNodeWithContentDescription("운동, 노션에서 열기").performClick()
        assertEquals(listOf("https://notion.so/p2"), opened)
    }

    @Test
    fun anIncompleteSetSaysThereAreMoreInNotion() {
        compose.setContent { MaterialTheme { ViewerContent(ViewerState.Ready(board(rows, complete = false)), onBack = {}, onOpen = {}) } }
        compose.onNodeWithText("처음 2행만 받았어요. 노션에 행이 더 있어요").assertExists()
    }

    @Test
    fun missingEmptyAndLoadingAreSpelledOut() {
        compose.setContent { MaterialTheme { ViewerContent(ViewerState.Missing, onBack = {}, onOpen = {}) } }
        compose.onNodeWithText("이 보기를 찾을 수 없어요. 설정에서 보기를 다시 등록해 주세요.").assertExists()
    }

    @Test
    fun anEmptyViewAndTheLoadingStateTellWhatIsHappening() {
        compose.setContent { MaterialTheme { ViewerContent(ViewerState.Ready(board(emptyList())), onBack = {}, onOpen = {}) } }
        compose.onNodeWithText("표시할 행이 없어요").assertExists()
    }

    @Test
    fun loadingShowsAProgressIndicatorWithAName() {
        compose.setContent { MaterialTheme { ViewerContent(ViewerState.Loading, onBack = {}, onOpen = {}) } }
        compose.onNodeWithContentDescription("불러오는 중").assertExists()
    }

    @Test
    fun theBackButtonHasAName() {
        var back = 0
        compose.setContent { MaterialTheme { ViewerContent(ViewerState.Missing, onBack = { back++ }, onOpen = {}) } }
        compose.onNodeWithContentDescription("뒤로").performClick()
        assertEquals(1, back)
    }
}
