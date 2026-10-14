package com.ysdoing.notioncal.core.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ysdoing.notioncal.core.data.db.DataSourceEntity
import com.ysdoing.notioncal.core.data.db.EventEntity
import com.ysdoing.notioncal.core.data.db.NotionCalDatabase
import com.ysdoing.notioncal.core.data.db.OutboxEntity
import com.ysdoing.notioncal.core.data.db.RecoveringOpenHelperFactory
import com.ysdoing.notioncal.core.data.db.SyncLogEntity
import com.ysdoing.notioncal.core.data.db.WidgetConfigEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

/**
 * PF-09: 마이그레이션 경로가 없는 DB(예: 더 새 버전 앱을 지우지 않고 이전 APK를 설치)로 시작해도 열리고,
 * DB 등록·위젯 설정·미전송 쓰기는 남고 일정 캐시만 비워진다.
 */
@RunWith(RobolectricTestRunner::class)
class DatabaseRecoveryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "recovery-test.db"

    @Before
    fun clean() {
        context.deleteDatabase(name)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(name)
    }

    private fun build(factory: RecoveringOpenHelperFactory = RecoveringOpenHelperFactory(now = { 9_000L })) =
        Room.databaseBuilder(context, NotionCalDatabase::class.java, name)
            .openHelperFactory(factory)
            .allowMainThreadQueries()
            .build()

    private fun event(id: String, pending: Int) = EventEntity(
        pageId = id, dataSourceId = "ds", title = "일정 $id", startDate = LocalDate.of(2026, 10, 13), endDate = null,
        startTime = null, endTime = null, isAllDay = true, done = false, colorKey = null, sortKey = null,
        url = "", lastEditedTime = 1000, pendingOps = pending,
    )

    private suspend fun seedAndBreakVersion() {
        val db = build()
        db.dataSourceDao().upsert(
            DataSourceEntity(
                dataSourceId = "ds", databaseId = "db", name = "Calendar", colorHex = "#0B6E99", titlePropId = "title",
                datePropId = "d", donePropId = "done", doneType = "CHECKBOX", doneOption = null, undoneOption = null,
                colorPropId = "c", sortPropId = null, lastSyncAt = 500, lastFullSyncAt = 500,
            ),
        )
        db.widgetConfigDao().upsert(WidgetConfigEntity(appWidgetId = 7, monthOffset = 1, fontScale = 120))
        db.eventDao().upsert(listOf(event("p-synced", 0), event("local-new", 1)))
        db.outboxDao().insert(
            OutboxEntity(pageId = "local-new", dataSourceId = "ds", op = "CREATE", changes = """{"title":"x"}""",
                baseValues = null, baseLastEdited = null, createdAt = 1_000),
        )
        db.syncLogDao().insert(SyncLogEntity(at = 100, trigger = "PERIODIC", result = "OK", durationMs = 1, pulled = 1, pushed = 0, error = null))
        db.close()

        // 이 앱이 모르는 더 높은 버전으로 표시한다 → Room은 99→3 경로를 찾지 못한다.
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { it.version = 99 }
    }

    @Test
    fun withoutRecoveryRoomFailsToOpen() = runTest {
        seedAndBreakVersion()
        val plain = Room.databaseBuilder(context, NotionCalDatabase::class.java, name).allowMainThreadQueries().build()
        try {
            plain.outboxDao().all()
            fail("마이그레이션 경로가 없으면 Room이 예외를 던져야 한다(테스트 전제 확인)")
        } catch (e: IllegalStateException) {
            assertTrue(RecoveringOpenHelperFactory.isRecoverable(e))
        } finally {
            plain.close()
        }
    }

    @Test
    fun recoversKeepingRegistrationsWidgetConfigsAndOutbox() = runTest {
        seedAndBreakVersion()
        val db = build()
        try {
            assertEquals(1, db.outboxDao().all().size)
            assertEquals("local-new", db.outboxDao().all().single().pageId)

            val source = db.dataSourceDao().get("ds")!!
            assertEquals("Calendar", source.name)
            assertNull(source.lastSyncAt) // 다음 동기화는 전체 조회
            assertNull(source.lastFullSyncAt)

            assertEquals(120, db.widgetConfigDao().get(7)!!.fontScale)

            assertNull(db.eventDao().get("p-synced")) // 캐시는 비워짐
            assertEquals(1, db.eventDao().get("local-new")!!.pendingOps) // 미전송 일정은 남음

            val log = db.syncLogDao().observeLatest().first()!!
            assertEquals("CACHE_RESET", log.trigger)
            assertEquals(RecoveringOpenHelperFactory.RESULT_RECOVERED, log.result)
            assertEquals(9_000L, log.at)
        } finally {
            db.close()
        }
    }

    @Test
    fun injectedFaultIsNotSwallowed() = runTest {
        val db = build(RecoveringOpenHelperFactory(beforeOpen = { throw IllegalStateException("주입한 초기화 실패") }))
        try {
            db.outboxDao().all()
            fail("고장 주입 예외는 그대로 올라가야 한다(PF-10 진단 화면으로)")
        } catch (e: IllegalStateException) {
            assertEquals("주입한 초기화 실패", e.message)
        } finally {
            db.close()
        }
    }

    @Test
    fun resetRequestRecreatesEvenWithoutError() = runTest {
        seedAndBreakVersion()
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { it.version = 3 }
        var requested = true
        val db = build(RecoveringOpenHelperFactory(now = { 9_000L }, forceRecover = { requested.also { requested = false } }))
        try {
            assertNull(db.eventDao().get("p-synced"))
            assertEquals(1, db.outboxDao().all().size)
            assertEquals(RecoveringOpenHelperFactory.REASON_RESET_REQUESTED, db.syncLogDao().observeLatest().first()!!.error)
        } finally {
            db.close()
        }
    }
}
