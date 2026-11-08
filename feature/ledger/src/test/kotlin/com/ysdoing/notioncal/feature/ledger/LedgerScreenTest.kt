package com.ysdoing.notioncal.feature.ledger

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
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
        // 요약 아래 두 칸(예정·월 예산)은 라벨 위·값 아래. 낭독은 칸마다 '예정 5만'처럼 한 번에 읽는다.
        compose.onNodeWithContentDescription("예정 5만").assertExists()
        compose.onNodeWithText("+2,500,000원").assertExists() // 지출 아래 수입 줄(뱅크샐러드식)
        compose.onNodeWithContentDescription("월 예산 남음 92.8만").assertExists() // 100만 − 2.21만 − 5만(예정)
        compose.onNodeWithText("10/20까지 반영").assertExists()
        compose.onNodeWithText("이번 주 2.8만 남음 · 4일").assertExists() // 10만 − 2.21만 − 5만
        compose.onNodeWithTag("ledgerList").performScrollToNode(hasTestTag("ledgerGrid"))
        compose.onNodeWithContentDescription("10월 19일, 지출 12,300원").assertExists()
        compose.onNodeWithContentDescription("10월 24일, 예정 50,000원").assertExists()
        compose.onNodeWithContentDescription("10월 17일, 수입 2,500,000원, 무지출").assertExists() // 수입만 있는 날도 지출은 없다
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
        compose.onNodeWithText("휴지통으로 보내기").performClick()
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
        assertEquals(emptyList<String>(), deleted) // 확인 전에는 지우지 않는다
        compose.onNodeWithText("휴지통으로 보내기").performClick()
        assertEquals(listOf("manual"), deleted)
    }

    /**
     * 날짜를 누르면 그 날 거래 목록(지출 '-', 수입 '+')을 그리드 아래에 보인다(처음은 오늘).
     * '거래 추가'는 오늘, '이 날 추가'는 고른 날로 LD-07 입력 시트를 연다.
     */
    @Test
    fun dayTapShowsEntriesAndAddOpensInput() {
        val rows = listOf(tx("a", 19, -12_300, "점심"), tx("b", 19, 30_000, "용돈", flow = Flow.IN), tx("c", 20, -9_800, "저녁"))
        val opened = mutableListOf<LocalDate>()
        compose.setContent { MaterialTheme { LedgerScreen(board(rows), LedgerActions(onAdd = { opened += it })) } }
        compose.onNodeWithTag("ledgerList").performScrollToNode(hasTestTag("ledgerDayEntries"))
        compose.onNodeWithText("10월 21일(수)").assertExists()
        compose.onNodeWithText("이 날 거래가 없습니다").assertExists()

        compose.onNodeWithTag("ledgerList").performScrollToNode(hasTestTag("ledgerGrid"))
        compose.onNodeWithContentDescription("10월 19일, 지출 12,300원, 수입 30,000원").performClick()
        compose.onNodeWithTag("ledgerList").performScrollToNode(hasTestTag("ledgerDayEntries"))
        compose.onNodeWithText("10월 19일(월)").assertExists()
        compose.onNodeWithText("점심").assertExists()
        compose.onNodeWithText("-12,300원").assertExists()
        // 월 수입 줄에도 같은 금액이 있으므로 날짜 목록 안에서만 찾는다.
        compose.onNode(hasText("+30,000원") and hasAnyAncestor(hasTestTag("ledgerDayEntries"))).assertExists()
        compose.onNodeWithText("저녁").assertDoesNotExist()

        compose.onNodeWithText("이 날 추가").performClick()
        compose.onNodeWithContentDescription("거래 추가").performClick()
        assertEquals(listOf(d(19), today), opened)
    }

    /** 가계부 위젯의 항목 있는 날로 열면: 보는 달이 다르면 그 달로 옮기고, 같으면 그 날을 고르고 한 번만 처리했다고 알린다. */
    @Test
    fun openDateShiftsToItsMonthThenSelectsTheDay() {
        val rows = listOf(tx("a", 19, -12_300, "점심"), tx("c", 20, -9_800, "저녁"))
        val shifts = mutableListOf<Long>()
        var handled = 0
        val actions = LedgerActions(onShiftMonth = { shifts += it }, onOpenDateHandled = { handled++ })
        compose.setContent { MaterialTheme { LedgerScreen(board(rows), actions, openDate = LocalDate.of(2026, 12, 3)) } }
        compose.waitForIdle()
        assertEquals(listOf(2L), shifts) // 10월 → 12월
        assertEquals(0, handled)
    }

    @Test
    fun openDateInTheShownMonthSelectsTheDay() {
        val rows = listOf(tx("a", 19, -12_300, "점심"), tx("c", 20, -9_800, "저녁"))
        var handled = 0
        compose.setContent { MaterialTheme { LedgerScreen(board(rows), LedgerActions(onOpenDateHandled = { handled++ }), openDate = d(19)) } }
        compose.onNodeWithTag("ledgerList").performScrollToNode(hasTestTag("ledgerDayEntries"))
        compose.onNodeWithText("10월 19일(월)").assertExists()
        compose.onNodeWithText("점심").assertExists()
        compose.onNodeWithText("저녁").assertDoesNotExist()
        assertEquals(1, handled)
    }

    @Test
    fun openDateIsDroppedWhenTheLedgerIsNotRegistered() {
        var handled = 0
        compose.setContent { MaterialTheme { LedgerScreen(board(emptyList(), registered = false), LedgerActions(onOpenDateHandled = { handled++ }), openDate = d(19)) } }
        compose.waitForIdle()
        assertEquals(1, handled)
    }

    @Test
    fun emptyStateExplainsRegistration() {
        var opened = 0
        compose.setContent { MaterialTheme { LedgerScreen(board(emptyList(), registered = false), LedgerActions(onOpenSettings = { opened++ })) } }
        compose.onNodeWithText("등록한 가계부가 없습니다").assertExists()
        compose.onNodeWithText("설정 열기").performClick()
        assertEquals(1, opened)
    }

    /** LD-15 뱅크샐러드식: 지출 아래 수입 줄, '무지출 총 N일', 주 합계 줄(이 달 날짜만), 무지출 날 낭독. */
    @Test
    fun noSpendRowWeekTotalsAndDayDescription() {
        val rows = listOf(tx("a", 19, -12_300, "점심"), tx("b", 20, -9_800, "저녁"), tx("pay", 17, 2_500_000, "급여", flow = Flow.IN))
        compose.setContent { MaterialTheme { LedgerScreen(board(rows), LedgerActions()) } }
        // 반영 기준일 10/20까지 20일 중 지출한 19·20일을 뺀 18일.
        compose.onNodeWithContentDescription("10월 무지출 총 18일").assertExists()
        compose.onNodeWithTag("ledgerList").performScrollToNode(hasTestTag("ledgerGrid"))
        compose.onNodeWithContentDescription("주 합계 지출 22,100원, 수입 0원").assertExists() // 10/18~24
        compose.onNodeWithContentDescription("주 합계 지출 0원, 수입 2,500,000원").assertExists() // 10/11~17
        compose.onNodeWithContentDescription("10월 15일, 무지출").assertExists()
        compose.onNodeWithContentDescription("10월 17일, 수입 2,500,000원, 무지출").assertExists()
        compose.onNodeWithContentDescription("10월 19일, 지출 12,300원").assertExists() // 지출한 날에는 붙지 않는다
    }

    @Test
    fun noSpendRowIsHiddenUntilTransactionsAreImported() {
        compose.setContent { MaterialTheme { LedgerScreen(board(emptyList()), LedgerActions()) } }
        compose.onNodeWithTag("ledgerNoSpend").assertDoesNotExist()
    }

    @Test
    fun noSpendRowShowsWhenTheMonthHasTransactions() {
        compose.setContent { MaterialTheme { LedgerScreen(board(listOf(tx("a", 19, -12_300))), LedgerActions()) } }
        compose.onNodeWithTag("ledgerNoSpend").assertExists() // 태그는 낭독 정리 전에 붙어야 남는다
        compose.onNodeWithContentDescription("10월 무지출 총 18일").assertExists() // 반영 기준일 10/19까지 19일 중 지출한 19일을 뺀 18일
    }
}
