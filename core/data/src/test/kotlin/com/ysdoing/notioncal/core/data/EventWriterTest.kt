package com.ysdoing.notioncal.core.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ysdoing.notioncal.core.data.db.DataSourceEntity
import com.ysdoing.notioncal.core.data.db.EventEntity
import com.ysdoing.notioncal.core.data.db.NotionCalDatabase
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
import java.time.ZoneOffset

/** NS-04: 쓰기는 Room에 먼저 반영하고 아웃박스 작업을 남긴다. 위젯 갱신·전송 요청도 함께 보낸다. */
@RunWith(RobolectricTestRunner::class)
class EventWriterTest {
    private lateinit var db: NotionCalDatabase
    private lateinit var writer: EventWriter
    private var widgetRequests = 0
    private var outboxRequests = 0

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), NotionCalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        writer = EventWriter(
            db, Clock.fixed(Instant.parse("2026-10-13T03:00:00Z"), ZoneOffset.UTC),
            { widgetRequests++ }, { outboxRequests++ },
        )
    }

    @After
    fun tearDown() = db.close()

    private suspend fun seed() {
        db.dataSourceDao().upsert(
            DataSourceEntity(
                dataSourceId = "ds", databaseId = "db", name = "Calendar", colorHex = "#0B6E99", titlePropId = "title",
                datePropId = "d", donePropId = "done", doneType = "CHECKBOX", doneOption = null, undoneOption = null,
                colorPropId = "c", sortPropId = null,
                colorOptions = """[{"id":"opt-learn","name":"Learn","color":"blue"},{"id":"opt-habit","name":"Habit","color":"red"}]""",
            ),
        )
        db.eventDao().upsert(
            listOf(
                EventEntity(
                    pageId = "p1", dataSourceId = "ds", title = "영어 스터디", startDate = LocalDate.of(2026, 10, 13),
                    endDate = null, startTime = null, endTime = null, isAllDay = true, done = false, colorKey = "blue",
                    sortKey = null, url = "https://www.notion.so/p1", lastEditedTime = 1000, colorOptionId = "opt-learn",
                ),
            ),
        )
    }

    @Test
    fun createShowsImmediatelyWithTempIdAndQueuesCreate() = runTest {
        seed()
        val id = writer.create("ds", "  새 일정 ", DateValue.allDay(LocalDate.of(2026, 10, 20)))

        assertTrue(id.startsWith(EventWriter.TEMP_PREFIX))
        val event = db.eventDao().get(id)!!
        assertEquals("새 일정", event.title)
        assertEquals(LocalDate.of(2026, 10, 20), event.startDate)
        assertEquals(1, event.pendingOps)
        val row = db.outboxDao().all().single()
        assertEquals("CREATE", row.op)
        assertEquals(1, widgetRequests)
        assertEquals(1, outboxRequests)
    }

    @Test
    fun updateKeepsBaseValuesForConflictCheck() = runTest {
        seed()
        writer.update("p1", EventChange(title = "영어 회화", select = SelectValue("opt-habit")))

        val event = db.eventDao().get("p1")!!
        assertEquals("영어 회화", event.title)
        assertEquals("red", event.colorKey) // 옵션 목록에서 색을 찾아 바로 반영
        assertEquals(1, event.pendingOps)
        val row = db.outboxDao().all().single()
        assertEquals("UPDATE", row.op)
        assertEquals(1000L, row.baseLastEdited)
        val base = OutboxJson.decodeFromString(EventChange.serializer(), row.baseValues!!)
        assertEquals(EventChange(title = "영어 스터디", select = SelectValue("opt-learn")), base)
    }

    @Test
    fun editingUnsentCreateMergesIntoCreate() = runTest {
        seed()
        val id = writer.create("ds", "초안", DateValue.allDay(LocalDate.of(2026, 10, 20)))
        writer.update(id, EventChange(title = "최종"))
        writer.setDone(id, true)

        val row = db.outboxDao().all().single()
        assertEquals("CREATE", row.op)
        val change = OutboxJson.decodeFromString(EventChange.serializer(), row.changes)
        assertEquals("최종", change.title)
        assertEquals(true, change.done)
    }

    @Test
    fun trashHidesLocallyAndKeepsSnapshot() = runTest {
        seed()
        writer.trash("p1")
        assertNull(db.eventDao().get("p1"))
        val row = db.outboxDao().all().single()
        assertEquals("TRASH", row.op)
        assertEquals("영어 스터디", OutboxJson.decodeFromString(EventSnapshot.serializer(), row.baseValues!!).title)
        assertEquals(listOf("p1"), db.outboxDao().pageIds())
    }

    @Test
    fun trashingUnsentCreateDropsEverything() = runTest {
        seed()
        val id = writer.create("ds", "취소할 일정", DateValue.allDay(LocalDate.of(2026, 10, 20)))
        writer.trash(id)
        assertNull(db.eventDao().get(id))
        assertTrue(db.outboxDao().all().isEmpty())
    }

    @Test
    fun timedDateSetsAllDayFalse() = runTest {
        seed()
        val start = Instant.parse("2026-10-13T06:00:00Z").toEpochMilli()
        writer.update("p1", EventChange(date = DateValue("2026-10-13", startTime = start, endTime = start + 3_600_000)))
        val event = db.eventDao().get("p1")!!
        assertEquals(false, event.isAllDay)
        assertEquals(start, event.startTime)
    }
}
