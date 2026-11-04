package com.ysdoing.notioncal.core.data.view

import com.ysdoing.notioncal.core.data.WidgetRefresher
import com.ysdoing.notioncal.core.data.db.VIEW_CELL_SEPARATOR
import com.ysdoing.notioncal.core.data.db.VIEW_ERROR_NOT_FOUND
import com.ysdoing.notioncal.core.data.db.VIEW_ERROR_UNSUPPORTED
import com.ysdoing.notioncal.core.data.db.ViewDao
import com.ysdoing.notioncal.core.data.db.ViewSnapshotEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject
import javax.inject.Singleton

/** [coverUrl]·[progress]는 책 위젯(M5)용: 표지 주소(없으면 빈 글자)와 읽은 비율 0~1(없으면 null). */
data class ViewRow(val pageId: String, val url: String, val cells: List<String>, val coverUrl: String = "", val progress: Float? = null)

/** VW-03 위젯이 그리는 보기 한 장. [syncedAt]이 0이면 아직 못 받았고, [lastError]가 있으면 마지막 동기화가 실패했다. */
data class ViewBoard(
    val viewId: String,
    val name: String,
    val type: String,
    val columns: List<String>,
    val rows: List<ViewRow>,
    val syncedAt: Long,
    val lastError: String?,
    /** 쓰기 정보(종류·선택지, 제목·날짜 열 포함). 보이는 속성에 더해 책 위젯의 숨긴 읽은 쪽·전체 쪽이 `role`과 함께 들어 있을 수 있다(열 이름 목록 [columns]에는 없다). 비어 있으면 아직 받지 못해 바꾸거나 만들 수 없다. */
    val columnInfo: List<ViewColumn> = emptyList(),
    /** 노션 보기 `configuration` 원본 JSON(보드의 `group_by` 등, Phase 11a). 없으면 빈 글자. */
    val viewConfig: String = "",
    /** 행을 상한 안에서 다 받았는가. false면 노션에 더 있다('N+ 더 보기'). */
    val rowsComplete: Boolean = true,
) {
    /** 새 항목을 만들 수 있다(제목 속성을 안다). 위젯 '+'와 입력 시트가 이것으로 정한다. */
    val canCreate: Boolean get() = columnInfo.any { it.type == "title" }

    /** 새 항목에 날짜를 넣을 열이 있다. */
    val hasDateColumn: Boolean get() = columnInfo.any { it.type == "date" }

    /** M5b 책 위젯의 ±쪽: 읽은 쪽 속성을 안다(보기에서 숨겨져 있어도 된다). */
    val canAdjustPages: Boolean get() = columnInfo.any { it.role == ViewColumn.ROLE_PAGES_READ }

    /** [index]번째 열이 위젯에서 눌러 바꿀 수 있는 속성이면 그 정보, 아니면 null. */
    fun tappableAt(index: Int): ViewColumn? =
        columns.getOrNull(index)?.let { name -> columnInfo.firstOrNull { it.name == name && it.tappable } }

    /** 노션에서 보기를 찾을 수 없다(지워졌거나 권한이 없음). 네트워크 실패 같은 일시 오류는 아니다. */
    val notFound: Boolean get() = lastError == VIEW_ERROR_NOT_FOUND

    /** 노션이 이 보기를 400으로 거부한다(앱이 설정·행을 읽을 수 없는 종류의 보기). 일시 오류가 아니라 다른 보기를 골라야 한다. */
    val unsupported: Boolean get() = lastError == VIEW_ERROR_UNSUPPORTED
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
                    rows = rows.map { r -> ViewRow(r.pageId, r.url, r.cells.split(VIEW_CELL_SEPARATOR), r.coverUrl, r.progress) },
                    syncedAt = it.syncedAt,
                    lastError = it.lastError,
                    columnInfo = ViewJson.columns(it.columnInfo),
                    viewConfig = it.viewConfig,
                    rowsComplete = it.rowsComplete,
                )
            }
        }

    suspend fun remove(viewId: String) {
        dao.remove(viewId)
        widgetRefresher.requestUpdate()
    }
}
