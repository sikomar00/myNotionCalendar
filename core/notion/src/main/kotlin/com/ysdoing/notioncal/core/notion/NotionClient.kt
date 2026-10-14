package com.ysdoing.notioncal.core.notion

import com.ysdoing.notioncal.core.notion.dto.CreatePageDto
import com.ysdoing.notioncal.core.notion.dto.DataSourceDto
import com.ysdoing.notioncal.core.notion.dto.DatabaseDto
import com.ysdoing.notioncal.core.notion.dto.ErrorDto
import com.ysdoing.notioncal.core.notion.dto.PageDto
import com.ysdoing.notioncal.core.notion.dto.ParentDto
import com.ysdoing.notioncal.core.notion.dto.QueryRequestDto
import com.ysdoing.notioncal.core.notion.dto.UpdatePageDto
import com.ysdoing.notioncal.core.notion.dto.UserDto
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

/**
 * [NotionApi] 호출을 감싸 실패를 [NotionException]으로 바꾼다.
 * API(OkHttp·SSL 초기화)는 첫 호출 때 만든다. 화면 ViewModel이 메인 스레드에서 이 클래스를 주입받아도
 * 무거운 초기화는 호출하는 쪽의 IO 스레드에서 일어난다(PF-03).
 */
@Singleton
class NotionClient @Inject constructor(
    private val apiLazy: Lazy<NotionApi>,
    private val json: Json,
) : NotionReader, NotionWriter {
    constructor(api: NotionApi, json: Json) : this(Lazy { api }, json)

    private val api: NotionApi get() = apiLazy.get()

    suspend fun me(): UserDto = call { api.me() }

    suspend fun database(databaseId: String): DatabaseDto = call { api.database(databaseId) }

    override suspend fun dataSource(dataSourceId: String): DataSourceDto = call { api.dataSource(dataSourceId) }

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
