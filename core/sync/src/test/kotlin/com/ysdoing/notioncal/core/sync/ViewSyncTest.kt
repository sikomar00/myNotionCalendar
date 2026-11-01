package com.ysdoing.notioncal.core.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ysdoing.notioncal.core.data.WidgetRefresher
import com.ysdoing.notioncal.core.data.db.NotionCalDatabase
import com.ysdoing.notioncal.core.data.db.VIEW_CELL_SEPARATOR
import com.ysdoing.notioncal.core.data.db.ViewSnapshotEntity
import com.ysdoing.notioncal.core.notion.NotionError
import com.ysdoing.notioncal.core.notion.NotionException
import com.ysdoing.notioncal.core.notion.ViewReader
import com.ysdoing.notioncal.core.notion.dto.PageDto
import com.ysdoing.notioncal.core.notion.dto.ViewConfigDto
import com.ysdoing.notioncal.core.notion.dto.ViewDto
import com.ysdoing.notioncal.core.notion.dto.ViewPropertyDto
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** VW-01 보기 스냅샷: 보이는 열만 순서대로 글자로 저장하고, 실패하면 이전 스냅샷을 남기며, 연결 문제면 멈춘다. */
@RunWith(RobolectricTestRunner::class)
class ViewSyncTest {
    private lateinit var db: NotionCalDatabase
    private val clock = Clock.fixed(Instant.parse("2026-10-22T03:00:00Z"), ZoneOffset.UTC)
    private val failures = mutableMapOf<String, NotionError>()
    private val requested = mutableListOf<String>()
    private var widgetUpdates = 0

    private fun page(id: String, title: String, ctgr: String) = PageDto(
        id = id,
        url = "https://notion.so/$id",
        lastEditedTime = "2026-10-22T00:00:00.000Z",
        properties = Json.parseToJsonElement(
            """{"Name":{"type":"title","title":[{"plain_text":"$title"}]},
                "Ctgr":{"type":"select","select":{"name":"$ctgr"}},
                "Hidden":{"type":"checkbox","checkbox":true}}""",
        ).jsonObject,
    )

    private val reader = object : ViewReader {
        override suspend fun viewIds(dataSourceId: String) = emptyList<String>()

        override suspend fun view(viewId: String): ViewDto {
            requested += viewId
            failures[viewId]?.let { throw NotionException(it) }
            return ViewDto(
                id = viewId,
                name = "할 일 $viewId",
                type = "table",
                configuration = ViewConfigDto(
                    listOf(ViewPropertyDto("a", "Ctgr"), ViewPropertyDto("b", "Hidden", visible = false), ViewPropertyDto("c", "Name")),
                ),
            )
        }

        override suspend fun rows(dataSourceId: String, view: ViewDto, limit: Int) =
            listOf(page("p1", "첫째", "집"), page("p2", "둘째", "일"))
    }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), NotionCalDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    private suspend fun register(vararg ids: String) =
        ids.forEach { db.viewDao().upsert(ViewSnapshotEntity(it, "ds", "이름", "table")) }

    private fun sync() = ViewSync(reader, db.viewDao(), { widgetUpdates++ }, clock)

    @Test
    fun savesVisibleColumnsInViewOrderAndRequestsWidgetUpdate() = runTest {
        register("v1")
        sync().syncAll()
        val view = db.viewDao().all().single()
        assertEquals("할 일 v1", view.name)
        assertEquals(listOf("Ctgr", "Name"), view.columns.split(VIEW_CELL_SEPARATOR))
        assertEquals(clock.millis(), view.syncedAt)
        assertNull(view.lastError)
        assertEquals(listOf("집${VIEW_CELL_SEPARATOR}첫째", "일${VIEW_CELL_SEPARATOR}둘째"), db.viewDao().rows("v1").map { it.cells })
        assertEquals(1, widgetUpdates)
    }

    @Test
    fun failedViewKeepsPreviousSnapshotAndRecordsError() = runTest {
        register("v1")
        sync().syncAll()
        failures["v1"] = NotionError.Validation(null)
        sync().syncAll()
        assertEquals("Validation", db.viewDao().all().single().lastError)
        assertEquals(2, db.viewDao().rows("v1").size)
    }

    @Test
    fun connectionProblemStopsBeforeOtherViews() = runTest {
        register("v1", "v2")
        failures["v1"] = NotionError.Network
        failures["v2"] = NotionError.Network
        sync().syncAll()
        assertEquals(1, requested.size)
    }

    @Test
    fun otherViewsContinueAfterOneBrokenView() = runTest {
        register("v1", "v2")
        failures["v1"] = NotionError.NotShared
        sync().syncAll()
        assertEquals(2, requested.size)
        assertEquals(2, db.viewDao().rows("v2").size)
    }
}
