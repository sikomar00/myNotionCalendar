package com.ysdoing.notioncal.core.data.view

import com.ysdoing.notioncal.core.data.OutboxRequester
import com.ysdoing.notioncal.core.data.WidgetRefresher
import com.ysdoing.notioncal.core.data.db.VIEW_CELL_SEPARATOR
import com.ysdoing.notioncal.core.data.db.VIEW_WRITE_CREATE
import com.ysdoing.notioncal.core.data.db.ViewDao
import com.ysdoing.notioncal.core.data.db.ViewRowEntity
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
 * 숫자는 마지막으로 받은 값에 더한다(노션에서 그 사이 바뀐 값과의 충돌은 보지 않는다, 일정 아웃박스와 다르다). 책 위젯의 ±쪽도 같다:
 * 노션에서 읽은 쪽을 고친 뒤 동기화되기 전에 위젯에서 누르면 낡은 값에 더한 값이 노션을 덮는다(동기화는 열 때·30분마다).
 */
@Singleton
class ViewWriter @Inject constructor(
    private val dao: ViewDao,
    private val widgets: WidgetRefresher,
    private val requester: OutboxRequester,
    private val clock: Clock,
) {
    /** 체크박스를 뒤집는다. */
    suspend fun toggle(viewId: String, pageId: String, propId: String): Boolean = change(viewId, pageId, propId, "checkbox") { _, current, _ ->
        JsonPrimitive((current as? JsonPrimitive)?.booleanOrNull != true)
    }

    /** select·status를 다음 선택지로 바꾼다(끝이면 처음). */
    suspend fun cycle(viewId: String, pageId: String, propId: String): Boolean = change(viewId, pageId, propId, "select", "status") { column, current, _ ->
        column.nextOption((current as? JsonPrimitive)?.contentOrNull)?.let { JsonPrimitive(it.id) }
    }

    /**
     * 속성 하나에 [value]를 쓴다(Phase 10-5, [ViewColumn.WRITABLE_TYPES] 전부). 값의 모양은 [ViewColumn.display]에 적혀 있고,
     * 열 종류에 맞지 않으면(또는 없는 열·행이면) 아무것도 하지 않고 false다. 바꾸기 전 값이 큐의 기준값이 된다(전송 전 충돌 확인).
     */
    suspend fun set(viewId: String, pageId: String, propId: String, value: JsonElement): Boolean =
        change(viewId, pageId, propId, *ViewColumn.WRITABLE_TYPES.toTypedArray()) { column, _, _ -> value.takeIf { column.accepts(it) } }

    /** 숫자에 [delta]를 더한다(값이 없으면 0에서 시작). 0.1을 여러 번 더해도 0.30000000000000004가 되지 않게 10진수로 더한다. */
    suspend fun add(viewId: String, pageId: String, propId: String, delta: Double): Boolean = change(viewId, pageId, propId, "number") { _, current, _ ->
        val base = current.number() ?: 0.0
        JsonPrimitive(BigDecimal.valueOf(base).add(BigDecimal.valueOf(delta)).toDouble())
    }

    /**
     * 책 위젯 ±쪽: 읽은 쪽 속성(보기에서 숨겨져 있어도 된다)에 [delta]쪽을 더한다. 0 아래로, 전체 쪽(알면)을 넘어 가지 않는다.
     * 읽은 비율은 읽은 쪽÷전체 쪽으로 바로 다시 계산해 막대가 움직이고(노션 수식과 같다고 보는 근사, 다음 동기화가 맞춘다), 바뀐 것이 없으면 false.
     */
    suspend fun addPages(viewId: String, pageId: String, delta: Int): Boolean {
        val columns = ViewJson.columns(dao.snapshot(viewId)?.columnInfo.orEmpty())
        val read = columns.firstOrNull { it.role == ViewColumn.ROLE_PAGES_READ } ?: return false
        val total = columns.firstOrNull { it.role == ViewColumn.ROLE_PAGES_TOTAL }
        return change(
            viewId, pageId, read.id, "number",
            after = { row, typed -> row.copy(progress = readFraction(typed, read, total) ?: row.progress) },
        ) { _, current, typed ->
            val before = current.number() ?: 0.0
            // 이미 전체 쪽을 넘은 값(전체 쪽을 잘못 적은 경우)은 +가 깎지 않는다: 상한은 지금 값과 전체 쪽 중 큰 쪽이다.
            val limit = maxOf(totalPages(typed, total) ?: Double.MAX_VALUE, before)
            val after = (before + delta).coerceIn(0.0, limit)
            if (after == before) null else JsonPrimitive(after)
        }
    }

    private fun readFraction(typed: Map<String, JsonElement>, read: ViewColumn, total: ViewColumn?): Float? {
        val pages = totalPages(typed, total) ?: return null
        return ((typed[read.id].number() ?: 0.0) / pages).toFloat().coerceIn(0f, 1f)
    }

    private fun totalPages(typed: Map<String, JsonElement>, total: ViewColumn?): Double? = total?.let { typed[it.id].number() }?.takeIf { it > 0 }

    private fun JsonElement?.number(): Double? = (this as? JsonPrimitive)?.doubleOrNull

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
        after: (ViewRowEntity, Map<String, JsonElement>) -> ViewRowEntity = { row, _ -> row },
        next: (ViewColumn, JsonElement?, Map<String, JsonElement>) -> JsonElement?,
    ): Boolean {
        val changed = dao.mutate(viewId, pageId) { snapshot, row ->
            val column = ViewJson.columns(snapshot.columnInfo).firstOrNull { it.id == propId && it.type in types } ?: return@mutate null
            val typed = ViewJson.typed(row.typed)
            val value = next(column, typed[propId], typed) ?: return@mutate null
            val cells = row.cells.split(VIEW_CELL_SEPARATOR).toMutableList()
            val at = snapshot.columns.split(VIEW_CELL_SEPARATOR).indexOf(column.name)
            if (at in cells.indices) cells[at] = column.display(value)
            val write = ViewWriteEntity(
                viewId = viewId, pageId = pageId, dataSourceId = snapshot.dataSourceId, propId = propId, propType = column.type,
                value = ViewJson.valueText(value), createdAt = clock.millis(), baseValue = typed[propId]?.toString(),
            )
            val changedTyped = typed + (propId to value)
            after(row.copy(cells = cells.joinToString(VIEW_CELL_SEPARATOR), typed = ViewJson.encodeTyped(changedTyped)), changedTyped) to write
        }
        if (changed) {
            widgets.requestUpdate()
            requester.request()
        }
        return changed
    }
}
