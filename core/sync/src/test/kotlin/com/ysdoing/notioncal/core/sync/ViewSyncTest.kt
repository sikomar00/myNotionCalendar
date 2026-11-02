package com.ysdoing.notioncal.core.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ysdoing.notioncal.core.data.WidgetRefresher
import com.ysdoing.notioncal.core.data.db.NotionCalDatabase
import com.ysdoing.notioncal.core.data.db.VIEW_CELL_SEPARATOR
import com.ysdoing.notioncal.core.data.db.VIEW_ERROR_NOT_FOUND
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
    private var viewProperties = listOf(ViewPropertyDto("a", "Ctgr"), ViewPropertyDto("b", "Hidden", visible = false), ViewPropertyDto("c", "Name"))
    private val crashes = mutableSetOf<String>()
    private val removeDuringFetch = mutableSetOf<String>()
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
            if (viewId in crashes) throw IllegalStateException("boom") // 노션 오류가 아닌 예상 밖 예외(직렬화·Room 등)
            if (viewId in removeDuringFetch) db.viewDao().remove(viewId) // 받는 사이 사용자가 보기 등록을 지운 경우
            failures[viewId]?.let { throw NotionException(it) }
            return ViewDto(
                id = viewId,
                name = "할 일 $viewId",
                type = "table",
                configuration = ViewConfigDto(viewProperties),
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
    fun savesVisibleColumnsWithTitleFirstAndRequestsWidgetUpdate() = runTest {
        // 노션 API의 열 순서는 제목이 앞이 아닐 수 있다(실기기: Ctgr·Dday·Time·Name·Pri.·Date). 위젯은 폭이 좁으면 뒤 열부터 줄이고
        // 목록 모드는 첫 열을 제목 줄로 쓰므로, 제목을 항상 첫 열로 두고 나머지는 보기의 순서를 따른다.
        register("v1")
        sync().syncAll()
        val view = db.viewDao().all().single()
        assertEquals("할 일 v1", view.name)
        assertEquals(listOf("Name", "Ctgr"), view.columns.split(VIEW_CELL_SEPARATOR))
        assertEquals(clock.millis(), view.syncedAt)
        assertNull(view.lastError)
        assertEquals(listOf("첫째${VIEW_CELL_SEPARATOR}집", "둘째${VIEW_CELL_SEPARATOR}일"), db.viewDao().rows("v1").map { it.cells })
        assertEquals(1, widgetUpdates)
    }

    @Test
    fun titleHiddenInTheViewStaysHidden() = runTest {
        // 보기에서 제목을 숨겼다면(visible=false) 그 선택을 따른다. 제목을 억지로 넣지 않는다.
        viewProperties = listOf(ViewPropertyDto("a", "Ctgr"), ViewPropertyDto("c", "Name", visible = false))
        register("v1")
        sync().syncAll()
        assertEquals(listOf("Ctgr"), db.viewDao().all().single().columns.split(VIEW_CELL_SEPARATOR))
    }

    @Test
    fun titleMissingFromTheViewSettingsIsAddedFirst() = runTest {
        // 보드·갤러리·목록 보기는 카드 제목이 항상 보여서 설정의 열 목록에 제목이 없을 수 있다. 제목을 넣지 않으면
        // 위젯이 값이 빈 다른 속성만 그려 행이 통째로 빈다(실기기 Reading 보기). 설정에 아예 없으면 첫 열로 넣는다.
        viewProperties = listOf(ViewPropertyDto("a", "Ctgr"))
        register("v1")
        sync().syncAll()
        assertEquals(listOf("Name", "Ctgr"), db.viewDao().all().single().columns.split(VIEW_CELL_SEPARATOR))
    }

    @Test
    fun noVisibleColumnsFallsBackToTheTitleOnly() = runTest {
        viewProperties = emptyList()
        register("v1")
        sync().syncAll()
        assertEquals(listOf("Name"), db.viewDao().all().single().columns.split(VIEW_CELL_SEPARATOR))
    }

    @Test
    fun titleAlreadyFirstKeepsViewOrder() = runTest {
        viewProperties = listOf(ViewPropertyDto("c", "Name"), ViewPropertyDto("a", "Ctgr"))
        register("v1")
        sync().syncAll()
        assertEquals(listOf("Name", "Ctgr"), db.viewDao().all().single().columns.split(VIEW_CELL_SEPARATOR))
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
    fun deletedViewIsRecordedAsNotFoundAndKeepsRows() = runTest {
        // VW-06: 노션에서 보기를 지우면(404) 위젯이 '찾을 수 없음'을 알릴 수 있게 같은 이름으로 남기고, 이전 행은 지우지 않는다.
        register("v1")
        sync().syncAll()
        failures["v1"] = NotionError.NotShared
        sync().syncAll()
        assertEquals(VIEW_ERROR_NOT_FOUND, db.viewDao().all().single().lastError)
        assertEquals(2, db.viewDao().rows("v1").size)
    }

    @Test
    fun errorNamesAreStableStringsNotClassNames() = runTest {
        // 릴리스(R8)는 클래스 이름을 바꾸므로 클래스 이름이 아니라 정해 둔 문자열로 남겨야 한다. 위젯의 '찾을 수 없음' 판단이 이 값에 달려 있다.
        register("v1")
        val expected = mapOf<NotionError, String>(
            NotionError.NotShared to VIEW_ERROR_NOT_FOUND,
            NotionError.Unauthorized to "Unauthorized",
            NotionError.Validation(null) to "Validation",
            NotionError.RateLimited to "RateLimited",
            NotionError.Network to "Network",
            NotionError.Server(503) to "Server",
        )
        expected.forEach { (error, name) ->
            failures["v1"] = error
            sync().syncAll()
            assertEquals(name, db.viewDao().all().single().lastError)
        }
    }

    @Test
    fun unexpectedErrorInOneViewDoesNotStopTheRest() = runTest {
        // SyncWorker는 이 호출을 막지 않는다. 노션 오류가 아닌 예외도 그 보기만 실패로 남기고 다른 보기·동기화 작업은 계속한다.
        register("v1", "v2")
        crashes += "v1"
        sync().syncAll()
        assertEquals("Unexpected", db.viewDao().all().first { it.viewId == "v1" }.lastError)
        assertEquals(2, db.viewDao().rows("v2").size)
        assertEquals(1, widgetUpdates)
    }

    @Test
    fun rateLimitOrServerOutageStopsBeforeOtherViews() = runTest {
        // 429·5xx는 보기마다 똑같이 실패하므로 남은 보기를 더 부르지 않는다(속도 제한을 키우지 않는다).
        register("v1", "v2")
        listOf(NotionError.RateLimited, NotionError.Server(503)).forEach { error ->
            requested.clear()
            failures["v1"] = error
            failures["v2"] = error
            sync().syncAll()
            assertEquals(error.toString(), 1, requested.size)
        }
    }

    @Test
    fun viewDeletedWhileSyncingStaysDeleted() = runTest {
        register("v1")
        removeDuringFetch += "v1"
        sync().syncAll()
        assertEquals(emptyList<String>(), db.viewDao().all().map { it.viewId })
        assertEquals(0, db.viewDao().rows("v1").size)
    }

    @Test
    fun syncingOneViewLeavesOthersAlone() = runTest {
        // 등록 화면은 방금 등록한 보기 하나만 받는다. 다른 보기를 기다리게 하지 않는다.
        register("v1", "v2")
        sync().sync("v1")
        assertEquals(listOf("v1"), requested)
        assertEquals(2, db.viewDao().rows("v1").size)
        assertEquals(0, db.viewDao().rows("v2").size)
        assertEquals(1, widgetUpdates)
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
