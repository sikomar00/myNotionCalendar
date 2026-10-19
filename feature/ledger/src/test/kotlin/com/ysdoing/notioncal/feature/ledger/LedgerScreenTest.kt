package com.ysdoing.notioncal.feature.ledger

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasTestTag
import com.ysdoing.notioncal.core.data.ledger.BudgetRow
import com.ysdoing.notioncal.core.data.ledger.Flow
import com.ysdoing.notioncal.core.data.ledger.LedgerBoard
import com.ysdoing.notioncal.core.data.ledger.LedgerMath
import com.ysdoing.notioncal.core.data.ledger.LedgerRules
import com.ysdoing.notioncal.core.data.ledger.LedgerTx
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/** LD-03·04·08·09 💰 탭: 요약·주 예산 줄·그리드 금액, 중복·확인 필요 버튼, 빈 상태. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w384dp-h832dp-xxhdpi")
class LedgerScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val today = LocalDate.of(2026, 10, 21)
    private fun d(day: Int) = LocalDate.of(2026, 10, day)
    private fun tx(
        id: String, day: Int, amount: Long, title: String = id, flow: Flow = Flow.OUT, planned: Boolean = false, created: Long = 0, manual: Boolean = false,
    ) = LedgerTx(id, d(day), "12:00", amount, flow, "식비", title, if (manual) null else "토스뱅크 통장", planned, manual, createdAt = created)

    private fun board(rows: List<LedgerTx>, budgets: List<BudgetRow> = emptyList(), registered: Boolean = true): LedgerBoard {
        val s = LedgerMath.summarize(rows, mapOf("식비" to "생활비"), emptySet(), today, DayOfWeek.SUNDAY, YearMonth.of(2026, 10), budgets, LedgerRules())
        return LedgerBoard(YearMonth.of(2026, 10), today, DayOfWeek.SUNDAY, s, registered)
    }

    @Test
    fun summaryWeekLineAndGrid() {
        val rows = listOf(
            tx("a", 19, -12_300, "점심"),
            tx("b", 20, -9_800, "저녁"),
            tx("pay", 17, 2_500_000, "급여", flow = Flow.IN),
            tx("plan", 24, -50_000, "운동화", planned = true),
        )
        val budgets = listOf(BudgetRow("주", "2026-W43", "전체", 100_000), BudgetRow("월", "2026-10", "전체", 1_000_000))
        compose.setContent { MaterialTheme { LedgerScreen(board(rows, budgets), LedgerActions()) } }

        compose.onNodeWithText("22,100원").assertExists() // 이번 달 지출
        compose.onNodeWithText("예정 5만").assertExists()
        compose.onNodeWithText("수입 +250만").assertExists()
        compose.onNodeWithText("월 예산 92.8만 남음").assertExists() // 100만 − 2.21만 − 5만(예정)
        compose.onNodeWithText("10/20까지 반영").assertExists()
        compose.onNodeWithText("이번 주 2.8만 남음 · 4일").assertExists() // 10만 − 2.21만 − 5만
        compose.onNodeWithTag("ledgerList").performScrollToNode(hasTestTag("ledgerGrid"))
        compose.onNodeWithContentDescription("10월 19일, 지출 12,300원").assertExists()
        compose.onNodeWithContentDescription("10월 24일, 예정 50,000원").assertExists()
        compose.onNodeWithContentDescription("10월 17일, 수입 2,500,000원").assertExists()
    }

    @Test
    fun overBudgetIsWrittenNotOnlyColored() {
        val rows = listOf(tx("a", 19, -115_000))
        compose.setContent { MaterialTheme { LedgerScreen(board(rows, listOf(BudgetRow("주", "2026-W43", "전체", 100_000))), LedgerActions()) } }
        compose.onNodeWithText("이번 주 1.5만 초과 · 4일").assertExists()
    }

    @Test
    fun duplicatesAndReviewActions() {
        val rows = listOf(
            tx("first", 19, -9_800, "김밥천국", created = 1),
            tx("second", 19, -9_800, "김밥천국", created = 2),
            tx("old-plan", 15, -30_000, "회식", planned = true),
        )
        val resolved = mutableListOf<String>()
        val kept = mutableListOf<String>()
        val deleted = mutableListOf<String>()
        val converted = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                LedgerScreen(
                    board(rows),
                    LedgerActions(
                        onResolve = { resolved += it.extra.single().pageId },
                        onKeepBoth = { kept += it.keep.pageId },
                        onDelete = { deleted += it },
                        onConvert = { converted += it },
                    ),
                )
            }
        }
        compose.onNodeWithText("중복 1건").assertExists()
        compose.onNodeWithText("먼저 만든 것만 남기기").performClick()
        compose.onNodeWithText("둘 다 유지").performClick()
        compose.onNodeWithText("확인 필요 1건").assertExists()
        compose.onNodeWithText("삭제").performClick()
        compose.onNodeWithText("실제로 전환").performClick()
        assertEquals(listOf("second"), resolved)
        assertEquals(listOf("first"), kept)
        assertEquals(listOf("old-plan"), deleted)
        assertEquals(listOf("old-plan"), converted)
    }

    /** LD-10: 앱입력 + 같은 거래(±1일·같은 금액)가 들어오면 한 건분만 세고 앱입력을 정리 대상으로, 짝 없는 현금은 계속 센다. */
    @Test
    fun matchedManualIsCountedOnceAndCanBeDeleted() {
        val rows = listOf(
            tx("manual", 19, -9_000, "점심", manual = true),
            tx("cash", 19, -7_000, "시장(현금)", manual = true),
            tx("bank", 20, -9_000, "김밥천국"),
        )
        val deleted = mutableListOf<String>()
        compose.setContent { MaterialTheme { LedgerScreen(board(rows), LedgerActions(onDelete = { deleted += it })) } }
        compose.onNodeWithText("16,000원").assertExists() // 9,000 + 현금 7,000
        compose.onNodeWithText("정리 대상 1건").assertExists()
        compose.onNodeWithText("삭제").performClick()
        assertEquals(listOf("manual"), deleted)
    }

    /** LD-07: '거래 추가'는 오늘, 날짜 칸은 그 날짜로 입력 시트를 연다. */
    @Test
    fun addButtonAndDayClickOpenInput() {
        val opened = mutableListOf<LocalDate>()
        compose.setContent { MaterialTheme { LedgerScreen(board(listOf(tx("a", 19, -12_300))), LedgerActions(onAdd = { opened += it }, onDayClick = { opened += it })) } }
        compose.onNodeWithContentDescription("거래 추가").performClick()
        compose.onNodeWithTag("ledgerList").performScrollToNode(hasTestTag("ledgerGrid"))
        compose.onNodeWithContentDescription("10월 19일, 지출 12,300원").performClick()
        assertEquals(listOf(today, d(19)), opened)
    }

    @Test
    fun emptyStateExplainsRegistration() {
        var opened = 0
        compose.setContent { MaterialTheme { LedgerScreen(board(emptyList(), registered = false), LedgerActions(onOpenSettings = { opened++ })) } }
        compose.onNodeWithText("등록한 가계부가 없습니다").assertExists()
        compose.onNodeWithText("설정 열기").performClick()
        assertEquals(1, opened)
    }
}
