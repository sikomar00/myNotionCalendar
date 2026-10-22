package com.ysdoing.notioncal.core.sync

import com.ysdoing.notioncal.core.data.WidgetRefresher
import com.ysdoing.notioncal.core.data.db.VIEW_CELL_SEPARATOR
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

/**
 * VW-01 등록한 보기마다 노션의 보기 설정과 행을 받아 Room 스냅샷으로 바꿔 넣는다. 위젯은 스냅샷만 읽는다.
 * 한 보기가 실패하면 그 보기의 이전 스냅샷을 그대로 두고 오류만 남긴다(VW-06).
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
            try {
                val (meta, rows) = snapshot(registered)
                dao.replace(meta, rows)
                changed = true
            } catch (e: NotionException) {
                dao.markError(registered.viewId, e.error::class.simpleName.orEmpty())
                changed = true
                // 연결·토큰 문제는 다른 보기도 똑같이 실패하므로 더 부르지 않는다.
                if (e.error == NotionError.Network || e.error == NotionError.Unauthorized) break
            }
        }
        if (changed) widgets.requestUpdate()
    }

    private suspend fun snapshot(registered: ViewSnapshotEntity): Pair<ViewSnapshotEntity, List<ViewRowEntity>> {
        val view = reader.view(registered.viewId)
        val pages = reader.rows(registered.dataSourceId, view)
        val columns = view.configuration?.properties.orEmpty().filter { it.visible }.map { it.propertyName }
            .ifEmpty { listOfNotNull(pages.firstOrNull()?.titlePropertyName()) }
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
