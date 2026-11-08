package com.ysdoing.notioncal.feature.quicksheet

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.ysdoing.notioncal.core.data.view.ViewBoard
import com.ysdoing.notioncal.core.data.view.ViewChoice
import com.ysdoing.notioncal.core.data.view.ViewColumn
import com.ysdoing.notioncal.core.data.view.ViewRow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Phase 11b ED-01~05: 보기 행 편집 시트의 내용. 속성 순서·잠금·바뀐 것만 저장·휴지통 확인·큰 글꼴. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class ViewRowEditorTest {
    @get:Rule
    val compose = createComposeRule()

    private val title = ViewColumn("t", "이름", "title")
    private val status = ViewColumn("st", "상태", "status", listOf(ViewChoice("o1", "할 일", color = "gray"), ViewChoice("o2", "완료", color = "green")))
    private val formula = ViewColumn("fm", "점수", "formula")
    private val done = ViewColumn("dn", "끝남", "checkbox")
    private val pages = ViewColumn("pg", "쪽수", "number")
    private val memo = ViewColumn("tx", "메모", "rich_text")
    private val tags = ViewColumn("tg", "태그", "multi_select", listOf(ViewChoice("a", "가", color = "blue"), ViewChoice("b", "나")))
    private val day = ViewColumn("dt", "날짜", "date")
    private val link = ViewColumn("ur", "링크", "url")

    private val columns = listOf(title, status, formula, done, pages, memo, tags, day, link)
    private val board = ViewBoard("v1", "할 일", "table", columns.map { it.name }, emptyList(), 1, null, columns)
    private val row = ViewRow(
        "p1", "https://notion.so/p1", listOf("첫째", "할 일", "7", "", "12", "메모", "가", "2026-11-04", ""),
        typed = mapOf(
            "t" to JsonPrimitive("첫째"), "st" to JsonPrimitive("o1"), "pg" to JsonPrimitive(12.0), "tx" to JsonPrimitive("메모"),
            "tg" to JsonArray(listOf(JsonPrimitive("a"))),
            "dt" to JsonObject(mapOf("start" to JsonPrimitive("2026-11-04"))),
        ),
    )

    private val saved = mutableListOf<Map<String, JsonElement>>()
    private val opened = mutableListOf<String>()
    private var trashed = 0
    private var closed = 0
    private var saveResult = true

    private fun show(modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier) {
        compose.setContent {
            MaterialTheme {
                ViewRowEditor(
                    board, row,
                    ViewRowActions(onSave = { saved += it; saveResult }, onTrash = { trashed++ }, onOpen = { opened += it }, onClose = { closed++ }),
                    modifier = modifier,
                )
            }
        }
    }

    @Test
    fun showsTheTitleAndEveryPropertyInViewOrderWithLockedOnesMarked() {
        show()
        assertTrue("시트 머리에 행 제목이 없다", compose.onAllNodesWithText("첫째").fetchSemanticsNodes().isNotEmpty())
        listOf("이름", "상태", "점수", "끝남", "쪽수", "메모", "태그", "날짜", "링크").forEach { assertTrue("$it 속성이 없다", compose.onAllNodesWithText(it).fetchSemanticsNodes().isNotEmpty()) }
        compose.onNodeWithText("노션이 계산하는 값입니다").assertExists() // 수식은 잠금 + 이유(ED-02)
        compose.onNodeWithContentDescription("읽기 전용").assertExists()
        compose.onNodeWithText("7", useUnmergedTree = true).assertExists() // 잠긴 값도 보여 준다
    }

    @Test
    fun savingSendsOnlyTheChangedProperties() {
        show()
        compose.onNodeWithText("저장").assertIsNotEnabled() // 바뀐 것이 없으면 저장할 것이 없다
        compose.onNodeWithText("이름").performTextClearance()
        compose.onNodeWithText("이름").performTextInput("둘째")
        compose.onNodeWithText("끝남").performClick()
        compose.onNodeWithText("완료").performClick() // 상태 옵션 칩
        compose.onNodeWithText("저장").performScrollTo().assertIsEnabled().performClick()
        assertEquals(
            listOf(mapOf("t" to JsonPrimitive("둘째"), "dn" to JsonPrimitive(true), "st" to JsonPrimitive("o2"))),
            saved,
        )
        assertEquals(1, closed)
    }

    @Test
    fun turningOffAllDayOnADatePropertyMakesItATimedValue() {
        // Phase 12c: 종일 스위치를 끄면 기본 9~10시(서울)의 시간 일정이 되고, 날짜는 그대로다
        show()
        compose.onNodeWithText("종일").performScrollTo().performClick()
        compose.onNodeWithText("저장").performScrollTo().assertIsEnabled().performClick()
        val date = saved.single().getValue("dt") as JsonObject
        assertEquals(JsonPrimitive("2026-11-04T09:00:00.000+09:00"), date["start"])
        assertEquals(JsonPrimitive("2026-11-04T10:00:00.000+09:00"), date["end"])
    }

    @Test
    fun anInvalidNumberShowsAnErrorAndBlocksSaving() {
        show()
        compose.onNodeWithText("쪽수").performTextClearance()
        compose.onNodeWithText("쪽수").performTextInput("12쪽")
        compose.onNodeWithText("숫자만 넣으세요").assertExists()
        compose.onNodeWithText("저장").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun clearingANumberSavesAnEmptyValue() {
        show()
        compose.onNodeWithText("쪽수").performTextClearance()
        compose.onNodeWithText("저장").performScrollTo().performClick()
        assertEquals(listOf(mapOf<String, JsonElement>("pg" to JsonNull)), saved)
    }

    @Test
    fun aFailedSaveKeepsTheEditorOpenAndSaysSo() {
        saveResult = false
        show()
        compose.onNodeWithText("메모").performTextInput("!")
        compose.onNodeWithText("저장").performScrollTo().performClick()
        compose.onNodeWithText("저장하지 못했습니다. 다시 시도하세요").assertExists()
        assertEquals(0, closed)
    }

    @Test
    fun aNewMultiSelectOptionIsAddedByName() {
        show()
        compose.onNodeWithText("새 옵션 이름").performTextInput("새 태그")
        compose.onNodeWithText("추가").performClick()
        compose.onNodeWithText("저장").performScrollTo().performClick()
        val tg = saved.single().getValue("tg") as JsonArray
        assertEquals(JsonPrimitive("a"), tg[0]) // 이미 고른 옵션은 그대로
        assertEquals("새 태그", ((tg[1] as JsonObject)["name"] as JsonPrimitive).content)
    }

    @Test
    fun aSyncWhileTheSheetIsOpenDoesNotTurnUntouchedFieldsIntoEdits() {
        // 시트를 연 뒤 동기화로 행이 바뀌어도, 건드리지 않은 칸은 바뀐 것이 아니다(저장하면 노션 쪽 변경을 되돌려 쓰게 된다).
        var current by androidx.compose.runtime.mutableStateOf(row)
        compose.setContent {
            MaterialTheme { ViewRowEditor(board, current, ViewRowActions(onSave = { saved += it; true }, onTrash = {}, onOpen = {}, onClose = {})) }
        }
        current = row.copy(typed = row.typed + ("st" to JsonPrimitive("o2")))
        compose.waitForIdle()
        compose.onNodeWithText("저장").performScrollTo().assertIsNotEnabled() // 아무것도 안 건드렸다
        compose.onNodeWithText("메모").performTextInput("!")
        compose.onNodeWithText("저장").performScrollTo().performClick()
        assertEquals(listOf(setOf("tx")), saved.map { it.keys })
    }

    @Test
    fun aMultiSelectOptionNameWithACommaCannotBeAdded() {
        // 노션은 다중 선택 옵션 이름에 쉼표를 허용하지 않는다(보내면 400으로 거부되어 태그가 조용히 사라진다).
        show()
        compose.onNodeWithText("새 옵션 이름").performTextInput("a,b")
        compose.onNodeWithText("쉼표(,)는 쓸 수 없습니다").assertExists()
        compose.onNodeWithText("추가").assertIsNotEnabled()
    }

    @Test
    fun aNumberFieldRejectsNotANumberWords() {
        show()
        compose.onNodeWithText("쪽수").performTextClearance()
        compose.onNodeWithText("쪽수").performTextInput("NaN")
        compose.onNodeWithText("숫자만 넣으세요").assertExists()
        compose.onNodeWithText("저장").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun aMultiSelectOptionCanBeToggledOffAndOn() {
        show()
        compose.onNodeWithText("나").performClick()
        compose.onNodeWithText("가").performClick()
        compose.onNodeWithText("저장").performScrollTo().performClick()
        assertEquals(JsonArray(listOf(JsonPrimitive("b"))), saved.single()["tg"])
    }

    @Test
    fun theDateCanBeCleared() {
        show()
        compose.onNodeWithText("날짜 지우기").performScrollTo().performClick()
        compose.onNodeWithText("저장").performScrollTo().performClick()
        assertEquals(listOf(mapOf<String, JsonElement>("dt" to JsonNull)), saved)
    }

    @Test
    fun openInNotionGivesTheRowAddress() {
        show()
        compose.onNodeWithText("노션에서 열기").performScrollTo().performClick()
        assertEquals(listOf("https://notion.so/p1"), opened)
    }

    @Test
    fun trashAsksFirstAndThenSendsTheRowToTheTrash() {
        show()
        compose.onNodeWithText("휴지통으로 보내기").performScrollTo().performClick()
        compose.onNodeWithText("휴지통으로 보낼까요?").assertExists()
        assertEquals(0, trashed)
        compose.onNodeWithText("보내기").performClick()
        assertEquals(1, trashed)
        assertEquals(1, closed)
    }

    @Test
    fun cancellingTheTrashDialogKeepsTheRow() {
        show()
        compose.onNodeWithText("휴지통으로 보내기").performScrollTo().performClick()
        compose.onNodeWithText("취소").performClick()
        assertEquals(0, trashed)
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi", fontScale = 2.0f)
    fun theThreeButtonsAreReachableAtTheLargestFont() {
        // UX-04: 글꼴 2.0에서도 저장·노션에서 열기·휴지통이 잘리지 않고 닿는다(저장은 폭 전체, 나머지는 아래 줄에서 줄바꿈).
        show()
        listOf("저장", "노션에서 열기", "휴지통으로 보내기").forEach { compose.onNodeWithText(it).performScrollTo().assertIsDisplayed() }
    }
}
