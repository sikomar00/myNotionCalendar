package com.ysdoing.notioncal.core.sync

import com.ysdoing.notioncal.core.data.IoDispatcher
import com.ysdoing.notioncal.core.data.db.ViewDao
import com.ysdoing.notioncal.core.data.db.ViewSnapshotEntity
import com.ysdoing.notioncal.core.notion.NotionError
import com.ysdoing.notioncal.core.notion.NotionException
import com.ysdoing.notioncal.core.notion.ViewReader
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** 보기 하나. [type]이 null이면 설정을 읽지 못한 보기다(9-A: 일부 보기는 `GET /views/{id}`가 400). */
data class ViewOption(val viewId: String, val name: String, val type: String?, val registered: Boolean) {
    /** VW-05 table·list는 그대로, chart·board·calendar·gallery는 각자의 그림으로(자리가 없으면 목록으로 대신), 나머지 지원 종류는 평평한 목록으로 보여 준다. */
    val supported: Boolean get() = type in SUPPORTED_TYPES

    /** 노션의 보기 배치를 못 그리고 목록으로 보여 주는 종류(지금은 타임라인). */
    val shownAsList: Boolean get() = supported && type !in DRAWN_TYPES

    private companion object {
        val DRAWN_TYPES = setOf("table", "list", "chart", "board", "calendar", "gallery")
        val SUPPORTED_TYPES = setOf("table", "list", "board", "gallery", "timeline", "calendar", "chart")
    }
}

sealed interface ViewListResult {
    data class Ok(val dataSourceId: String, val databaseName: String, val views: List<ViewOption>) : ViewListResult
    data object InvalidLink : ViewListResult
    data class Failed(val error: NotionError) : ViewListResult
}

/** VW-04 설정의 '보기 추가': DB 링크 → 보기 목록 → 하나 등록(바로 한 번 받는다). */
@Singleton
class ViewRegistration @Inject constructor(
    private val connection: ConnectionService,
    private val reader: ViewReader,
    private val dao: ViewDao,
    private val sync: ViewSync,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    suspend fun list(link: String): ViewListResult = withContext(io) {
        when (val inspected = connection.inspect(link)) {
            InspectResult.InvalidLink -> ViewListResult.InvalidLink
            is InspectResult.Failed -> ViewListResult.Failed(inspected.error)
            is InspectResult.Ok -> try {
                val dataSourceId = inspected.inspection.dataSourceId
                val registered = dao.all().mapTo(HashSet()) { it.viewId }
                val views = reader.viewIds(dataSourceId).map { id -> readViewOption(reader, id, registered) }
                ViewListResult.Ok(dataSourceId, inspected.inspection.name, views)
            } catch (e: NotionException) {
                ViewListResult.Failed(e.error)
            }
        }
    }

    /**
     * 등록하고 이 보기만 바로 받는다(다른 보기를 기다리지 않는다). 이미 등록한 보기면 설정만 새로 받는다.
     * 받기가 실패해도 등록은 남고, 위젯이 '동기화 실패' 같은 상태를 글자로 알린다(VW-06).
     */
    suspend fun register(dataSourceId: String, view: ViewOption) = withContext(io) {
        check(view.supported) { "지원하지 않는 보기" }
        val existing = dao.all().firstOrNull { it.viewId == view.viewId }
        dao.upsert(existing ?: ViewSnapshotEntity(view.viewId, dataSourceId, view.name, view.type.orEmpty()))
        sync.sync(view.viewId)
    }
}

/**
 * 보기 하나의 이름·종류를 읽는다. 설정을 읽지 못하는 보기(400, 9-A)는 [ViewOption.type]이 null이라 '지원하지 않음'으로 보인다.
 * 연결·속도 제한 같은 그 밖의 오류는 던져서 목록 전체를 실패로 알린다(일부만 '지원하지 않음'으로 보이면 오해하게 된다).
 */
internal suspend fun readViewOption(reader: ViewReader, viewId: String, registered: Set<String>): ViewOption {
    val view = try {
        reader.view(viewId)
    } catch (e: NotionException) {
        if (e.error is NotionError.Validation) null else throw e
    }
    return ViewOption(viewId, view?.name.orEmpty(), view?.type, viewId in registered)
}
