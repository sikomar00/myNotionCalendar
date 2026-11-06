package com.ysdoing.notioncal.core.sync

import android.database.SQLException
import com.ysdoing.notioncal.core.data.WidgetRefresher
import com.ysdoing.notioncal.core.data.db.VIEW_CELL_SEPARATOR
import com.ysdoing.notioncal.core.data.db.VIEW_ERROR_NOT_FOUND
import com.ysdoing.notioncal.core.data.db.VIEW_ERROR_UNSUPPORTED
import com.ysdoing.notioncal.core.data.db.ViewDao
import com.ysdoing.notioncal.core.data.db.ViewRowEntity
import com.ysdoing.notioncal.core.data.db.SourceSchemaEntity
import com.ysdoing.notioncal.core.data.db.ViewSnapshotEntity
import com.ysdoing.notioncal.core.data.view.CoverStore
import com.ysdoing.notioncal.core.data.view.GalleryConfig
import com.ysdoing.notioncal.core.data.view.ViewColumn
import com.ysdoing.notioncal.core.data.view.ViewFilter
import com.ysdoing.notioncal.core.data.view.ViewJson
import com.ysdoing.notioncal.core.data.view.ViewChoice
import com.ysdoing.notioncal.core.notion.NotionError
import com.ysdoing.notioncal.core.notion.NotionException
import com.ysdoing.notioncal.core.notion.NotionJson
import com.ysdoing.notioncal.core.notion.ViewReader
import com.ysdoing.notioncal.core.notion.dto.DataSourceDto
import com.ysdoing.notioncal.core.notion.dto.PageDto
import com.ysdoing.notioncal.core.notion.dto.PropertySchemaDto
import com.ysdoing.notioncal.core.notion.mapping.NotionValue
import com.ysdoing.notioncal.core.notion.mapping.PropertyCodec
import com.ysdoing.notioncal.core.notion.mapping.displayValue
import com.ysdoing.notioncal.core.notion.viewFilter
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/** 표지 내려받기에 쓸 수 있는 시간. 넘으면 그만두고 나머지는 다음 동기화에서 받는다. */
private const val COVER_PHASE_MILLIS = 45_000L

/** 노션 오류가 아닌 예상 밖 예외(직렬화·Room 등)로 한 보기가 실패했을 때 남기는 값. */
private const val ERROR_UNEXPECTED = "Unexpected"

/**
 * VW-01 등록한 보기마다 노션의 보기 설정과 행을 받아 Room 스냅샷으로 바꿔 넣는다. 위젯은 스냅샷만 읽는다.
 * 한 보기가 실패하면 그 보기의 이전 스냅샷을 그대로 두고 오류만 남긴다(VW-06). 다른 보기와 호출한 쪽(SyncWorker)에는 영향이 없다.
 */
@Singleton
class ViewSync @Inject constructor(
    private val reader: ViewReader,
    private val dao: ViewDao,
    private val widgets: WidgetRefresher,
    private val clock: Clock,
    private val covers: CoverSync,
) {
    /** 쓰기가 남아 받지 못하고 건너뛴 보기 ID. */
    private val skipped: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    suspend fun syncAll() {
        var changed = false
        val schemas = HashMap<String, DataSourceDto?>() // 같은 DB의 보기가 여럿이어도 스키마는 한 번만 읽는다
        for (registered in dao.all()) {
            val error = syncOne(registered, schemas)
            changed = true
            // 연결·토큰·속도 제한·서버 장애는 다른 보기도 똑같이 실패하므로 더 부르지 않는다.
            if (error != null && error.isSharedAcrossViews()) break
        }
        if (changed) widgets.requestUpdate()
        // 표지는 위젯을 먼저 갱신한 뒤에 받는다(데이터가 바뀌면 즉시 갱신, 느린 이미지 서버가 동기화를 붙잡지 않게). 새로 받았으면 한 번 더 갱신한다.
        val saved = fetchCovers(dao.all())
        // 표지 파일은 등록한 모든 보기의 주소를 모아 정리한다(보기 하나만 보고 지우면 다른 보기의 표지가 사라진다).
        covers.prune(dao.coverUrls())
        if (saved) widgets.requestUpdate()
    }

    /** 저장된 행의 앞쪽 표지를 받는다. 시간이 [COVER_PHASE_MILLIS]를 넘으면 그만두고 나머지는 다음 동기화에 맡긴다. 새로 받은 표지가 있으면 true. */
    private suspend fun fetchCovers(views: Collection<ViewSnapshotEntity>): Boolean {
        val urls = views.flatMap { dao.coverUrls(it.viewId, CoverStore.coverLimit(it.viewType)) }
        return withTimeoutOrNull(COVER_PHASE_MILLIS) { covers.fetch(urls) } ?: false
    }

    /**
     * SY-06: 전송이 끝난 페이지가 든 보기([pageIds])와, 새로 만든 페이지가 들어갈 소스의 보기([dataSourceIds])를 다시 받는다(필터에서
     * 빠지고 순서가 정해지고 '반영 대기'가 풀리게). 해당하는 보기가 없으면 아무것도 하지 않는다.
     */
    suspend fun syncContaining(pageIds: Collection<String>, dataSourceIds: Collection<String>) {
        val viewIds = pageIds.flatMap { dao.viewsContaining(it) } + dataSourceIds.flatMap { dao.viewIdsOfSource(it) }
        val schemas = HashMap<String, DataSourceDto?>() // 보기마다 같은 소스의 스키마를 다시 받지 않는다
        viewIds.toSet().forEach { syncView(it, schemas) }
    }

    /**
     * 보내지 않은 쓰기(위젯 즉시 동작의 4초 지연 포함) 때문에 받지 못하고 건너뛴 보기 중 이제 쓰기가 다 나간 보기를 받는다.
     * 그 사이 눌린 새로고침·앱 열기 동기화가 아무 일도 하지 않고 끝나지 않게 처리기가 큐를 비운 뒤 부른다. 앱이 죽으면 기억이 사라지고 다음 주기 동기화가 한다.
     */
    suspend fun syncSkipped() {
        val ready = skipped.filter { dao.pendingWrites(it) == 0 }
        if (ready.isEmpty()) return
        skipped.removeAll(ready.toSet())
        val schemas = HashMap<String, DataSourceDto?>()
        ready.forEach { syncView(it, schemas) }
    }

    /** 방금 등록한 보기 하나만 받는다(등록 화면). 다른 보기를 기다리게 하지 않는다. */
    suspend fun sync(viewId: String) = syncView(viewId, HashMap())

    private suspend fun syncView(viewId: String, schemas: MutableMap<String, DataSourceDto?>) {
        val registered = dao.all().firstOrNull { it.viewId == viewId } ?: return
        syncOne(registered, schemas)
        widgets.requestUpdate()
        if (fetchCovers(dao.all().filter { it.viewId == viewId })) widgets.requestUpdate()
    }

    /** 보기 하나를 받아 스냅샷을 바꾼다. 실패하면 이전 스냅샷을 두고 오류만 남기며, 노션 오류는 돌려준다(성공이거나 예상 밖 예외면 null). */
    private suspend fun syncOne(registered: ViewSnapshotEntity, schemas: MutableMap<String, DataSourceDto?>): NotionError? = try {
        // 보내지 않은 바꿈이 남은 보기는 받지 않는다. 방금 한 체크를 노션의 이전 값으로 덮어 되돌리지 않게, 보낸 뒤에 받는다.
        if (dao.pendingWrites(registered.viewId) > 0) {
            skipped += registered.viewId // 쓰기가 나간 뒤 받는다([syncSkipped])
            return null
        }
        val fetchedAt = clock.millis() // 받는 도중에 위젯에서 바꾼 보기는 replace가 덮어쓰지 않는다
        val (meta, rows) = snapshot(registered, schemas)
        dao.replace(meta, rows, fetchedAt)
        null
    } catch (e: NotionException) {
        dao.markError(registered.viewId, e.error.storedName())
        e.error
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        dao.markError(registered.viewId, ERROR_UNEXPECTED)
        null
    }

    /** 쓰기 정보용 스키마. 못 읽어도 행은 받을 수 있어 null로 두고, 그때는 지난번 쓰기 정보를 그대로 쓴다. */
    private suspend fun schemaOrNull(dataSourceId: String, cache: MutableMap<String, DataSourceDto?>): DataSourceDto? =
        if (dataSourceId in cache) {
            cache[dataSourceId]
        } else {
            try {
                reader.schema(dataSourceId)?.also { saveSchema(dataSourceId, it) }
            } catch (e: NotionException) {
                null
            }.also { cache[dataSourceId] = it }
        }

    /** CN-02: 소스 스키마 전체를 저장한다(추가 요청 없이, 읽은 김에). 바뀐 것이 없으면 쓰지 않는다. */
    private suspend fun saveSchema(dataSourceId: String, schema: DataSourceDto) {
        val json = NotionJson.encodeToString(DataSourceDto.serializer(), schema)
        try {
            if (dao.schema(dataSourceId)?.schemaJson != json) dao.upsertSchema(SourceSchemaEntity(dataSourceId, json, clock.millis()))
        } catch (e: SQLException) {
            // 캐시일 뿐이다: 저장하지 못해도 보기 행 갱신을 막지 않는다(다음 동기화가 다시 시도한다).
        }
    }

    private suspend fun snapshot(
        registered: ViewSnapshotEntity,
        schemas: MutableMap<String, DataSourceDto?>,
    ): Pair<ViewSnapshotEntity, List<ViewRowEntity>> {
        val view = reader.view(registered.viewId)
        val fetched = reader.viewRows(registered.dataSourceId, view)
        val pages = fetched.pages
        val schema = schemaOrNull(registered.dataSourceId, schemas)
        // 보기 설정에 속성 이름이 없으면(갤러리 등, 실측) 속성 id로 스키마에서 이름을 찾고, 찾지 못하는 속성(지워짐)은 뺀다.
        val nameById = schema?.properties?.values?.associate { it.id to it.name }.orEmpty()
        val configured = view.configuration?.properties.orEmpty()
            .map { (it.propertyName.ifEmpty { nameById[it.propertyId].orEmpty() }) to it.visible }
            .filter { (name, _) -> name.isNotEmpty() }
        // 제목은 행이 없어도 알 수 있게 스키마에서 찾는다(빈 보기나 카드 제목을 열 목록에 넣지 않는 보드·갤러리도 새 항목을 만들 수 있다).
        val titleName = pages.firstOrNull()?.titlePropertyName() ?: schema?.properties?.values?.firstOrNull { it.type == "title" }?.name
        val columns = visibleColumns(configured, titleName)
        // group_by는 표·목록도 갖지만 열로 나눠 그리는 것은 보드뿐이다. 그 밖의 보기에 숨은 선택 열을 넣으면 눌림 열로 세어져 모든 행이 높아진다.
        val groupConfig = view.configuration?.raw?.takeIf { view.type == "board" }
        // 캘린더는 date_property_id 속성(숨겨도)의 날짜로 월 격자를 그린다.
        val dateConfig = view.configuration?.raw?.takeIf { view.type == "calendar" }
        // 차트는 x축·y축·값 속성(숨겨도)의 값으로 폰에서 묶고 집계한다.
        val chartConfig = view.configuration?.raw?.takeIf { view.type == "chart" }
        // 보기 필터(보기 필터 + 빠른 필터)는 폰에서 항목이 필터에서 빠지는지 판정하려고 저장한다(IX-06). 필터가 가리키는 속성은 숨겨져 있어도 값을 남긴다.
        val filterJson = viewFilter(view)?.toString().orEmpty()
        val filter = ViewFilter.parse(filterJson)
        val writable = schema?.let { s ->
            val roles = withChartRole(withDateRole(withGroupRole(withReadingRoles(writableColumns(columns, s), s), s, groupConfig), s, dateConfig), s, chartConfig)
            withFilterRole(roles, s, filter)
        } ?: ViewJson.columns(registered.columnInfo).filter { it.name in columns || it.role.isNotEmpty() }
        val meta = registered.copy(
            name = view.name,
            viewType = view.type,
            columns = columns.joinToString(VIEW_CELL_SEPARATOR),
            syncedAt = clock.millis(),
            lastError = null,
            columnInfo = ViewJson.encodeColumns(writable),
            viewConfig = view.configuration?.raw?.takeIf { it.isNotEmpty() }?.toString().orEmpty(),
            filterJson = filterJson,
            rowsComplete = !fetched.hasMore,
        )
        // 갤러리는 보기가 정한 표지 출처(속성·페이지 표지)를 따른다(정하지 않았으면 기존 휴리스틱이 먼저라 같은 주소를 읽는 책 위젯이 바뀌지 않는다). 다른 보기는 휴리스틱만 쓴다.
        val gallery = GalleryConfig.parse(view.configuration?.raw).takeIf { view.type == "gallery" }
        val rows = pages.mapIndexed { i, page ->
            val cells = columns.joinToString(VIEW_CELL_SEPARATOR) { name -> cellText(writable.firstOrNull { it.name == name }, page.properties[name] as? JsonObject) }
            val typed = writable.filter { it.writable }.associate { it.id to typedValue(it, page.properties[it.name] as? JsonObject) }
            ViewRowEntity(registered.viewId, i, page.id, page.url, cells, ViewJson.encodeTyped(typed), gallery?.let { coverUrlOf(page, it) } ?: coverUrlOf(page.properties), progressOf(page.properties))
        }
        return meta to rows
    }

    private fun PageDto.titlePropertyName(): String? =
        properties.entries.firstOrNull { ((it.value as? JsonObject)?.get("type") as? JsonPrimitive)?.contentOrNull == "title" }?.key
}

/**
 * 행의 칸 글자. 노션 형식이 있는 숫자 열은 그 형식대로(`₩12,000`, `25%`), 나머지는 노션 읽기 표기(displayValue)다. 칸 글자가 로컬 쓰기 뒤
 * `ViewColumn.display`로 다시 만들어지는 것과 같은 모양이라 편집 전후 글자가 바뀌지 않는다.
 */
internal fun cellText(column: ViewColumn?, property: JsonObject?): String =
    if (column?.type == "number" && column.numberFormat.isNotEmpty()) column.display(typedValue(column, property)) else displayValue(property ?: JsonObject(emptyMap()))

/**
 * 보이는 열의 속성 정보. 쓸 수 있는 종류뿐 아니라 읽기 전용으로 보이는 종류(수식·롤업 등)도 담으니 [ViewColumn.writable]로 가른다.
 * 스키마에 없는 열은 빠진다. 상태 선택지에는 그룹 이름(할 일·진행 중·완료)을 붙인다.
 */
internal fun writableColumns(columnNames: List<String>, schema: DataSourceDto): List<ViewColumn> {
    val byName = schema.properties.values.associateBy { it.name }
    return columnNames.mapNotNull { name -> byName[name]?.let(::viewColumn) }
}

/**
 * 스키마 속성 하나를 쓰기 정보로. 쓸 수 있는 종류([ViewColumn.INFO_TYPES])와 읽기 전용이라도 보이는 종류([ViewColumn.DISPLAY_ONLY_TYPES])만, 그 밖은 null.
 * 선택지는 옵션 목록(색 포함, 상태는 그룹 이름도)으로 담는다.
 */
internal fun viewColumn(property: PropertySchemaDto): ViewColumn? {
    if (property.type !in ViewColumn.INFO_TYPES && property.type !in ViewColumn.DISPLAY_ONLY_TYPES) return null
    val options = when (property.type) {
        "select" -> property.select?.options.orEmpty().map { ViewChoice(it.id, it.name, color = it.color) }
        "multi_select" -> property.multiSelect?.options.orEmpty().map { ViewChoice(it.id, it.name, color = it.color) }
        "status" -> {
            // 그룹 순서(할 일 → 진행 중 → 완료)로 담는다: 눌러 순환할 때 노션이 옵션을 준 순서가 아니라 이 순서를 따른다(IX-02). 그룹이 없는 옵션은 뒤에 원래 순서로.
            val groups = property.status?.groups.orEmpty()
            property.status?.options.orEmpty()
                .map { option -> option to groups.indexOfFirst { option.id in it.optionIds } }
                .sortedBy { (_, group) -> if (group < 0) Int.MAX_VALUE else group }
                .map { (option, group) -> ViewChoice(option.id, option.name, groups.getOrNull(group)?.name, option.color) }
        }
        else -> emptyList()
    }
    return ViewColumn(property.id, property.name, property.type, options, numberFormat = property.number?.format.orEmpty().takeIf { property.type == "number" }.orEmpty())
}

/**
 * 행의 속성 JSON에서 [column]의 원래 값을 꺼낸다(코덱으로 읽은 값을 [ViewColumn.display]가 읽는 모양으로). 비어 있거나 속성이 없거나
 * 열 종류와 다르면 JsonNull.
 */
internal fun typedValue(column: ViewColumn, property: JsonObject?): JsonElement {
    property ?: return JsonNull
    val value = PropertyCodec.decode(property)
    if (value.type != column.type) return JsonNull
    return when (value) {
        is NotionValue.Check -> JsonPrimitive(value.checked)
        is NotionValue.Choice -> value.option?.id?.let(::JsonPrimitive) ?: JsonNull
        is NotionValue.Num -> value.value?.let(::JsonPrimitive) ?: JsonNull
        is NotionValue.Text -> JsonPrimitive(value.text)
        is NotionValue.Str -> value.value?.let(::JsonPrimitive) ?: JsonNull
        is NotionValue.Choices -> JsonArray(value.options.mapNotNull { option -> option.id?.let(::JsonPrimitive) })
        is NotionValue.DateRange -> dateValue(value)
        else -> JsonNull
    }
}

private fun dateValue(date: NotionValue.DateRange): JsonElement {
    val start = date.start ?: return JsonNull
    return buildJsonObject {
        put("start", start)
        date.end?.let { put("end", it) }
        date.timeZone?.let { put("time_zone", it) }
    }
}

/**
 * 위젯에 그릴 열. 보기 설정에서 보이게 한 속성을 그 순서대로 두되, 제목([title])은 항상 첫 열이다.
 * 위젯은 폭이 좁으면 뒤 열부터 줄이고 목록 모드는 첫 열을 제목 줄로 쓰기 때문이다.
 * - 표 보기는 제목이 열 목록에 있고 순서가 앞이 아닐 수 있다(실기기 AllTodos: 4번째) → 앞으로 옮긴다.
 * - 보드·갤러리·목록 보기는 카드 제목이 항상 보여 열 목록에 제목이 없을 수 있다 → 첫 열로 넣는다(안 넣으면 값이 빈 속성만 남는다).
 * - 설정이 제목을 명시적으로 숨겼다면(`visible=false`) 그 선택을 따른다.
 */
internal fun visibleColumns(configured: List<Pair<String, Boolean>>, title: String?): List<String> {
    val visible = configured.filter { (_, shown) -> shown }.map { (name, _) -> name }
    val titleHidden = title != null && configured.any { (name, shown) -> name == title && !shown }
    return when {
        title == null || titleHidden -> visible
        else -> listOf(title) + (visible - title)
    }.ifEmpty { listOfNotNull(title) }
}

private fun NotionError.isSharedAcrossViews(): Boolean =
    this == NotionError.Network || this == NotionError.Unauthorized || this == NotionError.RateLimited || this is NotionError.Server

/**
 * `view_snapshots.lastError`에 남기는 값. 릴리스(R8)는 클래스 이름을 바꾸므로 `::class.simpleName`을 쓰지 않고 정한 문자열로 매핑한다.
 * `when`이 모든 경우를 다뤄야 해서 오류 종류가 늘면 여기서 컴파일이 막힌다.
 */
internal fun NotionError.storedName(): String = when (this) {
    NotionError.NotShared -> VIEW_ERROR_NOT_FOUND
    NotionError.Unauthorized -> "Unauthorized"
    is NotionError.Validation -> VIEW_ERROR_UNSUPPORTED
    NotionError.RateLimited -> "RateLimited"
    NotionError.Network -> "Network"
    is NotionError.Server -> "Server"
}
