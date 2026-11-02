package com.ysdoing.notioncal.core.sync

import com.ysdoing.notioncal.core.data.WidgetRefresher
import com.ysdoing.notioncal.core.data.db.VIEW_CELL_SEPARATOR
import com.ysdoing.notioncal.core.data.db.VIEW_ERROR_NOT_FOUND
import com.ysdoing.notioncal.core.data.db.ViewDao
import com.ysdoing.notioncal.core.data.db.ViewRowEntity
import com.ysdoing.notioncal.core.data.db.ViewSnapshotEntity
import com.ysdoing.notioncal.core.notion.NotionError
import com.ysdoing.notioncal.core.notion.NotionException
import com.ysdoing.notioncal.core.notion.ViewReader
import com.ysdoing.notioncal.core.notion.dto.PageDto
import com.ysdoing.notioncal.core.notion.mapping.displayValue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

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
) {
    suspend fun syncAll() {
        var changed = false
        for (registered in dao.all()) {
            val error = syncOne(registered)
            changed = true
            // 연결·토큰·속도 제한·서버 장애는 다른 보기도 똑같이 실패하므로 더 부르지 않는다.
            if (error != null && error.isSharedAcrossViews()) break
        }
        if (changed) widgets.requestUpdate()
    }

    /** 방금 등록한 보기 하나만 받는다(등록 화면). 다른 보기를 기다리게 하지 않는다. */
    suspend fun sync(viewId: String) {
        val registered = dao.all().firstOrNull { it.viewId == viewId } ?: return
        syncOne(registered)
        widgets.requestUpdate()
    }

    /** 보기 하나를 받아 스냅샷을 바꾼다. 실패하면 이전 스냅샷을 두고 오류만 남기며, 노션 오류는 돌려준다(성공이거나 예상 밖 예외면 null). */
    private suspend fun syncOne(registered: ViewSnapshotEntity): NotionError? = try {
        val (meta, rows) = snapshot(registered)
        dao.replace(meta, rows)
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

    private suspend fun snapshot(registered: ViewSnapshotEntity): Pair<ViewSnapshotEntity, List<ViewRowEntity>> {
        val view = reader.view(registered.viewId)
        val pages = reader.rows(registered.dataSourceId, view)
        val configured = view.configuration?.properties.orEmpty()
        val columns = visibleColumns(configured.map { it.propertyName to it.visible }, pages.firstOrNull()?.titlePropertyName())
        val meta = registered.copy(
            name = view.name,
            viewType = view.type,
            columns = columns.joinToString(VIEW_CELL_SEPARATOR),
            syncedAt = clock.millis(),
            lastError = null,
        )
        val rows = pages.mapIndexed { i, page ->
            val cells = columns.joinToString(VIEW_CELL_SEPARATOR) { displayValue(page.properties[it] as? JsonObject ?: JsonObject(emptyMap())) }
            ViewRowEntity(registered.viewId, i, page.id, page.url, cells)
        }
        return meta to rows
    }

    private fun PageDto.titlePropertyName(): String? =
        properties.entries.firstOrNull { ((it.value as? JsonObject)?.get("type") as? JsonPrimitive)?.contentOrNull == "title" }?.key
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
private fun NotionError.storedName(): String = when (this) {
    NotionError.NotShared -> VIEW_ERROR_NOT_FOUND
    NotionError.Unauthorized -> "Unauthorized"
    is NotionError.Validation -> "Validation"
    NotionError.RateLimited -> "RateLimited"
    NotionError.Network -> "Network"
    is NotionError.Server -> "Server"
}
