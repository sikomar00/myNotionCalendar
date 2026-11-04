package com.ysdoing.notioncal.core.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ysdoing.notioncal.core.data.WidgetRefresher
import com.ysdoing.notioncal.core.data.db.NotionCalDatabase
import com.ysdoing.notioncal.core.data.db.VIEW_CELL_SEPARATOR
import com.ysdoing.notioncal.core.data.db.VIEW_ERROR_NOT_FOUND
import com.ysdoing.notioncal.core.data.db.ViewSnapshotEntity
import com.ysdoing.notioncal.core.data.db.ViewWriteEntity
import com.ysdoing.notioncal.core.data.view.CoverStore
import com.ysdoing.notioncal.core.data.view.ViewColumn
import com.ysdoing.notioncal.core.data.view.ViewJson
import kotlinx.serialization.json.JsonPrimitive
import com.ysdoing.notioncal.core.notion.NotionError
import com.ysdoing.notioncal.core.notion.NotionException
import com.ysdoing.notioncal.core.notion.NotionJson
import com.ysdoing.notioncal.core.notion.ViewReader
import com.ysdoing.notioncal.core.notion.dto.DataSourceDto
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import kotlinx.coroutines.flow.first
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

    /** M3: 쓰기 정보(종류·선택지)를 읽을 스키마와, 행을 바꿔 끼울 수 있는 페이지 목록. */
    private var schema = DataSourceDto(id = "ds")
    private var schemaCalls = 0
    private var rowsMore = false
    private var viewRaw: kotlinx.serialization.json.JsonObject = kotlinx.serialization.json.JsonObject(emptyMap())
    private var viewType = "table"
    private var viewFilter: kotlinx.serialization.json.JsonObject? = null
    private var schemaError: NotionError? = null
    private var rowPages: List<PageDto>? = null

    private val reader = object : ViewReader {
        override suspend fun viewIds(dataSourceId: String) = emptyList<String>()

        override suspend fun schema(dataSourceId: String): DataSourceDto {
            schemaCalls++
            schemaError?.let { throw NotionException(it) }
            return schema
        }

        override suspend fun view(viewId: String): ViewDto {
            requested += viewId
            if (viewId in crashes) throw IllegalStateException("boom") // 노션 오류가 아닌 예상 밖 예외(직렬화·Room 등)
            if (viewId in removeDuringFetch) db.viewDao().remove(viewId) // 받는 사이 사용자가 보기 등록을 지운 경우
            failures[viewId]?.let { throw NotionException(it) }
            return ViewDto(
                id = viewId,
                name = "할 일 $viewId",
                type = viewType,
                filter = viewFilter,
                configuration = ViewConfigDto(viewProperties, viewRaw),
            )
        }

        override suspend fun viewRows(dataSourceId: String, view: ViewDto, limit: Int) =
            com.ysdoing.notioncal.core.notion.ViewRows(rows(dataSourceId, view, limit), hasMore = rowsMore)

        override suspend fun rows(dataSourceId: String, view: ViewDto, limit: Int) =
            rowPages ?: listOf(page("p1", "첫째", "집"), page("p2", "둘째", "일"))
    }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), NotionCalDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        db.close()
        coverDir.deleteRecursively()
    }

    private suspend fun register(vararg ids: String) =
        ids.forEach { db.viewDao().upsert(ViewSnapshotEntity(it, "ds", "이름", "table")) }

    private val coverDir = java.nio.file.Files.createTempDirectory("covers").toFile()
    private var coverBytes: ByteArray? = null
    private val coverRequests = mutableListOf<String>()
    private val covers = CoverSync(CoverStore(coverDir, pruneGraceMillis = 0), CoverDownload { url -> coverRequests += url; coverBytes })

    private fun sync() = ViewSync(reader, db.viewDao(), { widgetUpdates++ }, clock, covers)

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

    // --- M3: 위젯에서 값을 되쓰기 위한 정보를 함께 저장한다.

    private val writableSchema = Json { ignoreUnknownKeys = true }.decodeFromString(
        DataSourceDto.serializer(),
        """{"id":"ds","properties":{
            "Name":{"id":"title","name":"Name","type":"title"},
            "Done":{"id":"dn","name":"Done","type":"checkbox"},
            "Status":{"id":"st","name":"Status","type":"status","status":{
                "options":[{"id":"o1","name":"할 일"},{"id":"o2","name":"진행 중"},{"id":"o3","name":"완료"}],
                "groups":[{"id":"g1","name":"To-do","option_ids":["o1"]},{"id":"g3","name":"Complete","option_ids":["o3"]}]}},
            "Pages":{"id":"pg","name":"Pages","type":"number"},
            "When":{"id":"wh","name":"When","type":"date"},
            "Note":{"id":"nt","name":"Note","type":"rich_text"}}}""",
    )

    private fun richPage(id: String, done: Boolean, status: String, pages: String) = PageDto(
        id = id,
        url = "https://notion.so/$id",
        lastEditedTime = "2026-10-22T00:00:00.000Z",
        properties = Json.parseToJsonElement(
            """{"Name":{"type":"title","title":[{"plain_text":"$id"}]},
                "Done":{"type":"checkbox","checkbox":$done},
                "Status":{"type":"status","status":{"id":"$status","name":"x"}},
                "Pages":{"type":"number","number":$pages},
                "Note":{"type":"rich_text","rich_text":[{"plain_text":"메모"}]}}""",
        ).jsonObject,
    )

    @Test
    fun savesWriteInfoAndTypedValuesForWritableColumnsOnly() = runTest {
        viewProperties = listOf("Name", "Done", "Status", "Pages", "When", "Note").mapIndexed { i, name -> ViewPropertyDto("p$i", name) }
        schema = writableSchema
        rowPages = listOf(richPage("a", true, "o2", "12"), richPage("b", false, "o3", "null"))
        register("v1")
        sync().syncAll()
        val columns = ViewJson.columns(db.viewDao().all().single().columnInfo)
        // 바꿀 수 있는 종류(제목·체크·상태·선택·숫자·날짜·메모 등, Phase 10-5). 보이는 열 순서대로, 스키마에 있는 것만.
        assertEquals(
            listOf("Name" to "title", "Done" to "checkbox", "Status" to "status", "Pages" to "number", "When" to "date", "Note" to "rich_text"),
            columns.map { it.name to it.type },
        )
        val status = columns.single { it.id == "st" }
        assertEquals(listOf("o1" to "To-do", "o3" to "Complete", "o2" to null), status.options.map { it.id to it.group }) // 그룹 순서, 그룹 없는 옵션은 뒤
        val rows = db.viewDao().rows("v1")
        // 원래 값은 바꿀 수 있는 속성만 담는다(값이 없는 날짜는 JsonNull, 메모는 글자).
        assertEquals(
            mapOf(
                "title" to JsonPrimitive("a"), "dn" to JsonPrimitive(true), "st" to JsonPrimitive("o2"), "pg" to JsonPrimitive(12.0),
                "wh" to kotlinx.serialization.json.JsonNull, "nt" to JsonPrimitive("메모"),
            ),
            ViewJson.typed(rows[0].typed),
        )
        assertEquals(JsonPrimitive(false), ViewJson.typed(rows[1].typed)["dn"])
        assertEquals("o3", (ViewJson.typed(rows[1].typed)["st"] as JsonPrimitive).content)
        assertEquals(kotlinx.serialization.json.JsonNull, ViewJson.typed(rows[1].typed)["pg"]) // 값이 비어 있다
        // 표시용 글자는 그대로다(날짜 열은 이 행에 값이 없어 빈 칸).
        assertEquals("a${VIEW_CELL_SEPARATOR}✓${VIEW_CELL_SEPARATOR}x${VIEW_CELL_SEPARATOR}12${VIEW_CELL_SEPARATOR}${VIEW_CELL_SEPARATOR}메모", rows[0].cells)
    }

    @Test
    fun theBoardGroupPropertyIsKeptEvenWhenItIsHiddenInTheView() = runTest {
        // Phase 11a: 보드의 group_by 속성은 카드에 안 보여도 행의 값(typed)과 옵션을 알아야 열로 나눌 수 있다. 보이지 않아도 'group' 역할로 담는다.
        schema = Json { ignoreUnknownKeys = true }.decodeFromString(
            DataSourceDto.serializer(),
            """{"id":"ds","properties":{
                "Name":{"id":"title","name":"Name","type":"title"},
                "Status":{"id":"st","name":"Status","type":"status","status":{"options":[{"id":"o1","name":"할 일","color":"gray"},{"id":"o2","name":"완료","color":"green"}],"groups":[]}}}}""",
        )
        viewProperties = listOf(ViewPropertyDto("title", "Name")) // Status는 카드에 보이지 않는다
        viewType = "board"
        viewRaw = Json.parseToJsonElement("""{"type":"board","group_by":{"property_id":"st","type":"status"}}""").jsonObject
        rowPages = listOf(
            PageDto(
                id = "a", url = "u", lastEditedTime = "2026-10-22T00:00:00.000Z",
                properties = Json.parseToJsonElement("""{"Name":{"type":"title","title":[{"plain_text":"a"}]},"Status":{"type":"status","status":{"id":"o2","name":"완료"}}}""").jsonObject,
            ),
        )
        register("v1")
        sync().syncAll()
        val columns = ViewJson.columns(db.viewDao().all().single().columnInfo)
        assertEquals(listOf("Name" to "", "Status" to ViewColumn.ROLE_GROUP), columns.map { it.name to it.role })
        assertEquals(JsonPrimitive("o2"), ViewJson.typed(db.viewDao().rows("v1").single().typed)["st"])
        assertEquals(listOf("할 일" to "gray", "완료" to "green"), columns.single { it.role == ViewColumn.ROLE_GROUP }.options.map { it.name to it.color })
    }

    @Test
    fun aGroupedTableGetsNoHiddenGroupColumn() = runTest {
        // 노션 표·목록도 group_by를 갖는다. 보드가 아니면 숨은 선택 열을 만들지 않는다(눌림 열로 세어져 모든 행이 높아지므로).
        schema = Json { ignoreUnknownKeys = true }.decodeFromString(
            DataSourceDto.serializer(),
            """{"id":"ds","properties":{
                "Name":{"id":"title","name":"Name","type":"title"},
                "Status":{"id":"st","name":"Status","type":"status","status":{"options":[{"id":"o1","name":"할 일","color":"gray"}],"groups":[]}}}}""",
        )
        viewProperties = listOf(ViewPropertyDto("title", "Name"))
        viewRaw = Json.parseToJsonElement("""{"type":"table","group_by":{"property_id":"st","type":"status"}}""").jsonObject
        register("v1")
        sync().syncAll()
        val columns = ViewJson.columns(db.viewDao().all().single().columnInfo)
        assertEquals(listOf("Name"), columns.map { it.name })
    }

    @Test
    fun theGroupByShapeIsReadFromAnObjectOrAPlainPropertyId() {
        val schema = Json { ignoreUnknownKeys = true }.decodeFromString(
            DataSourceDto.serializer(),
            """{"id":"ds","properties":{"Status":{"id":"st","name":"Status","type":"status"},"Memo":{"id":"mm","name":"Memo","type":"rich_text"}}}""",
        )
        fun group(config: String) = withGroupRole(emptyList(), schema, Json.parseToJsonElement(config).jsonObject).map { it.id to it.role }
        assertEquals(listOf("st" to ViewColumn.ROLE_GROUP), group("""{"group_by":{"property_id":"st"}}"""))
        assertEquals(listOf("st" to ViewColumn.ROLE_GROUP), group("""{"group_by":"st"}"""))
        assertEquals(emptyList<Pair<String, String>>(), group("""{"group_by":{"property_id":"mm"}}""")) // 선택·상태만 열로 나눈다
        assertEquals(emptyList<Pair<String, String>>(), group("""{"type":"table"}"""))
    }

    @Test
    fun columnInfoCoversEveryVisiblePropertyWithOptionColors() = runTest {
        // Phase 11a: 읽기 전용 유형(수식·롤업)도 보이는 열이면 쓰기 정보에 담아 편집 시트가 잠금 줄로 보여 주고, 옵션은 색을 함께 담는다.
        schema = Json { ignoreUnknownKeys = true }.decodeFromString(
            DataSourceDto.serializer(),
            """{"id":"ds","properties":{
                "Name":{"id":"title","name":"Name","type":"title"},
                "Calc":{"id":"fm","name":"Calc","type":"formula","formula":{"expression":"1"}},
                "Sum":{"id":"ru","name":"Sum","type":"rollup","rollup":{"function":"sum"}},
                "Ctgr":{"id":"ct","name":"Ctgr","type":"select","select":{"options":[{"id":"a","name":"집","color":"blue"},{"id":"b","name":"회사","color":"green"}]}}}}""",
        )
        viewProperties = listOf("Name", "Calc", "Sum", "Ctgr").mapIndexed { i, name -> ViewPropertyDto("p$i", name) }
        rowPages = listOf(
            PageDto(
                id = "a", url = "u", lastEditedTime = "2026-10-22T00:00:00.000Z",
                properties = Json.parseToJsonElement(
                    """{"Name":{"type":"title","title":[{"plain_text":"a"}]},"Calc":{"type":"formula","formula":{"type":"number","number":2}},
                        "Sum":{"type":"rollup","rollup":{"type":"number","number":3,"function":"sum"}},"Ctgr":{"type":"select","select":{"id":"a","name":"집"}}}""",
                ).jsonObject,
            ),
        )
        register("v1")
        sync().syncAll()
        val columns = ViewJson.columns(db.viewDao().all().single().columnInfo)
        assertEquals(listOf("Name" to true, "Calc" to false, "Sum" to false, "Ctgr" to true), columns.map { it.name to it.writable })
        assertEquals(listOf("집" to "blue", "회사" to "green"), columns.single { it.name == "Ctgr" }.options.map { it.name to it.color })
        // 원래 값은 쓸 수 있는 열만 담는다.
        assertEquals(setOf("title", "ct"), ViewJson.typed(db.viewDao().rows("v1").single().typed).keys)
    }

    @Test
    fun readsTheTypedValuesOfMultiSelectDateAndUrl() = runTest {
        val tagsSchema = Json { ignoreUnknownKeys = true }.decodeFromString(
            DataSourceDto.serializer(),
            """{"id":"ds","properties":{
                "Name":{"id":"title","name":"Name","type":"title"},
                "Tags":{"id":"tg","name":"Tags","type":"multi_select","multi_select":{"options":[{"id":"a","name":"집"},{"id":"b","name":"회사"}]}},
                "When":{"id":"wh","name":"When","type":"date"},
                "Link":{"id":"ur","name":"Link","type":"url"}}}""",
        )
        schema = tagsSchema
        viewProperties = listOf("Name", "Tags", "When", "Link").mapIndexed { i, name -> ViewPropertyDto("p$i", name) }
        rowPages = listOf(
            PageDto(
                id = "a", url = "https://notion.so/a", lastEditedTime = "2026-10-22T00:00:00.000Z",
                properties = Json.parseToJsonElement(
                    """{"Name":{"type":"title","title":[{"plain_text":"a"}]},
                        "Tags":{"type":"multi_select","multi_select":[{"id":"a","name":"집"},{"id":"b","name":"회사"}]},
                        "When":{"type":"date","date":{"start":"2026-11-04","end":"2026-11-05"}},
                        "Link":{"type":"url","url":"https://a.b"}}""",
                ).jsonObject,
            ),
        )
        register("v1")
        sync().syncAll()
        val typed = ViewJson.typed(db.viewDao().rows("v1").single().typed)
        assertEquals(kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b"))), typed["tg"])
        assertEquals(
            kotlinx.serialization.json.buildJsonObject {
                put("start", JsonPrimitive("2026-11-04"))
                put("end", JsonPrimitive("2026-11-05"))
            },
            typed["wh"],
        )
        assertEquals(JsonPrimitive("https://a.b"), typed["ur"])
        assertEquals(listOf("집", "회사"), ViewJson.columns(db.viewDao().all().single().columnInfo).single { it.id == "tg" }.options.map { it.name }) // 새 옵션 이름 쓰기용
    }

    @Test
    fun aViewWithUnsentChangesIsNotReadUntilTheyAreSent() = runTest {
        register("v1")
        sync().syncAll()
        val row = db.viewDao().rows("v1").first()
        db.viewDao().enqueue(row, ViewWriteEntity(viewId = "v1", pageId = row.pageId, dataSourceId = "ds", propId = "dn", propType = "checkbox", value = "true", createdAt = 1))
        requested.clear()
        sync().syncAll()
        assertEquals(emptyList<String>(), requested) // 노션에 묻지도 않는다(속도 제한도 아낀다)
        assertEquals(row, db.viewDao().rows("v1").first())
    }

    @Test
    fun aReadStartedBeforeAWidgetChangeDoesNotOverwriteIt() = runTest {
        register("v1")
        sync().syncAll()
        val row = db.viewDao().rows("v1").first()
        // 위젯에서 이 읽기가 시작된 뒤에 바꿨고(시각이 더 늦다) 이미 노션에 보냈다 → 읽은 이전 값으로 덮지 않는다.
        val later = clock.millis() + 1_000
        db.viewDao().enqueue(row.copy(cells = "위젯에서 바꿈"), ViewWriteEntity(viewId = "v1", pageId = row.pageId, dataSourceId = "ds", propId = "dn", propType = "checkbox", value = "true", createdAt = later))
        db.viewDao().deleteWriteById(db.viewDao().firstWrite()!!.id)
        requested.clear()
        sync().syncAll()
        assertEquals(listOf("v1"), requested) // 노션에서는 받았지만
        assertEquals("위젯에서 바꿈", db.viewDao().rows("v1").first().cells) // 덮지 않았다
    }

    @Test
    fun readsTheSchemaOncePerDataSourceNotOncePerView() = runTest {
        viewProperties = listOf("Name", "Done").mapIndexed { i, name -> ViewPropertyDto("p$i", name) }
        schema = writableSchema
        rowPages = listOf(richPage("a", true, "o1", "1"))
        register("v1", "v2")
        sync().syncAll()
        assertEquals(1, schemaCalls)
    }

    @Test
    fun aSchemaFailureKeepsTheLastWriteInfoAndStillRefreshesRows() = runTest {
        viewProperties = listOf("Name", "Done", "Status").mapIndexed { i, name -> ViewPropertyDto("p$i", name) }
        schema = writableSchema
        rowPages = listOf(richPage("a", true, "o2", "1"))
        register("v1")
        sync().syncAll()
        val before = db.viewDao().all().single().columnInfo
        schemaError = NotionError.Network
        rowPages = listOf(richPage("a", false, "o3", "1"))
        sync().syncAll()
        val view = db.viewDao().all().single()
        assertEquals(before, view.columnInfo) // 쓰기 정보는 지난번 것을 쓴다
        assertNull(view.lastError) // 행은 받았으니 실패가 아니다
        val typed = ViewJson.typed(db.viewDao().rows("v1").single().typed)
        assertEquals(JsonPrimitive(false), typed["dn"]) // 새로 받은 값이 들어간다
        assertEquals("o3", (typed["st"] as JsonPrimitive).content)
    }

    @Test
    fun anEmptyBoardViewStillKnowsItsTitleSoItCanCreateItems() = runTest {
        // 보드·갤러리·목록 보기는 카드 제목을 열 목록에 넣지 않고, 행이 없으면 제목 이름을 행에서 알 수도 없다.
        viewProperties = listOf(ViewPropertyDto("a", "Done"))
        schema = writableSchema
        rowPages = emptyList()
        register("v1")
        sync().syncAll()
        val view = db.viewDao().all().single()
        assertEquals(listOf("Name", "Done"), view.columns.split(VIEW_CELL_SEPARATOR)) // 제목이 첫 열
        assertEquals("title", ViewJson.columns(view.columnInfo).first().type)
    }

    // --- M5 책 위젯: 표지 주소·읽은 비율은 보이는 열과 무관하게 속성에서 읽고, 표지를 받아 둔다.

    private fun bookPage(id: String, cover: String?, ing: Int, total: Int) = PageDto(
        id = id,
        url = "https://notion.so/$id",
        lastEditedTime = "2026-10-22T00:00:00.000Z",
        properties = Json.parseToJsonElement(
            """{"Name":{"type":"title","title":[{"plain_text":"책 $id"}]},
                "cover":{"type":"files","files":[${if (cover == null) "" else """{"type":"external","external":{"url":"$cover"}}"""}]},
                "ing":{"type":"number","number":$ing},"page":{"type":"number","number":$total}}""",
        ).jsonObject,
    )

    @Test
    fun rowsCarryTheCoverUrlAndProgressEvenWhenThoseColumnsAreHidden() = runTest {
        register("v1")
        rowPages = listOf(bookPage("b1", "https://img.example/1.jpg", 30, 300), bookPage("b2", null, 0, 100))
        sync().syncAll()
        val rows = db.viewDao().observeRows("v1").first()
        assertEquals("https://img.example/1.jpg", rows[0].coverUrl)
        assertEquals(0.1f, rows[0].progress!!, 0.0001f)
        assertEquals("", rows[1].coverUrl) // 표지 없음
        assertEquals(0f, rows[1].progress)
    }

    private val bookSchema = Json { ignoreUnknownKeys = true }.decodeFromString(
        DataSourceDto.serializer(),
        """{"id":"ds","properties":{
            "Name":{"id":"title","name":"Name","type":"title"},
            "cover":{"id":"cv","name":"cover","type":"files"},
            "ing":{"id":"ing","name":"ing","type":"number"},
            "page":{"id":"pg","name":"page","type":"number"}}}""",
    )

    @Test
    fun hiddenReadingNumbersAreTaggedForTheBookWidgetAndKeptInTheRows() = runTest {
        schema = bookSchema
        register("v1")
        rowPages = listOf(bookPage("b1", null, 30, 300))
        sync().syncAll()
        val columns = ViewJson.columns(db.viewDao().all().single().columnInfo)
        assertEquals(listOf("Name" to "", "ing" to ViewColumn.ROLE_PAGES_READ, "page" to ViewColumn.ROLE_PAGES_TOTAL), columns.map { it.name to it.role })
        assertEquals(
            mapOf("title" to JsonPrimitive("책 b1"), "ing" to JsonPrimitive(30.0), "pg" to JsonPrimitive(300.0)),
            ViewJson.typed(db.viewDao().rows("v1").single().typed),
        )
        // 스키마를 못 읽어도 지난번 표시는 남는다(숨긴 열이라 보이는 열 목록에 없어도 지우지 않는다).
        schemaError = NotionError.Network
        sync().syncAll()
        assertEquals(2, ViewJson.columns(db.viewDao().all().single().columnInfo).count { it.role.isNotEmpty() })
    }

    @Test
    fun readingRolesNeedAPairByNameAndLeaveOtherViewsAlone() {
        val none = withReadingRoles(listOf(ViewColumn("t", "Name", "title")), writableSchema) // Pages 하나뿐이라 짝이 아니다
        assertEquals(listOf(""), none.map { it.role })
        val onlyRead = Json { ignoreUnknownKeys = true }.decodeFromString(DataSourceDto.serializer(), """{"id":"ds","properties":{"ing":{"id":"ing","name":"ing","type":"number"}}}""")
        assertEquals(listOf(ViewColumn.ROLE_PAGES_READ), withReadingRoles(emptyList(), onlyRead).map { it.role }) // 전체 쪽은 몰라도 ±는 된다
        // 보기에서 보이는 ing·page는 새로 더하지 않고 표시만 단다(같은 열이 둘이 되지 않게).
        val visible = listOf(ViewColumn("ing", "ing", "number"), ViewColumn("pg", "page", "number"))
        assertEquals(listOf(ViewColumn.ROLE_PAGES_READ, ViewColumn.ROLE_PAGES_TOTAL), withReadingRoles(visible, bookSchema).map { it.role })
    }

    @Test
    fun savesTheWholeSchemaOfASourceAndKeepsItWhenItCannotBeReadLater() = runTest {
        schema = bookSchema
        register("v1", "v2") // 같은 소스의 보기가 둘이어도 스키마는 한 줄
        rowPages = listOf(bookPage("b1", null, 1, 10))
        sync().syncAll()
        val saved = db.viewDao().schema("ds")!!
        assertEquals(bookSchema, NotionJson.decodeFromString(DataSourceDto.serializer(), saved.schemaJson))
        schemaError = NotionError.Network // 못 읽으면 지난번 것을 그대로 둔다
        sync().syncAll()
        assertEquals(saved, db.viewDao().schema("ds"))
    }

    @Test
    fun removingTheLastViewOfASourceDropsItsSavedSchemaButNotWhileAnotherViewUsesIt() = runTest {
        schema = bookSchema
        register("v1", "v2")
        sync().syncAll()
        db.viewDao().remove("v1")
        assertTrue(db.viewDao().schema("ds") != null) // v2가 아직 쓴다
        db.viewDao().remove("v2")
        assertNull(db.viewDao().schema("ds"))
    }

    @Test
    fun syncContainingReadsOnlyTheViewsThatHoldThosePages() = runTest {
        register("v1", "v2", "v3")
        rowPages = listOf(bookPage("b1", null, 1, 10))
        sync().syncAll() // v1·v2·v3 모두 b1 행을 갖는다
        db.viewDao().replace(db.viewDao().snapshot("v3")!!, emptyList()) // v3는 b1을 갖지 않는다
        requested.clear()

        sync().syncContaining(listOf("b1", "없는 페이지"), emptyList())

        assertEquals(setOf("v1", "v2"), requested.toSet()) // 그 페이지가 든 보기만 다시 받는다(SY-06)
    }

    @Test
    fun syncContainingAlsoReadsEveryViewOfASourceForNewlyCreatedPages() = runTest {
        // 방금 만든 페이지는 아직 어느 보기 행에도 없다. 그 소스의 보기를 모두 다시 받아야 새 행이 보인다(SY-06).
        register("v1", "v2")
        rowPages = listOf(bookPage("b1", null, 1, 10))
        sync().syncAll()
        requested.clear()

        sync().syncContaining(emptyList(), listOf("ds", "다른 소스"))

        assertEquals(setOf("v1", "v2"), requested.toSet())
    }

    @Test
    fun namesAreResolvedFromTheSchemaWhenTheViewConfigurationOnlyHasPropertyIds() = runTest {
        // 실기기 실측(Phase 11a 스파이크): 갤러리 보기 설정의 properties에는 property_name이 없다. 이름 없는 열이 쌓이지 않게 스키마로 찾는다.
        schema = writableSchema
        viewProperties = listOf(ViewPropertyDto("title", ""), ViewPropertyDto("dn", ""), ViewPropertyDto("지워진-속성", ""))
        rowPages = listOf(richPage("a", true, "o1", "1"))
        register("v1")
        sync().syncAll()
        assertEquals(listOf("Name", "Done"), db.viewDao().all().single().columns.split(VIEW_CELL_SEPARATOR)) // 모르는 id는 뺀다
    }

    @Test
    fun savesTheViewConfigurationAndWhetherAllRowsWereReceived() = runTest {
        // Phase 11a: 보드의 group_by 같은 설정 원본을 저장하고, 행이 상한을 넘으면 '다 받지 못함'으로 표시한다(+N 대신 N+).
        viewRaw = Json.parseToJsonElement("""{"type":"board","group_by":{"property_id":"st"}}""").jsonObject
        rowsMore = true
        register("v1")
        sync().syncAll()
        val view = db.viewDao().snapshot("v1")!!
        assertEquals("st", Json.parseToJsonElement(view.viewConfig).jsonObject["group_by"]!!.jsonObject["property_id"]!!.let { (it as JsonPrimitive).content })
        assertFalse(view.rowsComplete)
        rowsMore = false
        viewRaw = kotlinx.serialization.json.JsonObject(emptyMap())
        sync().syncAll()
        assertTrue(db.viewDao().snapshot("v1")!!.rowsComplete)
        assertEquals("", db.viewDao().snapshot("v1")!!.viewConfig) // 설정이 없으면 빈 글자
    }

    @Test
    fun coversAreDownloadedOnceAndAFailureKeepsTheSnapshot() = runTest {
        register("v1")
        rowPages = listOf(bookPage("b1", "https://img.example/1.jpg", 1, 10))
        coverBytes = byteArrayOf(1, 2, 3)
        sync().syncAll()
        assertEquals(listOf("https://img.example/1.jpg"), coverRequests)
        assertTrue(CoverStore(coverDir).has("https://img.example/1.jpg"))
        sync().syncAll()
        assertEquals(1, coverRequests.size) // 받아 둔 표지는 다시 받지 않는다
        // 새 책의 표지를 못 받아도 보기 동기화는 성공이고 행은 그대로 들어간다
        coverBytes = null
        rowPages = listOf(bookPage("b1", "https://img.example/1.jpg", 1, 10), bookPage("b2", "https://img.example/2.jpg", 2, 10))
        sync().syncAll()
        assertEquals(2, db.viewDao().observeRows("v1").first().size)
        assertNull(db.viewDao().snapshot("v1")!!.lastError)
        // 못 받은 주소는 한동안 다시 두드리지 않는다
        val before = coverRequests.size
        sync().syncAll()
        assertEquals(before, coverRequests.size)
    }

    @Test
    fun coversAreFetchedAfterTheWidgetsWereToldTheDataChanged() = runTest {
        register("v1")
        rowPages = listOf(bookPage("b1", "https://img.example/1.jpg", 1, 10))
        coverBytes = byteArrayOf(1)
        var updatesWhenDownloading = -1
        val observing = CoverSync(CoverStore(coverDir, pruneGraceMillis = 0), CoverDownload { updatesWhenDownloading = widgetUpdates; coverBytes })
        ViewSync(reader, db.viewDao(), { widgetUpdates++ }, clock, observing).syncAll()
        // 느린 이미지 서버가 위젯 갱신을 붙잡지 않는다: 표지를 받기 전에 이미 한 번 갱신했고, 새로 받았으면 한 번 더 갱신한다.
        assertEquals(1, updatesWhenDownloading)
        assertEquals(2, widgetUpdates)
    }

    @Test
    fun syncAllPrunesCoversNoViewUsesAnymore() = runTest {
        register("v1")
        rowPages = listOf(bookPage("b1", "https://img.example/1.jpg", 1, 10))
        coverBytes = byteArrayOf(1)
        sync().syncAll()
        rowPages = listOf(bookPage("b2", "https://img.example/2.jpg", 1, 10))
        sync().syncAll()
        assertFalse(CoverStore(coverDir).has("https://img.example/1.jpg")) // 책이 빠지면 표지 파일도 정리
        assertTrue(CoverStore(coverDir).has("https://img.example/2.jpg"))
    }

    @Test
    fun statusOptionsAreOrderedByGroupSoATapCyclesToDoThenInProgressThenComplete() {
        // Phase 11c IX-02: 노션이 옵션을 어떤 순서로 주든 상태 순환은 그룹 순서(할 일 → 진행 중 → 완료)다. 그룹이 없는 옵션은 뒤에 원래 순서로.
        val property = Json { ignoreUnknownKeys = true }.decodeFromString(
            com.ysdoing.notioncal.core.notion.dto.PropertySchemaDto.serializer(),
            """{"id":"st","name":"상태","type":"status","status":{"options":[
                {"id":"c","name":"끝","color":"green"},{"id":"x","name":"기타"},{"id":"a","name":"시작 전","color":"gray"},{"id":"b","name":"진행","color":"blue"}],
              "groups":[{"id":"g1","name":"To-do","option_ids":["a"]},{"id":"g2","name":"In progress","option_ids":["b"]},{"id":"g3","name":"Complete","option_ids":["c"]}]}}""",
        )
        val column = viewColumn(property)!!
        assertEquals(listOf("a", "b", "c", "x"), column.options.map { it.id })
        assertEquals(listOf("To-do", "In progress", "Complete", null), column.options.map { it.group })
        assertEquals("b", column.nextOption("a")!!.id) // 할 일 → 진행 중
        assertEquals("c", column.nextOption("b")!!.id) // 진행 중 → 완료
    }

    // --- Phase 11c IX-06·AD-01: 보기 필터 원본을 저장하고, 필터가 가리키는 숨은 속성도 값을 남긴다(폰에서 판정하려고).

    private val statusSchema = Json { ignoreUnknownKeys = true }.decodeFromString(
        DataSourceDto.serializer(),
        """{"id":"ds","properties":{
            "Name":{"id":"title","name":"Name","type":"title"},
            "Status":{"id":"st","name":"Status","type":"status","status":{"options":[{"id":"o1","name":"할 일"},{"id":"o2","name":"완료"}],"groups":[]}},
            "Formula":{"id":"fm","name":"Formula","type":"formula"}}}""",
    )

    private fun statusPage() = PageDto(
        id = "a", url = "u", lastEditedTime = "2026-10-22T00:00:00.000Z",
        properties = Json.parseToJsonElement("""{"Name":{"type":"title","title":[{"plain_text":"a"}]},"Status":{"type":"status","status":{"id":"o1","name":"할 일"}}}""").jsonObject,
    )

    @Test
    fun theViewFilterIsStoredAndAHiddenFilterPropertyKeepsItsValues() = runTest {
        schema = statusSchema
        viewProperties = listOf(ViewPropertyDto("title", "Name")) // Status는 보기에 보이지 않지만 필터가 쓴다
        viewFilter = Json.parseToJsonElement("""{"property":"st","status":{"equals":"할 일"}}""").jsonObject
        rowPages = listOf(statusPage())
        register("v1")
        sync().syncAll()
        val snapshot = db.viewDao().all().single()
        assertEquals("""{"property":"st","status":{"equals":"할 일"}}""", snapshot.filterJson)
        val columns = ViewJson.columns(snapshot.columnInfo)
        assertEquals(listOf("Name" to "", "Status" to ViewColumn.ROLE_FILTER), columns.map { it.name to it.role })
        assertEquals(JsonPrimitive("o1"), ViewJson.typed(db.viewDao().rows("v1").single().typed)["st"]) // 값이 남아야 판정한다
        assertEquals(listOf("Name"), snapshot.columns.split(VIEW_CELL_SEPARATOR)) // 보이는 열 목록에는 들어가지 않는다
    }

    @Test
    fun aFilterOnAVisibleOrUnjudgeablePropertyAddsNoExtraColumn() = runTest {
        schema = statusSchema
        viewProperties = listOf(ViewPropertyDto("title", "Name"), ViewPropertyDto("st", "Status"))
        viewFilter = Json.parseToJsonElement("""{"and":[{"property":"st","status":{"equals":"할 일"}},{"property":"fm","formula":{"string":{"equals":"x"}}}]}""").jsonObject
        rowPages = listOf(statusPage())
        register("v1")
        sync().syncAll()
        val columns = ViewJson.columns(db.viewDao().all().single().columnInfo)
        assertEquals(listOf("Name" to "", "Status" to ""), columns.map { it.name to it.role }) // 보이는 속성은 그대로, 수식은 쓰기 정보에 없다
    }

    @Test
    fun aViewWithoutAFilterStoresNone() = runTest {
        register("v1")
        sync().syncAll()
        assertEquals("", db.viewDao().all().single().filterJson)
    }

    // --- Phase 11c: 보내지 않은 쓰기(지연 중 포함) 때문에 건너뛴 보기는 쓰기가 다 나간 뒤 받는다(그 사이 새로고침이 사라지지 않게).

    @Test
    fun aViewSkippedBecauseOfPendingWritesIsSyncedOnceTheyAreGone() = runTest {
        register("v1")
        db.viewDao().enqueueCreate(
            com.ysdoing.notioncal.core.data.db.ViewWriteEntity(viewId = "v1", pageId = "new-1", dataSourceId = "ds", propId = "title", propType = "create", value = "{}", createdAt = 1),
        )
        val views = sync()
        views.syncAll()
        assertEquals(emptyList<String>(), requested) // 쓰기가 남은 보기는 받지 않는다
        views.syncSkipped()
        assertEquals(emptyList<String>(), requested) // 쓰기가 아직 남았으니 계속 기다린다
        db.viewDao().deleteWriteById(db.viewDao().firstWrite()!!.id)
        views.syncSkipped()
        assertEquals(listOf("v1"), requested) // 이제 받는다
        views.syncSkipped()
        assertEquals(listOf("v1"), requested) // 한 번만
    }
}
