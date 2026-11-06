package com.ysdoing.notioncal.core.data.view

import com.ysdoing.notioncal.core.data.APP_ZONE
import com.ysdoing.notioncal.core.data.OutboxRequester
import com.ysdoing.notioncal.core.data.WidgetRefresher
import com.ysdoing.notioncal.core.data.db.EventDao
import com.ysdoing.notioncal.core.data.db.VIEW_CELL_SEPARATOR
import com.ysdoing.notioncal.core.data.db.VIEW_WRITE_CREATE
import com.ysdoing.notioncal.core.data.db.SentWriteEntity
import com.ysdoing.notioncal.core.data.db.ViewDao
import com.ysdoing.notioncal.core.data.db.ViewRowEntity
import com.ysdoing.notioncal.core.data.db.ViewSnapshotEntity
import com.ysdoing.notioncal.core.data.db.ViewWriteEntity
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
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
 * 숫자는 마지막으로 받은 값에 더하고(책 위젯의 ±쪽도 같다), 바꾸기 전 값을 기준값으로 큐에 남긴다. 전송하는 쪽(:core:sync의
 * `ViewWriteProcessor`)이 보내기 직전에 노션 값을 읽어, 그 사이 노션에서 바뀌었다면 노션 값을 남기고 충돌로 기록한다(SY-05).
 */
@Singleton
class ViewWriter @Inject constructor(
    private val dao: ViewDao,
    private val events: EventDao,
    private val widgets: WidgetRefresher,
    private val requester: OutboxRequester,
    private val clock: Clock,
) {
    /**
     * 체크박스를 뒤집는다. [sendDelayMs]가 있으면(위젯의 즉시 동작은 [UNDO_WINDOW_MS]) 그만큼 전송을 미뤄 연타를 한 번의 전송으로 합치고
     * 그 사이 되돌릴 수 있게 한다. 0이면 바로 보낸다(시트 저장 등).
     */
    suspend fun toggle(viewId: String, pageId: String, propId: String, sendDelayMs: Long = 0): Boolean =
        change(viewId, pageId, propId, "checkbox", sendDelayMs = sendDelayMs) { _, current, _ ->
            JsonPrimitive((current as? JsonPrimitive)?.booleanOrNull != true)
        }

    /** select·status를 다음 선택지로 바꾼다(끝이면 처음). [sendDelayMs]는 [toggle]과 같다. */
    suspend fun cycle(viewId: String, pageId: String, propId: String, sendDelayMs: Long = 0): Boolean =
        change(viewId, pageId, propId, "select", "status", sendDelayMs = sendDelayMs) { column, current, _ ->
            column.nextOption((current as? JsonPrimitive)?.contentOrNull)?.let { JsonPrimitive(it.id) }
        }

    /**
     * 속성 하나에 [value]를 쓴다(Phase 10-5, [ViewColumn.WRITABLE_TYPES] 전부). 값의 모양은 [ViewColumn.display]에 적혀 있고,
     * 열 종류에 맞지 않으면(또는 없는 열·행이면) 아무것도 하지 않고 false다. 바꾸기 전 값이 큐의 기준값이 된다(전송 전 충돌 확인).
     */
    suspend fun set(viewId: String, pageId: String, propId: String, value: JsonElement, sendDelayMs: Long = 0): Boolean =
        change(viewId, pageId, propId, *ViewColumn.WRITABLE_TYPES.toTypedArray(), sendDelayMs = sendDelayMs) { column, _, _ ->
            blankToNull(column, value).takeIf { column.accepts(it) }
        }

    /**
     * 한 행의 속성 여러 개를 한 묶음으로 쓴다(행 편집 시트 저장). 같은 묶음 번호라 보낸 뒤 되돌릴 때 한꺼번에 되돌아간다. 하나라도 못 쓰면 false다.
     */
    suspend fun setAll(viewId: String, pageId: String, values: Map<String, JsonElement>): Boolean {
        val batch = clock.millis()
        return values.map { (propId, value) ->
            change(viewId, pageId, propId, *ViewColumn.WRITABLE_TYPES.toTypedArray(), batchId = batch) { column, _, _ ->
                blankToNull(column, value).takeIf { column.accepts(it) }
            }
        }.all { it }
    }

    /**
     * 해빗 체크(Phase 12e RC-03): [day]의 [propId] 습관을 뒤집는다. 그날 행이 있으면 체크를 토글하고, 없으면 아직 안 보낸 그날 새 항목에 합치고
     * (하루 페이지 1개만), 그것도 없으면 제목 'M월 d일'·그 날짜·체크한 습관으로 새 항목을 만든다(첫 체크 때 만든다). 같은 날 행이 여럿이면 앞의 것이다.
     * 보기에 날짜 열이나 그 체크박스가 없으면 false다. 위젯 즉시 동작이라 [sendDelayMs]만큼 미뤄 그 안에 되돌릴 수 있다.
     * shortcut: 받은 행(100개 상한·보기 필터)과 큐만 보니, 그날 행이 가려져 있거나 방금 보낸 새 항목을 아직 다시 받지 못했으면 같은 날 페이지가 둘 생길 수 있다.
     */
    suspend fun checkHabit(viewId: String, propId: String, day: LocalDate, sendDelayMs: Long = UNDO_WINDOW_MS): Boolean {
        val columns = ViewJson.columns(dao.snapshot(viewId)?.columnInfo.orEmpty())
        val date = ViewColumn.dateColumnOf(columns) ?: return false
        if (columns.none { it.id == propId && it.type == "checkbox" }) return false
        val row = dao.rows(viewId).firstOrNull { ViewJson.typed(it.typed)[date.id].startDate() == day }
        if (row != null) return toggle(viewId, row.pageId, propId, sendDelayMs)
        if (flipInPendingCreate(viewId, propId, day)) return true
        return create(viewId, HabitTitle.of(day), day, mapOf(propId to JsonPrimitive(true)), sendDelayMs)
    }

    /** 그날 새 항목이 아직 큐에 있으면 거기서 [propId] 체크를 뒤집는다(체크가 모두 빠지고 제목이 자동 제목이면 그 새 항목은 지운다). 합쳤으면 true. */
    private suspend fun flipInPendingCreate(viewId: String, propId: String, day: LocalDate): Boolean {
        val (write, spec) = dao.pendingCreates(viewId).firstNotNullOfOrNull { w -> ViewJson.parseCreate(w.value)?.takeIf { it.date == day.toString() }?.let { w to it } } ?: return false
        val checked = spec.props[propId] == JsonPrimitive(true)
        val props = if (checked) spec.props - propId else spec.props + (propId to JsonPrimitive(true))
        if (props.isEmpty() && spec.title == HabitTitle.of(day)) dao.deleteWriteById(write.id) else dao.updateWriteValue(write.id, ViewJson.encodeCreate(spec.copy(props = props)))
        widgets.requestUpdate()
        return true
    }

    /** URL·이메일·전화의 공백 글자는 비우기다(노션이 빈 글자를 거부하고, 로컬과 노션의 비어 있음이 어긋나지 않게). */
    private fun blankToNull(column: ViewColumn, value: JsonElement): JsonElement =
        if (column.type in BLANK_IS_EMPTY && value is JsonPrimitive && value.isString && value.content.isBlank()) JsonNull else value

    /** 숫자에 [delta]를 더한다(값이 없으면 0에서 시작). 0.1을 여러 번 더해도 0.30000000000000004가 되지 않게 10진수로 더한다. */
    suspend fun add(viewId: String, pageId: String, propId: String, delta: Double, sendDelayMs: Long = 0): Boolean =
        change(viewId, pageId, propId, "number", sendDelayMs = sendDelayMs) { _, current, _ ->
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
     * 제목이 비었거나 보기에 제목 열 정보가 없으면 false. 보기에 날짜 열이 없으면 날짜는 넣지 않는다(캘린더의 날짜 속성, 없으면 첫 날짜 열에 넣는다).
     * shortcut: 노션이 만들었는데 응답이 끊기면(시간 초과·연결 끊김·5xx) 처리기가 다시 보내 같은 항목이 둘 생길 수 있다(일정 아웃박스의
     * '이미 만들었나' 확인이 없다), 문제가 되면 만든 시각과 제목으로 찾아본다.
     */
    suspend fun create(viewId: String, title: String, date: LocalDate?, props: Map<String, JsonElement> = emptyMap(), sendDelayMs: Long = 0): Boolean {
        val name = title.trim()
        if (name.isEmpty()) return false
        val snapshot = dao.snapshot(viewId) ?: return false
        val columns = ViewJson.columns(snapshot.columnInfo)
        val titleColumn = columns.firstOrNull { it.type == "title" } ?: return false
        val dateColumn = ViewColumn.dateColumnOf(columns)?.takeIf { date != null }
        // 값 묶음(프리셋·맥락 채움)은 쓸 수 있는 속성에 맞는 값만 싣는다. 제목과 날짜 인자가 정한 날짜 속성은 따로 정하니 뺀다(잘못된 값 하나로 항목을 잃지 않게 거른다).
        val extra = props.filter { (id, value) ->
            val column = columns.firstOrNull { it.id == id }
            column != null && column.writable && column.id != titleColumn.id && column.id != dateColumn?.id && value !is JsonNull && column.accepts(value)
        }
        val spec = ViewCreate(name, date?.takeIf { dateColumn != null }?.toString(), dateColumn?.id, extra)
        val queued = dao.enqueueCreate(
            ViewWriteEntity(
                viewId = viewId, pageId = "new-${UUID.randomUUID()}", dataSourceId = snapshot.dataSourceId, propId = titleColumn.id,
                propType = VIEW_WRITE_CREATE, value = ViewJson.encodeCreate(spec), createdAt = clock.millis(), sendAfter = sendAfterOf(sendDelayMs),
            ),
        )
        if (queued) wake(sendDelayMs)
        return queued
    }

    /**
     * 행(노션 페이지)을 휴지통으로 보낸다(ED-05, `in_trash`라 노션에서 복원할 수 있고 영구 삭제는 없다). 모든 보기에서 그 행을 바로 지우고 큐에 쌓는다.
     * 보기나 행이 없으면 아무것도 하지 않고 false다.
     */
    suspend fun trash(viewId: String, pageId: String): Boolean {
        val snapshot = dao.snapshot(viewId) ?: return false
        val row = dao.row(viewId, pageId) ?: return false
        val queued = dao.enqueueTrash(viewId, pageId, titleOf(snapshot, row), clock.millis())
        if (queued) {
            events.delete(listOf(pageId)) // 이 DB가 일정 DB로도 등록돼 있으면 월 화면의 일정도 바로 사라진다(일정 시트의 삭제와 같다)
            widgets.requestUpdate()
            requester.request()
        }
        return queued
    }

    /**
     * 액션 버튼(Phase 12c IX-07): [action]의 변경을 이 행에 차례로 적용한다. 날짜 연산은 변경마다 그 순간 값에서 계산하고, 변경들은 같은 묶음 번호를 달고
     * 위젯 즉시 동작처럼 [UNDO_WINDOW_MS] 뒤에 보내져 그 안에 되돌릴 수 있다. 하나라도 적용할 수 없으면(없는 행·속성, 맞지 않는 값) 아무것도 쓰지 않고 false다
     * (다시 눌렀을 때 '하루 뒤로' 같은 상대 변경이 두 번 먹지 않게 먼저 모두 확인한다).
     */
    suspend fun applyAction(viewId: String, pageId: String, action: ViewAction): Boolean {
        if (!canApply(viewId, pageId, action)) return false
        val batch = clock.millis()
        val today = LocalDate.now(clock.withZone(APP_ZONE))
        return action.changes.map { step ->
            val types = if (step.op == ActionOp.SET) ViewColumn.WRITABLE_TYPES.toTypedArray() else arrayOf("date")
            change(viewId, pageId, step.propId, *types, sendDelayMs = UNDO_WINDOW_MS, batchId = batch) { column, current, _ -> actionValue(step, column, current, today) }
        }.all { it }
    }

    /** 액션의 모든 변경이 이 행에 적용될 수 있는가: 행과 속성이 있고, 종류가 맞고, 쓸 값이 열에 맞는다. */
    private suspend fun canApply(viewId: String, pageId: String, action: ViewAction): Boolean {
        val columns = ViewJson.columns(dao.snapshot(viewId)?.columnInfo.orEmpty())
        if (dao.row(viewId, pageId) == null) return false
        return action.changes.all { step ->
            val column = columns.firstOrNull { it.id == step.propId } ?: return@all false
            if (step.op == ActionOp.SET) column.writable && setValueOf(column, step.value)?.let(column::accepts) == true else column.type == "date"
        }
    }

    private fun actionValue(change: ActionChange, column: ViewColumn, current: JsonElement?, today: LocalDate): JsonElement? = when (change.op) {
        ActionOp.SET -> setValueOf(column, change.value)?.takeIf { column.accepts(it) }
        ActionOp.SHIFT_DAYS -> (TypedDate.parse(current) ?: TypedDate(today.toString())).shiftedDays(change.days.toLong()).toJson()
        ActionOp.TODAY -> (TypedDate.parse(current)?.withStart(today) ?: TypedDate(today.toString())).toJson()
    }

    /** 액션의 글자 값을 열 종류에 맞는 JSON으로(체크박스 true/false, 숫자, 그 밖은 글자·옵션 ID). 숫자가 아닌 숫자 글자는 null. */
    private fun setValueOf(column: ViewColumn, text: String): JsonElement? = when (column.type) {
        "checkbox" -> JsonPrimitive(text == "true")
        "number" -> ViewActions.parseNumber(text)?.let(::JsonPrimitive)
        else -> JsonPrimitive(text)
    }

    /** 행의 제목: 제목 속성이 보기에 보이면 그 칸, 아니면 빈 글자(실패 기록의 제목이 된다). */
    private fun titleOf(snapshot: ViewSnapshotEntity, row: ViewRowEntity): String {
        val titleName = ViewJson.columns(snapshot.columnInfo).firstOrNull { it.type == "title" }?.name ?: return ""
        val at = snapshot.columns.split(VIEW_CELL_SEPARATOR).indexOf(titleName)
        return row.cells.split(VIEW_CELL_SEPARATOR).getOrNull(at).orEmpty()
    }

    private suspend fun change(
        viewId: String,
        pageId: String,
        propId: String,
        vararg types: String,
        sendDelayMs: Long = 0,
        batchId: Long = 0,
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
                value = ViewJson.valueText(value), createdAt = clock.millis(), baseValue = typed[propId]?.toString(), sendAfter = sendAfterOf(sendDelayMs), batchId = batchId,
            )
            val changedTyped = typed + (propId to value)
            after(row.copy(cells = cells.joinToString(VIEW_CELL_SEPARATOR), typed = ViewJson.encodeTyped(changedTyped)), changedTyped) to write
        }
        if (changed) {
            widgets.requestUpdate()
            wake(sendDelayMs)
        }
        return changed
    }

    /**
     * 되돌리기(IX-05): 그 보기에서 아직 지연 중인 쓰기(위젯 즉시 동작 뒤 4초 안)를 모두 지우고 행을 처음(노션에 있던) 값으로 되돌린다.
     * 연타로 합쳐진 쓰기도 처음 값으로 돌아간다. 새 항목은 쓰기만 지운다. 이미 보낼 때가 된 쓰기는 건드리지 않는다. 되돌린 쓰기 수를 돌려준다.
     */
    suspend fun undoPending(viewId: String): Int {
        val undone = dao.undoDelayed(viewId, clock.millis(), ::restoredRow)
        if (undone > 0) widgets.requestUpdate()
        return undone
    }

    /** [write]를 쌓기 전 값([ViewWriteEntity.baseValue], 없으면 값 없음)으로 행의 칸과 원래 값을 되돌린다. 열을 모르면 null(그대로 둔다). */
    private fun restoredRow(snapshot: ViewSnapshotEntity, row: ViewRowEntity, write: ViewWriteEntity): ViewRowEntity? {
        val column = ViewJson.columns(snapshot.columnInfo).firstOrNull { it.id == write.propId } ?: return null
        val original = ViewJson.parseValue(write.baseValue) ?: return null
        val typed = ViewJson.typed(row.typed)
        val restored = if (original is JsonNull) typed - write.propId else typed + (write.propId to original)
        val cells = row.cells.split(VIEW_CELL_SEPARATOR).toMutableList()
        val at = snapshot.columns.split(VIEW_CELL_SEPARATOR).indexOf(column.name)
        if (at in cells.indices) cells[at] = column.display(original)
        return row.copy(cells = cells.joinToString(VIEW_CELL_SEPARATOR), typed = ViewJson.encodeTyped(restored))
    }

    /**
     * 위젯 '되돌리기' 줄: 지연 중인 쓰기가 있으면 그것을([undoPending]), 없으면 보낸 지 [SENT_UNDO_WINDOW_MS] 안의 가장 최근 변경 묶음을([undoSent]) 되돌린다.
     * 되돌린 수를 돌려준다.
     */
    suspend fun undo(viewId: String): Int = undoPending(viewId).takeIf { it > 0 } ?: undoSent(viewId)

    /**
     * 이미 보낸 변경 되돌리기(Phase 12c IX-05 후반): 가장 최근에 보낸 한 묶음(같은 액션·같은 시트 저장이 만든 쓰기들, 묶음이 아니면 쓰기 하나)의 각 칸에 **반대 쓰기**를 쌓는다.
     * 칸이 아직 보낸 값인 기록만 대상이다(그 뒤에 또 바꿨다면 건드리지 않는다, 위젯 줄이 세는 것과 같다). 기준값은 보낸 값이라 처리기가 보내기 직전에 노션 값과 견주어,
     * 그 사이 노션에서 바뀌었으면 충돌로 기록하고 노션 값을 남긴다. 비울 수 없는 상태 칸은 이전 값이 없으면 건너뛴다. 되돌리는 쓰기는 [UNDO_BATCH_ID]라 다시 기록되지 않는다
     * (다시 되돌리기로 이어지지 않는다). 되돌린 칸 수를 돌려준다.
     * shortcut: 노션 자동화가 이 변경 때문에 만든 부수 효과(다른 속성)는 되돌리지 못한다.
     */
    suspend fun undoSent(viewId: String): Int {
        val recent = dao.sentSince(viewId, clock.millis() - SENT_UNDO_WINDOW_MS).filter { it.holdsIn(dao.row(viewId, it.pageId)) }
        val newest = recent.firstOrNull() ?: return 0
        val batch = recent.filter { if (newest.batchId != 0L) it.batchId == newest.batchId else it.id == newest.id }
        val undone = batch.count { sent ->
            val prior = ViewJson.parseValue(sent.priorValue) ?: return@count false
            change(viewId, sent.pageId, sent.propId, sent.propType, batchId = UNDO_BATCH_ID) { column, current, _ ->
                prior.takeIf { ViewJson.valueText(current) == sent.sentValue && column.accepts(it) }
            }
        }
        dao.deleteSent(batch.map { it.id })
        return undone
    }

    private fun sendAfterOf(sendDelayMs: Long): Long = if (sendDelayMs > 0) clock.millis() + sendDelayMs else 0

    /** 바로 보낼 수 있으면 처리기를 깨우고, 지연이 있으면 그 시각에 깨우도록 요청한다(그 전에는 처리기가 이 쓰기를 건너뛴다). */
    private fun wake(sendDelayMs: Long) = if (sendDelayMs > 0) requester.requestAfter(sendDelayMs) else requester.request()

    companion object {
        /** 위젯 즉시 동작의 전송 지연(IX-05). 이 동안 연타는 한 번의 전송으로 합쳐지고(IX-03) 되돌릴 수 있다. */
        const val UNDO_WINDOW_MS = 4_000L

        /** 보낸 뒤에도 '보낸 변경 되돌리기'를 줄 시간(2분, Phase 12c). 처리기는 이보다 오래된 보낸 기록을 지운다. */
        const val SENT_UNDO_WINDOW_MS = 120_000L

        /** 보낸 변경을 되돌리는 쓰기가 다는 묶음 번호. 처리기는 이 쓰기를 보낸 기록에 남기지 않는다. */
        const val UNDO_BATCH_ID = -1L
    }
}

private val BLANK_IS_EMPTY = setOf("url", "email", "phone_number")

/** 보낸 바꿈의 칸이 [row]에서 아직 보낸 값인가(그 뒤에 또 바뀌지 않았나). 행이 없으면 false. 위젯 줄과 [ViewWriter.undoSent]가 같은 판정을 쓴다. */
internal fun SentWriteEntity.holdsIn(row: ViewRowEntity?): Boolean =
    row != null && ViewJson.valueText(ViewJson.typed(row.typed)[propId]) == sentValue
