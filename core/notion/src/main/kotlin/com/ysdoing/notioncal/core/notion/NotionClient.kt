package com.ysdoing.notioncal.core.notion

import com.ysdoing.notioncal.core.notion.dto.CreatePageDto
import com.ysdoing.notioncal.core.notion.dto.DataSourceDto
import com.ysdoing.notioncal.core.notion.dto.DatabaseDto
import com.ysdoing.notioncal.core.notion.dto.ErrorDto
import com.ysdoing.notioncal.core.notion.dto.PageDto
import com.ysdoing.notioncal.core.notion.dto.ParentDto
import com.ysdoing.notioncal.core.notion.dto.QueryRequestDto
import com.ysdoing.notioncal.core.notion.dto.SearchRequestDto
import com.ysdoing.notioncal.core.notion.dto.plainTitle
import com.ysdoing.notioncal.core.notion.dto.UpdatePageDto
import com.ysdoing.notioncal.core.notion.dto.UserDto
import com.ysdoing.notioncal.core.notion.dto.ViewDto
import com.ysdoing.notioncal.core.notion.dto.ViewQueryRequestDto
import dagger.Lazy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/** 동기화 엔진이 쓰는 읽기 호출. 테스트에서는 메모리 구현으로 바꾼다. */
interface NotionReader {
    suspend fun dataSource(dataSourceId: String): DataSourceDto

    /** `next_cursor`를 따라 끝까지 받는다. 한 쿼리 1만 건 제한에 걸리면 [QueryResult.complete]가 false다. */
    suspend fun queryAll(dataSourceId: String, filter: JsonObject?): QueryResult

    /** 단건 재조회. 지워졌거나(404) 접근할 수 없으면 null. */
    suspend fun pageOrNull(pageId: String): PageDto?
}

/** 아웃박스 처리기가 쓰는 쓰기 호출(NS-04). 테스트에서는 메모리 구현으로 바꾼다. */
interface NotionWriter {
    /** 템플릿 없이 만든다. */
    suspend fun createPage(dataSourceId: String, properties: JsonObject): PageDto

    suspend fun updatePage(pageId: String, properties: JsonObject): PageDto

    /** 휴지통 이동(삭제는 이것만 한다). 이미 없으면(404) null. */
    suspend fun trashPage(pageId: String): PageDto?
}

/** 이 연결에 공유된 DB 하나(검색 결과). 등록 조회는 [databaseId]로 한다([dataSourceId]로 DB를 조회하면 404). */
data class SharedDataSource(val databaseId: String, val dataSourceId: String, val name: String)

/** 공유 DB 검색 결과. [truncated]면 [MAX_SEARCH_PAGES]쪽에서 멈춰 더 있을 수 있다. */
data class SharedDataSources(val items: List<SharedDataSource>, val truncated: Boolean)

/** 공유 DB 검색을 받는 최대 쪽 수(쪽당 100개). */
internal const val MAX_SEARCH_PAGES = 5

/**
 * [NotionApi] 호출을 감싸 실패를 [NotionException]으로 바꾼다.
 * API(OkHttp·SSL 초기화)는 첫 호출 때 만든다. 화면 ViewModel이 메인 스레드에서 이 클래스를 주입받아도
 * 무거운 초기화는 호출하는 쪽의 IO 스레드에서 일어난다(PF-03).
 */
@Singleton
class NotionClient @Inject constructor(
    private val apiLazy: Lazy<NotionApi>,
    private val json: Json,
) : NotionReader, NotionWriter, ViewReader {
    constructor(api: NotionApi, json: Json) : this(Lazy { api }, json)

    private val api: NotionApi get() = apiLazy.get()

    suspend fun me(): UserDto = call { api.me() }

    suspend fun database(databaseId: String): DatabaseDto = call { api.database(databaseId) }

    /** LD-01: 페이지의 자식 DB(ID, 제목). 다음 쪽까지 끝까지 받는다. */
    suspend fun childDatabases(pageId: String): List<Pair<String, String>> {
        val found = mutableListOf<Pair<String, String>>()
        var cursor: String? = null
        do {
            val page = call { api.blockChildren(pageId, cursor) }
            page.results.forEach { block -> block.childDatabase?.let { found += block.id to it.title } }
            cursor = if (page.hasMore) page.nextCursor else null
        } while (cursor != null)
        return found
    }

    /**
     * NS-02: 이 연결에 공유된 DB(data source) 목록. DB 아래에 있지 않은 것(부모가 DB가 아님)은 등록할 수 없어 뺀다.
     * 공유한 DB가 아주 많아도 끝없이 받지 않게 [MAX_SEARCH_PAGES]쪽(500개)까지만 받고, 그랬으면 [SharedDataSources.truncated].
     * 휴지통에 있는 것은 뺀다.
     */
    suspend fun sharedDataSources(): SharedDataSources {
        val found = mutableListOf<SharedDataSource>()
        var cursor: String? = null
        var pages = 0
        do {
            val page = call { api.search(SearchRequestDto(startCursor = cursor)) }
            page.results.forEach { result ->
                val databaseId = result.parent?.databaseId ?: return@forEach
                if (!result.inTrash) found += SharedDataSource(databaseId, result.id, result.title.plainTitle())
            }
            cursor = if (page.hasMore) page.nextCursor else null
        } while (cursor != null && ++pages < MAX_SEARCH_PAGES)
        return SharedDataSources(found, truncated = cursor != null)
    }

    override suspend fun dataSource(dataSourceId: String): DataSourceDto = call { api.dataSource(dataSourceId) }

    /** CN-03: 보기 목록을 끝까지 받는다(100개씩 나뉘어 온다). */
    override suspend fun viewIds(dataSourceId: String): List<String> {
        val ids = mutableListOf<String>()
        var cursor: String? = null
        do {
            val page = call { api.views(dataSourceId, startCursor = cursor) }
            ids += page.results.map { it.id }
            cursor = if (page.hasMore) page.nextCursor else null
        } while (cursor != null)
        return ids
    }

    override suspend fun view(viewId: String): ViewDto = call { api.view(viewId) }

    /**
     * 보기 쿼리 결과의 페이지 id(앞에서 [limit]개). 결과는 15분 뒤 만료되어 쪽을 넘기다 404가 나면 처음부터 한 번 다시 만든다.
     * 보기가 지워졌거나 공유가 풀렸어도 404라, 다시 만들어도 404이면 그 오류를 그대로 던진다.
     */
    override suspend fun viewQueryIds(viewId: String, limit: Int): ViewQueryIds {
        for (attempt in 0..1) {
            try {
                return readViewQuery(viewId, limit)
            } catch (e: NotionException) {
                if (e.error != NotionError.NotShared || attempt == 1) throw e
            }
        }
        error("unreachable")
    }

    private suspend fun readViewQuery(viewId: String, limit: Int): ViewQueryIds {
        var page = call { api.createViewQuery(viewId, ViewQueryRequestDto(pageSize = limit.coerceIn(1, 100))) }
        val ids = page.results.map { it.id }.toMutableList()
        var complete = page.requestStatus?.type != "incomplete"
        while (page.hasMore && ids.size < limit && page.nextCursor != null) {
            page = call { api.viewQueryPage(viewId, page.id, page.nextCursor) }
            ids += page.results.map { it.id }
            if (page.requestStatus?.type == "incomplete") complete = false
        }
        return ViewQueryIds(ids.take(limit), complete)
    }

    override suspend fun schema(dataSourceId: String): DataSourceDto = dataSource(dataSourceId)

    override suspend fun rows(dataSourceId: String, view: ViewDto, limit: Int): List<PageDto> = viewRows(dataSourceId, view, limit).pages

    override suspend fun viewRows(dataSourceId: String, view: ViewDto, limit: Int): ViewRows {
        val response = call { api.query(dataSourceId, QueryRequestDto(filter = viewFilter(view), sorts = view.sorts, pageSize = limit.coerceIn(1, 100))) }
        return ViewRows(response.results, response.hasMore)
    }

    override suspend fun pageOrNull(pageId: String): PageDto? = try {
        call { api.page(pageId) }
    } catch (e: NotionException) {
        if (e.error == NotionError.NotShared) null else throw e
    }

    override suspend fun createPage(dataSourceId: String, properties: JsonObject): PageDto =
        call { api.createPage(CreatePageDto(ParentDto(dataSourceId = dataSourceId), properties)) }

    override suspend fun updatePage(pageId: String, properties: JsonObject): PageDto =
        call { api.updatePage(pageId, UpdatePageDto(properties = properties)) }

    override suspend fun trashPage(pageId: String): PageDto? = try {
        call { api.updatePage(pageId, UpdatePageDto(inTrash = true)) }
    } catch (e: NotionException) {
        if (e.error == NotionError.NotShared) null else throw e
    }

    override suspend fun queryAll(dataSourceId: String, filter: JsonObject?): QueryResult {
        val pages = mutableListOf<PageDto>()
        var cursor: String? = null
        var complete = true
        do {
            val response = call { api.query(dataSourceId, QueryRequestDto(filter = filter, startCursor = cursor)) }
            pages += response.results
            if (response.requestStatus?.type == "incomplete") complete = false
            cursor = if (response.hasMore) response.nextCursor else null
        } while (cursor != null)
        return QueryResult(pages, complete)
    }

    private suspend fun <T> call(block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: HttpException) {
        throw NotionException(mapHttp(e), e)
    } catch (e: IOException) {
        throw NotionException(NotionError.Network, e)
    } catch (e: SerializationException) {
        throw NotionException(NotionError.Server(null), e)
    }

    private fun mapHttp(e: HttpException): NotionError {
        val body = runCatching { e.response()?.errorBody()?.string() }.getOrNull()
        val code = body?.let { runCatching { json.decodeFromString(ErrorDto.serializer(), it).code }.getOrNull() }
        return when (e.code()) {
            401 -> NotionError.Unauthorized
            403, 404 -> NotionError.NotShared
            400 -> NotionError.Validation(code)
            429, 529 -> NotionError.RateLimited
            else -> NotionError.Server(e.code())
        }
    }
}

data class QueryResult(val pages: List<PageDto>, val complete: Boolean)
