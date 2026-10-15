package com.ysdoing.notioncal.core.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ysdoing.notioncal.core.data.db.DataSourceEntity
import com.ysdoing.notioncal.core.data.db.EventEntity
import com.ysdoing.notioncal.core.data.db.NotionCalDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

/** EX-11: DB별로 고른 속성·옵션(예: 우선순위 '1st')이면 일정을 강조하고, 강조 칸의 글자색은 배경과 대비된다. */
@RunWith(RobolectricTestRunner::class)
class EmphasisTest {
    private lateinit var db: NotionCalDatabase
    private val day = LocalDate.of(2026, 10, 16)

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), NotionCalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = db.close()

    private fun event(id: String, priority: String?, done: Boolean = false) = EventEntity(
        pageId = id, dataSourceId = "ds", title = id, startDate = day, endDate = null, startTime = null, endTime = null,
        isAllDay = true, done = done, colorKey = null, sortKey = null, url = "", lastEditedTime = 0,
        extraValues = encodeExtraValues(mapOf("pri" to PropValue(optionIds = listOfNotNull(priority)), "ctgr" to PropValue(optionIds = listOf("first")))),
    )

    @Test
    fun emphasizesChosenOptionOnly() = runTest {
        db.dataSourceDao().upsert(
            DataSourceEntity(
                dataSourceId = "ds", databaseId = "db", name = "Calendar", colorHex = "#0B6E99", titlePropId = "title",
                datePropId = "d", donePropId = null, doneType = null, doneOption = null, undoneOption = null, colorPropId = null, sortPropId = null,
            ),
        )
        db.eventDao().upsert(listOf(event("top", "first"), event("plain", null), event("finished", "first", done = true)))
        val repo = EventRepository(db.eventDao())
        fun byId(list: List<CalendarEvent>) = list.associateBy { it.pageId }

        // 강조를 고르기 전에는 아무것도 강조하지 않는다.
        assertTrue(repo.observeRange(day, day).first().none { it.emphasized })

        db.dataSourceDao().setEmphasis("ds", "pri", encodeIdList(listOf("first")))
        val events = byId(repo.observeRange(day, day).first())
        assertTrue(events.getValue("top").showsEmphasis)
        assertFalse(events.getValue("plain").emphasized)
        // 다른 속성(ctgr)에 같은 옵션 ID가 있어도 강조 속성만 본다. 완료한 일정은 강조해 그리지 않는다.
        assertTrue(events.getValue("finished").emphasized)
        assertFalse(events.getValue("finished").showsEmphasis)

        db.dataSourceDao().setEmphasis("ds", null, null)
        assertTrue(repo.observeRange(day, day).first().none { it.emphasized })
    }

    @Test
    fun onColorContrastsWithBackground() {
        val dark = 0xFF191919.toInt()
        val white = 0xFFFFFFFF.toInt()
        assertEquals(dark, NotionColors.onColor("#DFAB01")) // 노랑(Event) 위에는 먹색
        assertEquals(dark, NotionColors.onColor("#F2C94C"))
        assertEquals(white, NotionColors.onColor("#0B6E99")) // 파랑(Learn) 위에는 흰색
        assertEquals(white, NotionColors.onColor("#E03E3E")) // 빨강(Habit)
        assertEquals(white, NotionColors.onColor("#6940A5"))
    }

    @Test
    fun malformedValuesAreNotEmphasized() {
        assertFalse(isEmphasized(null, "pri", "[\"first\"]"))
        assertFalse(isEmphasized("{bad json", "pri", "[\"first\"]"))
        assertFalse(isEmphasized(encodeExtraValues(mapOf("pri" to PropValue(optionIds = listOf("first")))), "pri", "[]"))
    }
}
