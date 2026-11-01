package com.ysdoing.notioncal.core.sync

import com.ysdoing.notioncal.core.data.BudgetPropIds
import com.ysdoing.notioncal.core.data.CategoryPropIds
import com.ysdoing.notioncal.core.data.LedgerPropIds
import com.ysdoing.notioncal.core.data.SourceKind
import com.ysdoing.notioncal.core.data.sourceKind
import com.ysdoing.notioncal.core.notion.NotionJson
import com.ysdoing.notioncal.core.notion.dto.DataSourceDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

/** LD-01 가계부 등록: §8.4 프리셋 추천, 금액이 number가 아니면 막기, 같은 페이지의 예산·카테고리 마스터 찾기, 세 행 한 묶음. */
class LedgerRegistrationTest {
    private val notion = FakeNotionStore { Instant.EPOCH }
    private fun schema(json: String) = NotionJson.decodeFromString(DataSourceDto.serializer(), json)

    @Test
    fun ledgerPresetMatchesTransactionsDatabase() {
        assertEquals(
            LedgerChoice("title", "nAlJ", amount = "aMt", flow = "fLw", category = "cAt", time = "tIme", payment = "pAy", planned = "pLn", manual = "mAn"),
            LedgerSuggester.suggestLedger(notion.ledgerSchema),
        )
        assertNull(LedgerSuggester.problem(notion.ledgerSchema))
        // 📅 Calendar(금액·타입 없음)은 가계부로 추천하지 않는다.
        assertNull(LedgerSuggester.suggestLedger(notion.schema))
    }

    /** LD-01 완료 기준: 금액이 number가 아니면 등록을 막고 이유를 보인다. */
    @Test
    fun amountMustBeNumber() {
        val textAmount = schema(
            """{"id":"x","properties":{"내용":{"id":"title","name":"내용","type":"title"},"날짜":{"id":"d","name":"날짜","type":"date"},
               "금액":{"id":"a","name":"금액","type":"rich_text"},"타입":{"id":"t","name":"타입","type":"select"}}}""",
        )
        assertEquals(LedgerProblem.AMOUNT_NOT_NUMBER, LedgerSuggester.problem(textAmount))
        assertNull(LedgerSuggester.suggestLedger(textAmount))
        val noType = schema(
            """{"id":"x","properties":{"내용":{"id":"title","name":"내용","type":"title"},"날짜":{"id":"d","name":"날짜","type":"date"},
               "금액":{"id":"a","name":"금액","type":"number"}}}""",
        )
        assertEquals(LedgerProblem.NO_TYPE_SELECT, LedgerSuggester.problem(noType))
    }

    @Test
    fun budgetAndCategoryPresets() {
        assertEquals(BudgetChoice("title", periodType = "tYp", periodValue = "vAl", group = "gRp", amount = "aMt"), LedgerSuggester.suggestBudget(notion.budgetSchema))
        assertEquals(listOf("전체", "생활비"), LedgerSuggester.groupOptions(notion.budgetSchema, "gRp"))
        assertEquals(CategoryChoice("title", group = "gRp"), LedgerSuggester.suggestCategory(notion.categorySchema))
    }

    /** 거래내역과 같은 페이지의 자식 DB 중 제목으로 예산·카테고리 마스터를 고른다. */
    @Test
    fun companionsAreFoundByTitle() {
        val children = listOf("db-ledger" to "거래내역", "db-budget" to " 예산 ", "db-cat" to "카테고리 마스터", "db-x" to "예산 메모")
        assertEquals("db-budget" to "db-cat", LedgerSuggester.pickCompanions(children))
        assertEquals(null to null, LedgerSuggester.pickCompanions(listOf("db-ledger" to "거래내역")))
    }

    @Test
    fun registrationBuildsThreeLinkedRows() {
        val (ledger, budget, category) = ledgerRows(
            ledger = HabitTarget("ledger", "db-ledger", "거래내역"),
            ledgerChoice = LedgerChoice("title", "nAlJ", "aMt", "fLw", "cAt", "tIme", "pAy", "pLn", "mAn"),
            budget = HabitTarget("budget", "db-budget", "예산"),
            budgetChoice = BudgetChoice("title", "tYp", "vAl", "gRp", "aMt"),
            category = HabitTarget("category", "db-cat", "카테고리 마스터"),
            categoryChoice = CategoryChoice("title", "gRp"),
            budgetGroup = "생활비",
            spendNegative = true,
            colorHex = "#DFAB01",
            existing = emptyMap(),
            nextPosition = 5,
        )
        assertEquals(SourceKind.LEDGER, ledger.sourceKind)
        assertEquals("nAlJ", ledger.datePropId)
        assertEquals("aMt", LedgerPropIds.parse(ledger.rolePropIds).amount)
        assertEquals("생활비", ledger.budgetGroup)
        assertEquals(5, ledger.position)

        assertEquals(SourceKind.BUDGET, budget.sourceKind)
        assertNull(budget.datePropId)
        assertEquals("ledger", budget.linkDataSourceId)
        assertEquals("vAl", BudgetPropIds.parse(budget.rolePropIds).periodValue)
        assertEquals(SourceKind.CATEGORY, category.sourceKind)
        assertEquals("gRp", CategoryPropIds.parse(category.rolePropIds).group)
        assertEquals("ledger", category.linkDataSourceId)

        // 다시 등록해도 켜기·위치는 지키고, 매핑이 같으면 동기화 기준을 남긴다.
        val (again, _, _) = ledgerRows(
            HabitTarget("ledger", "db-ledger", "거래내역"), LedgerChoice("title", "nAlJ", "aMt", "fLw", "cAt", "tIme", "pAy", "pLn", "mAn"),
            HabitTarget("budget", "db-budget", "예산"), BudgetChoice("title", "tYp", "vAl", "gRp", "aMt"),
            HabitTarget("category", "db-cat", "카테고리 마스터"), CategoryChoice("title", "gRp"),
            "전체", true, "#DFAB01", mapOf("ledger" to ledger.copy(enabled = false, lastSyncAt = 9, position = 2)), 7,
        )
        assertEquals(false, again.enabled)
        assertEquals(2, again.position)
        assertEquals(9L, again.lastSyncAt)
        assertEquals("전체", again.budgetGroup)
    }
}
