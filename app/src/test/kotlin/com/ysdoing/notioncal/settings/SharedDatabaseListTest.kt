package com.ysdoing.notioncal.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performClick
import com.ysdoing.notioncal.core.notion.NotionError
import com.ysdoing.notioncal.core.sync.SharedDatabase
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** NS-02 '노션에서 고르기': 줄을 누르면 그 DB로 조회, 등록한 DB는 '등록됨'으로 누를 수 없고, 비었거나 실패하면 안내한다. */
@RunWith(RobolectricTestRunner::class)
class SharedDatabaseListTest {
    @get:Rule
    val compose = createComposeRule()

    private val books = SharedDatabase("db-books", "ds-books", "Books", registered = false)
    private val calendar = SharedDatabase("db-cal", "ds-cal", "Calendar", registered = true)

    @Test
    fun pickingARowOpensItAndARegisteredOneCannotBePicked() {
        val picked = mutableListOf<SharedDatabase>()
        compose.setContent { MaterialTheme { SharedDatabaseList(SharedListUi(databases = listOf(books, calendar)), onPick = { picked += it }, onRetry = {}) } }
        compose.onNodeWithText("Books").performClick()
        assertEquals(listOf(books), picked)
        compose.onNodeWithText("등록됨").assertExists()
        compose.onNodeWithText("Calendar", useUnmergedTree = true).performClick()
        assertEquals(listOf(books), picked) // 등록한 DB는 눌러도 고르지 않는다
    }

    @Test
    fun anEmptyListExplainsHowToConnect() {
        compose.setContent { MaterialTheme { SharedDatabaseList(SharedListUi(databases = emptyList()), onPick = {}, onRetry = {}) } }
        compose.onNodeWithText("연결 추가", substring = true).assertExists()
    }

    @Test
    fun aFailureOffersRetry() {
        var retried = 0
        compose.setContent { MaterialTheme { SharedDatabaseList(SharedListUi(error = NotionError.Network), onPick = {}, onRetry = { retried++ }) } }
        compose.onNodeWithText("다시 불러오기").performClick()
        assertEquals(1, retried)
    }

    /** 검색 칸: 이름으로 거르고(대소문자·띄어쓰기 무시), 없으면 안내한다. */
    @Test
    fun searchFiltersByNameIgnoringCaseAndSpaces() {
        val list = listOf(books, calendar, SharedDatabase("db-bq", "ds-bq", "초보자를 위한 BigQuery 입문", registered = false))
        assertEquals(listOf("초보자를 위한 BigQuery 입문"), filterShared(list, "big query").map { it.name })
        assertEquals(list, filterShared(list, "  "))
        compose.setContent { MaterialTheme { SharedDatabaseList(SharedListUi(databases = list), onPick = {}, onRetry = {}) } }
        compose.onNodeWithTag("sharedSearch").performTextInput("cal")
        compose.onNodeWithText("Calendar").assertExists()
        compose.onNodeWithText("Books").assertDoesNotExist()
        compose.onNodeWithTag("sharedSearch").performTextClearance()
        compose.onNodeWithTag("sharedSearch").performTextInput("없는이름")
        compose.onNodeWithText("찾는 이름의 DB가 없습니다.").assertExists()
    }
}
