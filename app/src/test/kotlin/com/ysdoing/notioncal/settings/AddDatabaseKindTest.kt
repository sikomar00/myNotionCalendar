package com.ysdoing.notioncal.settings

import com.ysdoing.notioncal.core.data.SourceKind
import com.ysdoing.notioncal.core.data.ledger.LedgerRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 다시 연결·같은 링크 재등록: 이미 등록한 DB를 다른 종류로 등록하면 종류가 바뀌고 짝을 잃으므로 막는다. 예산 그룹은 사용자가 고른 것을 지킨다. */
class AddDatabaseKindTest {
    @Test
    fun aRegisteredDatabaseKeepsItsKind() {
        assertTrue(kindLost(AddKind.CALENDAR, SourceKind.LEDGER))
        assertTrue(kindLost(AddKind.CALENDAR, SourceKind.HABIT_LOG))
        assertTrue(kindLost(AddKind.HABIT_LOG, SourceKind.LEDGER)) // 거래내역 → 습관 기록
        assertTrue(kindLost(AddKind.LEDGER, SourceKind.HABIT_LOG))
        assertTrue(kindLost(AddKind.LEDGER, SourceKind.CALENDAR))
        assertTrue(kindLost(AddKind.CALENDAR, SourceKind.HABIT)) // 짝 잃은 🌱 습관·예산·카테고리는 어느 종류로도 못 한다
        assertTrue(kindLost(AddKind.CALENDAR, SourceKind.BUDGET))
        assertFalse(kindLost(AddKind.LEDGER, SourceKind.LEDGER))
        assertFalse(kindLost(AddKind.CALENDAR, SourceKind.CALENDAR))
        assertFalse(kindLost(AddKind.LEDGER, null)) // 새로 등록
    }

    @Test
    fun budgetGroupPrefersTheChosenOneThenAll() {
        val options = listOf("생활비", LedgerRules.ALL_GROUP, "식비")
        assertEquals("식비", pickBudgetGroup(options, "식비"))
        assertEquals(LedgerRules.ALL_GROUP, pickBudgetGroup(options, "사라진 그룹"))
        assertEquals(LedgerRules.ALL_GROUP, pickBudgetGroup(options, null))
        assertEquals("생활비", pickBudgetGroup(listOf("생활비"), null))
        assertEquals(LedgerRules.ALL_GROUP, pickBudgetGroup(null, "식비"))
    }
}
