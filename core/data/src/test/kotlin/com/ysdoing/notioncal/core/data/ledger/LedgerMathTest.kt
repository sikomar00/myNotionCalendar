package com.ysdoing.notioncal.core.data.ledger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/**
 * LD-02·04·05·08·09·10 계산. 픽스처는 사용자 뱅크샐러드 내보내기(2026-10-16~18, 15행) 구조를 본뜬다:
 * 지출 음수·수입 양수·이체 +/− 쌍, 지출인데 양수면 환불.
 */
class LedgerMathTest {
    private fun d(month: Int, day: Int) = LocalDate.of(2026, month, day)
    private fun d(day: Int) = d(10, day)

    private var seq = 0L
    private fun tx(
        day: LocalDate, time: String?, flow: Flow?, category: String?, title: String, amount: Long,
        payment: String? = "토스뱅크 통장", planned: Boolean = false, manual: Boolean = false, id: String? = null,
    ) = LedgerTx(id ?: "p${++seq}", day, time, amount, flow, category, title, payment, planned, manual, createdAt = seq)

    private val groups = mapOf(
        "식비" to "생활비", "카페/간식" to "생활비", "교통" to "생활비", "온라인쇼핑" to "생활비",
        "저축" to "금융/자산", "현금" to "이체/기타", "내계좌이체" to "이체/기타", "급여" to "수입", "금융수입" to "수입",
    )

    /** 2026-10-16~18 내보내기 15행. */
    private fun export15() = listOf(
        tx(d(16), "08:12", Flow.OUT, "식비", "GS25", -4_500),
        tx(d(16), "12:30", Flow.OUT, "식비", "김밥천국", -9_800),
        tx(d(16), "13:00", Flow.TRANSFER, "내계좌이체", "내 저축계좌", -100_000),
        tx(d(16), "13:00", Flow.TRANSFER, "내계좌이체", "내 저축계좌", 100_000, payment = "카카오페이 머니"),
        tx(d(16), "18:00", Flow.OUT, "저축", "자유적금", -200_000),
        tx(d(17), "09:00", Flow.IN, "급여", "회사", 2_500_000),
        tx(d(17), "11:00", Flow.OUT, "온라인쇼핑", "쿠팡", -32_300),
        tx(d(17), "15:00", Flow.OUT, "온라인쇼핑", "쿠팡", 12_000), // 환불
        tx(d(17), "19:00", Flow.OUT, "카페/간식", "스타벅스", -5_600),
        tx(d(17), "21:10", Flow.TRANSFER, "이체", "엄마", -30_000),
        tx(d(18), "10:00", Flow.OUT, "교통", "티머니", -1_250),
        tx(d(18), "12:00", Flow.OUT, "식비", "한솥", -12_000),
        tx(d(18), "13:00", Flow.OUT, "현금", "ATM 출금", -50_000),
        tx(d(18), "20:00", Flow.IN, "금융수입", "이자", 320),
        tx(d(18), "22:00", Flow.OUT, "카페/간식", "편의점", -2_100),
    )

    private fun summary(
        rows: List<LedgerTx>,
        today: LocalDate = d(18),
        weekStart: DayOfWeek = DayOfWeek.SUNDAY,
        budgets: List<BudgetRow> = emptyList(),
        rules: LedgerRules = LedgerRules(),
        keeps: Set<Pair<String, String>> = emptySet(),
    ) = LedgerMath.summarize(rows, groups, keeps, today, weekStart, YearMonth.from(today), budgets, rules)

    @Test
    fun signsRefundsTransfersAndExcludedGroups() {
        val s = summary(export15())
        // 10/16: 4,500 + 9,800(이체·적금은 빼고), 10/17: 32,300 − 12,000(환불) + 5,600, 10/18: 1,250 + 12,000 + 2,100(현금 출금은 뺌)
        assertEquals(14_300L, s.days.getValue(d(16)).spend)
        assertEquals(25_900L, s.days.getValue(d(17)).spend)
        assertEquals(15_350L, s.days.getValue(d(18)).spend)
        assertEquals(2_500_000L, s.days.getValue(d(17)).income)
        assertEquals(320L, s.days.getValue(d(18)).income)
        assertEquals(55_550L, s.monthSpend)
        assertEquals(2_500_320L, s.monthIncome)
        assertEquals(d(18), s.asOf) // 가져온 거래 중 가장 늦은 날짜
    }

    /** 양식이 바뀌어 지출이 양수로 오면 설정값 하나로 맞춘다(LD-02). */
    @Test
    fun spendSignIsConfigurable() {
        val flipped = export15().map { it.copy(amount = -it.amount) }
        assertEquals(55_550L, summary(flipped, rules = LedgerRules(spendNegative = false)).monthSpend)
    }

    /** LD-08: 같은 CSV를 두 번 병합해도 합계는 같고, 중복 묶음이 나온다. '둘 다 유지'한 쌍은 둘 다 센다. */
    @Test
    fun mergingSameCsvTwiceDoesNotChangeTotals() {
        val once = export15()
        val twice = once + once.map { it.copy(pageId = it.pageId + "-dup", createdAt = it.createdAt + 100) }
        val s = summary(twice)
        assertEquals(55_550L, s.monthSpend)
        assertEquals(2_500_320L, s.monthIncome)
        assertEquals(15, s.duplicates.size)
        // 먼저 만든 행을 남기고 나중 행을 지울 대상으로 낸다.
        assertTrue(s.duplicates.all { g -> g.keep.createdAt < g.extra.single().createdAt })

        val kept = summary(twice, keeps = setOf("p2" to "p2-dup")) // 김밥천국 9,800: 실제로 두 번 샀다
        assertEquals(55_550L + 9_800L, kept.monthSpend)
        assertEquals(14, kept.duplicates.size)
    }

    /** 같은 분·같은 상대의 이체 두 다리는 결제수단이 달라 중복이 아니다(LD-08 키에 결제수단 포함). */
    @Test
    fun transferLegsWithDifferentPaymentAreNotDuplicates() {
        assertTrue(summary(export15()).duplicates.isEmpty())
    }

    /**
     * LD-09: 예정 10/20 5만, CSV가 10/8까지만 있을 때 10/21에도 예정 5만 유지 →
     * 10/20 실제 5만 병합 후 지출 5만(10만 아님), 예정 행은 '확인 필요'.
     */
    @Test
    fun plannedIsCountedOnceAroundAsOfDate() {
        val imported = tx(d(8), "10:00", Flow.OUT, "식비", "점심", -8_000)
        val planned = tx(d(20), null, Flow.OUT, "온라인쇼핑", "운동화", -50_000, payment = null, planned = true)
        val before = summary(listOf(imported, planned), today = d(21))
        assertEquals(d(8), before.asOf)
        assertEquals(50_000L, before.days.getValue(d(20)).planned)
        assertEquals(50_000L, before.monthPlanned)
        assertEquals(8_000L, before.monthSpend)
        assertTrue(before.needsReview.isEmpty())

        val actual = tx(d(20), "14:00", Flow.OUT, "온라인쇼핑", "운동화", -50_000)
        val after = summary(listOf(imported, planned, actual), today = d(21))
        assertEquals(d(20), after.asOf)
        assertEquals(58_000L, after.monthSpend)
        assertEquals(0L, after.monthPlanned)
        assertEquals(listOf(planned.pageId), after.needsReview.map { it.pageId })
    }

    /** LD-10: 앱입력은 실제 지출로 세다가, 날짜 ±1일·금액이 같은 가져온 거래가 생기면 빼고 정리 대상으로 낸다. */
    @Test
    fun manualEntryIsReplacedByMatchingImport() {
        val manual = tx(d(19), "12:10", Flow.OUT, "식비", "점심", -9_000, payment = null, manual = true)
        val cash = tx(d(19), "18:00", Flow.OUT, "식비", "시장(현금)", -7_000, payment = null, manual = true)
        assertEquals(16_000L, summary(listOf(manual, cash), today = d(21)).monthSpend)

        val imported = tx(d(20), "12:11", Flow.OUT, "식비", "백반집", -9_000) // 카드 매입은 다음 날로 잡힐 수 있다
        val s = summary(listOf(manual, cash, imported), today = d(21))
        assertEquals(16_000L, s.monthSpend) // 9,000(가져온 것) + 7,000(짝 없는 현금)
        assertEquals(listOf(manual.pageId to imported.pageId), s.matchedManual.map { it.first.pageId to it.second.pageId })
    }

    /**
     * 💰 탭 날짜 목록: 그 날 칸 숫자를 이루는 행만 시간 순으로(중복 나머지·짝 맞은 앱입력·이체·제외 그룹은 빠짐).
     * 값은 지출 −, 수입·환불 +로 칸 합계와 같다.
     */
    @Test
    fun dayEntriesMatchCellTotals() {
        val s = summary(export15())
        val day17 = s.entries.getValue(d(17))
        assertEquals(listOf("회사", "쿠팡", "쿠팡", "스타벅스"), day17.map { it.tx.title })
        assertEquals(listOf(2_500_000L, -32_300L, 12_000L, -5_600L), day17.map { it.value })
        assertEquals(-s.days.getValue(d(17)).spend, day17.filter { it.kind == DayEntry.Kind.SPEND }.sumOf { it.value })
        assertEquals(listOf("GS25", "김밥천국"), s.entries.getValue(d(16)).map { it.tx.title }) // 이체·적금 빠짐

        val manual = tx(d(19), "12:10", Flow.OUT, "식비", "점심", -9_000, payment = null, manual = true)
        val imported = tx(d(19), "12:11", Flow.OUT, "식비", "백반집", -9_000)
        val plan = tx(d(24), null, Flow.OUT, "온라인쇼핑", "운동화", -50_000, payment = null, planned = true)
        val s2 = summary(listOf(manual, imported, imported.copy(pageId = "dup", createdAt = 99), plan), today = d(21))
        assertEquals(listOf("백반집"), s2.entries.getValue(d(19)).map { it.tx.title })
        assertEquals(DayEntry.Kind.PLANNED to -50_000L, s2.entries.getValue(d(24)).single().let { it.kind to it.value })
    }

    /** LD-04: 일요일 시작 주 10/18~10/24의 예산 키는 그 주 월요일(10/19)의 ISO 주 '2026-W43'. */
    @Test
    fun weekKeysFollowWeekStartAndIsoWeekOfMonday() {
        assertEquals("2026-W43", LedgerMath.weekKey(d(18), DayOfWeek.SUNDAY))
        assertEquals("2026-W43", LedgerMath.weekKey(d(24), DayOfWeek.SUNDAY))
        assertEquals("2026-W42", LedgerMath.weekKey(d(18), DayOfWeek.MONDAY)) // 월요일 시작이면 ISO 주와 같다
        assertEquals("2026-W40", LedgerMath.weekKey(d(9, 27), DayOfWeek.SUNDAY)) // 두 달에 걸친 주
        assertEquals("2026-W40", LedgerMath.weekKey(d(10, 3), DayOfWeek.SUNDAY))
        assertEquals("2026-W53", LedgerMath.weekKey(LocalDate.of(2027, 1, 2), DayOfWeek.SUNDAY)) // 해를 넘는 주
        assertEquals("2026-W03", LedgerMath.normalizeWeekKey("2026-W3"))
    }

    /** 10/18(일) 거래는 10/18~10/24 주에 들어간다. 주 예산 남음과 남은 날. */
    @Test
    fun weekBudgetUsesSundayWeek() {
        val budgets = listOf(BudgetRow("주", "2026-W43", "전체", 100_000), BudgetRow("월", "2026-10", "전체", 1_000_000))
        val s = summary(export15(), today = d(21), budgets = budgets)
        assertEquals(d(18), s.weekStart)
        assertEquals(15_350L, s.weekSpend) // 10/18만 이번 주
        assertEquals(100_000L, s.weekBudget)
        assertEquals(84_650L, s.weekRemaining)
        assertEquals(4, s.weekDaysLeft) // 수·목·금·토
        assertEquals(1_000_000L - 55_550L, s.monthRemaining)
    }

    /** LD-05: 그 기간 행이 없거나 비면 같은 유형의 가장 최근 값을 이어 쓴다. 예산이 아예 없으면 null. */
    @Test
    fun budgetFallsBackToMostRecentValue() {
        val budgets = listOf(
            BudgetRow("월", "2026-09", "전체", 900_000),
            BudgetRow("월", "2026-10", "전체", null), // 비어 있음
            BudgetRow("주", "2026-W41", "전체", 150_000),
            BudgetRow("주", "2026-W44", "전체", 999_999), // 앞날 값은 쓰지 않는다
            BudgetRow("월", "2026-10", "생활비", 500_000), // 다른 그룹
        )
        val s = summary(export15(), today = d(21), budgets = budgets)
        assertEquals(900_000L, s.monthBudget)
        assertEquals(150_000L, s.weekBudget)

        val none = summary(export15(), today = d(21))
        assertNull(none.monthBudget)
        assertNull(none.weekRemaining)
    }

    /** 예산 그룹 '생활비'는 그룹 생활비 지출과만 비교한다. 남음에는 그 기간의 예정도 뺀다. */
    @Test
    fun budgetGroupAndPlannedReduceRemaining() {
        val planned = tx(d(23), null, Flow.OUT, "식비", "회식", -30_000, payment = null, planned = true)
        val budgets = listOf(BudgetRow("주", "2026-W43", "생활비", 100_000))
        val s = summary(export15() + planned, today = d(21), budgets = budgets, rules = LedgerRules(budgetGroup = "생활비"))
        assertEquals(100_000L - 15_350L - 30_000L, s.weekRemaining)
    }

    /** LD-02: 2,000건 월 합계가 50ms 안(JVM, 여유 있게 반복 측정 중 최솟값). */
    @Test
    fun twoThousandRowsSummarizeQuickly() {
        val rows = (0 until 2_000).map { i ->
            tx(d(1 + i % 31), "%02d:%02d".format(i % 24, i % 60), Flow.OUT, "식비", "가게 $i", -(1_000L + i))
        }
        repeat(3) { summary(rows, today = d(31)) } // 데우기
        val best = (0 until 5).minOf { kotlin.system.measureTimeMillis { summary(rows, today = d(31)) } }
        assertTrue("월 합계 ${best}ms", best < 50)
    }
}
