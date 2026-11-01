package com.ysdoing.notioncal.core.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ysdoing.notioncal.core.data.DdayRemovedNotice
import com.ysdoing.notioncal.core.data.SyncTrigger
import com.ysdoing.notioncal.core.data.WidgetRefresher
import com.ysdoing.notioncal.core.data.db.DataSourceEntity
import com.ysdoing.notioncal.core.data.db.DdayPinEntity
import com.ysdoing.notioncal.core.data.db.NotionCalDatabase
import com.ysdoing.notioncal.core.sync.FakeNotionStore.Companion.CALENDAR
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** DD-01 디데이 동기화: 노션에서 날짜를 바꾸면 따라가고, 휴지통으로 가면 고정을 풀고 알린다. 범위 밖 고정 일정도 받는다. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class DdaySyncTest {
    private lateinit var db: NotionCalDatabase
    private var now = Instant.parse("2026-10-19T03:00:00Z")
    private val clock = object : Clock() {
        override fun getZone(): ZoneId = ZoneId.of("UTC")
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = now
    }
    private val notion = FakeNotionStore { now }
    private val notices = mutableListOf<String>()

    private fun engine() = NotionSyncEngine(
        notion, db, clock, UnconfinedTestDispatcher(), WidgetRefresher.None, DdayRemovedNotice { notices += it },
    )

    @Before
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), NotionCalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        db.dataSourceDao().upsert(
            DataSourceEntity(
                dataSourceId = CALENDAR, databaseId = "db", name = "Calendar", colorHex = "#0B6E99",
                titlePropId = "title", datePropId = "dAtE", donePropId = "dOnE", doneType = "CHECKBOX",
                doneOption = null, undoneOption = null, colorPropId = null, sortPropId = null,
            ),
        )
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun dateChangeInNotionIsFollowed() = runTest {
        notion.seed("exam", "기말고사", "2026-10-31")
        engine().sync(SyncTrigger.APP_OPEN)
        db.ddayDao().upsert(DdayPinEntity("exam"))

        notion.pages.getValue("exam").apply {
            start = "2026-11-02"
            lastEdited = now.plusSeconds(10)
        }
        now = now.plusSeconds(900)
        engine().sync(SyncTrigger.PERIODIC)
        assertEquals(LocalDate.of(2026, 11, 2), db.eventDao().get("exam")!!.startDate)
        assertEquals(listOf("exam"), db.ddayDao().all().map { it.pageId })
    }

    /** 동기화 범위(±2개월) 밖 고정 일정은 범위 대조가 보지 않으므로, 앱을 열 때 한 건씩 확인한다. */
    @Test
    fun trashedPinOutsideRangeIsUnpinnedWithNotice() = runTest {
        notion.seed("wedding", "결혼식", "2027-06-12")
        notion.seed("exam", "기말고사", "2026-10-31")
        engine().sync(SyncTrigger.APP_OPEN)
        assertNull(db.eventDao().get("wedding")) // 범위 밖이라 아직 없음
        db.ddayDao().upsert(DdayPinEntity("wedding"))
        db.ddayDao().upsert(DdayPinEntity("exam"))

        now = now.plusSeconds(900)
        engine().sync(SyncTrigger.APP_OPEN)
        assertEquals(LocalDate.of(2027, 6, 12), db.eventDao().get("wedding")!!.startDate) // 고정 일정은 범위 밖이어도 받는다

        notion.pages.getValue("wedding").inTrash = true
        now = now.plusSeconds(900)
        engine().sync(SyncTrigger.PERIODIC) // 주기 동기화는 범위 밖 한 건 조회를 하지 않는다
        assertEquals(2, db.ddayDao().all().size)

        engine().sync(SyncTrigger.APP_OPEN)
        assertEquals(listOf("exam"), db.ddayDao().all().map { it.pageId })
        assertNull(db.eventDao().get("wedding"))
        assertEquals(listOf("결혼식"), notices)
    }

    /** 범위 안 고정 일정이 휴지통으로 가면 범위 대조가 지우고, 그 고정도 풀린다. */
    @Test
    fun trashedPinInsideRangeIsUnpinned() = runTest {
        notion.seed("exam", "기말고사", "2026-10-31")
        engine().sync(SyncTrigger.APP_OPEN)
        db.ddayDao().upsert(DdayPinEntity("exam"))
        notion.pages.getValue("exam").inTrash = true
        now = now.plusSeconds(900)
        engine().sync(SyncTrigger.APP_OPEN)
        assertTrue(db.ddayDao().all().isEmpty())
        assertEquals(listOf("기말고사"), notices)
    }
}
