package com.ysdoing.notioncal.core.data.view

import com.ysdoing.notioncal.core.data.OutboxRequester
import com.ysdoing.notioncal.core.data.WidgetRefresher
import com.ysdoing.notioncal.core.data.db.VIEW_CELL_SEPARATOR
import com.ysdoing.notioncal.core.data.db.VIEW_WRITE_CREATE
import com.ysdoing.notioncal.core.data.db.ViewDao
import com.ysdoing.notioncal.core.data.db.ViewWriteEntity
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 보기 위젯 행의 속성을 바꾼다(체크 토글·상태 순환·숫자 증감). 화면·위젯은 이것만 부르고 노션 API는 모른다.
 * 로컬 행을 먼저 고치고(위젯에 바로 반영) 보내지 않은 바꿈 큐(view_writes)에 쌓는다. 전송은 :core:sync의 처리기가 한다.
 * 바꿀 수 없는 속성이거나 행·열 정보가 없으면 아무것도 하지 않고 false를 돌려준다.
 * 현재 값을 읽어 새 값을 만들고 저장하기까지가 한 트랜잭션이라, 이어 누른 탭이 서로의 값을 덮지 않는다.
 * 숫자는 마지막으로 받은 값에 더한다(노션에서 그 사이 바뀐 값과의 충돌은 보지 않는다, 일정 아웃박스와 다르다).
 */
@Singleton
class ViewWriter @Inject constructor(
    private val dao: ViewDao,
    private val widgets: WidgetRefresher,
    private val requester: OutboxRequester,
    private val clock: Clock,
) {
    /** 체크박스를 뒤집는다. */
    suspend fun toggle(viewId: String, pageId: String, propId: String): Boolean = change(viewId, pageId, propId, "checkbox") { _, current ->
        JsonPrimitive((current as? JsonPrimitive)?.booleanOrNull != true)
    }

    /** select·status를 다음 선택지로 바꾼다(끝이면 처음). */
    suspend fun cycle(viewId: String, pageId: String, propId: String): Boolean = change(viewId, pageId, propId, "select", "status") { column, current ->
        column.nextOption((current as? JsonPrimitive)?.contentOrNull)?.let { JsonPrimitive(it.id) }
    }

    /** 숫자에 [delta]를 더한다(값이 없으면 0에서 시작). 0.1을 여러 번 더해도 0.30000000000000004가 되지 않게 10진수로 더한다. */
    suspend fun add(viewId: String, pageId: String, propId: String, delta: Double): Boolean = change(viewId, pageId, propId, "number") { _, current ->
        val base = (current as? JsonPrimitive)?.doubleOrNull ?: 0.0
        JsonPrimitive(BigDecimal.valueOf(base).add(BigDecimal.valueOf(delta)).toDouble())
    }

    /**
     * 새 항목(제목 + 선택 날짜)을 만든다. 로컬 행은 만들지 않고 큐에만 쌓는다. 노션이 만든 뒤 처리기가 이 보기를 바로 다시 받아 행이 나타난다.
     * 제목이 비었거나 보기에 제목 열 정보가 없으면 false. 보기에 날짜 열이 없으면 날짜는 넣지 않는다(첫 날짜 열에 넣는다).
     * shortcut: 노션이 만들었는데 응답이 끊기면(시간 초과·연결 끊김·5xx) 처리기가 다시 보내 같은 항목이 둘 생길 수 있다(일정 아웃박스의
     * '이미 만들었나' 확인이 없다), 문제가 되면 만든 시각과 제목으로 찾아본다.
     */
    suspend fun create(viewId: String, title: String, date: LocalDate?): Boolean {
        val name = title.trim()
        if (name.isEmpty()) return false
        val snapshot = dao.snapshot(viewId) ?: return false
        val columns = ViewJson.columns(snapshot.columnInfo)
        val titleColumn = columns.firstOrNull { it.type == "title" } ?: return false
        val dateColumn = columns.firstOrNull { it.type == "date" }?.takeIf { date != null }
        val spec = ViewCreate(name, date?.takeIf { dateColumn != null }?.toString(), dateColumn?.id)
        val queued = dao.enqueueCreate(
            ViewWriteEntity(
                viewId = viewId, pageId = "new-${UUID.randomUUID()}", dataSourceId = snapshot.dataSourceId, propId = titleColumn.id,
                propType = VIEW_WRITE_CREATE, value = ViewJson.encodeCreate(spec), createdAt = clock.millis(),
            ),
        )
        if (queued) requester.request()
        return queued
    }

    private suspend fun change(
        viewId: String,
        pageId: String,
        propId: String,
        vararg types: String,
        next: (ViewColumn, JsonElement?) -> JsonElement?,
    ): Boolean {
        val changed = dao.mutate(viewId, pageId) { snapshot, row ->
            val column = ViewJson.columns(snapshot.columnInfo).firstOrNull { it.id == propId && it.type in types } ?: return@mutate null
            val typed = ViewJson.typed(row.typed)
            val value = next(column, typed[propId]) ?: return@mutate null
            val cells = row.cells.split(VIEW_CELL_SEPARATOR).toMutableList()
            val at = snapshot.columns.split(VIEW_CELL_SEPARATOR).indexOf(column.name)
            if (at in cells.indices) cells[at] = column.display(value)
            val write = ViewWriteEntity(
                viewId = viewId, pageId = pageId, dataSourceId = snapshot.dataSourceId, propId = propId, propType = column.type,
                value = ViewJson.valueText(value), createdAt = clock.millis(),
            )
            row.copy(cells = cells.joinToString(VIEW_CELL_SEPARATOR), typed = ViewJson.encodeTyped(typed + (propId to value))) to write
        }
        if (changed) {
            widgets.requestUpdate()
            requester.request()
        }
        return changed
    }
}
