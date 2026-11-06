package com.ysdoing.notioncal.core.data.view

import com.ysdoing.notioncal.core.data.WidgetRefresher
import com.ysdoing.notioncal.core.data.db.VIEW_CELL_SEPARATOR
import com.ysdoing.notioncal.core.data.db.VIEW_ERROR_NOT_FOUND
import com.ysdoing.notioncal.core.data.db.VIEW_ERROR_UNSUPPORTED
import com.ysdoing.notioncal.core.data.db.ViewDao
import com.ysdoing.notioncal.core.data.db.ViewSnapshotEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.coroutines.flow.combine
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/** [coverUrl]·[progress]는 책 위젯(M5)용: 표지 주소(없으면 빈 글자)와 읽은 비율 0~1(없으면 null). */
data class ViewRow(
    val pageId: String,
    val url: String,
    val cells: List<String>,
    val coverUrl: String = "",
    val progress: Float? = null,
    /** 쓸 수 있는 열의 원래 값(속성 ID → 체크 bool·옵션 ID·숫자·글자 등, [ViewColumn.display] 참고). 칩 색·보드 그룹·편집 시트가 쓴다. */
    val typed: Map<String, JsonElement> = emptyMap(),
)

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
    /** 지연이 걸린 쓰기들의 전송 시각(Phase 11c). 지금보다 뒤인 것이 '되돌릴 수 있는' 쓰기다([undoableAt]). */
    val pendingSendAfter: List<Long> = emptyList(),
    /** 노션 보기 필터 JSON(Phase 11c). 없으면 빈 글자. */
    val filterJson: String = "",
    /** 바꾸고 아직 노션에 보내지 않은 행 ID. 이 행이 필터에서 벗어나면 '빠지는 중'이다([isLeaving]). */
    val pendingPageIds: Set<String> = emptySet(),
    /** 노션에 아직 만들러 보내지 않은 새 항목의 날짜(Phase 11h '하루 한 줄' 가드, [takenDates]). */
    val pendingCreateDates: Set<LocalDate> = emptySet(),
    /**
     * 노션에 보낸 바꿈 중 행의 값이 아직 그 값인 것의 보낸 시각(Phase 12c). 보낸 뒤 [ViewWriter.SENT_UNDO_WINDOW_MS] 안이면 위젯이
     * '보낸 변경 되돌리기'를 준다([sentUndoableAt]). 그 뒤에 사용자가 같은 칸을 또 바꿨다면 되돌릴 것이 아니라 빠진다.
     */
    val sentUndoAt: List<Long> = emptyList(),
    /** 아직 보내지 않은 새 항목이 체크한 속성(날짜 → 속성 ID들, Phase 12e). 해빗 격자가 대기 중인 오늘 페이지를 '체크됨'으로 보인다. */
    val pendingCreateChecks: Map<LocalDate, Set<String>> = emptyMap(),
) {
    private val filter: ViewFilter by lazy { ViewFilter.parse(filterJson) }

    /**
     * IX-06 필터 이탈: 방금 바꿔 아직 보내지 않은 행이 보기 필터에서 빠졌으면 true(위젯이 흐림 + 취소선으로 그리고 전송 뒤 보기를 다시 받아 지운다).
     * 바꾸지 않은 행과 폰에서 판정할 수 없는 필터는 false다(어긋난 판정으로 멀쩡한 행을 흐리게 하지 않으려고).
     */
    fun isLeaving(row: ViewRow): Boolean = row.pageId in pendingPageIds && filter.matches(columnInfo, row.typed) == FilterMatch.NO

    /** [now]에 되돌릴 수 있는 쓰기 수. 전송 시각이 되면(같거나 지나면) 보낼 쓰기라 되돌릴 수 없다. */
    fun undoableAt(now: Long): Int = pendingSendAfter.count { it > now }

    /** [now]에 되돌릴 수 있는 보낸 변경 수(보낸 뒤 [ViewWriter.SENT_UNDO_WINDOW_MS] 안). */
    fun sentUndoableAt(now: Long): Int = sentUndoAt.count { it >= now - ViewWriter.SENT_UNDO_WINDOW_MS }

    /** 새 항목을 만들 수 있다(제목 속성을 안다). 위젯 '+'와 입력 시트가 이것으로 정한다. */
    val canCreate: Boolean get() = columnInfo.any { it.type == "title" }

    /**
     * 새 항목의 날짜가 들어가는 날짜 열([dateColumn])에 이미 항목이 있는 날짜들: 받아 둔 행과 아직 보내지 않은 새 항목. 날짜 열이 없으면 비어 있다.
     * 행은 상한(100) 안에서만 알아서 그보다 먼 날짜는 못 볼 수 있다.
     */
    fun takenDates(): Set<LocalDate> {
        val prop = dateColumn?.id ?: return emptySet()
        return rows.mapNotNull { it.typed[prop].startDate() }.toSet() + pendingCreateDates
    }

    /** 새 항목의 날짜가 들어가는 열이자 캘린더가 읽는 열([ViewColumn.dateColumnOf]). 없으면 null. */
    val dateColumn: ViewColumn? get() = ViewColumn.dateColumnOf(columnInfo)

    /** 새 항목에 날짜를 넣을 열이 있다. */
    val hasDateColumn: Boolean get() = dateColumn != null

    /** 새 항목 시트·입력 양식이 다루는 속성([InputForm.usable]). */
    val formColumns: List<ViewColumn> get() = InputForm.usable(columnInfo, columns)

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
        combine(
            dao.observe(viewId), dao.observeRows(viewId), combine(dao.observePendingSendAfter(viewId), dao.observeSent(viewId)) { pending, sent -> pending to sent },
            dao.observePendingPageIds(viewId), dao.observePendingCreateValues(viewId),
        ) { view, rows, (pending, sent), pendingPages, createValues ->
            val creates = createValues.mapNotNull(ViewJson::parseCreate)
            view?.let {
                ViewBoard(
                    viewId = it.viewId,
                    name = it.name,
                    type = it.viewType,
                    columns = it.columns.split(VIEW_CELL_SEPARATOR).filter(String::isNotEmpty),
                    rows = rows.map { r -> ViewRow(r.pageId, r.url, r.cells.split(VIEW_CELL_SEPARATOR), r.coverUrl, r.progress, ViewJson.typed(r.typed)) },
                    syncedAt = it.syncedAt,
                    lastError = it.lastError,
                    columnInfo = ViewJson.columns(it.columnInfo),
                    viewConfig = it.viewConfig,
                    rowsComplete = it.rowsComplete,
                    pendingSendAfter = pending,
                    filterJson = it.filterJson,
                    pendingPageIds = pendingPages.toSet(),
                    pendingCreateDates = creates.mapNotNull { it.date?.dateOrNull() }.toSet(),
                    pendingCreateChecks = creates.checkedByDate(),
                    sentUndoAt = sent.filter { s -> s.holdsIn(rows.firstOrNull { r -> r.pageId == s.pageId }) }.map { s -> s.sentAt },
                )
            }
        }

    /** 새 항목들이 체크한 속성을 날짜별로 모은다(같은 날 여럿이면 합친다). 날짜가 없는 새 항목은 뺀다. */
    private fun List<ViewCreate>.checkedByDate(): Map<LocalDate, Set<String>> =
        mapNotNull { create -> create.date?.dateOrNull()?.let { it to create.props.filterValues { v -> v == JsonPrimitive(true) }.keys } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, sets) -> sets.flatten().toSet() }

    suspend fun remove(viewId: String) {
        dao.remove(viewId)
        widgetRefresher.requestUpdate()
    }
}
