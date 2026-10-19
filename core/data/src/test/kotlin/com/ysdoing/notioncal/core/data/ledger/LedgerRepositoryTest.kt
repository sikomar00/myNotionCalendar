package com.ysdoing.notioncal.core.data.ledger

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ysdoing.notioncal.core.data.EventChange
import com.ysdoing.notioncal.core.data.EventWriter
import com.ysdoing.notioncal.core.data.OutboxJson
import com.ysdoing.notioncal.core.data.OutboxRequester
import com.ysdoing.notioncal.core.data.SettingsRepository
import com.ysdoing.notioncal.core.data.SourceKind
import com.ysdoing.notioncal.core.data.WidgetRefresher
import com.ysdoing.notioncal.core.data.db.BudgetEntity
import com.ysdoing.notioncal.core.data.db.CategoryGroupEntity
import com.ysdoing.notioncal.core.data.db.DataSourceEntity
import com.ysdoing.notioncal.core.data.db.EventEntity
import com.ysdoing.notioncal.core.data.db.NotionCalDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset

/** LD-02·05·08·09 저장소: Room에서 읽어 계산, 중복 정리·둘 다 유지·예정 삭제/전환은 아웃박스로. */
@RunWith(RobolectricTestRunner::class)
class LedgerRepositoryTest {
    @get:Rule
    val tmp = TemporaryFolder()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dbName = "ledger-repo-test.db"
    private lateinit var db: NotionCalDatabase
    private val today = LocalDate.of(2026, 10, 21)
    private val clock = Clock.fixed(Instant.parse("2026-10-21T03:00:00Z"), ZoneOffset.UTC)
    private val october = YearMonth.of(2026, 10)

    @Before
    fun setUp() = runTest {
        context.deleteDatabase(dbName)
        // 성능 측정은 실제 파일 DB(인덱스 포함)로 한다.
        db = Room.databaseBuilder(context, NotionCalDatabase::class.java, dbName).allowMainThreadQueries().build()
        db.dataSourceDao().upsert(
            DataSourceEntity(
                dataSourceId = "ledger", databaseId = "db-ledger", name = "거래내역", colorHex = "#DFAB01", titlePropId = "title",
                datePropId = "d", donePropId = null, doneType = null, doneOption = null, undoneOption = null,
                colorPropId = null, sortPropId = null, kind = SourceKind.LEDGER.name,
            ),
        )
        // 예산·카테고리 마스터 행은 등록(켜 둔) DB와 조인해 읽는다.
        for ((id, kind) in listOf("budget" to SourceKind.BUDGET, "category" to SourceKind.CATEGORY)) {
            db.dataSourceDao().upsert(
                DataSourceEntity(
                    dataSourceId = id, databaseId = "db-$id", name = id, colorHex = "#DFAB01", titlePropId = "title",
                    datePropId = null, donePropId = null, doneType = null, doneOption = null, undoneOption = null,
                    colorPropId = null, sortPropId = null, kind = kind.name, linkDataSourceId = "ledger",
                ),
            )
        }
        db.ledgerDao().insertCategoryGroups(
            listOf(CategoryGroupEntity("c1", "category", "식비", "생활비"), CategoryGroupEntity("c2", "category", "저축", "금융/자산")),
        )
    }

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase(dbName)
    }

    private fun TestScope.repository(): LedgerRepository {
        val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { File(tmp.root, "settings.preferences_pb") }
        return LedgerRepository(db, EventWriter(db, clock, WidgetRefresher.None, OutboxRequester.None), SettingsRepository(store, WidgetRefresher {}), clock)
    }

    private fun tx(
        id: String, date: LocalDate, amount: Long, flow: Flow = Flow.OUT, category: String = "식비", time: String? = "12:00",
        planned: Boolean = false, manual: Boolean = false, created: Long = 0, title: String = id,
    ) = EventEntity(
        pageId = id, dataSourceId = "ledger", title = title, startDate = date, endDate = null, startTime = null, endTime = null,
        isAllDay = true, done = false, colorKey = null, sortKey = null, url = "", lastEditedTime = 0, createdTime = created,
        amount = amount, flow = flow.name, category = category, txTime = time, payment = "토스뱅크 통장", planned = planned, manual = manual,
    )

    @Test
    fun boardSumsMonthAndUsesGlobalAsOf() = runTest {
        db.eventDao().upsert(
            listOf(
                tx("a", LocalDate.of(2026, 10, 19), -9_800),
                tx("b", LocalDate.of(2026, 10, 20), -200_000, category = "저축"),
                tx("c", LocalDate.of(2026, 10, 17), 2_500_000, flow = Flow.IN, category = "급여"),
                tx("later", LocalDate.of(2027, 1, 5), -1_000), // 범위 밖이지만 반영 기준일은 DB 전체에서
            ),
        )
        db.ledgerDao().insertBudgets(listOf(BudgetEntity("w43", "budget", "주", "2026-W43", "전체", 100_000)))
        val board = repository().observeBoard(october, today).first()
        assertTrue(board.registered)
        assertEquals(9_800L, board.summary.monthSpend)
        assertEquals(2_500_000L, board.summary.monthIncome)
        assertEquals(LocalDate.of(2027, 1, 5), board.summary.asOf)
        assertEquals(90_200L, board.summary.weekRemaining)
    }

    /** LD-08: 정리하면 나중 행이 휴지통(아웃박스 TRASH)으로 가고, 둘 다 유지는 앱 상태에 남아 합계에 둘 다 센다. */
    @Test
    fun duplicatesResolveOrKeepBoth() = runTest {
        db.eventDao().upsert(
            listOf(
                tx("first", LocalDate.of(2026, 10, 19), -9_800, created = 1, title = "김밥천국"),
                tx("second", LocalDate.of(2026, 10, 19), -9_800, created = 2, title = "김밥천국"),
                tx("x1", LocalDate.of(2026, 10, 20), -4_500, created = 1, title = "GS25"),
                tx("x2", LocalDate.of(2026, 10, 20), -4_500, created = 2, title = "GS25"),
            ),
        )
        val repo = repository()
        val board = repo.observeBoard(october, today).first()
        assertEquals(14_300L, board.summary.monthSpend)
        assertEquals(2, board.summary.duplicates.size)

        repo.resolveDuplicates(board.summary.duplicates.first { it.keep.pageId == "first" })
        assertEquals(listOf("TRASH" to "second"), db.outboxDao().all().map { it.op to it.pageId })

        repo.keepBoth(board.summary.duplicates.first { it.keep.pageId == "x1" })
        val after = repo.observeBoard(october, today).first()
        assertTrue(after.summary.duplicates.isEmpty())
        assertEquals(9_800L + 4_500L * 2, after.summary.monthSpend)
    }

    /** LD-09: 확인 필요 예정 행은 삭제하거나 실제(앱입력)로 바꾼다. 둘 다 아웃박스로 간다. */
    @Test
    fun reviewPlannedRows() = runTest {
        db.eventDao().upsert(
            listOf(
                tx("imported", LocalDate.of(2026, 10, 20), -8_000),
                tx("plan1", LocalDate.of(2026, 10, 18), -50_000, planned = true),
                tx("plan2", LocalDate.of(2026, 10, 19), -30_000, planned = true),
            ),
        )
        val repo = repository()
        assertEquals(listOf("plan1", "plan2"), repo.observeBoard(october, today).first().summary.needsReview.map { it.pageId }.sorted())

        repo.trash("plan1")
        repo.convertPlannedToActual("plan2")
        val ops = db.outboxDao().all()
        assertEquals(listOf("TRASH" to "plan1", "UPDATE" to "plan2"), ops.map { it.op to it.pageId })
        val change = OutboxJson.decodeFromString(EventChange.serializer(), ops[1].changes)
        assertEquals(false to true, change.ledger!!.planned to change.ledger.manual)
        // 앱입력이 된 행은 바로 실제 지출로 센다(짝이 없으므로).
        assertEquals(38_000L, repo.observeBoard(october, today).first().summary.monthSpend)
    }

    /** LD-02 완료 기준: 2,000건 DB에서 월 합계(조회 + 계산)가 50ms 이내(PC 기준 회귀 감시, 중앙값). */
    @Test
    fun twoThousandRowsBoardUnder50ms() = runTest {
        db.eventDao().upsert(
            (0 until 2_000).map { n ->
                tx("t$n", LocalDate.of(2026, 1, 1).plusDays(n * 365L / 2_000), -(1_000L + n), time = "%02d:%02d".format(n % 24, n % 60), created = n.toLong())
            },
        )
        val repo = repository()
        repo.observeBoard(october, today).first() // DB 열기·준비문 비용 제외
        val timings = (0 until 9).map {
            val started = System.nanoTime()
            repo.observeBoard(october, today).first()
            (System.nanoTime() - started) / 1_000_000.0
        }.sorted()
        val median = timings[timings.size / 2]
        println("LD-02 월 합계(2,000건): 중앙값 ${"%.1f".format(median)}ms")
        assertTrue("월 합계 중앙값 ${median}ms", median < 50.0)
    }

    /** LD-07: 오늘·지난 날은 앱입력, 앞날은 예정. 지출은 음수(양식 설정), 오늘이면 지금 시각(HH:MM). 내용이 비면 대분류. */
    @Test
    fun addWritesLedgerRowThroughOutbox() = runTest {
        val repo = repository()
        assertEquals(LedgerAddResult.SAVED, repo.add(today, 9_000, Flow.OUT, "식비", ""))
        assertEquals(LedgerAddResult.SAVED, repo.add(today.plusDays(3), 50_000, Flow.OUT, "온라인쇼핑", "운동화"))
        assertEquals(LedgerAddResult.SAVED, repo.add(today.minusDays(1), 300_000, Flow.IN, "급여", "알바비"))
        val changes = db.outboxDao().all().map { OutboxJson.decodeFromString(EventChange.serializer(), it.changes) }
        assertEquals(listOf("CREATE", "CREATE", "CREATE"), db.outboxDao().all().map { it.op })

        val (lunch, shoes, pay) = changes
        assertEquals("식비", lunch.title)
        assertEquals(-9_000L, lunch.ledger!!.amount)
        assertEquals("OUT", lunch.ledger.flow)
        assertEquals("12:00", lunch.ledger.time) // 서울 12:00(clock 03:00Z)
        assertEquals(false to true, lunch.ledger.planned to lunch.ledger.manual)
        assertEquals(true to false, shoes.ledger!!.planned to shoes.ledger.manual)
        assertEquals(null, shoes.ledger.time)
        assertEquals(300_000L, pay.ledger!!.amount)
        assertEquals("알바비", pay.title)

        // 저장 즉시 Room에 반영돼 합계에 들어간다(위젯 즉시 반영).
        val board = repo.observeBoard(october, today).first()
        assertEquals(9_000L, board.summary.monthSpend)
        assertEquals(50_000L, board.summary.monthPlanned)
    }

    @Test
    fun addNeedsRegisteredLedgerAndPositiveAmount() = runTest {
        val repo = repository()
        assertEquals(LedgerAddResult.INVALID_AMOUNT, repo.add(today, 0, Flow.OUT, "식비", ""))
        db.dataSourceDao().setEnabled("ledger", false)
        assertEquals(LedgerAddResult.NO_LEDGER, repo.add(today, 1_000, Flow.OUT, "식비", ""))
        assertTrue(db.outboxDao().all().isEmpty())
    }

    /** LD-07 대분류 칩: 최근 쓴 순, 그다음 카테고리 마스터의 나머지(지출은 수입 그룹 제외). */
    @Test
    fun categoryChipsRecentFirst() = runTest {
        db.ledgerDao().insertCategoryGroups(
            listOf(CategoryGroupEntity("c3", "category", "교통", "생활비"), CategoryGroupEntity("c4", "category", "급여", "수입")),
        )
        db.eventDao().upsert(
            listOf(tx("old", LocalDate.of(2026, 10, 1), -1_000, category = "저축"), tx("new", LocalDate.of(2026, 10, 20), -2_000, category = "교통")),
        )
        assertEquals(listOf("교통", "저축", "식비"), repository().categoryChoices(Flow.OUT))
        assertEquals(listOf("급여"), repository().categoryChoices(Flow.IN))
    }
}
