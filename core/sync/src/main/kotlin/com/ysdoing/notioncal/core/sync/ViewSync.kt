package com.ysdoing.notioncal.core.sync

import com.ysdoing.notioncal.core.data.WidgetRefresher
import com.ysdoing.notioncal.core.data.db.VIEW_CELL_SEPARATOR
import com.ysdoing.notioncal.core.data.db.VIEW_ERROR_NOT_FOUND
import com.ysdoing.notioncal.core.data.db.VIEW_ERROR_UNSUPPORTED
import com.ysdoing.notioncal.core.data.db.ViewDao
import com.ysdoing.notioncal.core.data.db.ViewRowEntity
import com.ysdoing.notioncal.core.data.db.SourceSchemaEntity
import com.ysdoing.notioncal.core.data.db.ViewSnapshotEntity
import com.ysdoing.notioncal.core.data.view.CoverStore
import com.ysdoing.notioncal.core.data.view.ViewColumn
import com.ysdoing.notioncal.core.data.view.ViewJson
import com.ysdoing.notioncal.core.data.view.ViewChoice
import com.ysdoing.notioncal.core.notion.NotionError
import com.ysdoing.notioncal.core.notion.NotionException
import com.ysdoing.notioncal.core.notion.NotionJson
import com.ysdoing.notioncal.core.notion.ViewReader
import com.ysdoing.notioncal.core.notion.dto.DataSourceDto
import com.ysdoing.notioncal.core.notion.dto.PageDto
import com.ysdoing.notioncal.core.notion.mapping.NotionValue
import com.ysdoing.notioncal.core.notion.mapping.PropertyCodec
import com.ysdoing.notioncal.core.notion.mapping.displayValue
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
        val saved = fetchCovers(dao.all().map { it.viewId })
        // 표지 파일은 등록한 모든 보기의 주소를 모아 정리한다(보기 하나만 보고 지우면 다른 보기의 표지가 사라진다).
        covers.prune(dao.coverUrls())
        if (saved) widgets.requestUpdate()
    }

    /** 저장된 행의 앞쪽 표지를 받는다. 시간이 [COVER_PHASE_MILLIS]를 넘으면 그만두고 나머지는 다음 동기화에 맡긴다. 새로 받은 표지가 있으면 true. */
    private suspend fun fetchCovers(viewIds: Collection<String>): Boolean {
        val urls = viewIds.flatMap { dao.coverUrls(it, CoverStore.MAX_COVERS_PER_VIEW) }
        return withTimeoutOrNull(COVER_PHASE_MILLIS) { covers.fetch(urls) } ?: false
    }

    /**
     * SY-06: 전송이 끝난 페이지가 든 보기만 다시 받는다(필터에서 빠지고 순서가 정해지고 '반영 대기'가 풀리게). 그 페이지를 가진 보기가
     * 없으면 아무것도 하지 않는다.
     */
    suspend fun syncContaining(pageIds: Collection<String>) {
        pageIds.flatMap { dao.viewsContaining(it) }.toSet().forEach { sync(it) }
    }

    /** 방금 등록한 보기 하나만 받는다(등록 화면). 다른 보기를 기다리게 하지 않는다. */
    suspend fun sync(viewId: String) {
        val registered = dao.all().firstOrNull { it.viewId == viewId } ?: return
        syncOne(registered, HashMap())
        widgets.requestUpdate()
        if (fetchCovers(listOf(viewId))) widgets.requestUpdate()
    }

    /** 보기 하나를 받아 스냅샷을 바꾼다. 실패하면 이전 스냅샷을 두고 오류만 남기며, 노션 오류는 돌려준다(성공이거나 예상 밖 예외면 null). */
    private suspend fun syncOne(registered: ViewSnapshotEntity, schemas: MutableMap<String, DataSourceDto?>): NotionError? = try {
        // 보내지 않은 바꿈이 남은 보기는 받지 않는다. 방금 한 체크를 노션의 이전 값으로 덮어 되돌리지 않게, 보낸 뒤에 받는다.
        if (dao.pendingWrites(registered.viewId) > 0) return null
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
        if (dao.schema(dataSourceId)?.schemaJson != json) dao.upsertSchema(SourceSchemaEntity(dataSourceId, json, clock.millis()))
    }

    private suspend fun snapshot(
        registered: ViewSnapshotEntity,
        schemas: MutableMap<String, DataSourceDto?>,
    ): Pair<ViewSnapshotEntity, List<ViewRowEntity>> {
        val view = reader.view(registered.viewId)
        val pages = reader.rows(registered.dataSourceId, view)
        val schema = schemaOrNull(registered.dataSourceId, schemas)
        val configured = view.configuration?.properties.orEmpty()
        // 제목은 행이 없어도 알 수 있게 스키마에서 찾는다(빈 보기나 카드 제목을 열 목록에 넣지 않는 보드·갤러리도 새 항목을 만들 수 있다).
        val titleName = pages.firstOrNull()?.titlePropertyName() ?: schema?.properties?.values?.firstOrNull { it.type == "title" }?.name
        val columns = visibleColumns(configured.map { it.propertyName to it.visible }, titleName)
        val writable = schema?.let { withReadingRoles(writableColumns(columns, it), it) }
            ?: ViewJson.columns(registered.columnInfo).filter { it.name in columns || it.role.isNotEmpty() }
        val meta = registered.copy(
            name = view.name,
            viewType = view.type,
            columns = columns.joinToString(VIEW_CELL_SEPARATOR),
            syncedAt = clock.millis(),
            lastError = null,
            columnInfo = ViewJson.encodeColumns(writable),
        )
        val rows = pages.mapIndexed { i, page ->
            val cells = columns.joinToString(VIEW_CELL_SEPARATOR) { displayValue(page.properties[it] as? JsonObject ?: JsonObject(emptyMap())) }
            val typed = writable.filter { it.writable }.associate { it.id to typedValue(it, page.properties[it.name] as? JsonObject) }
            ViewRowEntity(registered.viewId, i, page.id, page.url, cells, ViewJson.encodeTyped(typed), coverUrlOf(page.properties), progressOf(page.properties))
        }
        return meta to rows
    }

    private fun PageDto.titlePropertyName(): String? =
        properties.entries.firstOrNull { ((it.value as? JsonObject)?.get("type") as? JsonPrimitive)?.contentOrNull == "title" }?.key
}

/**
 * 보이는 열 중 위젯에서 바꾸거나 새 항목을 만들 때 쓰는 속성(체크·선택·상태·숫자, 그리고 제목·날짜)의 정보.
 * 스키마에 없는 열(수식·롤업 등)은 빠진다. 상태 선택지에는 그룹 이름(할 일·진행 중·완료)을 붙인다.
 */
internal fun writableColumns(columnNames: List<String>, schema: DataSourceDto): List<ViewColumn> {
    val byName = schema.properties.values.associateBy { it.name }
    return columnNames.mapNotNull { name ->
        val property = byName[name] ?: return@mapNotNull null
        if (property.type !in ViewColumn.INFO_TYPES) return@mapNotNull null
        val options = when (property.type) {
            "select" -> property.select?.options.orEmpty().map { ViewChoice(it.id, it.name) }
            "multi_select" -> property.multiSelect?.options.orEmpty().map { ViewChoice(it.id, it.name) }
            "status" -> {
                val groups = property.status?.groups.orEmpty()
                property.status?.options.orEmpty().map { option -> ViewChoice(option.id, option.name, groups.firstOrNull { option.id in it.optionIds }?.name) }
            }
            else -> emptyList()
        }
        ViewColumn(property.id, property.name, property.type, options)
    }
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
