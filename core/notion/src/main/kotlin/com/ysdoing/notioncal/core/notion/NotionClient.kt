package com.ysdoing.notioncal.core.notion

import com.ysdoing.notioncal.core.notion.dto.DataSourceDto
import com.ysdoing.notioncal.core.notion.dto.DatabaseDto
import com.ysdoing.notioncal.core.notion.dto.ErrorDto
import com.ysdoing.notioncal.core.notion.dto.PageDto
import com.ysdoing.notioncal.core.notion.dto.QueryRequestDto
import com.ysdoing.notioncal.core.notion.dto.UserDto
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

/** [NotionApi] 호출을 감싸 실패를 [NotionException]으로 바꾼다. */
@Singleton
class NotionClient @Inject constructor(
    private val api: NotionApi,
    private val json: Json,
) : NotionReader {
    suspend fun me(): UserDto = call { api.me() }

    suspend fun database(databaseId: String): DatabaseDto = call { api.database(databaseId) }

    override suspend fun dataSource(dataSourceId: String): DataSourceDto = call { api.dataSource(dataSourceId) }

    override suspend fun pageOrNull(pageId: String): PageDto? = try {
        call { api.page(pageId) }
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
