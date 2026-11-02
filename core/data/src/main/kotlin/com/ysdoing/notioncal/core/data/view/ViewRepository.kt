package com.ysdoing.notioncal.core.data.view

import com.ysdoing.notioncal.core.data.WidgetRefresher
import com.ysdoing.notioncal.core.data.db.VIEW_CELL_SEPARATOR
import com.ysdoing.notioncal.core.data.db.VIEW_ERROR_NOT_FOUND
import com.ysdoing.notioncal.core.data.db.ViewDao
import com.ysdoing.notioncal.core.data.db.ViewSnapshotEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject
import javax.inject.Singleton

data class ViewRow(val pageId: String, val url: String, val cells: List<String>)

/** VW-03 위젯이 그리는 보기 한 장. [syncedAt]이 0이면 아직 못 받았고, [lastError]가 있으면 마지막 동기화가 실패했다. */
data class ViewBoard(
    val viewId: String,
    val name: String,
    val type: String,
    val columns: List<String>,
    val rows: List<ViewRow>,
    val syncedAt: Long,
    val lastError: String?,
) {
    /** 노션에서 보기를 찾을 수 없다(지워졌거나 권한이 없음). 네트워크 실패 같은 일시 오류는 아니다. */
    val notFound: Boolean get() = lastError == VIEW_ERROR_NOT_FOUND
}

/** VW-04·06 등록한 보기와 스냅샷을 Room에서 읽는다. 네트워크는 쓰지 않는다. */
@Singleton
class ViewRepository @Inject constructor(
    private val dao: ViewDao,
    private val widgetRefresher: WidgetRefresher,
) {
    fun observeRegistered(): Flow<List<ViewSnapshotEntity>> = dao.observeAll()

    /** 등록하지 않았거나 지워졌으면 null. */
    fun observe(viewId: String): Flow<ViewBoard?> =
        combine(dao.observe(viewId), dao.observeRows(viewId)) { view, rows ->
            view?.let {
                ViewBoard(
                    viewId = it.viewId,
                    name = it.name,
                    type = it.viewType,
                    columns = it.columns.split(VIEW_CELL_SEPARATOR).filter(String::isNotEmpty),
                    rows = rows.map { r -> ViewRow(r.pageId, r.url, r.cells.split(VIEW_CELL_SEPARATOR)) },
                    syncedAt = it.syncedAt,
                    lastError = it.lastError,
                )
            }
        }

    suspend fun remove(viewId: String) {
        dao.remove(viewId)
        widgetRefresher.requestUpdate()
    }
}
