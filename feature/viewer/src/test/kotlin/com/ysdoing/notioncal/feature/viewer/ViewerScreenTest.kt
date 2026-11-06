package com.ysdoing.notioncal.feature.viewer

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.ysdoing.notioncal.core.data.view.ViewBoard
import com.ysdoing.notioncal.core.data.view.ViewChoice
import com.ysdoing.notioncal.core.data.view.ViewColumn
import com.ysdoing.notioncal.core.data.view.ViewRow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
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

    private val info = listOf(ViewColumn("t", "이름", "title"), ViewColumn("c", "분류", "select", listOf(ViewChoice("h", "집"), ViewChoice("w", "일"))))

    private fun board(rows: List<ViewRow>, complete: Boolean = true) =
        ViewBoard("v1", "할 일", "table", listOf("이름", "분류"), rows, 1, null, info, rowsComplete = complete)

    private val rows = listOf(
        ViewRow("p1", "https://notion.so/p1", listOf("장보기", "집"), typed = mapOf("t" to JsonPrimitive("장보기"), "c" to JsonPrimitive("h"))),
        ViewRow("p2", "https://notion.so/p2", listOf("운동", ""), typed = mapOf("t" to JsonPrimitive("운동"))),
    )

    @Test
    fun readyShowsTheNameTheRowsAndTheirColumns() {
        compose.setContent { MaterialTheme { ViewerContent(ViewerState.Ready(board(rows)), onBack = {}, onOpen = {}) } }
        compose.onNodeWithText("할 일").assertExists() // 보기 이름
        compose.onNodeWithText("2행").assertExists()
        compose.onNodeWithText("장보기").assertExists()
        compose.onNodeWithText("분류 집").assertExists() // 값이 있는 열만 '열 이름 값'으로
    }

    @Test
    fun tappingARowOpensTheEditSheetAndItsOpenButtonGivesTheNotionAddress() {
        val opened = mutableListOf<String>()
        compose.setContent { MaterialTheme { ViewerContent(ViewerState.Ready(board(rows)), onBack = {}, onOpen = { opened += it }) } }
        compose.onNodeWithText("저장").assertDoesNotExist() // 시트는 행을 누르기 전에는 없다
        compose.onNodeWithContentDescription("운동, 편집").performClick()
        compose.onNodeWithText("저장").assertExists()
        compose.onNodeWithText("노션에서 열기").performScrollTo().performClick()
        assertEquals(listOf("https://notion.so/p2"), opened)
    }

    @Test
    fun aRowWithoutAnAddressHasNoOpenButton() {
        val noUrl = listOf(ViewRow("p3", "", listOf("주소 없음", ""), typed = mapOf("t" to JsonPrimitive("주소 없음"))))
        compose.setContent { MaterialTheme { ViewerContent(ViewerState.Ready(board(noUrl)), onBack = {}, onOpen = {}) } }
        compose.onNodeWithContentDescription("주소 없음, 편집").performClick()
        compose.onNodeWithText("노션에서 열기").assertDoesNotExist()
        compose.onNodeWithText("휴지통으로 보내기").assertExists()
    }

    @Test
    fun savingFromTheSheetPassesThePageAndOnlyTheChanges() {
        val saved = mutableListOf<Pair<String, Map<String, JsonElement>>>()
        compose.setContent {
            MaterialTheme { ViewerContent(ViewerState.Ready(board(rows)), onBack = {}, onOpen = {}, onSave = { page, changes -> saved += page to changes; true }) }
        }
        compose.onNodeWithContentDescription("장보기, 편집").performClick()
        compose.onNodeWithText("일").performClick() // 분류를 '일'로
        compose.onNodeWithText("저장").performScrollTo().performClick()
        assertEquals(listOf<Pair<String, Map<String, JsonElement>>>("p1" to mapOf("c" to JsonPrimitive("w"))), saved)
        compose.onNodeWithText("저장").assertDoesNotExist() // 저장하면 시트가 닫힌다
    }

    @Test
    fun trashingFromTheSheetPassesThePage() {
        val trashed = mutableListOf<String>()
        compose.setContent {
            MaterialTheme { ViewerContent(ViewerState.Ready(board(rows)), onBack = {}, onOpen = {}, onTrash = { trashed += it }) }
        }
        compose.onNodeWithContentDescription("운동, 편집").performClick()
        compose.onNodeWithText("휴지통으로 보내기").performScrollTo().performClick()
        compose.onNodeWithText("보내기").performClick()
        assertEquals(listOf("p2"), trashed)
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
