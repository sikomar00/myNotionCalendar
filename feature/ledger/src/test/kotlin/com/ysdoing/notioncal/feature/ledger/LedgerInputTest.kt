package com.ysdoing.notioncal.feature.ledger

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.ysdoing.notioncal.core.data.ledger.Flow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/** LD-07 빠른 입력: 금액 입력 외 탭 2번 이하(칩 1 + 저장 1)로 저장, 최근 칩 미리 고름, 앞날은 예정 표시. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w384dp-h832dp-xxhdpi")
class LedgerInputTest {
    @get:Rule
    val compose = createComposeRule()

    private val today = LocalDate.of(2026, 10, 19)
    private val saved = mutableListOf<List<Any?>>()

    private val loaded = mutableListOf<Flow>()

    private fun show(date: LocalDate = today, categories: List<String> = listOf("식비", "교통", "카페/간식")) {
        compose.setContent {
            MaterialTheme {
                LedgerInputContent(
                    date = date, today = today, categories = categories, onFlowChange = { loaded += it },
                    onSave = { amount, flow, category, title -> saved += listOf(amount, flow, category, title) }, onCancel = {},
                )
            }
        }
    }

    @Test
    fun amountThenChipThenSave() {
        show()
        compose.onNodeWithText("저장").assertIsNotEnabled() // 금액 전에는 저장할 수 없다
        compose.onNodeWithTag("ledgerAmount").performTextInput("9,000")
        compose.onNodeWithText("교통").performClick() // 탭 1
        compose.onNodeWithText("저장").performClick() // 탭 2
        assertEquals(listOf(listOf<Any?>(9_000L, Flow.OUT, "교통", "")), saved)
    }

    @Test
    fun mostRecentCategoryIsPreselected() {
        show()
        compose.onNodeWithTag("ledgerAmount").performTextInput("4500")
        compose.onNodeWithText("저장").performClick()
        assertEquals(listOf(listOf<Any?>(4_500L, Flow.OUT, "식비", "")), saved)
    }

    @Test
    fun incomeToggleAndPlannedHeader() {
        show(date = today.plusDays(5))
        compose.onNodeWithText("10월 24일(토)").assertExists()
        compose.onNodeWithText("예정").assertExists()
        compose.onNodeWithText("수입").performClick()
        compose.onNodeWithTag("ledgerAmount").performTextInput("300000")
        compose.onNodeWithText("저장").performClick()
        assertEquals(Flow.IN, saved.single()[1])
        compose.onNodeWithText("예정").assertExists()
    }

    /** 화면을 돌려 시트가 다시 만들어져도 되살린 '수입'의 대분류를 읽는다(지출 대분류로 수입을 저장하지 않게). */
    @Test
    fun recreatedSheetLoadsCategoriesOfTheRestoredFlow() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            MaterialTheme {
                LedgerInputContent(date = today, today = today, categories = listOf("월급"), onFlowChange = { loaded += it }, onSave = { _, _, _, _ -> }, onCancel = {})
            }
        }
        compose.onNodeWithText("수입").performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        assertEquals(listOf(Flow.OUT, Flow.IN, Flow.IN), loaded) // 처음 열 때 지출, 수입을 누름, 다시 만들어진 뒤 수입
    }
}
