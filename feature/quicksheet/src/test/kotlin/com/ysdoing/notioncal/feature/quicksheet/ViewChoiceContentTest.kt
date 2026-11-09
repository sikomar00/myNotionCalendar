package com.ysdoing.notioncal.feature.quicksheet

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.ysdoing.notioncal.core.data.view.ViewChoice
import com.ysdoing.notioncal.core.data.view.ViewColumn
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Phase 11c IX-02: 선택지가 4개 이상인 select를 위젯에서 누르면 뜨는 작은 선택 시트. 고르면 바로 쓰기 큐로 간다. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class ViewChoiceContentTest {
    @get:Rule
    val compose = createComposeRule()

    private val options = listOf(ViewChoice("a", "집", color = "blue"), ViewChoice("b", "회사", color = "green"), ViewChoice("c", "학교"), ViewChoice("d", "기타"))
    private val select = ViewColumn("c", "분류", "select", options)
    private val status = ViewColumn("st", "상태", "status", options)
    private val picked = mutableListOf<String?>()
    private var cancelled = 0

    private fun show(column: ViewColumn, current: String? = "b") {
        compose.setContent { MaterialTheme { ViewChoiceContent("장보기", column, current, onPick = { picked += it }, onCancel = { cancelled++ }) } }
    }

    @Test
    fun showsTheRowTheColumnAndEveryOptionWithTheCurrentOneSelected() {
        show(select)
        compose.onNodeWithText("장보기").assertExists()
        compose.onNodeWithText("분류").assertExists()
        listOf("집", "회사", "학교", "기타").forEach { compose.onNodeWithText(it).assertExists() }
        compose.onNodeWithText("회사").assertIsSelected()
        compose.onNodeWithText("집").assertIsNotSelected()
    }

    @Test
    fun tappingAnOptionPicksIt() {
        show(select)
        compose.onNodeWithText("학교").performClick()
        assertEquals(listOf<String?>("c"), picked)
    }

    @Test
    fun aSelectCanBeCleared() {
        show(select)
        compose.onNodeWithText("비우기").performClick()
        assertEquals(listOf<String?>(null), picked)
    }

    @Test
    fun aStatusHasNoClearOption() {
        show(status)
        compose.onNodeWithText("비우기").assertDoesNotExist() // 노션 상태는 비울 수 없다
    }

    @Test
    fun cancelClosesWithoutPicking() {
        show(select)
        compose.onNodeWithText("취소").performClick()
        assertEquals(1, cancelled)
        assertEquals(emptyList<String?>(), picked)
    }
}
