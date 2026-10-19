package com.ysdoing.notioncal.core.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ysdoing.notioncal.core.data.BudgetPropIds
import com.ysdoing.notioncal.core.data.CategoryPropIds
import com.ysdoing.notioncal.core.data.DateValue
import com.ysdoing.notioncal.core.data.EventChange
import com.ysdoing.notioncal.core.data.EventWriter
import com.ysdoing.notioncal.core.data.LedgerPropIds
import com.ysdoing.notioncal.core.data.LedgerValue
import com.ysdoing.notioncal.core.data.OutboxRequester
import com.ysdoing.notioncal.core.data.SchemaIssue
import com.ysdoing.notioncal.core.data.SourceKind
import com.ysdoing.notioncal.core.data.SyncTrigger
import com.ysdoing.notioncal.core.data.WidgetRefresher
import com.ysdoing.notioncal.core.data.db.DataSourceEntity
import com.ysdoing.notioncal.core.data.db.NotionCalDatabase
import com.ysdoing.notioncal.core.notion.NotionJson
import com.ysdoing.notioncal.core.notion.dto.DataSourceDto
import com.ysdoing.notioncal.core.sync.FakeNotionStore.Companion.BUDGET
import com.ysdoing.notioncal.core.sync.FakeNotionStore.Companion.CATEGORY
import com.ysdoing.notioncal.core.sync.FakeNotionStore.Companion.LEDGER
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** LD-01·02·05 가계부 동기화: 거래 값, 예산·카테고리 전체 조회, 스키마 점검, '노션과 비교' 월 합계, 거래 쓰기. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class LedgerSyncTest {
    private lateinit var db: NotionCalDatabase
    private var now = Instant.parse("2026-10-19T03:00:00Z")
    private val clock = object : Clock() {
        override fun getZone(): ZoneId = ZoneId.of("UTC")
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = now
    }
    private val notion = FakeNotionStore { now }

    private fun engine() = NotionSyncEngine(notion, db, clock, UnconfinedTestDispatcher(), WidgetRefresher.None)
    private fun processor() = OutboxProcessor(notion, notion, db, clock, WidgetRefresher.None, UnconfinedTestDispatcher())

    @Before
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), NotionCalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        registerLedger(db)
        notion.categories["c-food"] = FakeNotionStore.Category("c-food", "식비", "생활비")
        notion.categories["c-save"] = FakeNotionStore.Category("c-save", "저축", "금융/자산")
        notion.budgets["b-oct"] = FakeNotionStore.Budget("b-oct", "월", "2026-10", "전체", 1_000_000)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun ledgerRowsBudgetsAndCategoriesSync() = runTest {
        notion.seedTx("t1", "김밥천국", "2026-10-16", -9_800, time = "12:30", createdAt = Instant.parse("2026-10-17T00:00:00Z"))
        notion.seedTx("t2", "운동화", "2026-10-20", -50_000, category = "온라인쇼핑", time = null, payment = null, planned = true)
        notion.seedTx("t3", "회사", "2026-10-17", 2_500_000, flow = "수입", category = "급여")
        engine().sync(SyncTrigger.APP_OPEN)

        val kimbap = db.eventDao().get("t1")!!
        assertEquals(-9_800L, kimbap.amount)
        assertEquals("OUT", kimbap.flow)
        assertEquals("식비", kimbap.category)
        assertEquals("12:30", kimbap.txTime)
        assertEquals("토스뱅크 통장", kimbap.payment)
        assertEquals(Instant.parse("2026-10-17T00:00:00Z").toEpochMilli(), kimbap.createdTime)
        assertEquals(true, db.eventDao().get("t2")!!.planned)
        assertEquals("IN", db.eventDao().get("t3")!!.flow)
        // 거래는 달력에 섞이지 않는다.
        assertTrue(db.eventDao().observeRange(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31)).first().isEmpty())
        assertEquals(LocalDate.of(2026, 10, 17), db.ledgerDao().observeAsOf().first())

        assertEquals(listOf("2026-10" to 1_000_000L), db.ledgerDao().observeBudgets().first().map { it.periodKey to it.amount })
        assertEquals(mapOf("식비" to "생활비", "저축" to "금융/자산"), db.ledgerDao().observeCategoryGroups().first().associate { it.category to it.groupName })

        // 노션에서 예산을 바꾸고 주 예산 행을 더하고, 카테고리를 지우면 다음 동기화에 그대로 반영된다.
        notion.budgets.getValue("b-oct").amount = 1_200_000
        notion.budgets["b-w43"] = FakeNotionStore.Budget("b-w43", "주", "2026-W43", "전체", 250_000)
        notion.categories.getValue("c-save").inTrash = true
        now = now.plusSeconds(900)
        engine().sync(SyncTrigger.PERIODIC)
        assertEquals(setOf("2026-10" to 1_200_000L, "2026-W43" to 250_000L), db.ledgerDao().observeBudgets().first().map { it.periodKey to it.amount }.toSet())
        assertEquals(listOf("식비"), db.ledgerDao().observeCategoryGroups().first().map { it.category })
        assertTrue(notion.queries.filter { it.first == BUDGET || it.first == CATEGORY }.all { it.second == null })
    }

    /** LD-01: 금액 속성이 number가 아니게 되면 그 DB 동기화를 멈추고(캐시 유지) 알린다. */
    @Test
    fun amountTypeChangeBlocksLedgerSync() = runTest {
        notion.seedTx("t1", "김밥천국", "2026-10-16", -9_800)
        engine().sync(SyncTrigger.APP_OPEN)
        notion.ledgerSchema = NotionJson.decodeFromString(
            DataSourceDto.serializer(),
            """{"id":"x","properties":{"내용":{"id":"title","name":"내용","type":"title"},"날짜":{"id":"nAlJ","name":"날짜","type":"date"},
               "금액":{"id":"aMt","name":"금액","type":"rich_text"},"타입":{"id":"fLw","name":"타입","type":"select"}}}""",
        )
        notion.pages.getValue("t1").amount = -1
        now = now.plusSeconds(900)
        engine().sync(SyncTrigger.PERIODIC)
        assertEquals(-9_800L, db.eventDao().get("t1")!!.amount)
        val issues = SchemaIssue.parse(db.dataSourceDao().get(LEDGER)!!.schemaIssues)
        assertTrue(issues.toString(), issues.any { it.role == SchemaIssue.AMOUNT && it.kind == SchemaIssue.TYPE_CHANGED && it.blocksSync })
    }

    /** §12 가계부 대조: 같은 규칙으로 낸 이번 달 지출·수입이 노션과 앱에서 같다. */
    @Test
    fun comparerChecksLedgerMonthTotals() = runTest {
        notion.seedTx("t1", "김밥천국", "2026-10-16", -9_800)
        notion.seedTx("t2", "적금", "2026-10-16", -200_000, category = "저축") // 금융/자산: 지출에서 뺌
        notion.seedTx("t3", "회사", "2026-10-17", 2_500_000, flow = "수입", category = "급여")
        engine().sync(SyncTrigger.APP_OPEN)
        val report = NotionComparer(notion, db, clock, UnconfinedTestDispatcher()).compare()
        val total = report.ledgerTotals.single()
        assertEquals(9_800L, total.remoteSpend)
        assertEquals(9_800L, total.localSpend)
        assertEquals(2_500_000L, total.localIncome)
        assertTrue(report.toString(), report.matches)
    }

    /** LD-07·09 쓰기: 아웃박스로 금액·타입(이름)·대분류·시간·예정·앱입력을 보낸다. */
    @Test
    fun ledgerCreateAndConvertGoThroughOutbox() = runTest {
        val writer = EventWriter(db, clock, WidgetRefresher.None, OutboxRequester.None)
        val id = writer.create(
            LEDGER, "점심", DateValue.allDay(LocalDate.of(2026, 10, 19)),
            ledger = LedgerValue(amount = -9_000, flow = "OUT", category = "식비", time = "12:10", planned = false, manual = true),
        )
        assertEquals(-9_000L, db.eventDao().get(id)!!.amount)
        processor().process()
        val page = notion.pages.values.single { it.dataSourceId == LEDGER }
        assertEquals(listOf(-9_000L, "지출", "식비", "12:10", false, true), listOf(page.amount, page.flow, page.category, page.time, page.planned, page.manual))

        // LD-09 '실제로 전환': 예정 → 앱입력.
        notion.seedTx("p1", "운동화", "2026-10-20", -50_000, planned = true)
        engine().sync(SyncTrigger.APP_OPEN)
        now = now.plusSeconds(60)
        writer.update("p1", EventChange(ledger = LedgerValue(planned = false, manual = true)))
        assertEquals(false, db.eventDao().get("p1")!!.planned)
        processor().process()
        assertEquals(false to true, notion.pages.getValue("p1").planned to notion.pages.getValue("p1").manual)
    }

    companion object {
        /** 거래내역 + 예산 + 카테고리 마스터 등록(FakeNotionStore의 속성 ID). */
        suspend fun registerLedger(db: NotionCalDatabase) {
            fun row(id: String, kind: SourceKind, date: String?, props: String?, link: String? = null) = DataSourceEntity(
                dataSourceId = id, databaseId = "db-$id", name = id, colorHex = "#DFAB01", titlePropId = "title",
                datePropId = date, donePropId = null, doneType = null, doneOption = null, undoneOption = null,
                colorPropId = null, sortPropId = null, kind = kind.name, rolePropIds = props, linkDataSourceId = link,
            )
            db.dataSourceDao().upsert(
                row(
                    LEDGER, SourceKind.LEDGER, "nAlJ",
                    LedgerPropIds.encode(LedgerPropIds("aMt", "fLw", "cAt", "tIme", "pAy", "pLn", "mAn")),
                ),
            )
            db.dataSourceDao().upsert(row(BUDGET, SourceKind.BUDGET, null, BudgetPropIds.encode(BudgetPropIds("tYp", "vAl", "gRp", "aMt")), LEDGER))
            db.dataSourceDao().upsert(row(CATEGORY, SourceKind.CATEGORY, null, CategoryPropIds.encode(CategoryPropIds("gRp")), LEDGER))
        }
    }
}
