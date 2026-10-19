package com.ysdoing.notioncal.core.data.ledger

import com.ysdoing.notioncal.core.data.APP_ZONE
import com.ysdoing.notioncal.core.data.EventChange
import com.ysdoing.notioncal.core.data.EventWriter
import com.ysdoing.notioncal.core.data.LedgerValue
import com.ysdoing.notioncal.core.data.SettingsRepository
import com.ysdoing.notioncal.core.data.SourceKind
import com.ysdoing.notioncal.core.data.calendar.GRID_DAYS
import com.ysdoing.notioncal.core.data.calendar.gridStart
import com.ysdoing.notioncal.core.data.db.LedgerDupKeepEntity
import com.ysdoing.notioncal.core.data.db.NotionCalDatabase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow as DataFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters
import javax.inject.Inject
import javax.inject.Singleton

/** LD-07 저장 결과. */
enum class LedgerAddResult { SAVED, INVALID_AMOUNT, NO_LEDGER }

/** 💰 탭·가계부 위젯이 그리는 것. [registered]가 false면 거래내역 DB를 아직 등록하지 않았다. */
data class LedgerBoard(
    val month: YearMonth,
    val today: LocalDate,
    val weekStart: DayOfWeek,
    val summary: LedgerSummary,
    val registered: Boolean,
) {
    /** 6주 그리드 첫날. */
    val gridStart: LocalDate get() = gridStart(month, weekStart)
}

/**
 * LD-02~10 가계부 데이터 쪽. Room만 읽고 계산은 [LedgerMath]가 한다. 쓰기(중복 정리·예정 전환·LD-07 입력)는
 * [EventWriter](아웃박스)로만 한다. 노션 API를 부르지 않는다.
 */
@Singleton
class LedgerRepository @Inject constructor(
    private val db: NotionCalDatabase,
    private val writer: EventWriter,
    private val settings: SettingsRepository,
    private val clock: Clock,
) {
    private val dao get() = db.ledgerDao()

    fun today(): LocalDate = clock.instant().atZone(APP_ZONE).toLocalDate()

    /**
     * [month] 6주 그리드와 [today]가 든 주(주 예산)를 덮는 범위의 거래를 읽어 계산한다. LD-10 ±1일 대조를 위해 하루씩 넓힌다.
     * 규칙(부호·예산 그룹)은 첫 번째로 켠 거래내역 DB의 설정을 쓴다.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeBoard(month: YearMonth, today: LocalDate): DataFlow<LedgerBoard> =
        settings.settings.map { it.weekStart }.distinctUntilChanged().flatMapLatest { weekStart ->
            val gridFrom = gridStart(month, weekStart)
            val weekFrom = today.with(TemporalAdjusters.previousOrSame(weekStart))
            val from = minOf(gridFrom, weekFrom, month.atDay(1)).minusDays(1)
            val to = maxOf(gridFrom.plusDays(GRID_DAYS - 1), weekFrom.plusDays(6)).plusDays(1)
            val inputs = combine(
                db.dataSourceDao().observeByKind(SourceKind.LEDGER.name),
                dao.observeRange(from, to),
                dao.observeAsOf(),
                dao.observeBudgets(),
                dao.observeCategoryGroups(),
            ) { sources, rows, asOf, budgets, groups -> Inputs(sources.firstOrNull { it.enabled }, rows, asOf, budgets, groups) }
            combine(inputs, dao.observeDupKeeps()) { input, keeps ->
                val source = input.source
                val rules = LedgerRules(source?.spendNegative ?: true, source?.budgetGroup ?: LedgerRules.ALL_GROUP)
                val summary = LedgerMath.summarize(
                    rows = input.rows.map { it.toLedgerTx() },
                    categoryGroups = input.groups.mapNotNull { g -> g.groupName?.let { g.category to it } }.toMap(),
                    keeps = keeps.mapTo(HashSet()) { it.pageIdA to it.pageIdB },
                    today = today,
                    weekStart = weekStart,
                    month = month,
                    budgets = input.budgets.map { it.toBudgetRow() },
                    rules = rules,
                    asOf = input.asOf,
                )
                LedgerBoard(month, today, weekStart, summary, registered = source != null)
            }
        }

    private class Inputs(
        val source: com.ysdoing.notioncal.core.data.db.DataSourceEntity?,
        val rows: List<com.ysdoing.notioncal.core.data.db.EventEntity>,
        val asOf: LocalDate?,
        val budgets: List<com.ysdoing.notioncal.core.data.db.BudgetEntity>,
        val groups: List<com.ysdoing.notioncal.core.data.db.CategoryGroupEntity>,
    )

    /**
     * LD-07 빠른 입력. [amount]는 양수(원). 앞날이면 '예정', 오늘·지난 날이면 '앱입력'을 켠다. 부호는 등록한 양식(지출 음수)을
     * 따르고, 오늘이면 지금 시각(HH:MM)을 넣는다. 내용이 비면 대분류(또는 타입)를 제목으로 쓴다. 노션에는 아웃박스로 간다.
     */
    suspend fun add(date: LocalDate, amount: Long, flow: Flow, category: String?, title: String): LedgerAddResult {
        if (amount <= 0) return LedgerAddResult.INVALID_AMOUNT
        val source = db.dataSourceDao().enabled().firstOrNull { it.kind == SourceKind.LEDGER.name } ?: return LedgerAddResult.NO_LEDGER
        val today = today()
        val planned = date.isAfter(today)
        val sign = if ((flow == Flow.OUT) == source.spendNegative) -1L else 1L
        val time = if (date == today) clock.instant().atZone(APP_ZONE).toLocalTime().format(HH_MM) else null
        val name = title.trim().ifEmpty { category?.takeIf { it.isNotBlank() } ?: flow.notionName }
        writer.create(
            source.dataSourceId, name, com.ysdoing.notioncal.core.data.DateValue.allDay(date),
            ledger = LedgerValue(amount = sign * amount, flow = flow.name, category = category, time = time, planned = planned, manual = !planned),
        )
        return LedgerAddResult.SAVED
    }

    /** LD-07 대분류 칩: 최근 쓴 순, 그다음 카테고리 마스터의 나머지(지출이면 수입 그룹 제외, 수입이면 수입 그룹만). */
    suspend fun categoryChoices(flow: Flow): List<String> {
        val recent = dao.recentCategories(flow.name, RECENT_CATEGORIES)
        val master = dao.observeCategoryGroups().first()
            .filter { (it.groupName == INCOME_GROUP) == (flow == Flow.IN) }
            .map { it.category }
        return (recent + master).distinct()
    }

    /** LD-08 정리: 먼저 만든 행만 남기고 나머지를 휴지통으로(아웃박스). 자동으로는 하지 않는다. */
    suspend fun resolveDuplicates(group: DuplicateGroup) {
        group.extra.forEach { writer.trash(it.pageId) }
    }

    /** LD-08 '둘 다 유지': 같은 키의 실제 거래 2건. 캐시가 아닌 앱 상태에 남긴다. */
    suspend fun keepBoth(group: DuplicateGroup) {
        group.extra.forEach { extra ->
            val (a, b) = listOf(group.keep.pageId, extra.pageId).sorted()
            dao.insertDupKeep(LedgerDupKeepEntity(a, b, clock.millis()))
        }
    }

    /** LD-09 확인 필요 → 삭제(휴지통). LD-10 정리 대상 앱입력 삭제에도 쓴다. */
    suspend fun trash(pageId: String) = writer.trash(pageId)

    /** LD-09 확인 필요 → 실제로 전환: 예정을 끄고 앱입력으로 둔다(가져온 거래와 짝이 생기면 LD-10이 정리한다). */
    suspend fun convertPlannedToActual(pageId: String) =
        writer.update(pageId, EventChange(ledger = LedgerValue(planned = false, manual = true)))

    private companion object {
        const val RECENT_CATEGORIES = 12
        const val INCOME_GROUP = "수입"
        val HH_MM: java.time.format.DateTimeFormatter = java.time.format.DateTimeFormatter.ofPattern("HH:mm")
    }
}
