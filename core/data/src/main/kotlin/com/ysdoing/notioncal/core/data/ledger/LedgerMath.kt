package com.ysdoing.notioncal.core.data.ledger

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.time.temporal.IsoFields
import java.time.temporal.TemporalAdjusters
import kotlin.math.abs

/** 거래 타입(노션 '타입': 지출·수입·이체). */
enum class Flow(val notionName: String) {
    OUT("지출"), IN("수입"), TRANSFER("이체");

    companion object {
        /** 노션 select 이름 → 타입. 모르는 값은 null(합계에 넣지 않는다). */
        fun ofName(name: String?): Flow? = when (name?.trim()) {
            "지출" -> OUT
            "수입" -> IN
            "이체" -> TRANSFER
            else -> null
        }
    }
}

/** 거래 한 건(거래내역 1행). [amount]는 원 단위, 부호는 노션 원본 그대로. */
data class LedgerTx(
    val pageId: String,
    val date: LocalDate,
    /** HH:MM(뱅크샐러드 원본). 앱입력·예정은 비어 있을 수 있다. */
    val time: String?,
    val amount: Long,
    val flow: Flow?,
    /** 대분류(select 이름). */
    val category: String?,
    val title: String,
    /** 결제수단(select 이름). */
    val payment: String?,
    val planned: Boolean,
    val manual: Boolean,
    /** 노션 created_time(epoch ms). 중복 중 먼저 만든 행을 남길 때 쓴다. */
    val createdAt: Long,
) {
    /** 가져온 거래(뱅크샐러드 CSV): 예정도 앱입력도 아님. */
    val imported: Boolean get() = !planned && !manual

    /** LD-08 중복 키: 날짜·시간·금액·내용·결제수단(노션 거래키 + 결제수단). */
    val dupKey: String get() = listOf(date.toString(), time.orEmpty(), amount.toString(), title.trim(), payment.orEmpty()).joinToString("|")
}

/** 예산 DB 한 행. [periodType]은 '월'·'주' 등 노션 옵션 이름, [amount]가 비면 null. */
data class BudgetRow(val periodType: String, val periodKey: String, val group: String?, val amount: Long?)

/**
 * 합계 규칙(LD-01·02·05). 지출 = 타입 '지출' 중 [excludedGroups]가 아닌 행(사용자 결정 2026-10-19).
 * [budgetGroup]이 '전체'면 그 지출 전부, 아니면 그 그룹 지출과만 예산을 비교한다.
 */
data class LedgerRules(
    /** 뱅크샐러드 원본: 지출이 음수(LD-02 설정값). */
    val spendNegative: Boolean = true,
    val budgetGroup: String = ALL_GROUP,
    val excludedGroups: Set<String> = setOf("금융/자산", "이체/기타"),
) {
    companion object {
        const val ALL_GROUP = "전체"
    }
}

data class DayMoney(val spend: Long = 0, val planned: Long = 0, val income: Long = 0)

/** LD-08 같은 키의 거래 묶음. [keep]은 먼저 만든 행, [extra]는 합계에서 뺀(정리하면 휴지통으로 갈) 나머지. */
data class DuplicateGroup(val keep: LedgerTx, val extra: List<LedgerTx>)

/** 한 달(과 이번 주) 가계부 계산 결과. 금액은 모두 양수 원 단위. */
data class LedgerSummary(
    val month: YearMonth,
    val days: Map<LocalDate, DayMoney>,
    val monthSpend: Long,
    val monthPlanned: Long,
    val monthIncome: Long,
    val weekStart: LocalDate,
    val weekSpend: Long,
    val weekPlanned: Long,
    val monthBudget: Long?,
    val weekBudget: Long?,
    val monthRemaining: Long?,
    val weekRemaining: Long?,
    /** 오늘 포함 이번 주에 남은 날. */
    val weekDaysLeft: Int,
    /** LD-09 반영 기준일: 가져온 거래 중 가장 늦은 날짜(없으면 null). */
    val asOf: LocalDate?,
    val duplicates: List<DuplicateGroup>,
    /** LD-09 반영 기준일이 지난 예정 행(어느 합계에도 넣지 않음). */
    val needsReview: List<LedgerTx>,
    /** LD-10 가져온 거래와 짝이 맞아 합계에서 뺀 앱입력 행(앱입력, 짝). */
    val matchedManual: List<Pair<LedgerTx, LedgerTx>>,
)

/**
 * 가계부 계산(순수 함수). Room은 범위의 행만 읽고 규칙은 여기서 적용한다.
 *
 * - 지출: 타입 '지출' 금액 합의 부호를 뒤집은 값(환불은 자연히 빠짐), 그룹 제외. 수입: 타입 '수입' 합. 이체는 어디에도 넣지 않는다.
 * - LD-08 중복(같은 키의 가져온 거래)은 한 번만 센다. '둘 다 유지' 쌍([keeps])은 둘 다 센다.
 * - LD-09 예정 행: 날짜가 반영 기준일보다 뒤면 예정, 아니면 '확인 필요'(합계 제외).
 * - LD-10 앱입력 행: 날짜 ±1일·금액·타입이 같은 가져온 거래와 1:1로 짝이 맞으면 뺀다. 짝이 없으면 실제 지출로 센다.
 */
object LedgerMath {
    const val MONTH = "월"
    const val WEEK = "주"

    fun summarize(
        rows: List<LedgerTx>,
        categoryGroups: Map<String, String>,
        keeps: Set<Pair<String, String>>,
        today: LocalDate,
        weekStart: DayOfWeek,
        month: YearMonth,
        budgets: List<BudgetRow>,
        rules: LedgerRules,
    ): LedgerSummary {
        val asOf = rows.filter { it.imported }.maxOfOrNull { it.date }

        // LD-08 중복: 가져온 거래만, 먼저 만든 행을 대표로.
        val duplicates = mutableListOf<DuplicateGroup>()
        val dropped = HashSet<String>()
        rows.filter { it.imported }.groupBy { it.dupKey }.values.filter { it.size > 1 }.forEach { group ->
            val sorted = group.sortedWith(compareBy<LedgerTx> { it.createdAt }.thenBy { it.pageId })
            val keep = sorted.first()
            val extra = sorted.drop(1).filterNot { (keep.pageId to it.pageId) in keeps || (it.pageId to keep.pageId) in keeps }
            if (extra.isNotEmpty()) {
                duplicates += DuplicateGroup(keep, extra)
                extra.forEach { dropped += it.pageId }
            }
        }
        val importedCounted = rows.filter { it.imported && it.pageId !in dropped }

        // LD-10 앱입력 ↔ 가져온 거래 1:1 짝.
        val used = HashSet<String>()
        val matched = mutableListOf<Pair<LedgerTx, LedgerTx>>()
        rows.filter { it.manual && !it.planned }.sortedBy { it.date }.forEach { manual ->
            val partner = importedCounted
                .filter { it.pageId !in used && it.amount == manual.amount && it.flow == manual.flow && abs(ChronoUnit.DAYS.between(it.date, manual.date)) <= 1 }
                .minByOrNull { abs(ChronoUnit.DAYS.between(it.date, manual.date)) }
            if (partner != null) {
                used += partner.pageId
                matched += manual to partner
            }
        }
        val matchedIds = matched.mapTo(HashSet()) { it.first.pageId }
        val actual = importedCounted + rows.filter { it.manual && !it.planned && it.pageId !in matchedIds }

        // LD-09 예정.
        val (planned, needsReview) = rows.filter { it.planned }.partition { asOf == null || it.date.isAfter(asOf) }

        val sign = if (rules.spendNegative) -1L else 1L
        fun group(tx: LedgerTx) = tx.category?.let(categoryGroups::get)
        fun isSpend(tx: LedgerTx) = tx.flow == Flow.OUT && group(tx) !in rules.excludedGroups
        fun isBudgetSpend(tx: LedgerTx) = isSpend(tx) && (rules.budgetGroup == LedgerRules.ALL_GROUP || group(tx) == rules.budgetGroup)
        fun spendOf(tx: LedgerTx) = sign * tx.amount
        fun incomeOf(tx: LedgerTx) = -sign * tx.amount

        val days = HashMap<LocalDate, DayMoney>()
        fun add(date: LocalDate, f: (DayMoney) -> DayMoney) {
            days[date] = f(days[date] ?: DayMoney())
        }
        actual.forEach { tx ->
            when {
                isSpend(tx) -> add(tx.date) { it.copy(spend = it.spend + spendOf(tx)) }
                tx.flow == Flow.IN -> add(tx.date) { it.copy(income = it.income + incomeOf(tx)) }
            }
        }
        planned.filter(::isSpend).forEach { tx -> add(tx.date) { it.copy(planned = it.planned + spendOf(tx)) } }

        fun inMonth(date: LocalDate) = YearMonth.from(date) == month
        val wStart = today.with(TemporalAdjusters.previousOrSame(weekStart))
        val wEnd = wStart.plusDays(6)
        fun inWeek(date: LocalDate) = !date.isBefore(wStart) && !date.isAfter(wEnd)

        val monthSpend = days.filterKeys(::inMonth).values.sumOf { it.spend }
        val monthPlanned = days.filterKeys(::inMonth).values.sumOf { it.planned }
        val monthIncome = days.filterKeys(::inMonth).values.sumOf { it.income }
        val weekSpend = days.filterKeys(::inWeek).values.sumOf { it.spend }
        val weekPlanned = days.filterKeys(::inWeek).values.sumOf { it.planned }

        // 예산은 그룹 기준 지출 + 그 기간 예정(쓸 돈)을 뺀 남음.
        fun budgetUse(inPeriod: (LocalDate) -> Boolean) =
            actual.filter { isBudgetSpend(it) && inPeriod(it.date) }.sumOf(::spendOf) +
                planned.filter { isBudgetSpend(it) && inPeriod(it.date) }.sumOf(::spendOf)

        val monthBudget = budgetFor(budgets, MONTH, monthKey(month), rules.budgetGroup)
        val weekBudget = budgetFor(budgets, WEEK, weekKey(today, weekStart), rules.budgetGroup)

        return LedgerSummary(
            month = month,
            days = days,
            monthSpend = monthSpend,
            monthPlanned = monthPlanned,
            monthIncome = monthIncome,
            weekStart = wStart,
            weekSpend = weekSpend,
            weekPlanned = weekPlanned,
            monthBudget = monthBudget,
            weekBudget = weekBudget,
            monthRemaining = monthBudget?.let { it - budgetUse(::inMonth) },
            weekRemaining = weekBudget?.let { it - budgetUse(::inWeek) },
            weekDaysLeft = (ChronoUnit.DAYS.between(today, wEnd) + 1).toInt(),
            asOf = asOf,
            duplicates = duplicates,
            needsReview = needsReview,
            matchedManual = matched,
        )
    }

    fun monthKey(month: YearMonth): String = month.toString()

    /** LD-04: 주(시작 요일은 MV-01) 안의 월요일이 속한 ISO 주. 10/18(일)~10/24 → '2026-W43'. */
    fun weekKey(date: LocalDate, weekStart: DayOfWeek): String {
        val start = date.with(TemporalAdjusters.previousOrSame(weekStart))
        val monday = start.with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY))
        return "%d-W%02d".format(monday.get(IsoFields.WEEK_BASED_YEAR), monday.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR))
    }

    private val WEEK_KEY = Regex("""^\s*(\d{4})\s*-\s*W\s*0*(\d{1,2})\s*$""", RegexOption.IGNORE_CASE)

    /** 예산 기간값의 주 표기를 'YYYY-Www'로 맞춘다(W3·W03·w03 모두 같은 주). 주가 아니면 앞뒤 공백만 뺀다. */
    fun normalizeWeekKey(value: String): String =
        WEEK_KEY.matchEntire(value)?.let { m -> "%s-W%02d".format(m.groupValues[1], m.groupValues[2].toInt()) } ?: value.trim()

    /** LD-05: 그 기간 값, 없거나 비면 같은 유형·그룹의 가장 최근(지난) 값. 앞날 값은 쓰지 않는다. */
    fun budgetFor(budgets: List<BudgetRow>, periodType: String, key: String, group: String): Long? =
        budgets.asSequence()
            .filter { it.periodType == periodType && (it.group ?: LedgerRules.ALL_GROUP) == group && it.amount != null }
            .map { normalizeWeekKey(it.periodKey) to it.amount!! }
            .filter { (k, _) -> k <= key }
            .maxByOrNull { it.first }
            ?.second
}
